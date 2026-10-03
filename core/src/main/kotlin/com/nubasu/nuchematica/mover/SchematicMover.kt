package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import com.nubasu.nuchematica.printer.PrinterSessionKey
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.printer.isActionableMissing
import com.nubasu.nuchematica.printer.isScaffoldAssistable
import com.nubasu.nuchematica.printer.schematicWorldBoundingBox
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.MissingBlockChange
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.Input
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal const val MOVER_MINIMUM_REACH: Double = 4.0

/** Exposes only each orphan scaffold column's top cell, nearest first. */
internal fun exposedStartupOrphansNearestFirst(
    positions: Collection<BlockPos>,
    playerPosition: Vec3,
): List<BlockPos> {
    val distinct = positions.mapTo(LinkedHashSet()) { pos -> pos.immutable() }
    return distinct.asSequence()
        .filter { pos -> pos.above() !in distinct }
        .sortedWith(
            compareBy(
                { pos -> Vec3.atCenterOf(pos).distanceToSqr(playerPosition) },
                { pos -> pos.x },
                { pos -> pos.y },
                { pos -> pos.z },
            ),
        )
        .toList()
}

/** Applies stop and velocity-reset flags without altering non-flying motion. */
internal fun controlledMoverVelocity(
    currentVelocity: Vec3,
    command: MoverCommand,
    flying: Boolean,
): Vec3 {
    if (command.stopMovement) return Vec3.ZERO
    if (!flying) return currentVelocity
    if (!command.resetHorizontalVelocity && currentVelocity.y == 0.0) return currentVelocity
    return Vec3(
        if (command.resetHorizontalVelocity) 0.0 else currentVelocity.x,
        0.0,
        if (command.resetHorizontalVelocity) 0.0 else currentVelocity.z,
    )
}

public object SchematicMover {
    private val INACTIVE_COMMAND = MoverCommand(
        jump = false,
        enableFlight = false,
        horizontalX = 0.0,
        horizontalZ = 0.0,
        vertical = 0,
        stopMovement = false,
    )
    private val STOP_COMMAND = INACTIVE_COMMAND.copy(stopMovement = true)

    internal val core: MoverCore = MoverCore()
    private val planTravelBoundsCache = PlanTravelBoundsCache()
    private val missingWorldCache = MoverMissingWorldCache(
        localToWorld = { pos -> SchematicRenderManager.localBlockToWorld(pos) },
    )
    private val placeabilityMemo = MoverPlaceabilityMemo()
    private val runTelemetry = MoverRunTelemetry()
    private var terminalSummaryLogged: Boolean = true

    internal var latestStatus: MoverStatus? = null
        private set

    private var latestCommand: MoverCommand = INACTIVE_COMMAND
    private var stopMovementApplied: Boolean = false

    internal fun toggleRequested(): Unit {
        val minecraft = Minecraft.getInstance()
        if (core.status().state.isActive()) {
            core.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
            latestStatus = core.status()
            logTerminalSummary(latestStatus!!)
            latestCommand = STOP_COMMAND
            minecraft.player?.let { player -> applyCommand(player, STOP_COMMAND) }
            MoverConnectionObserver.remove()
            SchematicPrinter.cleanupScaffolds()
            ChatSender.send("[nuchematica] auto-move: OFF")
            return
        }

        if (!SchematicPrinter.enabled) {
            ChatSender.send("[nuchematica] auto-move requires printer (P)")
            return
        }

        val player = minecraft.player
        val gameMode = minecraft.gameMode
        val isCreative = minecraft.level != null &&
            player != null &&
            gameMode?.playerMode?.isCreative == true
        val mayfly = player?.abilities?.mayfly == true
        val hasMissing = MissingBlockHolder.hasMissing()
        val effectiveReach = gameMode?.let { currentGameMode ->
            minOf(
                PrinterSettingsHolder.printerSettings.reach,
                currentGameMode.pickRange.toDouble(),
            )
        } ?: 0.0
        if (isCreative && mayfly && hasMissing && effectiveReach < MOVER_MINIMUM_REACH) {
            latestStatus = core.status()
            ChatSender.send("[nuchematica] auto-move requires reach >= 4.0")
            return
        }
        val state = core.toggleRequested(
            mayfly = mayfly,
            isCreative = isCreative,
            hasMissing = hasMissing,
        )
        latestStatus = core.status()

        when {
            !isCreative -> ChatSender.send("[nuchematica] auto-move requires creative")
            !mayfly -> ChatSender.send("[nuchematica] auto-move requires flight permission")
            !hasMissing -> ChatSender.send("[nuchematica] nothing to build")
            state == MoverState.TAKEOFF && player != null -> {
                runTelemetry.reset()
                terminalSummaryLogged = false
                SchematicPrinter.clearMoverCauses()
                SchematicPrinter.sweepOrphanScaffolds()
                latestCommand = INACTIVE_COMMAND
                stopMovementApplied = false
                MoverConnectionObserver.install(player.connection.connection)
                ChatSender.send("[nuchematica] auto-move: ON")
            }
        }
    }

    internal fun tick(): Unit {
        val minecraft = Minecraft.getInstance()
        val beforeState = core.status().state
        if (!beforeState.isActive()) {
            if (beforeState == MoverState.ABORTED || beforeState == MoverState.COMPLETE) {
                MoverConnectionObserver.remove()
            }
            return
        }

        val level = minecraft.level
        val player = minecraft.player
        val gameMode = minecraft.gameMode
        if (level == null || player == null || gameMode == null) {
            deactivateWithoutChat(player)
            return
        }
        if (
            PrintWorldModel.status() == PrintWorldModel.Status.CAPTURING ||
            MissingBlockHolder.isInitializing()
        ) {
            return
        }
        // The printer ticks first and may disable itself after creative mode is lost.
        if (!SchematicPrinter.enabled && gameMode.playerMode.isCreative) {
            stopForPrinterOff(player)
            return
        }
        val startupOrphans = SchematicPrinter.pendingStartupOrphanScaffolds()
        val gateY = SchematicPrinter.currentLayerGateY()
        val planModeActive = startupOrphans.isNotEmpty() ||
            (PrinterSettingsHolder.printerSettings.planFirstMode && SchematicPrinter.enabled)
        MoverConnectionObserver.install(player.connection.connection)
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val transformRevision = SchematicRenderManager.currentTransformRevision()
        val printerSessionKey = PrinterSessionKey(
            level = level,
            content = content,
            transformRevision = transformRevision,
        )
        val latestFeedSnapshot = SchematicPrinter.latestFeedSnapshot(printerSessionKey)
            ?.takeIf { snapshot -> snapshot.tick == level.gameTime }
        val freshFeedSnapshot = SchematicPrinter.freshFeedSnapshot(
            sessionKey = printerSessionKey,
            queueRevision = missing.revision,
        )
        val correctionPacket = MoverConnectionObserver.consumeCorrection()
        if (correctionPacket != null) {
            val collisionDiagnostic = if (correctionPacket.relativeArguments.isEmpty()) {
                val serverPosition = Vec3(correctionPacket.x, correctionPacket.y, correctionPacket.z)
                val attemptedDelta = player.position().subtract(serverPosition)
                val serverBox = player.boundingBox.move(serverPosition.subtract(player.position()))
                attemptedDelta to Entity.collideBoundingBox(
                    player,
                    attemptedDelta,
                    serverBox,
                    level,
                    emptyList(),
                )
            } else {
                null
            }
            com.mojang.logging.LogUtils.getLogger().info(
                "C3MOV correction-packet raw=({}, {}, {}) relative={} player={} velocity={} " +
                    "previousCommand=[x={},z={},vertical={},resetHorizontal={}] " +
                    "collisionAttempted={} collisionAllowed={}",
                correctionPacket.x,
                correctionPacket.y,
                correctionPacket.z,
                correctionPacket.relativeArguments,
                player.position(),
                player.deltaMovement,
                latestCommand.horizontalX,
                latestCommand.horizontalZ,
                latestCommand.vertical,
                latestCommand.resetHorizontalVelocity,
                collisionDiagnostic?.first,
                collisionDiagnostic?.second,
            )
        }
        runTelemetry.record(beforeState, latestFeedSnapshot)
        val missingWorld = missingWorldCache.sync(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            missingLocal = missing.missingLocal,
            changesSince = MissingBlockHolder::changesSince,
        )
        if (missingWorldCache.isRebuilding()) return
        placeabilityMemo.synchronize(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            levelIdentity = level,
        )
        val command = core.tick(
            MoverTickContext(
                sessionKey = MoverSessionKey(
                    levelIdentity = level,
                    contentIdentity = content,
                    transformRevision = transformRevision,
                ),
                playerPos = player.position(),
                onGround = player.isOnGround,
                flying = player.abilities.flying,
                mayfly = player.abilities.mayfly,
                isCreative = gameMode.playerMode.isCreative,
                guiOpen = minecraft.screen != null,
                hurt = player.hurtTime > 0,
                manualInput = manualInput(minecraft),
                correctionReceived = correctionPacket != null,
                queueRevision = missing.revision,
                feedSnapshot = freshFeedSnapshot,
                gateY = gateY,
                gatePhase = SchematicPrinter.currentLayerGatePhase(),
                missingWorld = missingWorld,
                isPlaceable = { worldPos ->
                    val categoryAndSupport = placeabilityMemo.isPlaceable(worldPos) {
                        val localPos = missingWorldCache.localPosition(worldPos)
                        val expectedState = localPos?.let { local -> content.blocks[local] }
                        expectedState != null && isActionableMissing(
                            worldPos,
                            expectedState,
                            PrintWorldModel::stateAt,
                            canScaffold = { pos, effectiveExpectedState, stateAt ->
                                isScaffoldAssistable(
                                    worldPos = pos,
                                    expectedState = effectiveExpectedState,
                                    stateAt = stateAt,
                                    isSchematicPosition = { candidate ->
                                        content.blocks.containsKey(
                                            SchematicRenderManager.worldBlockToLocal(candidate),
                                        )
                                    },
                                    playerFeetPos = player.position(),
                                )
                            },
                        )
                    }
                    categoryAndSupport &&
                        !SchematicPrinter.isDeferredWorldPos(worldPos, missing.revision) &&
                        (gateY == null || worldPos.y <= gateY)
                },
                expectedStateAt = { worldPos ->
                    missingWorldCache.localPosition(worldPos)?.let { local -> content.blocks[local] }
                },
                isPassableCell = { worldPos ->
                    level.getBlockState(worldPos)
                        .getCollisionShape(level, worldPos)
                        .isEmpty
                },
                reach = minOf(
                    PrinterSettingsHolder.printerSettings.reach,
                    gameMode.pickRange.toDouble(),
                ),
                pathProbe = { from, to -> pathProbe(player, level, from, to) },
                onWorkPositionAbandoned = { _ -> Unit },
                onWorkPositionUnproductive = { _, deferPositions ->
                    SchematicPrinter.deferNoProgress(deferPositions)
                },
                onPositionsUncoverable = { positions, cause ->
                    when (cause) {
                        MoverDeferralCause.MOVER_UNREACHABLE ->
                            SchematicPrinter.deferUnreachable(positions)
                        MoverDeferralCause.NO_PROGRESS ->
                            SchematicPrinter.deferNoProgress(positions)
                    }
                },
                onPlanPositionUnreachable = { worldPos ->
                    if (startupOrphans.isEmpty()) SchematicPrinter.requestPlanPositionUnreachable(worldPos)
                },
                canComplete = {
                    startupOrphans.isEmpty() && SchematicPrinter.canAutoMoveComplete()
                },
                onFinalSweepBackoffBypass = {
                    SchematicPrinter.grantFinalSweepBackoffBypass()
                },
                onHoldHardCap = {
                    com.mojang.logging.LogUtils.getLogger().warn(
                        "C3DBG[hold] hard cap t={} target={} queueRevision={}",
                        level.gameTime,
                        core.status().target,
                        missing.revision,
                    )
                },
                planMode = planModeActive,
                planStateAt = if (planModeActive) level::getBlockState else null,
                planSupportStateAt = if (planModeActive) level::getBlockState else null,
                planBreakTargets = if (startupOrphans.isNotEmpty()) {
                    startupOrphans.mapTo(LinkedHashSet()) { pos -> pos.immutable() }
                } else {
                    emptySet()
                },
                planTravelBounds = if (planModeActive) {
                    planTravelBoundsCache.get(content, transformRevision)
                } else {
                    null
                },
                planFrontier = if (startupOrphans.isNotEmpty()) {
                    val inFlight = startupOrphans.filter(SchematicPrinter::ownsPendingBreak)
                    val waiting = exposedStartupOrphansNearestFirst(startupOrphans, player.position())
                        .filterNot(SchematicPrinter::ownsPendingBreak)
                    val slime = Blocks.SLIME_BLOCK.defaultBlockState()
                    MoverPlanFrontier(
                        waitingForReach = waiting.map { pos -> MoverPlanFrontierTarget(pos, slime) },
                        columnBlocked = emptyList(),
                        inFlight = inFlight,
                        inFlightCollisionStates = inFlight.map { pos -> MoverPlanFrontierTarget(pos, slime) },
                        totalWaitingForReach = waiting.size,
                        totalColumnBlocked = 0,
                        totalRemainingActions = startupOrphans.size,
                    )
                } else {
                    SchematicPrinter.planFrontierSnapshot()?.let { snapshot ->
                        MoverPlanFrontier(
                            waitingForReach = snapshot.waitingForReach
                                .map { target -> MoverPlanFrontierTarget(target.pos, target.expected) },
                            columnBlocked = snapshot.columnBlocked,
                            inFlight = snapshot.inFlight,
                            inFlightCollisionStates = snapshot.inFlightCollisionStates
                                .map { target -> MoverPlanFrontierTarget(target.pos, target.expected) },
                            totalWaitingForReach = snapshot.totalWaitingForReach,
                            totalColumnBlocked = snapshot.totalColumnBlocked,
                            totalRemainingActions = snapshot.totalRemainingActions,
                        )
                    }
                },
                planSessionFinal = startupOrphans.isEmpty() && SchematicPrinter.isPlanSessionFinal(),
            ),
        )
        latestCommand = command
        latestStatus = core.status()
        applyCommand(player, command)
        notifyTerminalTransition(beforeState, latestStatus!!)
    }

    internal fun worldUnloaded(): Unit {
        deactivateWithoutChat(Minecraft.getInstance().player)
    }

    public fun onMovementInput(player: Player, input: Input): Unit {
        val status = latestStatus ?: return
        val command = latestCommand
        if (!status.state.isActive() || command.stopMovement) return

        val localPlayer = Minecraft.getInstance().player ?: return
        if (player !== localPlayer) return
        val yaw = Math.toRadians(localPlayer.yRot.toDouble())
        input.leftImpulse = (
            command.horizontalX * cos(yaw) + command.horizontalZ * sin(yaw)
            ).toFloat()
        input.forwardImpulse = (
            -command.horizontalX * sin(yaw) + command.horizontalZ * cos(yaw)
            ).toFloat()
        input.jumping = command.jump || command.vertical > 0
        input.shiftKeyDown = command.vertical < 0
    }

    public fun onLoggedOut(player: LocalPlayer?): Unit {
        deactivateWithoutChat(player)
    }

    private fun stopForPrinterOff(player: LocalPlayer?): Unit {
        core.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
        latestStatus = core.status()
        logTerminalSummary(latestStatus!!)
        latestCommand = STOP_COMMAND
        player?.let { applyCommand(it, STOP_COMMAND) }
        MoverConnectionObserver.remove()
        SchematicPrinter.cleanupScaffolds()
        ChatSender.send("[nuchematica] auto-move stopped (printer off)")
    }

    private fun deactivateWithoutChat(player: LocalPlayer?): Unit {
        if (core.status().state.isActive()) {
            core.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
        }
        latestStatus = core.status()
        latestStatus?.let(::logTerminalSummary)
        latestCommand = STOP_COMMAND
        player?.let { applyCommand(it, STOP_COMMAND) }
        MoverConnectionObserver.remove()
        SchematicPrinter.cleanupScaffolds()
    }

    private fun notifyTerminalTransition(beforeState: MoverState, status: MoverStatus): Unit {
        if (status.state == beforeState) return
        logTerminalSummary(status)
        when (status.state) {
            MoverState.ABORTED -> {
                MoverConnectionObserver.remove()
                SchematicPrinter.cleanupScaffolds()
                status.abortReason?.let { reason ->
                    ChatSender.send("[nuchematica] auto-move aborted: $reason")
                }
            }
            MoverState.COMPLETE -> {
                MoverConnectionObserver.remove()
                val breakdown = SchematicPrinter.completeBreakdown()
                ChatSender.send(
                    "[nuchematica] auto-move complete (remaining=${breakdown.totalRemaining} " +
                        "actionable=${breakdown.actionable} excluded=${breakdown.excluded} " +
                        "unsupported=${breakdown.unsupported} scaffoldAssisted=${breakdown.scaffoldAssisted} " +
                        "unreachable=${breakdown.unreachable} " +
                        "occupied=${breakdown.occupied} deferred=${breakdown.deferred})",
                )
            }
            else -> Unit
        }
    }

    private fun logTerminalSummary(status: MoverStatus): Unit {
        if (
            terminalSummaryLogged ||
            (status.state != MoverState.COMPLETE && status.state != MoverState.ABORTED)
        ) {
            return
        }
        val utilization = runTelemetry.snapshot()
        val path = core.pathTelemetry()
        val lane = core.laneTelemetry()
        val scaffold = SchematicPrinter.scaffoldTelemetry()
        com.mojang.logging.LogUtils.getLogger().info(
            "C3MOV summary terminal={} active={} U_feed={}/{} submitted={} accepted={} " +
                "CRUISE[active={} U_feed={} submitted={} accepted={}] " +
                "HOLD[active={} U_feed={} submitted={} accepted={}] " +
                "astar[invocations={} successes={}] " +
                "astarFail[volume={} budget={} nopath={} blocked={}] " +
                "astarResolve[startRescues={} goalRescues={}] " +
                "smoothing[waypoints={}->{}] " +
                "lane[passes={} segments={} laneTicks={}] " +
                "scaffold[placed={} broken={} chains={} maxLen={}]",
            status.state,
            utilization.totalActiveTicks,
            utilization.feedTicks,
            utilization.totalActiveTicks,
            utilization.submittedCount,
            utilization.acceptedCount,
            utilization.cruise.activeTicks,
            utilization.cruise.feedTicks,
            utilization.cruise.submittedCount,
            utilization.cruise.acceptedCount,
            utilization.hold.activeTicks,
            utilization.hold.feedTicks,
            utilization.hold.submittedCount,
            utilization.hold.acceptedCount,
            path.aStarInvocations,
            path.aStarSuccesses,
            path.aStarFailVolumeClamped,
            path.aStarFailBudgetExhausted,
            path.aStarFailNoPath,
            path.aStarFailStartOrGoalBlocked,
            path.aStarResolveStartRescues,
            path.aStarResolveGoalRescues,
            path.smoothingWaypointsIn,
            path.smoothingWaypointsOut,
            lane.passes,
            lane.segments,
            lane.laneTicks,
            scaffold.placed,
            scaffold.broken,
            scaffold.chains,
            scaffold.maxChainLength,
        )
        terminalSummaryLogged = true
    }

    private fun applyCommand(player: LocalPlayer, command: MoverCommand): Unit {
        if (command.enableFlight && player.abilities.mayfly) {
            player.abilities.flying = true
            player.onUpdateAbilities()
        }
        val currentVelocity = player.deltaMovement
        val controlledVelocity = controlledMoverVelocity(
            currentVelocity = currentVelocity,
            command = command,
            flying = player.abilities.flying,
        )
        if (command.stopMovement) {
            if (!stopMovementApplied) {
                player.setDeltaMovement(controlledVelocity)
                stopMovementApplied = true
            }
        } else {
            stopMovementApplied = false
            if (controlledVelocity != currentVelocity) {
                player.setDeltaMovement(controlledVelocity)
            }
        }
    }

    private fun manualInput(minecraft: Minecraft): Boolean {
        val options = minecraft.options
        return options.keyUp.isDown ||
            options.keyDown.isDown ||
            options.keyLeft.isDown ||
            options.keyRight.isDown ||
            options.keyJump.isDown ||
            options.keyShift.isDown
    }

    private fun pathProbe(
        player: LocalPlayer,
        level: ClientLevel,
        from: Vec3,
        to: Vec3,
    ): PathProbeResult {
        val delta = to.subtract(from)
        val steps = ceil(delta.length()).toInt().coerceAtLeast(1)
        val stepDelta = delta.scale(1.0 / steps.toDouble())
        var box = player.boundingBox.move(from.subtract(player.position()))

        for (step in 1..steps) {
            val stepPosition = from.add(stepDelta.scale(step.toDouble()))
            val chunkX = floor(stepPosition.x).toInt() shr CHUNK_SHIFT
            val chunkZ = floor(stepPosition.z).toInt() shr CHUNK_SHIFT
            if (!level.chunkSource.hasChunk(chunkX, chunkZ)) {
                return PathProbeResult(clear = false, chunkLoaded = false)
            }

            val allowed = Entity.collideBoundingBox(
                player,
                stepDelta,
                box,
                level,
                emptyList(),
            )
            if (
                abs(allowed.x - stepDelta.x) > COLLISION_EPSILON ||
                abs(allowed.y - stepDelta.y) > COLLISION_EPSILON ||
                abs(allowed.z - stepDelta.z) > COLLISION_EPSILON
            ) {
                return PathProbeResult(clear = false, chunkLoaded = true)
            }
            box = box.move(stepDelta)
        }
        return PathProbeResult(clear = true, chunkLoaded = true)
    }

    private fun MoverState.isActive(): Boolean {
        return this == MoverState.TAKEOFF ||
            this == MoverState.CRUISE ||
            this == MoverState.HOLD
    }

    private const val CHUNK_SHIFT: Int = 4
    private const val COLLISION_EPSILON: Double = 0.0000001
}

private class PlanTravelBoundsCache {
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var cached: Pair<BlockPos, BlockPos>? = null
    private var populated: Boolean = false

    internal fun get(content: SchematicCache, transformRevision: Long): Pair<BlockPos, BlockPos>? {
        if (populated && contentIdentity === content && this.transformRevision == transformRevision) {
            return cached
        }
        val worldBounds = schematicWorldBoundingBox(
            content.blocks.keys,
            SchematicRenderManager::localBlockToWorld,
            margin = PLAN_TRAVEL_BOUNDS_MARGIN,
        )
        cached = worldBounds?.let { (min, max) ->
            BlockPos(
                min.x - PLAN_TRAVEL_BOUNDS_INFLATE,
                min.y - PLAN_TRAVEL_BOUNDS_INFLATE,
                min.z - PLAN_TRAVEL_BOUNDS_INFLATE,
            ) to BlockPos(
                max.x + PLAN_TRAVEL_BOUNDS_INFLATE,
                max.y + PLAN_TRAVEL_BOUNDS_INFLATE,
                max.z + PLAN_TRAVEL_BOUNDS_INFLATE,
            )
        }
        contentIdentity = content
        this.transformRevision = transformRevision
        populated = true
        return cached
    }

    private companion object {
        private const val PLAN_TRAVEL_BOUNDS_MARGIN: Int = 2
        private const val PLAN_TRAVEL_BOUNDS_INFLATE: Int = 8
    }
}

/**
 * Revision-aware cache of transformed missing positions.
 *
 * Structural changes rebuild within [rebuildBudget]; ordinary revisions replay retained
 * changes. The last complete result remains visible until a rebuild finishes.
 */
internal class MoverMissingWorldCache(
    private val rebuildBudget: Int = PrintWorldModel.CAPTURE_CELLS_PER_TICK,
    private val localToWorld: (BlockPos) -> BlockPos,
) {
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L
    private var lastAppliedRevision: Long = 0L
    private val worldByLocal: LinkedHashMap<BlockPos, BlockPos> = LinkedHashMap()
    private val localByWorld: HashMap<BlockPos, BlockPos> = HashMap()

    internal var rebuildCount: Int = 0
        private set

    private var pendingEntries: Iterator<BlockPos>? = null
    private var pendingContentIdentity: Any? = null
    private var pendingTransformRevision: Long = 0L
    private var pendingMissingRevisionAtBegin: Long = 0L
    private var workingByLocal: LinkedHashMap<BlockPos, BlockPos> = LinkedHashMap()
    private var workingLocalByWorld: HashMap<BlockPos, BlockPos> = HashMap()

    internal fun isRebuilding(): Boolean = pendingEntries != null

    internal fun sync(
        missingRevision: Long,
        transformRevision: Long,
        contentIdentity: Any,
        missingLocal: List<BlockPos>,
        changesSince: (Long) -> List<MissingBlockChange>?,
    ): Collection<BlockPos> {
        if (pendingEntries != null) {
            val stillSameTarget = pendingContentIdentity === contentIdentity &&
                pendingTransformRevision == transformRevision
            if (!stillSameTarget) {
                beginRebuild(contentIdentity, transformRevision, missingLocal, missingRevision)
            }
            pumpRebuild(rebuildBudget)
            return worldByLocal.values
        }
        val structurallyStale = this.contentIdentity !== contentIdentity ||
            this.transformRevision != transformRevision
        if (structurallyStale) {
            beginRebuild(contentIdentity, transformRevision, missingLocal, missingRevision)
            pumpRebuild(rebuildBudget)
        } else if (lastAppliedRevision != missingRevision) {
            val changes = changesSince(lastAppliedRevision)
            if (changes != null) {
                for (change in changes) applyChange(change)
                lastAppliedRevision = missingRevision
            } else {
                beginRebuild(contentIdentity, transformRevision, missingLocal, missingRevision)
                pumpRebuild(rebuildBudget)
            }
        }
        return worldByLocal.values
    }

    internal fun rebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
    ): Collection<BlockPos> {
        beginRebuild(contentIdentity, transformRevision, missingLocal, lastAppliedRevision)
        pumpRebuild(Int.MAX_VALUE)
        return worldByLocal.values
    }

    private fun beginRebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
        missingRevisionAtBegin: Long,
    ): Unit {
        rebuildCount++
        pendingEntries = missingLocal.iterator()
        pendingContentIdentity = contentIdentity
        pendingTransformRevision = transformRevision
        pendingMissingRevisionAtBegin = missingRevisionAtBegin
        workingByLocal = LinkedHashMap()
        workingLocalByWorld = HashMap()
    }

    private fun pumpRebuild(budget: Int): Unit {
        val entries = pendingEntries ?: return
        var processed = 0
        while (entries.hasNext() && processed < budget) {
            val localPos = entries.next()
            val immutableLocal = localPos.immutable()
            val worldPos = localToWorld(localPos).immutable()
            workingByLocal[immutableLocal] = worldPos
            workingLocalByWorld[worldPos] = immutableLocal
            processed++
        }
        if (entries.hasNext()) return
        worldByLocal.clear()
        worldByLocal.putAll(workingByLocal)
        localByWorld.clear()
        localByWorld.putAll(workingLocalByWorld)
        contentIdentity = pendingContentIdentity
        transformRevision = pendingTransformRevision
        lastAppliedRevision = pendingMissingRevisionAtBegin
        pendingEntries = null
        workingByLocal = LinkedHashMap()
        workingLocalByWorld = HashMap()
    }

    internal fun applyChange(change: MissingBlockChange): Unit {
        if (!change.overlayChanged) return
        val immutableLocal = change.localPos.immutable()
        val previousWorld = worldByLocal.remove(immutableLocal)
        if (previousWorld != null) localByWorld.remove(previousWorld)
        if (!change.satisfied) {
            val worldPos = localToWorld(immutableLocal).immutable()
            worldByLocal[immutableLocal] = worldPos
            localByWorld[worldPos] = immutableLocal
        }
    }

    internal fun localPosition(worldPos: BlockPos): BlockPos? {
        return localByWorld[worldPos]
    }
}

internal class MoverPlaceabilityMemo {
    private var missingRevision: Long = 0L
    private var transformRevision: Long = 0L
    private var contentIdentity: Any? = null
    private var levelIdentity: Any? = null
    private val placeableByWorld: HashMap<BlockPos, Boolean> = HashMap()

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
            placeableByWorld.clear()
        }
        this.missingRevision = missingRevision
        this.transformRevision = transformRevision
        this.contentIdentity = contentIdentity
        this.levelIdentity = levelIdentity
    }

    internal fun isPlaceable(worldPos: BlockPos, calculate: () -> Boolean): Boolean {
        return placeableByWorld.getOrPut(worldPos.immutable(), calculate)
    }
}
