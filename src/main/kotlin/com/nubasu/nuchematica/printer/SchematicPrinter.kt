package com.nubasu.nuchematica.printer

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import com.nubasu.nuchematica.schematic.MissingBlockChange
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

public object SchematicPrinter {
    private const val NO_PROGRESS_BACKOFF_THRESHOLD: Int = 3
    private const val NO_PROGRESS_REBUILD_BACKOFF_TICKS: Int = 100

    private val activation: PrinterActivation = PrinterActivation()
    private val skipLog: PrinterSkipLog = PrinterSkipLog()
    private val deferralLedger: PrinterDeferralLedger = PrinterDeferralLedger()
    private val scaffoldLedger: ScaffoldLedger = ScaffoldLedger()
    private val runtime: PrinterRuntime = PrinterRuntime(
        skipLog = skipLog,
        isBlocked = { worldPos ->
            val level = Minecraft.getInstance().level
            level != null && deferralLedger.isDeferred(
                worldPos,
                PrintWorldModel::stateAt,
                MissingBlockHolder.missingSnapshot().revision,
            )
        },
        onRetryLimitBlocked = { worldPos ->
            Minecraft.getInstance().level?.let {
                deferralLedger.defer(worldPos, PrinterDeferralReason.RETRY_LIMIT, PrintWorldModel::stateAt)
            }
        },
        candidateSelector = PrinterCandidateSelector(
            skipLog = skipLog,
            onSkip = { reason, worldPos ->
                if (reason == PrinterSkipReason.PREDICTION_MISMATCH) {
                    Minecraft.getInstance().level?.let {
                        deferralLedger.defer(worldPos, PrinterDeferralReason.PREDICTION_MISMATCH, PrintWorldModel::stateAt)
                    }
                }
            },
            orientedPrediction = { item, context, expectedState ->
                val contextPlayer = context.player
                if (contextPlayer == null) {
                    null
                } else {
                    resolveOrientedRotation(
                        currentYaw = contextPlayer.yRot,
                        currentPitch = contextPlayer.xRot,
                        setRotation = { yaw, pitch ->
                            contextPlayer.setYRot(yaw)
                            contextPlayer.setXRot(pitch)
                        },
                        matches = {
                            val predicted = item.getPlacementState(context)
                            predicted != null && BlockStateEquivalence.matches(expectedState, predicted)
                        },
                    )
                }
            },
        ),
    )
    private val statusTracker: PrinterStatusTracker = PrinterStatusTracker()
    private val feedSnapshotStore: FeedSnapshotStore = FeedSnapshotStore()
    private val cameraEase: CameraEase = CameraEase()
    private val placementRotationSynchronizer: PlacementRotationSynchronizer = PlacementRotationSynchronizer()
    private val layerGate: PrinterLayerGate = PrinterLayerGate()
    private val layerGateMissingCache = LayerGateMissingCache(
        eligibleState = { state -> eligiblePrinterBlockItem(state) != null },
        localToWorld = { pos -> SchematicRenderManager.localBlockToWorld(pos) },
    )
    private val layerGateEligibleMissingCache = LayerGateEligibleMissingCache(
        entryAt = { localPos -> layerGateMissingCache[localPos] },
    )
    private val layerGateSupportMemo = LayerGateSupportMemo()
    private val layerGateScaffoldMemo = LayerGateScaffoldMemo()
    private var layerGateLevelIdentity: Any? = null
    private var layerGateContentIdentity: Any? = null
    private var layerGateTransformRevision: Long = 0L
    private var layerGateSessionInitialized: Boolean = false
    private var layerGateUpdated: Boolean = false
    private var layerGateY: Int? = null
    private var layerGatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT
    private var acceptedForLayerGate: Int = 0
    private var previousLayerGatePlayerPosition: Vec3? = null
    private var activeScaffoldPlan: ScaffoldPlan? = null
    private var pendingScaffoldStep: ScaffoldStep? = null
    private var scaffoldTerminalRearmed: Boolean = false
    private var scaffoldCellsPlaced: Int = 0
    private var scaffoldCellsBroken: Int = 0
    private var scaffoldChainsUsed: Int = 0
    private var scaffoldMaxChainLength: Int = 0
    private var scaffoldStallTicks: Int = 0
    private val startupOrphanScaffolds: MutableSet<BlockPos> = LinkedHashSet()

    private var planSessionKey: PrinterSessionKey? = null
    private var planAdapter: PlanRuntimeAdapter? = null
    private var planBehaviorSettings: PlacementBehaviorSettings? = null
    private var planSessionDirty: Boolean = false
    private var planNoProgressCount: Int = 0
    private var planRebuildBackoffTicksRemaining: Int = 0
    private var planBackoffMessaged: Boolean = false
    private var planDiscardedIdentity: PlanIdentity? = null
    private var planCoordinator: PlanCoordinator? = null
    private var planContentAssembler: PlanContentAssembler? = null
    private var planReservationCount: Int = 0
    private var planRetryUsed: Boolean = false
    private var planRetryIdentity: PlanIdentity? = null
    private var planLastPhase: PlanCoordinatorPhase? = null
    private var planCompletionMessaged: Boolean = false
    private var planCompletionReconciliation: PlanCompletionReconciliation? = null
    private var planRetryTargets: List<PlanTargetExpectation> = emptyList()
    private var planRefusedNoticeKey: PrinterSessionKey? = null
    private var planPhase: String? = null
    private var planBoundsIdentity: PlanIdentity? = null
    private var planBoundsPredicate: ((BlockPos) -> Boolean)? = null
    private var previousPlanFirstMode: Boolean = false

    internal val enabled: Boolean
        get() = activation.enabled

    internal fun currentLayerGateY(): Int? = layerGateY

    internal fun invalidatePlanSession(worldPos: BlockPos): Unit {
        val session = planAdapter
        if (session != null) {
            if (session.isWithinBounds(worldPos)) {
                planSessionDirty = true
                LogUtils.getLogger().info("[nuchematica] plan session invalidated by manual reconcile at {}", worldPos)
            }
            return
        }
        if (planBoundsPredicate?.invoke(worldPos) != false) {
            planRetryUsed = false
            planRetryIdentity = null
            planRetryTargets = emptyList()
            planCompletionReconciliation = null
            val activelyBuilding = planLastPhase != null
            if (PrinterSettingsHolder.printerSettings.planFirstMode || activelyBuilding) {
                LogUtils.getLogger().info("[nuchematica] plan retry budget reset by manual reconcile at {}", worldPos)
            }
        }
    }

    internal fun currentLayerGatePhase(): PrinterLayerGatePhase = layerGatePhase

    internal fun scaffoldTelemetry(): PrinterScaffoldTelemetry = PrinterScaffoldTelemetry(
        placed = scaffoldCellsPlaced,
        broken = scaffoldCellsBroken,
        chains = scaffoldChainsUsed,
        maxChainLength = scaffoldMaxChainLength,
    )

    internal fun latestFeedSnapshot(sessionKey: PrinterSessionKey): FeedSnapshot? {
        return feedSnapshotStore.latest(sessionKey)
    }

    internal fun freshFeedSnapshot(
        sessionKey: PrinterSessionKey,
        queueRevision: Long,
    ): FeedSnapshot? {
        return feedSnapshotStore.fresh(sessionKey, queueRevision)
    }

    internal fun ownsPendingPlacement(worldPos: BlockPos): Boolean {
        return runtime.attemptTracker.isInFlight(worldPos) || planAdapter?.isPlacementInFlight(worldPos) == true
    }

    internal fun ownsPendingBreak(worldPos: BlockPos): Boolean {
        return scaffoldLedger.isBreakInFlight(worldPos) || planAdapter?.isBreakInFlight(worldPos) == true
    }

    internal fun planFrontierSnapshot(): PlanFrontierSnapshot? {
        return planAdapter?.frontierSnapshot()
    }

    /** Queues a mover decision for revalidation on the next printer tick. */
    internal fun requestPlanPositionUnreachable(worldPos: BlockPos): Unit {
        planAdapter?.requestFrontierUnreachable(worldPos)
    }

    /** Reports completion only while the messaged plan remains the adopted session. */
    internal fun isPlanSessionFinal(): Boolean {
        return planCompletionMessaged && planAdapter != null
    }

    internal fun isDeferredWorldPos(worldPos: BlockPos, queueRevision: Long): Boolean {
        if (Minecraft.getInstance().level == null) return false
        return deferralLedger.isDeferred(worldPos, PrintWorldModel::stateAt, queueRevision)
    }

    internal fun clearMoverCauses(): Unit {
        deferralLedger.clearMoverCauses()
    }

    internal fun hasPendingStartupOrphanScaffolds(): Boolean {
        return pendingStartupOrphanScaffolds().isNotEmpty()
    }

    internal fun pendingStartupOrphanScaffolds(): List<BlockPos> {
        startupOrphanScaffolds.removeIf { pos -> !scaffoldLedger.isScaffoldCell(pos) }
        return startupOrphanScaffolds.toList()
    }

    internal fun grantFinalSweepBackoffBypass(): Unit {
        deferralLedger.grantFinalSweepBackoffBypass()
    }

    internal fun deferUnreachable(positions: Set<BlockPos>): Unit {
        deferMoverPositions(positions, PrinterDeferralReason.MOVER_UNREACHABLE)
    }

    internal fun deferNoProgress(positions: Set<BlockPos>): Unit {
        deferMoverPositions(positions, PrinterDeferralReason.NO_PROGRESS)
    }

    private fun deferMoverPositions(
        positions: Set<BlockPos>,
        reason: PrinterDeferralReason,
    ): Unit {
        if (Minecraft.getInstance().level == null) return
        val queueRevision = MissingBlockHolder.missingSnapshot().revision
        for (worldPos in positions) {
            deferralLedger.defer(worldPos, reason, PrintWorldModel::stateAt, queueRevision)
        }
    }

    internal var latestStatus: PrinterStatus? = null
        private set

    internal fun toggleRequested(isCreative: Boolean): PrinterActivationEvent {
        val event = activation.toggleRequested(isCreative)
        if (event == PrinterActivationEvent.ENABLED) {
            statusTracker.reset()
            resetLayerGateSession()
            deferralLedger.clearAll()
            feedSnapshotStore.clear()
            previousPlanFirstMode = PrinterSettingsHolder.printerSettings.planFirstMode
        } else {
            latestStatus = null
            runtime.cancelAll()
            feedSnapshotStore.clear()
            cleanupScaffolds()
            teardownPlanSession()
        }
        return event
    }

    internal fun tick(): Unit {
        if (!enabled) {
            latestStatus = null
            runtime.cancelAll()
            feedSnapshotStore.clear()
            ScaffoldBreakConnectionObserver.remove()
            return
        }

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        val gameMode = minecraft.gameMode
        if (level == null) teardownPlanSession()
        val isCreative = level != null && player != null && gameMode?.playerMode?.isCreative == true
        if (activation.tick(isCreative) == PrinterActivationEvent.AUTO_DISABLED) {
            ChatSender.send("[nuchematica] printer disabled (left creative mode)")
            cleanupScaffolds()
            teardownPlanSession()
        }
        if (!enabled || level == null || player == null || gameMode == null) {
            latestStatus = null
            runtime.cancelAll()
            feedSnapshotStore.clear()
            ScaffoldBreakConnectionObserver.remove()
            return
        }
        ScaffoldBreakConnectionObserver.install(player.connection.connection)
        if (
            PrintWorldModel.status() == PrintWorldModel.Status.CAPTURING ||
            MissingBlockHolder.isInitializing()
        ) {
            return
        }
        val settings = PrinterSettingsHolder.printerSettings
        if (settings.planFirstMode && !previousPlanFirstMode) {
            runtime.cancelAll()
            cleanupScaffolds()
        }
        previousPlanFirstMode = settings.planFirstMode
        tickScaffoldBreaks(level, player, gameMode)
        if (hasPendingStartupOrphanScaffolds()) {
            feedSnapshotStore.clear()
            return
        }
        if (settings.planFirstMode && PrintWorldModel.status() == PrintWorldModel.Status.READY) {
            tickPlanMode(level, player, gameMode, settings)
            return
        }
        if (settings.planFirstMode) {
            val currentIdentity = PrinterSessionKey(
                level,
                SchematicHolder.renderingBlocks,
                SchematicRenderManager.currentTransformRevision(),
            )
            val session = planSessionKey
            if (
                session != null &&
                (!session.matches(currentIdentity) || PrintWorldModel.status() == PrintWorldModel.Status.REFUSED)
            ) {
                teardownPlanSession()
            }
            planCoordinator?.cancel()
            if (
                PrintWorldModel.status() == PrintWorldModel.Status.REFUSED &&
                planRefusedNoticeKey?.matches(currentIdentity) != true
            ) {
                ChatSender.send("[nuchematica] schematic too large for plan mode; printer idle")
                planRefusedNoticeKey = currentIdentity
                planPhase = "refused"
                planNoProgressCount = 0
                planRebuildBackoffTicksRemaining = 0
                planBackoffMessaged = false
                val previousStatus = latestStatus
                latestStatus = PrinterStatus(
                    remaining = previousStatus?.remaining ?: 0,
                    placed = previousStatus?.placed ?: 0,
                    skips = emptyMap(),
                    planPhase = planPhase,
                )
            }
            feedSnapshotStore.clear()
        }
        if (settings.planFirstMode) return
        teardownPlanSession()
        runtime.rateLimiter.updateAttemptsPerTick(settings.attemptsPerTick)
        runtime.rateLimiter.updateIntervalTicks(settings.placementIntervalTicks)
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val transformRevision = SchematicRenderManager.currentTransformRevision()
        synchronizeLayerGateSession(level, content, transformRevision)
        val playerPosition = player.position()
        val playerMoved = previousLayerGatePlayerPosition?.let { previousPosition ->
            playerPosition.distanceToSqr(previousPosition) > PLAYER_MOVEMENT_DISTANCE_SQUARED
        } ?: false
        previousLayerGatePlayerPosition = playerPosition
        layerGateMissingCache.synchronize(
            contentIdentity = content,
            expectedStates = content.blocks,
            transformRevision = transformRevision,
            substituteLookalikes = settings.substituteLookalikes,
        )
        layerGateSupportMemo.synchronize(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        layerGateScaffoldMemo.synchronize(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        deferralLedger.synchronize(
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        val reach = minOf(settings.reach, gameMode.pickRange.toDouble())
        val eyePosition = player.eyePosition
        val eligibleMissing = layerGateEligibleMissingCache.sync(
            contentIdentity = content,
            transformRevision = transformRevision,
            substituteLookalikes = settings.substituteLookalikes,
            missingRevision = missing.revision,
            missingLocal = missing.missingLocal,
            changesSince = MissingBlockHolder::changesSince,
        )
        if (layerGateEligibleMissingCache.isRebuilding()) return
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            content.blocks.containsKey(SchematicRenderManager.worldBlockToLocal(pos))
        }
        val canScaffold: (BlockPos, BlockState, (BlockPos) -> BlockState) -> Boolean = { pos, expectedState, stateAtFn ->
            layerGateScaffoldMemo.canScaffold(pos, expectedState, stateAtFn, isSchematicPosition, playerPosition)
        }
        val isLayerSupported: (Int) -> Boolean = { y ->
            eligibleMissing.byY[y]?.any { entry ->
                isActionableMissing(
                    entry.worldPos,
                    entry.expectedState,
                    PrintWorldModel::stateAt,
                    hasSupport = layerGateSupportMemo::hasSupport,
                    canScaffold = canScaffold,
                ) && !deferralLedger.isDeferred(entry.worldPos, PrintWorldModel::stateAt, missing.revision)
            } == true
        }
        val gateYBeforeUpdate = if (layerGateUpdated) {
            layerGateY
        } else {
            eligibleMissing.sortedDistinctYs.firstOrNull(isLayerSupported)
        }
        val gateReach = reach + GATE_REACH_MARGIN
        val gateReachSquared = gateReach * gateReach
        var inReachAboveGate = 0
        var inReachEligibleAtOrBelowGate = 0
        if (gateYBeforeUpdate != null) {
            val yLow = kotlin.math.floor(eyePosition.y - gateReach).toInt()
            val yHigh = kotlin.math.ceil(eyePosition.y + gateReach).toInt()
            for (y in yLow..yHigh) {
                val bucket = eligibleMissing.byY[y] ?: continue
                for (entry in bucket) {
                    val centerX = entry.worldPos.x + 0.5
                    val centerY = entry.worldPos.y + 0.5
                    val centerZ = entry.worldPos.z + 0.5
                    val deltaX = eyePosition.x - centerX
                    val deltaY = eyePosition.y - centerY
                    val deltaZ = eyePosition.z - centerZ
                    val distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    if (distanceSquared <= gateReachSquared) {
                        if (entry.worldPos.y > gateYBeforeUpdate) inReachAboveGate++
                        if (
                            entry.worldPos.y <= gateYBeforeUpdate &&
                            isActionableMissing(
                                entry.worldPos,
                                entry.expectedState,
                                PrintWorldModel::stateAt,
                                hasSupport = layerGateSupportMemo::hasSupport,
                                canScaffold = canScaffold,
                            ) &&
                            !deferralLedger.isDeferred(entry.worldPos, PrintWorldModel::stateAt, missing.revision) &&
                            potentialSupportHitPoints(entry.worldPos, entry.expectedState).any { point ->
                                eyePosition.distanceToSqr(point) <= gateReachSquared
                            }
                        ) {
                            inReachEligibleAtOrBelowGate++
                        }
                    }
                }
            }
        }
        val gateYForTick = layerGate.update(
            eligibleYs = eligibleMissing.sortedDistinctYs,
            isLayerSupported = isLayerSupported,
            inReachAboveGate = inReachAboveGate,
            inReachEligibleAtOrBelowGate = inReachEligibleAtOrBelowGate,
            acceptedThisTick = acceptedForLayerGate,
            playerMoved = playerMoved,
            onFloorYEscalated = { floorY ->
                LogUtils.getLogger().info("C3DBG[gate] floorY escalated to {}", floorY)
            },
        )
        layerGateY = gateYForTick
        layerGatePhase = layerGate.currentPhase()
        layerGateUpdated = true
        val gatedMissingLocal = if (gateYForTick == null) {
            missing.missingLocal
        } else {
            missing.missingLocal.filter { localPos ->
                val entry = layerGateMissingCache[localPos]
                entry == null || entry.worldPos.y <= gateYForTick
            }
        }
        var scaffoldStepForTick: ScaffoldStep? = null
        var scaffoldCandidateForTick: PrinterCandidate? = null
        if (pendingScaffoldStep == null) {
            val currentPlan = activeScaffoldPlan
            val step = if (currentPlan != null) {
                val next = currentPlan.nextStep(scaffoldLedger::isScaffoldCell)
                if (next == null) {
                    activeScaffoldPlan = null
                    null
                } else {
                    next
                }
            } else if (!scaffoldLedger.isOutstanding()) {
                val plan = findScaffoldPlan(
                    gatedMissingLocal = gatedMissingLocal,
                    content = content,
                    missingRevision = missing.revision,
                    eyePosition = eyePosition,
                    reach = reach,
                    playerFeetPos = playerPosition,
                    isSchematicPosition = isSchematicPosition,
                )
                if (plan != null) {
                    activeScaffoldPlan = plan
                    if (plan.cells.size > 1) scaffoldChainsUsed++
                    if (plan.cells.size > scaffoldMaxChainLength) scaffoldMaxChainLength = plan.cells.size
                    plan.nextStep(scaffoldLedger::isScaffoldCell)
                } else {
                    null
                }
            } else {
                null
            }
            if (step != null) {
                scaffoldStepForTick = step
                scaffoldCandidateForTick = runtime.candidateSelector.select(
                    missingLocal = listOf(SchematicRenderManager.worldBlockToLocal(step.scaffoldPos)),
                    expectedStateAt = { SCAFFOLD_BLOCK_STATE },
                    localToWorld = { step.scaffoldPos },
                    stateAt = level::getBlockState,
                    placementContext = { expectedState, hit ->
                        BlockPlaceContext(
                            level,
                            player,
                            InteractionHand.MAIN_HAND,
                            ItemStack(expectedState.block.asItem()),
                            hit,
                        )
                    },
                    eyePosition = eyePosition,
                    reach = reach,
                    playerFeetPos = playerPosition,
                ).firstOrNull()
            }
        }
        val sessionKey = PrinterSessionKey(
            level = level,
            content = content,
            transformRevision = transformRevision,
        )
        val gateway = FacePlacementGateway(
            delegate = RealPlacementGateway(gameMode, player, level),
            facePlacement = settings.facePlacement,
            eyePosition = eyePosition,
            setCameraTarget = cameraEase::setTarget,
        )
        val completed = runtime.tick(
            PrinterTickContext(
                tick = level.gameTime,
                queueRevision = missing.revision,
                sessionKey = sessionKey,
                missingLocal = gatedMissingLocal,
                expectedStateAt = content.blocks::get,
                localToWorld = SchematicRenderManager::localBlockToWorld,
                stateAt = level::getBlockState,
                placementContext = { expectedState, hit ->
                    BlockPlaceContext(
                        level,
                        player,
                        InteractionHand.MAIN_HAND,
                        ItemStack(expectedState.block.asItem()),
                        hit,
                    )
                },
                eyePosition = eyePosition,
                reach = reach,
                itemSupplier = CreativeItemSupplier(player, gameMode),
                placementGateway = gateway,
                playerFeetPos = playerPosition,
                scaffoldCandidate = scaffoldCandidateForTick,
            ),
        )
        if (
            scaffoldCandidateForTick != null && scaffoldStepForTick != null &&
            runtime.attemptTracker.isInFlight(scaffoldCandidateForTick.worldPos)
        ) {
            pendingScaffoldStep = scaffoldStepForTick
        }
        runtime.feedSnapshot()?.let { snapshot ->
            feedSnapshotStore.update(sessionKey, snapshot)
        } ?: feedSnapshotStore.clear()
        completed.forEach { result ->
            // AttemptTracker owns progress; click reconciliation must not duplicate it.
            ClientBlockInteractHandler.pendingPlacePositions.remove(result.attempt.worldPos)
            val scaffoldStep = pendingScaffoldStep
            if (scaffoldStep != null && result.attempt.worldPos == scaffoldStep.scaffoldPos) {
                if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                    scaffoldLedger.recordPlaced(scaffoldStep.scaffoldPos, scaffoldStep.targetPos)
                    scaffoldCellsPlaced++
                    scaffoldStallTicks = 0
                    PrintWorldModel.recordWrite(scaffoldStep.scaffoldPos, result.observedState)
                }
                pendingScaffoldStep = null
                return@forEach
            }
            when (result.outcome) {
                PrinterAttemptOutcome.ACCEPTED,
                PrinterAttemptOutcome.WRONG_STATE,
                -> {
                    val observedState = level.getBlockState(result.attempt.worldPos)
                    MissingBlockHolder.placed(
                        result.attempt.worldPos,
                        observedState,
                    )?.let(SchematicRenderManager::onMissingBlockChange)
                    PrintWorldModel.recordWrite(result.attempt.worldPos, observedState)
                    scaffoldLedger.markConsumed(result.attempt.worldPos)
                }
                PrinterAttemptOutcome.REJECTED -> Unit
                // Late client state is reconciled by a rescan, not counted as progress.
                PrinterAttemptOutcome.TIMEOUT -> Unit
            }
        }
        tickScaffoldBreaks(level, player, gameMode)
        if (activeScaffoldPlan != null) {
            scaffoldStallTicks++
            if (scaffoldStallTicks >= SCAFFOLD_STALL_ABANDON_TICKS) {
                val stalledTarget = activeScaffoldPlan?.targetPos
                LogUtils.getLogger().error(
                    "C3DBG[scaffold] plan abandoned after {} stall ticks target={}",
                    scaffoldStallTicks,
                    stalledTarget,
                )
                cleanupScaffolds()
                if (stalledTarget != null) {
                    deferralLedger.defer(
                        stalledTarget,
                        PrinterDeferralReason.NO_PROGRESS,
                        PrintWorldModel::stateAt,
                        missing.revision,
                    )
                }
                scaffoldStallTicks = 0
            }
        } else {
            scaffoldStallTicks = 0
        }
        val acceptedThisTick = completed.count { result ->
            result.outcome == PrinterAttemptOutcome.ACCEPTED
        }
        acceptedForLayerGate = acceptedThisTick
        latestStatus = statusTracker.update(
            levelIdentity = level,
            contentIdentity = content,
            transformRevision = transformRevision,
            remaining = missing.missingLocal.size,
            acceptedDelta = acceptedThisTick,
            skips = runtime.skipLog.snapshot(),
        )
        if (settings.facePlacement) {
            val (easedYaw, easedPitch) = cameraEase.ease(player.yRot, player.xRot)
            player.setYRot(easedYaw)
            player.setXRot(easedPitch)
        }
    }

    private fun tickPlanMode(
        level: ClientLevel,
        player: LocalPlayer,
        gameMode: MultiPlayerGameMode,
        settings: PrinterSettings,
    ): Unit {
        feedSnapshotStore.clear()

        if (planSessionDirty) {
            val placedAtDiscard = planAdapter?.targetActionCounts()?.placed ?: 0
            val decision = evaluateNoProgressBackoff(
                placedAtDiscard = placedAtDiscard,
                currentNoProgressCount = planNoProgressCount,
                threshold = NO_PROGRESS_BACKOFF_THRESHOLD,
            )
            planNoProgressCount = decision.noProgressCount
            if (decision.armBackoff) {
                planRebuildBackoffTicksRemaining = NO_PROGRESS_REBUILD_BACKOFF_TICKS
                if (!planBackoffMessaged) {
                    ChatSender.send("[nuchematica] plan keeps getting invalidated; backing off")
                    planBackoffMessaged = true
                }
            } else if (decision.noProgressCount == 0) {
                planRebuildBackoffTicksRemaining = 0
                planBackoffMessaged = false
            }
            planDiscardedIdentity = planSessionKey?.let { key ->
                planBehaviorSettings?.let { behavior -> PlanIdentity(key.level, key.content, key.transformRevision, behavior) }
            }
            cancelPreparedPlacementRotation(player)
            sweepOutstandingScaffolds(planAdapter)
            planCoordinator?.cancel()
            planAdapter = null
            planSessionKey = null
            planSessionDirty = false
            planRetryUsed = false
            planRetryIdentity = null
            planRetryTargets = emptyList()
            planCompletionReconciliation = null
            planCompletionMessaged = false
            return
        }

        planRefusedNoticeKey = null

        val content = SchematicHolder.renderingBlocks
        val transformRevision = SchematicRenderManager.currentTransformRevision()
        val sessionKey = PrinterSessionKey(level, content, transformRevision)
        val behavior = PlacementBehaviorSettings(
            substituteLookalikes = settings.substituteLookalikes,
            placeWaterloggedDry = settings.placeWaterloggedDry,
        )

        val adapter = planAdapter
        if (adapter != null) {
            if (planSessionKey?.matches(sessionKey) == true && planBehaviorSettings == behavior) {
                tickPlanAdapter(adapter, content, level, player, gameMode, settings, behavior)
                return
            }
            teardownPlanSession()
            return
        }

        val identity = PlanIdentity(level, content, transformRevision, behavior)
        if (!noProgressBackoffAppliesToIdentity(planDiscardedIdentity, identity)) {
            planNoProgressCount = 0
            planRebuildBackoffTicksRemaining = 0
            planBackoffMessaged = false
        }
        if (planRebuildBackoffTicksRemaining > 0) {
            planRebuildBackoffTicksRemaining--
            return
        }
        val boundsPredicate = boundsPredicateFor(identity, content)
        when (val step = obtainPlanCoordinator().advance(identity, obtainPlanContentAssembler(), boundsPredicate)) {
            is PlanCoordinatorStep.Ready -> {
                val session = step.session
                val sessionBehavior = session.identity.behavior
                planAdapter = session.adapter
                planSessionKey = sessionKey
                planBehaviorSettings = sessionBehavior
                planReservationCount = session.plan.report.reservedCount
                planLastPhase = null
                planCompletionMessaged = false
                planCompletionReconciliation = null
                if (!(planRetryUsed && planRetryIdentity?.matches(session.identity) == true)) {
                    planRetryTargets = emptyList()
                }
                ChatSender.send(
                    "[nuchematica] plan ready (${session.adapter.targetActionCounts().remaining} targets)",
                )
                tickPlanAdapter(session.adapter, content, level, player, gameMode, settings, sessionBehavior)
            }
            is PlanCoordinatorStep.InProgress -> {
                val previousPhase = planLastPhase
                if (
                    (previousPhase == null || previousPhase == PlanCoordinatorPhase.IDLE) &&
                    step.phase == PlanCoordinatorPhase.SNAPSHOTTING
                ) {
                    ChatSender.send("[nuchematica] planning...")
                }
                planLastPhase = step.phase
                if (step.phase == PlanCoordinatorPhase.SNAPSHOTTING || step.phase == PlanCoordinatorPhase.PLANNING) {
                    planPhase = if (planRetryUsed && planRetryIdentity?.matches(identity) == true) {
                        "replanning"
                    } else {
                        "planning"
                    }
                    val previousStatus = latestStatus
                    latestStatus = PrinterStatus(
                        remaining = previousStatus?.remaining ?: 0,
                        placed = previousStatus?.placed ?: 0,
                        skips = emptyMap(),
                        planPhase = planPhase,
                    )
                }
            }
            PlanCoordinatorStep.Failed -> {
                planLastPhase = null
                planPhase = "failed"
                val previousStatus = latestStatus
                latestStatus = PrinterStatus(
                    remaining = previousStatus?.remaining ?: 0,
                    placed = previousStatus?.placed ?: 0,
                    skips = emptyMap(),
                    planPhase = planPhase,
                )
            }
        }
    }

    private fun tickPlanAdapter(
        adapter: PlanRuntimeAdapter,
        content: SchematicCache,
        level: ClientLevel,
        player: LocalPlayer,
        gameMode: MultiPlayerGameMode,
        settings: PrinterSettings,
        behavior: PlacementBehaviorSettings,
    ): Unit {
        maintainPreparedPlacementRotation(player)
        val eyePosition = player.eyePosition
        val reach = minOf(settings.reach, gameMode.pickRange.toDouble())
        val gateway = FacePlacementGateway(
            delegate = RealPlacementGateway(gameMode, player, level),
            facePlacement = settings.facePlacement,
            eyePosition = eyePosition,
            setCameraTarget = cameraEase::setTarget,
        )
        adapter.tick(
            PlanRuntimeTickContext(
                tick = level.gameTime,
                stateAt = PrintWorldModel::stateAt,
                liveStateAt = level::getBlockState,
                recordWrite = PrintWorldModel::recordWrite,
                eyePosition = eyePosition,
                reach = reach,
                playerFeetPos = player.position(),
                settings = behavior,
                placementContext = { expectedState, hit ->
                    BlockPlaceContext(
                        level,
                        player,
                        InteractionHand.MAIN_HAND,
                        ItemStack(expectedState.block.asItem()),
                        hit,
                    )
                },
                predictPlacement = { item, context -> item.getPlacementState(context) },
                orientedPrediction = planOrientedPrediction(behavior),
                itemSupplier = CreativeItemSupplier(player, gameMode),
                placementGateway = gateway,
                preparePlacementRotation = { actionId, pos, rotation ->
                    placementRotationSynchronizer.prepare(
                        key = PlacementRotationKey(actionId, pos),
                        tick = level.gameTime,
                        currentRotation = { PlacementRotation(player.yRot, player.xRot) },
                        applyLocalRotation = { current -> applyPlacementRotation(player, current) },
                        sendServerRotation = { current -> sendPlacementRotation(player, current) },
                        requiredRotation = rotation,
                    )
                },
                finishPlacementRotation = { actionId, pos ->
                    placementRotationSynchronizer.finish(
                        key = PlacementRotationKey(actionId, pos),
                        applyLocalRotation = { current -> applyPlacementRotation(player, current) },
                        sendServerRotation = { current -> sendPlacementRotation(player, current) },
                    )
                },
                cancelPlacementRotation = { actionId, pos ->
                    placementRotationSynchronizer.finish(
                        key = PlacementRotationKey(actionId, pos),
                        applyLocalRotation = { current -> applyPlacementRotation(player, current) },
                        sendServerRotation = { current -> sendPlacementRotation(player, current) },
                    )
                },
                destroy = { pos -> requestCreativeBlockBreak(gameMode, level, pos) },
                breakStateAt = { pos ->
                    ScaffoldBreakConnectionObserver.observedStateAt(pos) { level.getBlockState(pos) }
                },
                onBreakAttemptSettled = ScaffoldBreakConnectionObserver::finish,
                placementIntervalTicks = settings.placementIntervalTicks,
                attemptsPerTick = settings.attemptsPerTick,
                onFrozenLiveDivergence = { planSessionDirty = true },
                clearPendingPlace = ClientBlockInteractHandler.pendingPlacePositions::remove,
                clearPendingBreak = ClientBlockInteractHandler.pendingBreakPositions::remove,
            ),
        )

        val cursorStatus = adapter.cursor.status()
        val sweepPending = cursorStatus.isComplete && adapter.isSweepPending()
        if (cursorStatus.isComplete && !sweepPending && planCompletionReconciliation == null) {
            planCompletionReconciliation = reconcilePlanCompletion(
                schematicLocalPositions = content.blocks.keys,
                plannedTargets = planRetryTargets + adapter.targetExpectations(),
                localToWorld = SchematicRenderManager::localBlockToWorld,
                liveStateAt = level::getBlockState,
                onObserved = { pos, actual ->
                    PrintWorldModel.recordWrite(pos, actual)
                    MissingBlockHolder.placed(pos, actual)
                        ?.let(SchematicRenderManager::onMissingBlockChange)
                },
                matches = { expected, actual -> BlockStateEquivalence.matches(expected, actual, behavior) },
            )
        }
        val reconciliation = planCompletionReconciliation
        val adoptedKey = planSessionKey
        val currentIdentity = adoptedKey?.let { key ->
            PlanIdentity(key.level, key.content, key.transformRevision, behavior)
        }
        val retryAlreadyUsedForThisIdentity = planRetryUsed &&
            currentIdentity != null &&
            planRetryIdentity?.matches(currentIdentity) == true
        val willRetry = shouldAutoRetryPlanSession(
            status = cursorStatus,
            hasReservations = planReservationCount > 0,
            hasLiveMismatches = reconciliation?.mismatchedTargets?.isNotEmpty() == true,
            retryUsed = retryAlreadyUsedForThisIdentity,
        )
        planPhase = when {
            !cursorStatus.isComplete -> "executing"
            willRetry -> "replanning"
            else -> "complete"
        }
        latestStatus = PrinterStatus(
            remaining = cursorStatus.pendingCount + cursorStatus.waitingCount + cursorStatus.inFlightCount,
            placed = cursorStatus.doneCount,
            skips = emptyMap(),
            planPhase = planPhase,
        )
        val targetCounts = adapter.targetActionCounts()
        latestStatus = PrinterStatus(
            remaining = reconciliation?.mismatchedTargets?.size ?: targetCounts.remaining,
            placed = reconciliation?.let { it.targetCount - it.mismatchedTargets.size } ?: targetCounts.placed,
            skips = emptyMap(),
            planPhase = planPhase,
        )

        if (settings.facePlacement && !placementRotationSynchronizer.isPending) {
            val (easedYaw, easedPitch) = cameraEase.ease(player.yRot, player.xRot)
            player.setYRot(easedYaw)
            player.setXRot(easedPitch)
        }

        if (!cursorStatus.isComplete) return
        if (sweepPending) return
        planNoProgressCount = 0
        planRebuildBackoffTicksRemaining = 0
        planBackoffMessaged = false
        if (willRetry) {
            planRetryUsed = true
            planRetryIdentity = currentIdentity
            planRetryTargets = adapter.targetExpectations()
            planCompletionReconciliation = null
            cancelPreparedPlacementRotation(player)
            sweepOutstandingScaffolds(adapter)
            planAdapter = null
            planSessionKey = null
            planBehaviorSettings = null
            planCoordinator?.cancel()
            return
        }
        if (planCompletionMessaged) return
        planCompletionMessaged = true
        val unplaced = reconciliation?.mismatchedTargets?.size ?: targetCounts.failedOrSkipped
        if (unplaced == 0) {
            ChatSender.send("[nuchematica] plan complete")
        } else {
            ChatSender.send("[nuchematica] plan complete, $unplaced cells unplaced")
        }
    }

    private fun applyPlacementRotation(player: LocalPlayer, rotation: PlacementRotation): Unit {
        player.setYRot(rotation.yaw)
        player.setXRot(rotation.pitch)
        player.setYHeadRot(rotation.yaw)
    }

    private fun sendPlacementRotation(player: LocalPlayer, rotation: PlacementRotation): Unit {
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(rotation.yaw, rotation.pitch, player.isOnGround),
        )
    }

    private fun maintainPreparedPlacementRotation(player: LocalPlayer): Unit {
        placementRotationSynchronizer.maintain(
            applyLocalRotation = { rotation -> applyPlacementRotation(player, rotation) },
            sendServerRotation = { rotation -> sendPlacementRotation(player, rotation) },
        )
    }

    private fun cancelPreparedPlacementRotation(player: LocalPlayer? = Minecraft.getInstance().player): Unit {
        if (player == null) {
            placementRotationSynchronizer.discard()
            return
        }
        placementRotationSynchronizer.cancel(
            applyLocalRotation = { rotation -> applyPlacementRotation(player, rotation) },
            sendServerRotation = { rotation -> sendPlacementRotation(player, rotation) },
        )
    }

    private fun obtainPlanCoordinator(): PlanCoordinator {
        val existing = planCoordinator
        if (existing != null) return existing
        val created = PlanCoordinator(
            onPlanningFailure = { failure -> ChatSender.send("[nuchematica] plan failed: ${failure.message}") },
        )
        planCoordinator = created
        return created
    }

    private fun obtainPlanContentAssembler(): PlanContentAssembler {
        val existing = planContentAssembler
        if (existing != null) return existing
        val created = BudgetedPlanContentAssembler(
            missingLocal = { MissingBlockHolder.missingSnapshot().missingLocal },
            expectedState = { local -> SchematicHolder.renderingBlocks.blocks[local] },
            localToWorld = { local -> SchematicRenderManager.localBlockToWorld(local) },
        )
        planContentAssembler = created
        return created
    }

    private fun boundsPredicateFor(identity: PlanIdentity, content: SchematicCache): (BlockPos) -> Boolean {
        val cachedIdentity = planBoundsIdentity
        val cachedPredicate = planBoundsPredicate
        if (cachedIdentity != null && cachedPredicate != null && cachedIdentity.matches(identity)) {
            return cachedPredicate
        }
        val worldBounds = schematicWorldBoundingBox(
            content.blocks.keys,
            SchematicRenderManager::localBlockToWorld,
            margin = 2,
        )
        val predicate: (BlockPos) -> Boolean = if (worldBounds != null) {
            val (min, max) = worldBounds
            { pos -> pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z }
        } else {
            { true }
        }
        planBoundsIdentity = identity
        planBoundsPredicate = predicate
        return predicate
    }

    private fun sweepOutstandingScaffolds(adapter: PlanRuntimeAdapter?): Unit {
        if (adapter == null) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val gameMode = minecraft.gameMode ?: return
        if (planSessionKey?.level !== level) return
        adapter.sweepOutstandingScaffolds(
            level::getBlockState,
            { pos -> gameMode.startDestroyBlock(pos, Direction.DOWN) },
            PrintWorldModel::recordWrite,
        )
    }

    private fun teardownPlanSession(): Unit {
        cancelPreparedPlacementRotation()
        sweepOutstandingScaffolds(planAdapter)
        planCoordinator?.cancel()
        planSessionDirty = false
        planSessionKey = null
        planAdapter = null
        planBehaviorSettings = null
        planRetryUsed = false
        planRetryIdentity = null
        planRetryTargets = emptyList()
        planRefusedNoticeKey = null
        planLastPhase = null
        planCompletionMessaged = false
        planCompletionReconciliation = null
        planPhase = null
        planNoProgressCount = 0
        planRebuildBackoffTicksRemaining = 0
        planBackoffMessaged = false
        planDiscardedIdentity = null
        planContentAssembler = null
        planBoundsIdentity = null
        planBoundsPredicate = null
    }

    internal fun completeBreakdown(): PrinterCompleteBreakdown {
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null) {
            return PrinterCompleteBreakdown(
                totalRemaining = 0,
                excluded = 0,
                unsupported = 0,
                scaffoldAssisted = 0,
                unreachable = 0,
                occupied = 0,
                deferred = 0,
                actionable = 0,
            )
        }
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val entries = missing.missingLocal.mapNotNull { localPos ->
            val expected = content.blocks[localPos] ?: return@mapNotNull null
            SchematicRenderManager.localBlockToWorld(localPos) to expected
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            content.blocks.containsKey(SchematicRenderManager.worldBlockToLocal(pos))
        }
        val classification = classifyMissing(
            entries,
            PrintWorldModel::stateAt,
            missing.revision,
            deferralLedger,
            isSchematicPosition,
            minecraft.player?.position(),
        )
        return PrinterCompleteBreakdown(
            totalRemaining = missing.missingLocal.size,
            excluded = classification.excluded,
            unsupported = classification.unsupported,
            scaffoldAssisted = classification.scaffoldAssisted,
            unreachable = classification.unreachable,
            occupied = classification.occupied,
            deferred = classification.deferred,
            actionable = classification.actionable.size,
        )
    }

    /**
     * Allows mover completion only when actionable work and scaffold cleanup are exhausted.
     *
     * Exhausted scaffold breaks receive one bounded rearm before forced cleanup is allowed.
     */
    internal fun canAutoMoveComplete(): Boolean {
        val breakdown = completeBreakdown()
        val scaffoldOutstanding = scaffoldLedger.isOutstanding() ||
            pendingScaffoldStep != null ||
            activeScaffoldPlan != null
        val outcome = scaffoldTerminalOutcome(
            actionable = breakdown.actionable,
            scaffoldOutstanding = scaffoldOutstanding,
            alreadyRearmedThisEncounter = scaffoldTerminalRearmed,
        )
        scaffoldTerminalRearmed = outcome == ScaffoldTerminalOutcome.REARM_AND_REFUSE
        when (outcome) {
            ScaffoldTerminalOutcome.REARM_AND_REFUSE -> {
                pendingScaffoldStep = null
                activeScaffoldPlan = null
                val rearmedCount = scaffoldLedger.rearmExhausted()
                LogUtils.getLogger().error(
                    "C3DBG[complete] rejected: scaffold outstanding, re-armed {} exhausted break(s) ledger={}",
                    rearmedCount,
                    scaffoldLedger.snapshot().size,
                )
            }
            ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP -> {
                pendingScaffoldStep = null
                activeScaffoldPlan = null
                if (scaffoldLedger.isOutstanding()) cleanupScaffolds()
            }
            ScaffoldTerminalOutcome.REFUSE -> {
                LogUtils.getLogger().error(
                    "C3DBG[complete] rejected: total={} actionable={} gateY={}",
                    breakdown.totalRemaining,
                    breakdown.actionable,
                    layerGateY,
                )
            }
            ScaffoldTerminalOutcome.ALLOW_CLEAR -> Unit
        }
        return outcome == ScaffoldTerminalOutcome.ALLOW_CLEAR ||
            outcome == ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP
    }

    /** Cleans tracked and submitted scaffolds on every printer session exit path. */
    internal fun cleanupScaffolds(): Unit {
        val pending = pendingScaffoldStep
        if (!scaffoldLedger.isOutstanding() && pending == null && activeScaffoldPlan == null) return
        pendingScaffoldStep = null
        activeScaffoldPlan = null
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val gameMode = minecraft.gameMode
        val leftover = if (level == null || gameMode == null) {
            val count = scaffoldLedger.snapshot().size + if (pending != null) 1 else 0
            scaffoldLedger.clearAll()
            count
        } else {
            if (pending != null) scaffoldLedger.recordPlaced(pending.scaffoldPos, pending.targetPos)
            scaffoldLedger.cleanupAll(
                stateAt = level::getBlockState,
                destroy = { pos -> gameMode.startDestroyBlock(pos, Direction.DOWN) },
            )
        }
        if (leftover > 0) {
            ChatSender.send("[nuchematica] scaffold cleanup: $leftover left")
        }
        startupOrphanScaffolds.clear()
    }

    private fun tickScaffoldBreaks(
        level: ClientLevel,
        player: LocalPlayer,
        gameMode: MultiPlayerGameMode,
    ): Unit {
        val breakReach = minOf(
            PrinterSettingsHolder.printerSettings.reach,
            gameMode.pickRange.toDouble(),
        )
        val breakReachSquared = breakReach * breakReach
        scaffoldLedger.tickBreaks(
            tick = level.gameTime,
            stateAt = level::getBlockState,
            destroy = { pos -> requestCreativeBlockBreak(gameMode, level, pos) },
            canDestroy = { pos ->
                player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) <= breakReachSquared
            },
            breakStateAt = { pos ->
                ScaffoldBreakConnectionObserver.observedStateAt(pos) { level.getBlockState(pos) }
            },
            onAttemptSettled = { pos ->
                ScaffoldBreakConnectionObserver.finish(pos)
                ClientBlockInteractHandler.pendingBreakPositions.remove(pos)
            },
            onBroken = { scaffoldPos ->
                PrintWorldModel.recordWrite(scaffoldPos, Blocks.AIR.defaultBlockState())
                startupOrphanScaffolds.remove(scaffoldPos)
                scaffoldCellsBroken++
                scaffoldLedger.markConsumed(scaffoldPos)
            },
            onRetryExhausted = { scaffoldPos, attempts ->
                LogUtils.getLogger().error(
                    "C3DBG[scaffold] break retry exhausted pos={} attempts={}", scaffoldPos, attempts,
                )
            },
        )
    }

    internal fun sweepOrphanScaffolds(): Unit {
        val level = Minecraft.getInstance().level ?: return
        val content = SchematicHolder.renderingBlocks
        val bounds = schematicWorldBoundingBox(
            localPositions = content.blocks.keys,
            localToWorld = SchematicRenderManager::localBlockToWorld,
            margin = ORPHAN_SWEEP_MARGIN,
        ) ?: return
        val orphans = findOrphanScaffolds(
            boundsMin = bounds.first,
            boundsMax = bounds.second,
            expectedAt = { worldPos -> content.blocks[SchematicRenderManager.worldBlockToLocal(worldPos)] },
            stateAt = level::getBlockState,
            onBoundsExceeded = {
                LogUtils.getLogger().error(
                    "C3DBG[scaffold] orphan sweep skipped: box too large min={} max={}",
                    bounds.first,
                    bounds.second,
                )
            },
        )
        for (orphan in orphans) {
            if (scaffoldLedger.isScaffoldCell(orphan)) continue
            scaffoldLedger.recordPlaced(orphan, orphan)
            scaffoldLedger.markConsumed(orphan)
            startupOrphanScaffolds.add(orphan.immutable())
        }
    }

    private fun findScaffoldPlan(
        gatedMissingLocal: List<BlockPos>,
        content: SchematicCache,
        missingRevision: Long,
        eyePosition: Vec3,
        reach: Double,
        playerFeetPos: Vec3,
        isSchematicPosition: (BlockPos) -> Boolean,
    ): ScaffoldPlan? {
        val reachSquared = reach * reach
        for (localPos in gatedMissingLocal) {
            val expectedState = content.blocks[localPos] ?: continue
            val worldPos = SchematicRenderManager.localBlockToWorld(localPos)
            if (deferralLedger.isDeferred(worldPos, PrintWorldModel::stateAt, missingRevision)) continue
            if (
                isPlacementBlockedByPlayer(
                    worldPos,
                    expectedState,
                    playerFeetPos,
                    PrintWorldModel::stateAt,
                )
            ) {
                continue
            }
            val center = Vec3(worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5)
            if (center.distanceToSqr(eyePosition) > reachSquared) continue
            val eligibleAndReplaceable = isActionableMissing(
                worldPos,
                expectedState,
                PrintWorldModel::stateAt,
                hasSupport = { _, _, _ -> true },
            )
            if (!eligibleAndReplaceable) continue
            val supported = isActionableMissing(
                worldPos,
                expectedState,
                PrintWorldModel::stateAt,
                hasSupport = layerGateSupportMemo::hasSupport,
            )
            if (supported) continue
            val directPlan = ScaffoldPlanner.plan(
                targetPos = worldPos,
                expectedState = expectedState,
                isSchematicPosition = isSchematicPosition,
                stateAt = PrintWorldModel::stateAt,
                playerFeetPos = playerFeetPos,
            )
            if (directPlan != null) return directPlan
            val chainPlan = ScaffoldChainPlanner.plan(
                targetPos = worldPos,
                expectedState = expectedState,
                isSchematicPosition = isSchematicPosition,
                stateAt = PrintWorldModel::stateAt,
                playerFeetPos = playerFeetPos,
            )
            if (chainPlan != null) return chainPlan
        }
        return null
    }

    private fun synchronizeLayerGateSession(
        levelIdentity: Any,
        contentIdentity: Any,
        transformRevision: Long,
    ): Unit {
        if (
            !layerGateSessionInitialized ||
            layerGateLevelIdentity !== levelIdentity ||
            layerGateContentIdentity !== contentIdentity ||
            layerGateTransformRevision != transformRevision
        ) {
            resetLayerGateSession()
            layerGateSessionInitialized = true
            layerGateLevelIdentity = levelIdentity
            layerGateContentIdentity = contentIdentity
            layerGateTransformRevision = transformRevision
        }
    }

    private fun resetLayerGateSession(): Unit {
        cleanupScaffolds()
        layerGate.reset()
        layerGateLevelIdentity = null
        layerGateContentIdentity = null
        layerGateTransformRevision = 0L
        layerGateSessionInitialized = false
        layerGateUpdated = false
        layerGateY = null
        layerGatePhase = PrinterLayerGatePhase.ASCENT
        acceptedForLayerGate = 0
        previousLayerGatePlayerPosition = null
        scaffoldTerminalRearmed = false
        scaffoldCellsPlaced = 0
        scaffoldCellsBroken = 0
        scaffoldChainsUsed = 0
        scaffoldMaxChainLength = 0
        scaffoldStallTicks = 0
    }

    private const val GATE_REACH_MARGIN: Double = 1.0
    private const val PLAYER_MOVEMENT_DISTANCE_SQUARED: Double = 0.01
    private const val ORPHAN_SWEEP_MARGIN: Int = 2
    private const val SCAFFOLD_STALL_ABANDON_TICKS: Int = 200
}

internal data class LayerGateMissing(
    internal val localPos: BlockPos,
    internal val worldPos: BlockPos,
    internal val eligible: Boolean,
    internal val expectedState: BlockState,
)

/** Lazily maps schematic cells to layer-gate entries across structural revisions. */
internal class LayerGateMissingCache(
    private val eligibleState: (BlockState) -> Boolean,
    private val localToWorld: (BlockPos) -> BlockPos,
) {
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var substituteLookalikes: Boolean = true
    private var expectedStates: Map<BlockPos, BlockState> = emptyMap()
    private val memo: HashMap<BlockPos, LayerGateMissing> = HashMap()

    internal fun synchronize(
        contentIdentity: Any,
        expectedStates: Map<BlockPos, BlockState>,
        transformRevision: Long,
        substituteLookalikes: Boolean = true,
    ): Unit {
        val structurallyStale = this.contentIdentity !== contentIdentity ||
            this.transformRevision != transformRevision ||
            this.substituteLookalikes != substituteLookalikes
        if (structurallyStale) {
            memo.clear()
        }
        this.contentIdentity = contentIdentity
        this.expectedStates = expectedStates
        this.transformRevision = transformRevision
        this.substituteLookalikes = substituteLookalikes
    }

    internal operator fun get(localPos: BlockPos): LayerGateMissing? {
        memo[localPos]?.let { return it }
        val expectedState = expectedStates[localPos] ?: return null
        val entry = LayerGateMissing(
            localPos = localPos,
            worldPos = localToWorld(localPos),
            eligible = eligibleState(expectedState),
            expectedState = expectedState,
        )
        memo[localPos] = entry
        return entry
    }
}

internal data class LayerGateEligibleMissing(
    internal val sortedDistinctYs: List<Int>,
    internal val byY: Map<Int, Collection<LayerGateMissing>>,
)

/**
 * Maintains eligible layer-gate entries from incremental missing-block revisions.
 *
 * Structural changes or unavailable history trigger a budgeted rebuild; the previous
 * complete result stays visible until the rebuild finishes.
 */
internal class LayerGateEligibleMissingCache(
    private val rebuildBudget: Int = PrintWorldModel.CAPTURE_CELLS_PER_TICK,
    private val entryAt: (BlockPos) -> LayerGateMissing?,
) {
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var substituteLookalikes: Boolean = true
    private var lastAppliedRevision: Long = 0L
    private val byY: HashMap<Int, LinkedHashMap<BlockPos, LayerGateMissing>> = HashMap()
    private val yByLocal: HashMap<BlockPos, Int> = HashMap()
    private var sortedYs: List<Int> = emptyList()
    private var sortedYsDirty: Boolean = false

    internal var rebuildCount: Int = 0
        private set

    private var pendingEntries: Iterator<BlockPos>? = null
    private var pendingContentIdentity: Any? = null
    private var pendingTransformRevision: Long = 0L
    private var pendingSubstituteLookalikes: Boolean = true
    private var pendingMissingRevisionAtBegin: Long = 0L
    private var workingByY: HashMap<Int, LinkedHashMap<BlockPos, LayerGateMissing>> = HashMap()
    private var workingYByLocal: HashMap<BlockPos, Int> = HashMap()

    internal fun isRebuilding(): Boolean = pendingEntries != null

    internal fun sync(
        contentIdentity: Any,
        transformRevision: Long,
        substituteLookalikes: Boolean,
        missingRevision: Long,
        missingLocal: List<BlockPos>,
        changesSince: (Long) -> List<MissingBlockChange>?,
    ): LayerGateEligibleMissing {
        if (pendingEntries != null) {
            val stillSameTarget = pendingContentIdentity === contentIdentity &&
                pendingTransformRevision == transformRevision &&
                pendingSubstituteLookalikes == substituteLookalikes
            if (!stillSameTarget) {
                beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, missingRevision)
            }
            pumpRebuild(rebuildBudget)
            return snapshot()
        }
        val structurallyStale = this.contentIdentity !== contentIdentity ||
            this.transformRevision != transformRevision ||
            this.substituteLookalikes != substituteLookalikes
        if (structurallyStale) {
            beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, missingRevision)
            pumpRebuild(rebuildBudget)
        } else if (lastAppliedRevision != missingRevision) {
            val changes = changesSince(lastAppliedRevision)
            if (changes != null) {
                for (change in changes) applyChange(change)
                lastAppliedRevision = missingRevision
            } else {
                beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, missingRevision)
                pumpRebuild(rebuildBudget)
            }
        }
        return snapshot()
    }

    internal fun rebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
        substituteLookalikes: Boolean = true,
    ): LayerGateEligibleMissing {
        beginRebuild(contentIdentity, transformRevision, missingLocal, substituteLookalikes, lastAppliedRevision)
        pumpRebuild(Int.MAX_VALUE)
        return snapshot()
    }

    private fun beginRebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
        substituteLookalikes: Boolean,
        missingRevisionAtBegin: Long,
    ): Unit {
        rebuildCount++
        pendingEntries = missingLocal.iterator()
        pendingContentIdentity = contentIdentity
        pendingTransformRevision = transformRevision
        pendingSubstituteLookalikes = substituteLookalikes
        pendingMissingRevisionAtBegin = missingRevisionAtBegin
        workingByY = HashMap()
        workingYByLocal = HashMap()
    }

    private fun pumpRebuild(budget: Int): Unit {
        val entries = pendingEntries ?: return
        var processed = 0
        while (entries.hasNext() && processed < budget) {
            val localPos = entries.next()
            val entry = entryAt(localPos)
            if (entry != null && entry.eligible) {
                workingByY.getOrPut(entry.worldPos.y) { LinkedHashMap() }[localPos] = entry
                workingYByLocal[localPos] = entry.worldPos.y
            }
            processed++
        }
        if (entries.hasNext()) return
        byY.clear()
        byY.putAll(workingByY)
        yByLocal.clear()
        yByLocal.putAll(workingYByLocal)
        contentIdentity = pendingContentIdentity
        transformRevision = pendingTransformRevision
        substituteLookalikes = pendingSubstituteLookalikes
        lastAppliedRevision = pendingMissingRevisionAtBegin
        sortedYsDirty = true
        pendingEntries = null
        workingByY = HashMap()
        workingYByLocal = HashMap()
    }

    internal fun applyChange(change: MissingBlockChange): Unit {
        if (!change.overlayChanged) return
        val existingY = yByLocal.remove(change.localPos)
        if (existingY != null) {
            val bucket = byY[existingY]
            bucket?.remove(change.localPos)
            if (bucket != null && bucket.isEmpty()) {
                byY.remove(existingY)
                sortedYsDirty = true
            }
        }
        if (!change.satisfied) {
            val entry = entryAt(change.localPos)
            if (entry != null && entry.eligible) {
                val isNewY = entry.worldPos.y !in byY
                byY.getOrPut(entry.worldPos.y) { LinkedHashMap() }[change.localPos] = entry
                yByLocal[change.localPos] = entry.worldPos.y
                if (isNewY) sortedYsDirty = true
            }
        }
    }

    internal fun snapshot(): LayerGateEligibleMissing {
        if (sortedYsDirty) {
            sortedYs = byY.keys.sorted()
            sortedYsDirty = false
        }
        return LayerGateEligibleMissing(
            sortedDistinctYs = sortedYs,
            byY = byY.mapValues { (_, bucket) -> bucket.values },
        )
    }
}

internal class LayerGateSupportMemo {
    private var missingRevision: Long = 0L
    private var transformRevision: Long = 0L
    private var contentIdentity: Any? = null
    private var levelIdentity: Any? = null
    private val supportedByWorld: HashMap<BlockPos, Boolean> = HashMap()

    internal fun synchronize(
        missingRevision: Long,
        transformRevision: Long,
        contentIdentity: Any,
        levelIdentity: Any,
    ): Unit {
        if (
            this.missingRevision != missingRevision ||
            this.transformRevision != transformRevision ||
            this.contentIdentity !== contentIdentity ||
            this.levelIdentity !== levelIdentity
        ) {
            supportedByWorld.clear()
        }
        this.missingRevision = missingRevision
        this.transformRevision = transformRevision
        this.contentIdentity = contentIdentity
        this.levelIdentity = levelIdentity
    }

    internal fun hasSupport(
        worldPos: BlockPos,
        expectedState: BlockState,
        stateAt: (BlockPos) -> BlockState,
    ): Boolean {
        return supportedByWorld.getOrPut(worldPos.immutable()) {
            hasSupportNeighbor(worldPos, expectedState, stateAt)
        }
    }
}

/** Memoizes scaffold eligibility for one missing/content/transform/world revision. */
internal class LayerGateScaffoldMemo {
    private var missingRevision: Long = 0L
    private var transformRevision: Long = 0L
    private var contentIdentity: Any? = null
    private var levelIdentity: Any? = null
    private val scaffoldableByWorld: HashMap<BlockPos, Boolean> = HashMap()

    internal fun synchronize(
        missingRevision: Long,
        transformRevision: Long,
        contentIdentity: Any,
        levelIdentity: Any,
    ): Unit {
        if (
            this.missingRevision != missingRevision ||
            this.transformRevision != transformRevision ||
            this.contentIdentity !== contentIdentity ||
            this.levelIdentity !== levelIdentity
        ) {
            scaffoldableByWorld.clear()
        }
        this.missingRevision = missingRevision
        this.transformRevision = transformRevision
        this.contentIdentity = contentIdentity
        this.levelIdentity = levelIdentity
    }

    internal fun canScaffold(
        worldPos: BlockPos,
        expectedState: BlockState,
        stateAt: (BlockPos) -> BlockState,
        isSchematicPosition: (BlockPos) -> Boolean,
        playerFeetPos: Vec3?,
    ): Boolean {
        return scaffoldableByWorld.getOrPut(worldPos.immutable()) {
            isScaffoldAssistable(worldPos, expectedState, stateAt, isSchematicPosition, playerFeetPos)
        }
    }
}

internal enum class ScaffoldTerminalOutcome {
    ALLOW_CLEAR,
    ALLOW_FORCE_CLEANUP,
    REARM_AND_REFUSE,
    REFUSE,
}

internal fun scaffoldTerminalOutcome(
    actionable: Int,
    scaffoldOutstanding: Boolean,
    alreadyRearmedThisEncounter: Boolean,
): ScaffoldTerminalOutcome {
    if (actionable != 0) return ScaffoldTerminalOutcome.REFUSE
    if (!scaffoldOutstanding) return ScaffoldTerminalOutcome.ALLOW_CLEAR
    return if (alreadyRearmedThisEncounter) {
        ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP
    } else {
        ScaffoldTerminalOutcome.REARM_AND_REFUSE
    }
}

internal data class PrinterCompleteBreakdown(
    internal val totalRemaining: Int,
    internal val excluded: Int,
    internal val unsupported: Int,
    internal val scaffoldAssisted: Int,
    internal val unreachable: Int,
    internal val occupied: Int,
    internal val deferred: Int,
    internal val actionable: Int,
)

internal data class PrinterScaffoldTelemetry(
    internal val placed: Int,
    internal val broken: Int,
    internal val chains: Int,
    internal val maxChainLength: Int,
)

internal data class MissingClassification(
    internal val excluded: Int,
    internal val occupied: Int,
    internal val unreachable: Int,
    internal val deferred: Int,
    internal val unsupported: Int,
    internal val scaffoldAssisted: Int,
    internal val actionable: List<Pair<BlockPos, BlockState>>,
)

/**
 * Partitions missing targets by executable blocker and returns actionable placements.
 *
 * Scaffold-assisted targets are counted separately but remain actionable.
 */
internal fun classifyMissing(
    entries: List<Pair<BlockPos, BlockState>>,
    stateAt: (BlockPos) -> BlockState,
    queueRevision: Long,
    deferralLedger: PrinterDeferralLedger,
    isSchematicPosition: (BlockPos) -> Boolean,
    playerFeetPos: Vec3?,
): MissingClassification {
    var excluded = 0
    var occupied = 0
    var unreachable = 0
    var deferredCount = 0
    var unsupported = 0
    var scaffoldAssisted = 0
    val actionable = mutableListOf<Pair<BlockPos, BlockState>>()
    for ((worldPos, expected) in entries) {
        if (eligiblePrinterBlockItem(expected) == null) {
            excluded++
            continue
        }
        if (!isReplaceableTarget(stateAt(worldPos))) {
            occupied++
            continue
        }
        val activeCauses = deferralLedger.activeReasons(worldPos, stateAt, queueRevision)
        when {
            PrinterDeferralReason.MOVER_UNREACHABLE in activeCauses -> unreachable++
            activeCauses.isNotEmpty() -> deferredCount++
            hasSupportNeighbor(worldPos, expected, stateAt) -> actionable.add(worldPos to expected)
            isScaffoldAssistable(worldPos, expected, stateAt, isSchematicPosition, playerFeetPos) -> {
                scaffoldAssisted++
                actionable.add(worldPos to expected)
            }
            else -> unsupported++
        }
    }
    return MissingClassification(
        excluded = excluded,
        occupied = occupied,
        unreachable = unreachable,
        deferred = deferredCount,
        unsupported = unsupported,
        scaffoldAssisted = scaffoldAssisted,
        actionable = actionable,
    )
}

internal data class LookRotation(
    internal val yaw: Double,
    internal val pitch: Double,
)

internal fun placementRotation(eyePosition: Vec3, hitLocation: Vec3): LookRotation {
    val dx = hitLocation.x - eyePosition.x
    val dy = hitLocation.y - eyePosition.y
    val dz = hitLocation.z - eyePosition.z
    return LookRotation(
        yaw = Math.toDegrees(atan2(-dx, dz)),
        pitch = Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))),
    )
}

internal class CameraEase {
    private var targetYaw: Float? = null
    private var targetPitch: Float? = null

    internal fun setTarget(yaw: Float, pitch: Float): Unit {
        targetYaw = yaw
        targetPitch = pitch
    }

    internal fun ease(currentYaw: Float, currentPitch: Float): Pair<Float, Float> {
        val yaw = targetYaw ?: return currentYaw to currentPitch
        val pitch = targetPitch ?: currentPitch
        return step(currentYaw, yaw, MAX_STEP_DEGREES) to step(currentPitch, pitch, MAX_STEP_DEGREES)
    }

    internal companion object {
        internal const val MAX_STEP_DEGREES: Float = 15f

        internal fun step(current: Float, target: Float, maxStep: Float): Float {
            var delta = (target - current) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return when {
                delta > maxStep -> current + maxStep
                delta < -maxStep -> current - maxStep
                else -> current + delta
            }
        }
    }
}

internal class FacePlacementGateway(
    private val delegate: PlacementGateway,
    private val facePlacement: Boolean,
    private val eyePosition: Vec3,
    private val setCameraTarget: (Float, Float) -> Unit,
) : PlacementGateway {
    private var submitted: Boolean = false

    override fun submit(hit: BlockHitResult, requiredRotation: PlacementRotation?): Boolean {
        if (!submitted) {
            submitted = true
            if (facePlacement) {
                val rotation = placementRotation(eyePosition, hit.location)
                setCameraTarget(rotation.yaw.toFloat(), rotation.pitch.toFloat())
            }
        }
        return delegate.submit(hit, requiredRotation)
    }
}

internal class RealPlacementGateway(
    private val gameMode: MultiPlayerGameMode,
    private val player: LocalPlayer,
    private val level: ClientLevel,
) : PlacementGateway {
    override fun submit(hit: BlockHitResult, requiredRotation: PlacementRotation?): Boolean {
        if (requiredRotation == null) {
            return gameMode.useItemOn(player, level, InteractionHand.MAIN_HAND, hit).consumesAction()
        }
        val originalYaw = player.yRot
        val originalPitch = player.xRot
        return submitOrientedPlacement(
            requiredRotation = requiredRotation,
            originalRotation = PlacementRotation(originalYaw, originalPitch),
            setLocalRotation = { rotation ->
                player.setYRot(rotation.yaw)
                player.setXRot(rotation.pitch)
            },
            sendServerRotation = { rotation ->
                player.connection.send(
                    ServerboundMovePlayerPacket.Rot(rotation.yaw, rotation.pitch, player.isOnGround),
                )
            },
            useItemOn = {
                gameMode.useItemOn(player, level, InteractionHand.MAIN_HAND, hit).consumesAction()
            },
        )
    }
}

internal fun submitOrientedPlacement(
    requiredRotation: PlacementRotation,
    originalRotation: PlacementRotation,
    setLocalRotation: (PlacementRotation) -> Unit,
    sendServerRotation: (PlacementRotation) -> Unit,
    useItemOn: () -> Boolean,
): Boolean {
    return try {
        setLocalRotation(requiredRotation)
        sendServerRotation(requiredRotation)
        useItemOn()
    } finally {
        setLocalRotation(originalRotation)
        sendServerRotation(originalRotation)
    }
}
