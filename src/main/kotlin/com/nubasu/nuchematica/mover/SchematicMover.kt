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
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.MovementInputUpdateEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal const val MOVER_MINIMUM_REACH: Double = 4.0

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
        // Lambda, not a bound reference: SchematicMover is class-initialized during mod
        // construction on a modloading worker thread, and a bound reference would
        // class-init SchematicRenderManager there too (its mesh thread guard must
        // capture the client main thread).
        localToWorld = { pos -> SchematicRenderManager.localBlockToWorld(pos) },
    )
    private val placeabilityMemo = MoverPlaceabilityMemo()
    private val runTelemetry = MoverRunTelemetry()
    private var terminalSummaryLogged: Boolean = true

    internal var latestStatus: MoverStatus? = null
        private set

    private var latestCommand: MoverCommand = INACTIVE_COMMAND
    private var stopMovementApplied: Boolean = false

    // TEMP C3MOV (remove after the vertical-travel investigation): event-only movement
    // trace so the dominant source of vertical motion (layer dives / candidate
    // rebuilds / flight-loss bounces) can be measured from latest.log instead of
    // guessed.
    private var debugPreviousState: MoverState? = null
    private var debugPreviousTarget: Vec3? = null
    private var debugPreviousFlying: Boolean? = null
    private var debugPreviousGateY: Int? = null

    private fun debugTraceMovement(
        tick: Long,
        beforeState: MoverState,
        player: LocalPlayer,
        gateY: Int?,
    ): Unit {
        val logger = com.mojang.logging.LogUtils.getLogger()
        val status = latestStatus ?: return
        val playerY = String.format("%.1f", player.y)
        if (debugPreviousState != status.state) {
            logger.info(
                "C3MOV t={} state {}->{} playerY={}", tick, beforeState, status.state, playerY,
            )
            debugPreviousState = status.state
        }
        val target = status.target
        if (target != debugPreviousTarget && target != null) {
            val verticalDelta = String.format("%.1f", target.y - player.y)
            logger.info(
                "C3MOV t={} target -> ({}, {}, {}) playerY={} dy={}",
                tick, target.x, target.y, target.z, playerY, verticalDelta,
            )
            debugPreviousTarget = target
        }
        val flying = player.abilities.flying
        if (debugPreviousFlying == true && !flying && status.state.let {
                it == MoverState.CRUISE || it == MoverState.HOLD
            }
        ) {
            logger.info("C3MOV t={} flightLost playerY={}", tick, playerY)
        }
        debugPreviousFlying = flying
        if (gateY != debugPreviousGateY) {
            logger.info("C3MOV t={} gate {}->{}", tick, debugPreviousGateY, gateY)
            debugPreviousGateY = gateY
        }
    }

    internal fun toggleRequested(): Unit {
        val minecraft = Minecraft.getInstance()
        if (core.status().state.isActive()) {
            core.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
            latestStatus = core.status()
            logTerminalSummary(latestStatus!!)
            latestCommand = STOP_COMMAND
            minecraft.player?.let { player -> applyCommand(player, STOP_COMMAND) }
            MoverConnectionObserver.remove()
            // Manual auto-move toggle-off is a mandatory scaffold
            // cleanup trigger -- never leave the scaffold material in the world.
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
        // This toggle check only ever needed a presence test, not
        // the full missing list a missingSnapshot() call used to have to copy to answer
        // it (a 3.5M-entry missing set made even this one-shot check freeze).
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
                // Reclaim any slime scaffold stranded by a disconnect/rejoin
                // (the old ClientLevel was gone before cleanupScaffolds could run against
                // it) before this run starts, so the mover never bounces off it into an
                // instant SERVER_CORRECTION abort.
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
        // While PrintWorldModel is still building its snapshot, the
        // mover stays idle too -- the missing/gate state it would route against has not
        // consumed the capture yet either (see SchematicPrinter.tick's matching guard).
        // Leaves the previously applied command in effect rather than issuing STOP_COMMAND,
        // same as any other tick this function returns from early without a fresh command.
        // Same idle window extended to MissingBlockHolder's own
        // budgeted classification pass (see SchematicPrinter.tick's matching guard for why).
        if (
            PrintWorldModel.status() == PrintWorldModel.Status.CAPTURING ||
            MissingBlockHolder.isInitializing()
        ) {
            return
        }
        // Printer.tick runs first and auto-disables on creative loss. Let MoverCore
        // observe that loss before treating a disabled printer as a manual toggle.
        if (!SchematicPrinter.enabled && gameMode.playerMode.isCreative) {
            stopForPrinterOff(player)
            return
        }

        val gateY = SchematicPrinter.currentLayerGateY()
        val planModeActive = PrinterSettingsHolder.printerSettings.planFirstMode && SchematicPrinter.enabled
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
        runTelemetry.record(beforeState, latestFeedSnapshot)
        // Incremental sync -- an ordinary placement only bumps
        // missing.revision (contentIdentity/transformRevision unchanged), which applies
        // MissingBlockHolder's changesSince delta instead of rescanning every missing
        // position; only a reload/transform change does the O(all-missing) rebuild.
        val missingWorld = missingWorldCache.sync(
            missingRevision = missing.revision,
            transformRevision = transformRevision,
            contentIdentity = content,
            missingLocal = missing.missingLocal,
            changesSince = MissingBlockHolder::changesSince,
        )
        // A structural change (fresh auto-move-on for a just-loaded large schematic is
        // the common case: contentIdentity starts null, so this is always true the first
        // tick) can span several ticks of budgeted work -- the sync() call above already
        // advanced it as far as this tick's budget allows, but missingWorld is still the
        // PREVIOUS complete result (see isRebuilding's doc for why a stale-but-untorn
        // snapshot is not good enough to act on), so the mover stays idle for the rest of
        // this rebuild, leaving the previously applied command in effect exactly like the
        // idle-window guard earlier in this function.
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
                correctionReceived = MoverConnectionObserver.consumeCorrection(),
                queueRevision = missing.revision,
                feedSnapshot = freshFeedSnapshot,
                gateY = gateY,
                gatePhase = SchematicPrinter.currentLayerGatePhase(),
                missingWorld = missingWorld,
                isPlaceable = { worldPos ->
                    // A scaffold-plannable position is placeable
                    // here too (ASCENT lanes included, not just RECOVERY/final-sweep --
                    // the printer's own scaffoldAssistActive gate is gone), routed
                    // through the exact same isScaffoldAssistable the gate and
                    // classifyMissing use rather than a mover-local reimplementation.
                    val categoryAndSupport = placeabilityMemo.isPlaceable(worldPos) {
                        val localPos = missingWorldCache.localPosition(worldPos)
                        val expectedState = localPos?.let { local -> content.blocks[local] }
                        // Mover isPlaceable memo reads the
                        // frozen-world model instead of a live level read.
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
                // Lets the batch planner score coverage against the
                // selector-aligned hit envelope instead of block-center distance.
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
                // Candidate abandonment is diagnostic until every candidate is spent.
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
                canComplete = { SchematicPrinter.canAutoMoveComplete() },
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
                planTravelBounds = if (planModeActive) {
                    planTravelBoundsCache.get(content, transformRevision)
                } else {
                    null
                },
                planFrontier = SchematicPrinter.planFrontierSnapshot()?.let { snapshot ->
                    MoverPlanFrontier(
                        // Already in plan order and bounded to one layer segment upstream
                        // (see PlanRuntimeAdapter.frontierSnapshot's own doc) -- a nearest-first
                        // re-sort or an extra re-cap here would undo the whole point of a
                        // per-layer plan-order lookahead (MoverCore plans the ENTIRE segment's
                        // route in one pass, then advances through it -- see buildPlanRoute/
                        // tickPlanArrivalHold's own docs), so this is a straight conversion.
                        waitingForReach = snapshot.waitingForReach
                            .map { target -> MoverPlanFrontierTarget(target.pos, target.expected) },
                        columnBlocked = snapshot.columnBlocked,
                        inFlight = snapshot.inFlight,
                        totalWaitingForReach = snapshot.totalWaitingForReach,
                        totalColumnBlocked = snapshot.totalColumnBlocked,
                        totalRemainingActions = snapshot.totalRemainingActions,
                    )
                },
                planSessionFinal = SchematicPrinter.isPlanSessionFinal(),
            ),
        )
        latestCommand = command
        latestStatus = core.status()
        applyCommand(player, command)
        // TEMP C3MOV (remove after the vertical-travel investigation)
        debugTraceMovement(level.gameTime, beforeState, player, gateY)
        notifyTerminalTransition(beforeState, latestStatus!!)
    }

    internal fun worldUnloaded(): Unit {
        deactivateWithoutChat(Minecraft.getInstance().player)
    }

    @SubscribeEvent
    public fun onMovementInput(event: MovementInputUpdateEvent): Unit {
        val status = latestStatus ?: return
        val command = latestCommand
        if (!status.state.isActive() || command.stopMovement) return

        val player = Minecraft.getInstance().player ?: return
        if (event.player !== player) return
        val yaw = Math.toRadians(player.yRot.toDouble())
        event.input.leftImpulse = (
            command.horizontalX * cos(yaw) + command.horizontalZ * sin(yaw)
            ).toFloat()
        event.input.forwardImpulse = (
            -command.horizontalX * sin(yaw) + command.horizontalZ * cos(yaw)
            ).toFloat()
        event.input.jumping = command.jump || command.vertical > 0
        event.input.shiftKeyDown = command.vertical < 0
    }

    @SubscribeEvent
    public fun onLoggedOut(event: ClientPlayerNetworkEvent.LoggedOutEvent): Unit {
        deactivateWithoutChat(event.player)
    }

    private fun stopForPrinterOff(player: LocalPlayer?): Unit {
        core.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
        latestStatus = core.status()
        logTerminalSummary(latestStatus!!)
        latestCommand = STOP_COMMAND
        player?.let { applyCommand(it, STOP_COMMAND) }
        MoverConnectionObserver.remove()
        // Printer-off-driven stop is a mandatory scaffold cleanup
        // trigger too (usually a no-op here: SchematicPrinter's own toggle-off/
        // auto-disable path already cleaned up before this runs).
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
        // World unload/logout/forced deactivation is a mandatory
        // scaffold cleanup trigger -- the safety net for exit paths nothing else covers.
        SchematicPrinter.cleanupScaffolds()
    }

    private fun notifyTerminalTransition(beforeState: MoverState, status: MoverStatus): Unit {
        if (status.state == beforeState) return
        logTerminalSummary(status)
        when (status.state) {
            MoverState.ABORTED -> {
                MoverConnectionObserver.remove()
                // TEMP C3DBG (remove after the layer-pin investigation): a TRAPPED
                // abort is a terminal-convergence failure -- dump the same
                // classification COMPLETE gets, so the stuck remainder is diagnosable.
                if (status.abortReason == MoverAbortReason.TRAPPED) {
                    SchematicPrinter.debugDumpPinState("trapped-abort")
                }
                // Every tick()-driven abort (TRAPPED, damage, GUI,
                // gamemode loss, server correction, ...) is a mandatory scaffold
                // cleanup trigger -- never leave the scaffold material in the world.
                SchematicPrinter.cleanupScaffolds()
                status.abortReason?.let { reason ->
                    ChatSender.send("[nuchematica] auto-move aborted: $reason")
                }
            }
            MoverState.COMPLETE -> {
                MoverConnectionObserver.remove()
                // TEMP C3DBG (remove after the layer-pin investigation)
                SchematicPrinter.debugDumpPinState("auto-move-complete")
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
        if (command.stopMovement) {
            if (!stopMovementApplied) {
                player.setDeltaMovement(Vec3.ZERO)
                stopMovementApplied = true
            }
        } else {
            stopMovementApplied = false
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

// Plan-mode travel bounds for the collision-aware A* profile: the schematic's own
// world-space AABB (the same +2-margin PrintWorldModel/SchematicPrinter's own bounds
// caches use) inflated by a further 8 in every direction to allow routing outside the
// build itself (region-exit travel, e.g. around an exterior wall). Cached by
// (content, transformRevision) identity -- mirrors SchematicPrinter's own
// boundsPredicateFor cache -- so the O(content) scan runs only when the schematic or its
// transform actually changes, never every tick.
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

// Replaces the old full-rebuild-per-missingRevision cache with the
// same rebuild/applyChange/sync split as SchematicPrinter's LayerGateEligibleMissingCache
// (see that class's doc), including its budgeted rebuild: sync() only ever advances an
// in-progress structural rebuild by rebuildBudget entries per call instead of doing the
// whole O(all-missing) pass synchronously, so a multi-million-entry missing set does not
// freeze the tick that first sees a fresh content/transform identity. rebuildCount is
// test-visible for the same structural-guarantee assertion: ordinary per-placement
// traffic must never move it.
internal class MoverMissingWorldCache(
    // Injectable so tests can prove the per-call budget structurally (a
    // small budget over a small dataset) instead of needing a huge fixture to exercise
    // the multi-call path -- defaults to the exact production budget (PrintWorldModel's
    // own capture budget, piggybacking the existing pump pattern rather than inventing a
    // second tuning knob), so every existing/production call site is unaffected. Declared
    // before localToWorld (not after) so localToWorld stays the LAST constructor
    // parameter -- existing call sites use Kotlin's trailing-lambda shorthand
    // (`MoverMissingWorldCache { pos -> ... }`), which only binds to the final parameter.
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

    // Budgeted rebuild state, mirroring LayerGateEligibleMissingCache's own
    // pendingEntries/working-map shape. Without budgeting, a structural change would
    // call rebuild() -- an unconditional O(all-missing) pass -- synchronously inside
    // sync(), which SchematicMover.tick calls every tick: on a multi-million-entry
    // missing set, the FIRST tick after toggling auto-move on (contentIdentity starts
    // null, so structurallyStale is always true that tick) would freeze the client on
    // this single call. pendingEntries is non-null exactly while a rebuild episode spans
    // more than one sync() call; the OLD worldByLocal/localByWorld stay fully intact and
    // readable until the working copy swaps in atomically on completion.
    private var pendingEntries: Iterator<BlockPos>? = null
    private var pendingContentIdentity: Any? = null
    private var pendingTransformRevision: Long = 0L
    // The missingRevision as of beginRebuild -- the revision the swapped-in
    // worldByLocal/localByWorld will actually reflect once the pass completes, since
    // pendingEntries iterates the missingLocal view captured at that moment (frozen, per
    // MissingLocalView's own doc), not whatever the current tick's missingRevision
    // happens to be. A rebuild can span several ticks; stamping the COMPLETION-time
    // revision as "caught up to" would silently drop any change that landed during the
    // rebuild: worldByLocal would stay stuck at the begin-time view forever, since
    // lastAppliedRevision would (falsely) claim there is nothing left to replay.
    // Stamping the BEGIN-time revision instead means the very next sync() call's
    // ordinary changesSince(lastAppliedRevision) path replays everything that happened
    // during and after the rebuild, exactly like any other catch-up gap -- no new
    // machinery, just an honest revision stamp.
    private var pendingMissingRevisionAtBegin: Long = 0L
    private var workingByLocal: LinkedHashMap<BlockPos, BlockPos> = LinkedHashMap()
    private var workingLocalByWorld: HashMap<BlockPos, BlockPos> = HashMap()

    // True from the tick a rebuild episode starts until the budgeted pass
    // finishes swapping its result in. SchematicMover.tick gates on this exactly like it
    // already gates on PrintWorldModel.status() == CAPTURING / MissingBlockHolder
    // .isInitializing() -- the result this cache would return mid-rebuild is stale
    // (still the PREVIOUS complete result, per the no-torn-result guarantee above), not
    // merely incomplete, so a caller that pressed on regardless would route the mover
    // against outdated missing-world state for however many ticks the rebuild spans.
    internal fun isRebuilding(): Boolean = pendingEntries != null

    // Single per-tick entry point: a structural identity change forces one (budgeted)
    // rebuild; otherwise the missingRevision delta since last tick is replayed via
    // MissingBlockHolder.changesSince, falling back to one rebuild only if that span is
    // no longer retained. A rebuild already in progress just gets pumped further; a NEW
    // structural change arriving mid-rebuild restarts it against the fresh identity.
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
            // lastAppliedRevision is not touched here -- it only ever becomes correct
            // once pumpRebuild's completion swap-in sets it to the begin-time revision
            // (see pendingMissingRevisionAtBegin's doc). While still pumping, its stale
            // value is never read (every other branch of sync() is unreachable while
            // pendingEntries != null), so leaving it alone is safe.
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

    // Unbudgeted, complete-in-one-call rebuild -- kept for callers (tests, and any
    // future caller) that want the old synchronous "do it all now" behavior rather than
    // sync()'s tick-budgeted path. Implemented as beginRebuild + an unbounded pump so
    // the two paths share one body instead of two copies of the transform loop.
    internal fun rebuild(
        contentIdentity: Any,
        transformRevision: Long,
        missingLocal: List<BlockPos>,
    ): Collection<BlockPos> {
        // No missingRevision of its own (this path is independent of sync()'s revision
        // tracking) -- passes the current lastAppliedRevision through unchanged so
        // completion's swap-in sets it back to itself (a no-op) rather than to an
        // arbitrary value.
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

    // Advances the in-progress rebuild by at most `budget` positions. No-op unless a
    // rebuild is actually pending. Swaps the working maps into worldByLocal/localByWorld
    // atomically only once every position has been visited -- see isRebuilding's doc for
    // why the OLD result must stay exactly as it was until then.
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
        // The swapped-in worldByLocal/localByWorld reflect missingLocal exactly as it
        // stood at beginRebuild, not "now" -- see pendingMissingRevisionAtBegin's doc
        // for why stamping anything later here would silently drop concurrent changes.
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
