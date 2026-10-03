package com.nubasu.nuchematica.printer

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.mover.playerHasLocalEscapeRoute
import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Dependencies for one [PlanRuntimeAdapter.tick] call.
 *
 * [stateAt] is the frozen planning view. [liveStateAt] supplies attempt baselines and
 * confirms outcomes; [breakStateAt] may hide optimistic client removals until server
 * acknowledgement. Placement callbacks must use the same [settings] snapshot as the plan.
 */
internal class PlanRuntimeTickContext(
    internal val tick: Long,
    internal val stateAt: (BlockPos) -> BlockState,
    internal val liveStateAt: (BlockPos) -> BlockState,
    internal val recordWrite: (BlockPos, BlockState) -> Unit,
    internal val eyePosition: Vec3,
    internal val reach: Double,
    internal val playerFeetPos: Vec3?,
    internal val settings: PlacementBehaviorSettings,
    internal val placementContext: (BlockState, BlockHitResult) -> BlockPlaceContext,
    internal val predictPlacement: (BlockItem, BlockPlaceContext) -> BlockState?,
    internal val orientedPrediction: (BlockItem, BlockPlaceContext, BlockState) -> PlacementRotation?,
    internal val itemSupplier: ItemSupplier,
    internal val placementGateway: PlacementGateway,
    internal val preparePlacementRotation: (Long, BlockPos, PlacementRotation) -> Boolean = { _, _, _ -> true },
    internal val finishPlacementRotation: (Long, BlockPos) -> Unit = { _, _ -> },
    internal val cancelPlacementRotation: (Long, BlockPos) -> Unit = { _, _ -> },
    internal val destroy: (BlockPos) -> Boolean,
    internal val breakStateAt: (BlockPos) -> BlockState = liveStateAt,
    internal val onBreakAttemptSettled: (BlockPos) -> Unit = {},
    internal val placementIntervalTicks: Int,
    internal val attemptsPerTick: Int = PrinterRateLimiter.DEFAULT_ATTEMPTS_PER_TICK,
    internal val onFrozenLiveDivergence: () -> Unit = {},
    internal val clearPendingPlace: (BlockPos) -> Unit = {},
    internal val clearPendingBreak: (BlockPos) -> Unit = {},
)

/** Creates orientation trials bound to the plan's immutable behavior settings. */
internal fun planOrientedPrediction(
    settings: PlacementBehaviorSettings,
): (BlockItem, BlockPlaceContext, BlockState) -> PlacementRotation? = { item, context, expectedState ->
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
                predicted != null && BlockStateEquivalence.matches(expectedState, predicted, settings)
            },
        )
    }
}

private fun isTerminalActionState(state: ActionState): Boolean {
    return state is ActionState.Done || state is ActionState.Failed || state is ActionState.Skipped
}

private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()

internal const val STRICT_FRONTIER_STALL_TICKS: Long = 600L

internal const val FRONTIER_LOOKAHEAD_CAP: Int = 2560

private const val DIAG_LOG_INTERVAL_TICKS: Long = 100L

private data class InFlightAction(
    val actionId: Long,
    val attemptId: Long,
    val groupId: Int,
    val collisionState: BlockState,
)

private data class SweepInFlightAction(val actionId: Long, val collisionState: BlockState)

private data class GroupTarget(val actionId: Long, val pos: BlockPos, val expected: BlockState)

private data class ScaffoldCell(val pos: BlockPos, val placeActionId: Long, val removeActionId: Long)

private data class WaitingRecord(val worldPos: BlockPos, val expectedState: BlockState, val reason: WaitingReason)

private data class SweepSegmentKey(val unitIndex: Int, val y: Int)

private data class SweepSegment(val key: SweepSegmentKey, val startIndex: Int, val endIndexExclusive: Int)

private data class SweepTarget(
    val actionId: Long,
    val pos: BlockPos,
    val expected: BlockState,
    var supportDeferred: Boolean = false,
)

private enum class SweepDispatchOutcome {
    CONSUMED,
    BLOCKED,
    DEFERRED,
}

private class SweepPhase(val key: SweepSegmentKey, val queue: ArrayDeque<SweepTarget>, var headSinceTick: Long) {
    var frontColumnBlocked: Boolean = false
}

internal data class PlanTargetCounts(
    internal val remaining: Int,
    internal val placed: Int,
    internal val failedOrSkipped: Int,
)

internal class PlanFrontierTarget(internal val pos: BlockPos, internal val expected: BlockState)

/**
 * Movement-facing view of blocked and outstanding plan work.
 *
 * [waitingForReach] is ordered lookahead or the active sweep, not just actions whose
 * current state is [ActionState.Waiting]. [waitingForSupport] is diagnostic because
 * moving the player cannot satisfy it.
 */
internal class PlanFrontierSnapshot(
    internal val waitingForReach: List<PlanFrontierTarget>,
    internal val waitingForSupport: List<PlanFrontierTarget>,
    internal val columnBlocked: List<BlockPos>,
    internal val inFlight: List<BlockPos>,
    internal val inFlightCollisionStates: List<PlanFrontierTarget>,
    internal val totalWaitingForReach: Int,
    internal val totalColumnBlocked: Int,
    internal val totalRemainingActions: Int,
)

/**
 * Drives a [PlanExecutionCursor] through live placement and break gateways.
 *
 * The adapter also owns post-layer sweeps and scaffold cleanup checks that must settle
 * before the plan can complete.
 */
internal class PlanRuntimeAdapter(
    plan: PrintPlan,
    internal val cursor: PlanExecutionCursor,
    private val bounds: (BlockPos) -> Boolean = { true },
) {
    private val attemptTracker: PrinterAttemptTracker = PrinterAttemptTracker()
    private val removeTracker: PrinterAttemptTracker = PrinterAttemptTracker()
    private val rateLimiter: PrinterRateLimiter = PrinterRateLimiter()

    private val inFlightByPos: MutableMap<BlockPos, InFlightAction> = mutableMapOf()
    private val removeInFlightByPos: MutableMap<BlockPos, InFlightAction> = mutableMapOf()
    private val cleanupChecked: MutableSet<Int> = mutableSetOf()

    private val waitingByActionId: LinkedHashMap<Long, WaitingRecord> = LinkedHashMap()

    private var stalledFrontierActionId: Long? = null
    private var stalledFrontierSinceTick: Long = 0L
    private var strictStallEscapeCount: Int = 0
    private var moverUnreachableRequest: BlockPos? = null
    private var moverUnreachableResolveCount: Int = 0

    private var lastSweepPhaseDiagLogTick: Long = -DIAG_LOG_INTERVAL_TICKS
    private var lastStallEscapeDiagLogTick: Long = -DIAG_LOG_INTERVAL_TICKS
    private var lastBlockedHeadDiagLogTick: Long = -DIAG_LOG_INTERVAL_TICKS

    private val targetByGroup: Map<Int, GroupTarget>
    private val removeIdsByGroup: Map<Int, List<Long>>
    private val scaffoldCells: List<ScaffoldCell>

    private data class LookaheadEntry(
        val actionId: Long,
        val pos: BlockPos,
        val expected: BlockState,
        val groupId: Int,
        val unitIndex: Int,
        val isTargetAction: Boolean,
    )

    private val lookaheadEntries: List<LookaheadEntry>

    private var lookaheadResumeIndex: Int = 0

    private data class PlannedPosition(val actionId: Long, val unitIndex: Int)

    private val plannedPositionActionIds: Map<BlockPos, PlannedPosition>

    init {
        val targets = HashMap<Int, GroupTarget>()
        val removes = HashMap<Int, MutableList<Long>>()
        val cells = mutableListOf<ScaffoldCell>()
        val pendingScaffoldsByGroup = HashMap<Int, MutableMap<BlockPos, Long>>()
        val plannedPositions = HashMap<BlockPos, PlannedPosition>()
        val lookahead = mutableListOf<LookaheadEntry>()
        var nextId = 0L
        for ((unitIndex, unit) in executablePlanUnits(plan).withIndex()) {
            for (action in unit.actions) {
                val actionId = nextId++
                val groupId = groupIdOf(action)
                when (action) {
                    is PlanAction.PlaceTarget -> {
                        targets[groupId] = GroupTarget(actionId, action.pos, action.expected)
                        plannedPositions[action.pos] = PlannedPosition(actionId, unitIndex)
                        lookahead += LookaheadEntry(
                            actionId, action.pos, action.expected, groupId, unitIndex, isTargetAction = true,
                        )
                    }
                    is PlanAction.PlaceScaffold -> {
                        pendingScaffoldsByGroup.getOrPut(groupId) { mutableMapOf() }[action.pos] = actionId
                        plannedPositions[action.pos] = PlannedPosition(actionId, unitIndex)
                        lookahead += LookaheadEntry(
                            actionId, action.pos, SCAFFOLD_BLOCK_STATE, groupId, unitIndex, isTargetAction = false,
                        )
                    }
                    is PlanAction.RemoveScaffold -> {
                        removes.getOrPut(groupId) { mutableListOf() }.add(actionId)
                        val placeActionId = pendingScaffoldsByGroup[groupId]?.get(action.pos)
                        if (placeActionId != null) cells += ScaffoldCell(action.pos, placeActionId, actionId)
                        lookahead += LookaheadEntry(
                            actionId, action.pos, SCAFFOLD_BLOCK_STATE, groupId, unitIndex, isTargetAction = false,
                        )
                    }
                }
            }
        }
        targetByGroup = targets
        removeIdsByGroup = removes
        scaffoldCells = cells
        plannedPositionActionIds = plannedPositions
        lookaheadEntries = lookahead
    }

    private val segments: List<SweepSegment> = run {
        val built = mutableListOf<SweepSegment>()
        var index = 0
        while (index < lookaheadEntries.size) {
            val head = lookaheadEntries[index]
            val segmentUnitIndex = head.unitIndex
            val segmentY = targetByGroup.getValue(head.groupId).pos.y
            val start = index
            while (
                index < lookaheadEntries.size &&
                lookaheadEntries[index].unitIndex == segmentUnitIndex &&
                (!lookaheadEntries[index].isTargetAction || lookaheadEntries[index].pos.y == segmentY)
            ) {
                index++
            }
            built += SweepSegment(SweepSegmentKey(segmentUnitIndex, segmentY), start, index)
        }
        built
    }

    private var sweepSegmentCursor: Int = 0

    private val consumedSegments: MutableSet<SweepSegmentKey> = mutableSetOf()

    private var activeSweep: SweepPhase? = null

    private val sweepInFlightByPos: MutableMap<BlockPos, SweepInFlightAction> = mutableMapOf()

    /** Reports whether completion still awaits an active or final-layer sweep. */
    internal fun isSweepPending(): Boolean {
        return activeSweep != null || (cursor.isComplete && sweepSegmentCursor < segments.size)
    }

    private fun hasAdvancingPlannedSupportNeighbor(
        worldPos: BlockPos,
        dependentUnitIndex: Int,
        dependentActionId: Long,
    ): Boolean {
        for (direction in Direction.values()) {
            val neighbor = plannedPositionActionIds[worldPos.relative(direction)] ?: continue
            if (neighbor.unitIndex != dependentUnitIndex) continue
            val state = cursor.stateOf(neighbor.actionId)
            if (state is ActionState.InFlight) return true
            if (neighbor.actionId < dependentActionId && !isTerminalActionState(state)) return true
        }
        return false
    }

    internal fun outstandingScaffoldCells(): List<BlockPos> {
        return scaffoldCells
            .filter { cell -> mayHaveLanded(cursor.stateOf(cell.placeActionId)) }
            .filterNot { cell -> cursor.stateOf(cell.removeActionId) is ActionState.Done }
            .map { cell -> cell.pos }
    }

    private fun mayHaveLanded(state: ActionState): Boolean {
        return state is ActionState.Done ||
            state is ActionState.InFlight ||
            (state is ActionState.Failed && state.reason == ActionFailureReason.TIMEOUT)
    }

    internal fun sweepOutstandingScaffolds(
        liveStateAt: (BlockPos) -> BlockState,
        destroy: (BlockPos) -> Boolean,
        recordWrite: (BlockPos, BlockState) -> Unit,
    ): Unit {
        for (pos in outstandingScaffoldCells()) {
            if (liveStateAt(pos) == SCAFFOLD_BLOCK_STATE) {
                destroy(pos)
                val afterState = liveStateAt(pos)
                if (isReplaceableTarget(afterState)) recordWrite(pos, afterState)
            }
        }
    }

    internal fun targetActionCounts(): PlanTargetCounts {
        var remaining = 0
        var placed = 0
        var failedOrSkipped = 0
        for (target in targetByGroup.values) {
            val state = cursor.stateOf(target.actionId)
            if (state is ActionState.Done) {
                placed++
            } else if (!isTerminalActionState(state)) {
                remaining++
            } else {
                failedOrSkipped++
            }
        }
        return PlanTargetCounts(remaining = remaining, placed = placed, failedOrSkipped = failedOrSkipped)
    }

    internal fun targetExpectations(): List<PlanTargetExpectation> {
        return lookaheadEntries
            .asSequence()
            .filter(LookaheadEntry::isTargetAction)
            .map { entry -> PlanTargetExpectation(entry.pos, entry.expected) }
            .toList()
    }

    internal fun isPlacementInFlight(worldPos: BlockPos): Boolean {
        return inFlightByPos.containsKey(worldPos) ||
            removeInFlightByPos.containsKey(worldPos) ||
            sweepInFlightByPos.containsKey(worldPos)
    }

    internal fun isWithinBounds(worldPos: BlockPos): Boolean {
        return bounds(worldPos)
    }

    internal fun frontierSnapshot(): PlanFrontierSnapshot {
        val waitingForSupport = mutableListOf<PlanFrontierTarget>()
        val columnBlocked = mutableListOf<BlockPos>()
        val stale = mutableListOf<Long>()
        for ((actionId, record) in waitingByActionId) {
            if (cursor.stateOf(actionId) !is ActionState.Waiting) {
                stale += actionId
                continue
            }
            when (record.reason) {
                WaitingReason.WAITING_FOR_REACH,
                WaitingReason.WAITING_FOR_ROTATION,
                -> Unit
                WaitingReason.WAITING_FOR_SUPPORT ->
                    waitingForSupport += PlanFrontierTarget(record.worldPos, record.expectedState)
                WaitingReason.PLAYER_COLUMN_BLOCKED -> columnBlocked += record.worldPos
            }
        }
        for (actionId in stale) clearWaiting(actionId)

        val sweep = activeSweep
        val waitingForReach: List<PlanFrontierTarget>
        val sweepColumnBlocked: List<BlockPos>
        if (sweep != null) {
            waitingForReach = sweep.queue.map { target -> PlanFrontierTarget(target.pos, target.expected) }
            sweepColumnBlocked = if (sweep.frontColumnBlocked) {
                sweep.queue.firstOrNull()?.let { front -> listOf(front.pos) } ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            waitingForReach = planOrderLookahead()
            sweepColumnBlocked = columnBlocked
        }
        // Drained sweeps remain collision-relevant until their attempts settle.
        val sweepInFlight = sweepInFlightByPos.keys.toList()
        val inFlight = inFlightByPos.keys.toList() + removeInFlightByPos.keys.toList() + sweepInFlight
        val inFlightCollisionStates =
            inFlightByPos.map { (pos, action) -> PlanFrontierTarget(pos, action.collisionState) } +
                removeInFlightByPos.map { (pos, action) -> PlanFrontierTarget(pos, action.collisionState) } +
                sweepInFlightByPos.map { (pos, action) -> PlanFrontierTarget(pos, action.collisionState) }
        val status = cursor.status()
        return PlanFrontierSnapshot(
            waitingForReach = waitingForReach,
            waitingForSupport = waitingForSupport,
            columnBlocked = sweepColumnBlocked,
            inFlight = inFlight,
            inFlightCollisionStates = inFlightCollisionStates,
            totalWaitingForReach = waitingForReach.size,
            totalColumnBlocked = sweepColumnBlocked.size,
            totalRemainingActions = status.pendingCount + status.waitingCount + status.inFlightCount,
        )
    }

    internal fun isBreakInFlight(worldPos: BlockPos): Boolean = removeInFlightByPos.containsKey(worldPos)

    internal fun stallEscapeCount(): Int = strictStallEscapeCount

    internal fun moverUnreachableCount(): Int = moverUnreachableResolveCount

    internal fun requestFrontierUnreachable(worldPos: BlockPos): Unit {
        if (moverUnreachableRequest == null) {
            moverUnreachableRequest = worldPos.immutable()
        }
    }

    private fun planOrderLookahead(): List<PlanFrontierTarget> {
        while (
            lookaheadResumeIndex < lookaheadEntries.size &&
            isTerminalActionState(cursor.stateOf(lookaheadEntries[lookaheadResumeIndex].actionId))
        ) {
            lookaheadResumeIndex++
        }
        if (lookaheadResumeIndex >= lookaheadEntries.size) return emptyList()

        val head = lookaheadEntries[lookaheadResumeIndex]
        val segmentUnitIndex = head.unitIndex
        val segmentY = targetByGroup.getValue(head.groupId).pos.y

        val result = mutableListOf<PlanFrontierTarget>()
        var index = lookaheadResumeIndex
        while (index < lookaheadEntries.size && result.size < FRONTIER_LOOKAHEAD_CAP) {
            val entry = lookaheadEntries[index]
            if (entry.unitIndex != segmentUnitIndex) break
            if (entry.isTargetAction && entry.pos.y != segmentY) break
            if (!isTerminalActionState(cursor.stateOf(entry.actionId))) {
                result += PlanFrontierTarget(entry.pos, entry.expected)
            }
            index++
        }
        return result
    }

    internal fun tick(context: PlanRuntimeTickContext): Unit {
        rateLimiter.updateAttemptsPerTick(context.attemptsPerTick)
        rateLimiter.updateIntervalTicks(context.placementIntervalTicks)
        rateLimiter.beginTick(context.tick)

        val matches: (BlockState, BlockState) -> Boolean = { expected, actual ->
            BlockStateEquivalence.matches(expected, actual, context.settings)
        }

        for (result in attemptTracker.observe(context.tick, context.liveStateAt, matches)) {
            val sweepInFlight = sweepInFlightByPos.remove(result.attempt.worldPos)
            if (sweepInFlight != null) {
                context.clearPendingPlace(result.attempt.worldPos)
                if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                    context.recordWrite(result.attempt.worldPos, result.observedState)
                }
                continue
            }
            val inFlight = inFlightByPos.remove(result.attempt.worldPos) ?: continue
            context.clearPendingPlace(result.attempt.worldPos)
            val event: CursorEvent.Correlated = when (result.outcome) {
                PrinterAttemptOutcome.ACCEPTED -> {
                    context.recordWrite(result.attempt.worldPos, result.observedState)
                    CursorEvent.Accepted(inFlight.actionId, inFlight.attemptId)
                }
                PrinterAttemptOutcome.REJECTED -> CursorEvent.Rejected(inFlight.actionId, inFlight.attemptId)
                PrinterAttemptOutcome.WRONG_STATE -> CursorEvent.WrongState(inFlight.actionId, inFlight.attemptId)
                PrinterAttemptOutcome.TIMEOUT -> CursorEvent.Timeout(inFlight.actionId, inFlight.attemptId)
            }
            emit(event, inFlight.groupId, context)
        }

        for (result in removeTracker.observe(context.tick, context.breakStateAt, matches)) {
            context.onBreakAttemptSettled(result.attempt.worldPos)
            context.clearPendingBreak(result.attempt.worldPos)
            val inFlight = removeInFlightByPos.remove(result.attempt.worldPos) ?: continue
            val event: CursorEvent.Correlated = if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                context.recordWrite(result.attempt.worldPos, result.observedState)
                CursorEvent.RemoveConfirmed(inFlight.actionId, inFlight.attemptId)
            } else {
                CursorEvent.RemoveFailed(inFlight.actionId, inFlight.attemptId)
            }
            emit(event, inFlight.groupId, context)
        }

        applyMoverUnreachableRequest(context)

        val activeSweepAtStart = activeSweep
        if (activeSweepAtStart != null) {
            tickSweep(activeSweepAtStart, context)
            return
        }
        detectSweepPhaseBoundary(context)
        val sweepJustStarted = activeSweep
        if (sweepJustStarted != null) {
            tickSweep(sweepJustStarted, context)
            return
        }

        var blockedFrontierActionId: Long? = null
        var blockedFrontierAction: PlanAction? = null
        for (actionId in cursor.orderedFrontier()) {
            if (cursor.stateOf(actionId) is ActionState.InFlight) continue
            if (!rateLimiter.tryAcquire()) break
            val cursorAction = cursor.submitSpecific(actionId) ?: break
            dispatch(cursorAction, context)
            if (cursor.stateOf(actionId) is ActionState.Waiting) {
                blockedFrontierActionId = actionId
                blockedFrontierAction = cursorAction.action
                break
            }
        }
        applyStrictFrontierStallEscape(blockedFrontierActionId, context)
        logBlockedHeadDiagnostics(context.tick, blockedFrontierActionId, blockedFrontierAction)
    }

    private fun logBlockedHeadDiagnostics(tick: Long, actionId: Long?, action: PlanAction?): Unit {
        if (actionId == null || action == null) return
        if (tick - lastBlockedHeadDiagLogTick < DIAG_LOG_INTERVAL_TICKS) return
        lastBlockedHeadDiagLogTick = tick
        val pos = when (action) {
            is PlanAction.PlaceTarget -> action.pos
            is PlanAction.PlaceScaffold -> action.pos
            is PlanAction.RemoveScaffold -> action.pos
        }
        LogUtils.getLogger().info(
            "C3DBG[dispatch] blocked head actionId={} kind={} pos={} reason={} stallTicks={}",
            actionId,
            action::class.simpleName,
            pos.toShortString(),
            waitingByActionId[actionId]?.reason?.name ?: "unknown",
            tick - stalledFrontierSinceTick,
        )
    }

    private fun detectSweepPhaseBoundary(context: PlanRuntimeTickContext): Unit {
        while (sweepSegmentCursor < segments.size) {
            val segment = segments[sweepSegmentCursor]
            if (segment.key in consumedSegments) {
                sweepSegmentCursor++
                continue
            }
            var fullyTerminal = true
            for (index in segment.startIndex until segment.endIndexExclusive) {
                if (!isTerminalActionState(cursor.stateOf(lookaheadEntries[index].actionId))) {
                    fullyTerminal = false
                    break
                }
            }
            if (!fullyTerminal) return

            val candidates = ArrayDeque<SweepTarget>()
            for (index in segment.startIndex until segment.endIndexExclusive) {
                val entry = lookaheadEntries[index]
                if (!entry.isTargetAction) continue
                val state = cursor.stateOf(entry.actionId)
                val failedOrSkipped = state is ActionState.Failed || state is ActionState.Skipped
                if (!failedOrSkipped) continue
                if (BlockStateEquivalence.matches(entry.expected, context.liveStateAt(entry.pos), context.settings)) {
                    continue
                }
                candidates.addLast(SweepTarget(entry.actionId, entry.pos, entry.expected))
            }

            consumedSegments += segment.key
            sweepSegmentCursor++

            if (candidates.isNotEmpty()) {
                logSweepPhaseDiagnostics(
                    context.tick,
                    "phase-begin unit={} y={} queueSize={} heads={}",
                    segment.key.unitIndex,
                    segment.key.y,
                    candidates.size,
                    candidates.take(3).joinToString(",") { candidate -> candidate.pos.toShortString() },
                )
                activeSweep = SweepPhase(segment.key, candidates, context.tick)
                return
            }
        }
    }

    private fun applyMoverUnreachableRequest(context: PlanRuntimeTickContext): Unit {
        val requested = moverUnreachableRequest ?: return
        moverUnreachableRequest = null
        if (
            inFlightByPos.isNotEmpty() ||
            removeInFlightByPos.isNotEmpty() ||
            sweepInFlightByPos.isNotEmpty()
        ) {
            return
        }

        val sweep = activeSweep
        if (sweep != null) {
            val front = sweep.queue.firstOrNull() ?: return
            if (front.pos != requested) return
            if (!front.supportDeferred && hasQueuedSweepSupportNeighbor(sweep, front)) {
                front.supportDeferred = true
                sweep.queue.removeFirst()
                sweep.queue.addLast(front)
                sweep.headSinceTick = context.tick
                sweep.frontColumnBlocked = false
                LogUtils.getLogger().info(
                    "C3DBG[mover-unreachable] sweep-deferred-for-support unit={} y={} pos={}",
                    sweep.key.unitIndex,
                    sweep.key.y,
                    requested.toShortString(),
                )
                return
            }
            sweep.queue.removeFirst()
            sweep.headSinceTick = context.tick
            sweep.frontColumnBlocked = false
            moverUnreachableResolveCount++
            LogUtils.getLogger().info(
                "C3DBG[mover-unreachable] sweep unit={} y={} pos={}",
                sweep.key.unitIndex,
                sweep.key.y,
                requested.toShortString(),
            )
            if (sweep.queue.isEmpty()) {
                logSweepPhaseDiagnostics(context.tick, "phase-end unit={} y={}", sweep.key.unitIndex, sweep.key.y)
                activeSweep = null
            }
            return
        }

        while (
            lookaheadResumeIndex < lookaheadEntries.size &&
            isTerminalActionState(cursor.stateOf(lookaheadEntries[lookaheadResumeIndex].actionId))
        ) {
            lookaheadResumeIndex++
        }
        val head = lookaheadEntries.getOrNull(lookaheadResumeIndex) ?: return
        if (head.pos != requested) return
        val forced = cursor.submitSpecific(head.actionId) ?: return
        clearWaiting(forced.actionId)
        val event: CursorEvent.Correlated = when (forced.action) {
            is PlanAction.RemoveScaffold -> CursorEvent.RemoveFailed(forced.actionId, forced.attemptId)
            is PlanAction.PlaceScaffold,
            is PlanAction.PlaceTarget,
            -> CursorEvent.ResolveFailed(forced.actionId, forced.attemptId)
        }
        emit(event, groupIdOf(forced.action), context)
        stalledFrontierActionId = null
        moverUnreachableResolveCount++
        LogUtils.getLogger().info(
            "C3DBG[mover-unreachable] actionId={} pos={}",
            forced.actionId,
            requested.toShortString(),
        )
    }

    private fun tickSweep(sweep: SweepPhase, context: PlanRuntimeTickContext): Unit {
        while (sweep.queue.isNotEmpty()) {
            val front = sweep.queue.first()
            if (context.tick - sweep.headSinceTick > STRICT_FRONTIER_STALL_TICKS) {
                LogUtils.getLogger().info(
                    "C3DBG[sweep] consume unit={} y={} pos={} outcome=stall",
                    sweep.key.unitIndex, sweep.key.y, front.pos.toShortString(),
                )
                sweep.queue.removeFirst()
                sweep.headSinceTick = context.tick
                sweep.frontColumnBlocked = false
                continue
            }
            if (!rateLimiter.tryAcquire()) break
            when (dispatchSweepTarget(sweep, front, context)) {
                SweepDispatchOutcome.BLOCKED -> break
                SweepDispatchOutcome.DEFERRED -> {
                    sweep.queue.removeFirst()
                    sweep.queue.addLast(front)
                    sweep.headSinceTick = context.tick
                    sweep.frontColumnBlocked = false
                }
                SweepDispatchOutcome.CONSUMED -> {
                    sweep.queue.removeFirst()
                    sweep.headSinceTick = context.tick
                    sweep.frontColumnBlocked = false
                }
            }
        }
        if (sweep.queue.isEmpty()) {
            logSweepPhaseDiagnostics(context.tick, "phase-end unit={} y={}", sweep.key.unitIndex, sweep.key.y)
            activeSweep = null
        }
    }

    private fun logSweepPhaseDiagnostics(tick: Long, messageSuffix: String, vararg args: Any?): Unit {
        if (tick - lastSweepPhaseDiagLogTick < DIAG_LOG_INTERVAL_TICKS) return
        lastSweepPhaseDiagLogTick = tick
        LogUtils.getLogger().info("C3DBG[sweep] $messageSuffix", *args)
    }

    private fun dispatchSweepTarget(
        sweep: SweepPhase,
        target: SweepTarget,
        context: PlanRuntimeTickContext,
    ): SweepDispatchOutcome {
        if (isPlacementBlockedByPlayer(target.pos, target.expected, context.playerFeetPos, context.stateAt)) {
            context.cancelPlacementRotation(target.actionId, target.pos)
            sweep.frontColumnBlocked = true
            return SweepDispatchOutcome.BLOCKED
        }
        sweep.frontColumnBlocked = false

        val resolution = resolvePlacement(
            worldPos = target.pos,
            expectedState = target.expected,
            stateAt = context.stateAt,
            eyePosition = context.eyePosition,
            reach = context.reach,
            settings = context.settings,
            placementContext = context.placementContext,
            predictPlacement = context.predictPlacement,
            orientedPrediction = context.orientedPrediction,
        )
        if (resolution !is PlacementResolution.Resolved) {
            context.cancelPlacementRotation(target.actionId, target.pos)
        }
        val outcome = when (resolution) {
            is PlacementResolution.OutOfReach -> SweepDispatchOutcome.BLOCKED
            PlacementResolution.NoSupportFace,
            PlacementResolution.PredictionMismatch,
            -> when {
                hasInFlightSweepSupportNeighbor(target) -> SweepDispatchOutcome.BLOCKED
                !target.supportDeferred && hasQueuedSweepSupportNeighbor(sweep, target) -> {
                    target.supportDeferred = true
                    SweepDispatchOutcome.DEFERRED
                }
                else -> SweepDispatchOutcome.CONSUMED
            }
            PlacementResolution.CategoryExcluded,
            PlacementResolution.TargetNotReplaceable,
            -> SweepDispatchOutcome.CONSUMED
            is PlacementResolution.Resolved -> {
                if (
                    isPlacementBlockedByPlayer(
                        target.pos,
                        resolution.placementState,
                        context.playerFeetPos,
                        context.stateAt,
                    ) ||
                    placementWouldSealPlayerEscape(
                        target.pos,
                        resolution.placementState,
                        context,
                    )
                ) {
                    context.cancelPlacementRotation(target.actionId, target.pos)
                    sweep.frontColumnBlocked = true
                    SweepDispatchOutcome.BLOCKED
                } else if (!context.itemSupplier.ensureHolding(resolution.placementState)) {
                    context.cancelPlacementRotation(target.actionId, target.pos)
                    SweepDispatchOutcome.CONSUMED
                } else {
                    val liveClickedPos = context.placementContext(resolution.placementState, resolution.hit).clickedPos
                    if (liveClickedPos != target.pos) {
                        context.cancelPlacementRotation(target.actionId, target.pos)
                        SweepDispatchOutcome.CONSUMED
                    } else {
                        val requiredRotation = resolution.requiredRotation
                        if (
                            requiredRotation != null &&
                            !context.preparePlacementRotation(target.actionId, target.pos, requiredRotation)
                        ) {
                            SweepDispatchOutcome.BLOCKED
                        } else {
                            if (requiredRotation == null) {
                                context.cancelPlacementRotation(target.actionId, target.pos)
                            }
                            val baselineState = context.liveStateAt(target.pos)
                            val submitted = try {
                                context.placementGateway.submit(resolution.hit, requiredRotation)
                            } finally {
                                if (requiredRotation != null) {
                                    context.finishPlacementRotation(target.actionId, target.pos)
                                }
                            }
                            if (!submitted) {
                                context.clearPendingPlace(target.pos)
                                SweepDispatchOutcome.CONSUMED
                            } else {
                                val tracked = attemptTracker.attempt(
                                    worldPos = target.pos,
                                    expectedState = resolution.placementState,
                                    baselineState = baselineState,
                                    sentTick = context.tick,
                                    retryCount = 0,
                                )
                                if (tracked) {
                                    sweepInFlightByPos[target.pos.immutable()] =
                                        SweepInFlightAction(
                                            target.actionId,
                                            placementCollisionReservationState(resolution.placementState),
                                        )
                                }
                                SweepDispatchOutcome.CONSUMED
                            }
                        }
                    }
                }
            }
        }
        if (outcome == SweepDispatchOutcome.CONSUMED) {
            LogUtils.getLogger().info(
                "C3DBG[sweep] consume unit={} y={} pos={} outcome={}",
                sweep.key.unitIndex, sweep.key.y, target.pos.toShortString(), resolution::class.simpleName,
            )
        } else if (outcome == SweepDispatchOutcome.DEFERRED) {
            LogUtils.getLogger().info(
                "C3DBG[sweep] defer-for-support unit={} y={} pos={}",
                sweep.key.unitIndex,
                sweep.key.y,
                target.pos.toShortString(),
            )
        }
        return outcome
    }

    private fun hasQueuedSweepSupportNeighbor(sweep: SweepPhase, target: SweepTarget): Boolean {
        var skippedFront = false
        for (candidate in sweep.queue) {
            if (!skippedFront) {
                skippedFront = true
                continue
            }
            val direction = Direction.values().firstOrNull { target.pos.relative(it) == candidate.pos } ?: continue
            val face = direction.opposite
            if (
                isUsableSupportFace(target.expected, face) &&
                isUsableSupportNeighbor(target.expected, candidate.expected, face)
            ) {
                return true
            }
        }
        return false
    }

    private fun hasInFlightSweepSupportNeighbor(target: SweepTarget): Boolean {
        for (direction in Direction.values()) {
            val support = sweepInFlightByPos[target.pos.relative(direction)] ?: continue
            val face = direction.opposite
            if (
                isUsableSupportFace(target.expected, face) &&
                isUsableSupportNeighbor(target.expected, support.collisionState, face)
            ) {
                return true
            }
        }
        return false
    }

    private fun applyStrictFrontierStallEscape(blockedActionId: Long?, context: PlanRuntimeTickContext): Unit {
        if (blockedActionId == null) return
        if (blockedActionId != stalledFrontierActionId) {
            stalledFrontierActionId = blockedActionId
            stalledFrontierSinceTick = context.tick
            return
        }
        val actionId = blockedActionId
        if (context.tick - stalledFrontierSinceTick <= STRICT_FRONTIER_STALL_TICKS) return
        if (waitingByActionId[actionId]?.reason == WaitingReason.PLAYER_COLUMN_BLOCKED) return
        val forced = cursor.submitSpecific(actionId) ?: return
        val stalledPos = waitingByActionId[actionId]?.worldPos
        clearWaiting(actionId)
        emit(CursorEvent.ResolveFailed(forced.actionId, forced.attemptId), groupIdOf(forced.action), context)
        strictStallEscapeCount++
        stalledFrontierActionId = null
        logStallEscapeDiagnostics(context.tick, actionId, stalledPos)
    }

    private fun logStallEscapeDiagnostics(tick: Long, actionId: Long, pos: BlockPos?): Unit {
        if (tick - lastStallEscapeDiagLogTick < DIAG_LOG_INTERVAL_TICKS) return
        lastStallEscapeDiagLogTick = tick
        LogUtils.getLogger().info(
            "C3DBG[stall-escape] fired t={} actionId={} pos={}",
            tick, actionId, pos?.toShortString(),
        )
    }

    private fun clearWaiting(actionId: Long): Unit {
        waitingByActionId.remove(actionId)
    }

    private fun dispatch(cursorAction: CursorAction, context: PlanRuntimeTickContext): Unit {
        when (val action = cursorAction.action) {
            is PlanAction.PlaceTarget -> dispatchPlacement(cursorAction, action.pos, action.expected, context)
            is PlanAction.PlaceScaffold -> dispatchPlacement(cursorAction, action.pos, SCAFFOLD_BLOCK_STATE, context)
            is PlanAction.RemoveScaffold -> dispatchRemove(cursorAction, action.pos, context)
        }
    }

    private fun dispatchPlacement(
        cursorAction: CursorAction,
        worldPos: BlockPos,
        expectedState: BlockState,
        context: PlanRuntimeTickContext,
    ): Unit {
        val actionId = cursorAction.actionId
        val attemptId = cursorAction.attemptId
        val groupId = groupIdOf(cursorAction.action)

        if (isPlacementBlockedByPlayer(worldPos, expectedState, context.playerFeetPos, context.stateAt)) {
            context.cancelPlacementRotation(actionId, worldPos)
            waitingByActionId[actionId] = WaitingRecord(worldPos, expectedState, WaitingReason.PLAYER_COLUMN_BLOCKED)
            emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.PLAYER_COLUMN_BLOCKED), groupId, context)
            return
        }

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = expectedState,
            stateAt = context.stateAt,
            eyePosition = context.eyePosition,
            reach = context.reach,
            settings = context.settings,
            placementContext = context.placementContext,
            predictPlacement = context.predictPlacement,
            orientedPrediction = context.orientedPrediction,
        )
        if (resolution !is PlacementResolution.Resolved) {
            context.cancelPlacementRotation(actionId, worldPos)
        }
        when (resolution) {
            is PlacementResolution.OutOfReach -> {
                waitingByActionId[actionId] = WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_REACH)
                emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_REACH), groupId, context)
            }
            PlacementResolution.NoSupportFace -> {
                if (hasAdvancingPlannedSupportNeighbor(worldPos, cursorAction.unitIndex, actionId)) {
                    waitingByActionId[actionId] = WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_SUPPORT)
                    emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_SUPPORT), groupId, context)
                } else {
                    clearWaiting(actionId)
                    emit(CursorEvent.ResolveFailed(actionId, attemptId), groupId, context)
                }
            }
            PlacementResolution.PredictionMismatch -> {
                if (hasAdvancingPlannedSupportNeighbor(worldPos, cursorAction.unitIndex, actionId)) {
                    waitingByActionId[actionId] =
                        WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_SUPPORT)
                    emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_SUPPORT), groupId, context)
                } else {
                    clearWaiting(actionId)
                    emit(CursorEvent.ResolveFailed(actionId, attemptId), groupId, context)
                }
            }
            PlacementResolution.CategoryExcluded,
            PlacementResolution.TargetNotReplaceable,
            -> {
                clearWaiting(actionId)
                emit(CursorEvent.ResolveFailed(actionId, attemptId), groupId, context)
            }
            is PlacementResolution.Resolved -> {
                if (
                    isPlacementBlockedByPlayer(
                        worldPos,
                        resolution.placementState,
                        context.playerFeetPos,
                        context.stateAt,
                    ) ||
                    placementWouldSealPlayerEscape(
                        worldPos,
                        resolution.placementState,
                        context,
                    )
                ) {
                    context.cancelPlacementRotation(actionId, worldPos)
                    waitingByActionId[actionId] =
                        WaitingRecord(worldPos, expectedState, WaitingReason.PLAYER_COLUMN_BLOCKED)
                    emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.PLAYER_COLUMN_BLOCKED), groupId, context)
                    return
                }
                if (!context.itemSupplier.ensureHolding(resolution.placementState)) {
                    context.cancelPlacementRotation(actionId, worldPos)
                    clearWaiting(actionId)
                    emit(CursorEvent.ItemUnavailable(actionId, attemptId), groupId, context)
                    return
                }
                val liveClickedPos = context.placementContext(resolution.placementState, resolution.hit).clickedPos
                if (liveClickedPos != worldPos) {
                    context.cancelPlacementRotation(actionId, worldPos)
                    val hitSupportPos = resolution.hit.blockPos
                    val staleSupportState = context.stateAt(hitSupportPos)
                    val liveSupportState = context.liveStateAt(hitSupportPos)
                    context.recordWrite(hitSupportPos, liveSupportState)
                    context.recordWrite(worldPos, context.liveStateAt(worldPos))
                    LogUtils.getLogger().info(
                        "[nuchematica] plan retarget divergence at {}: frozen support was {}, live support is {}",
                        hitSupportPos, staleSupportState, liveSupportState,
                    )
                    context.onFrozenLiveDivergence()
                    waitingByActionId[actionId] =
                        WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_REACH)
                    emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_REACH), groupId, context)
                    return
                }
                val requiredRotation = resolution.requiredRotation
                if (
                    requiredRotation != null &&
                    !context.preparePlacementRotation(actionId, worldPos, requiredRotation)
                ) {
                    waitingByActionId[actionId] =
                        WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_ROTATION)
                    emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_ROTATION), groupId, context)
                    return
                }
                if (requiredRotation == null) context.cancelPlacementRotation(actionId, worldPos)
                val baselineState = context.liveStateAt(worldPos)
                val submitted = try {
                    context.placementGateway.submit(resolution.hit, requiredRotation)
                } finally {
                    if (requiredRotation != null) context.finishPlacementRotation(actionId, worldPos)
                }
                if (!submitted) {
                    context.clearPendingPlace(worldPos)
                    clearWaiting(actionId)
                    emit(CursorEvent.SubmitFailed(actionId, attemptId), groupId, context)
                    return
                }
                val tracked = attemptTracker.attempt(
                    worldPos = worldPos,
                    expectedState = resolution.placementState,
                    baselineState = baselineState,
                    sentTick = context.tick,
                    retryCount = 0,
                )
                clearWaiting(actionId)
                if (tracked) {
                    inFlightByPos[worldPos.immutable()] =
                        InFlightAction(
                            actionId,
                            attemptId,
                            groupId,
                            placementCollisionReservationState(resolution.placementState),
                        )
                    emit(CursorEvent.Submitted(actionId, attemptId), groupId, context)
                } else {
                    emit(CursorEvent.SubmitFailed(actionId, attemptId), groupId, context)
                }
            }
        }
    }

    private fun placementWouldSealPlayerEscape(
        worldPos: BlockPos,
        placementState: BlockState,
        context: PlanRuntimeTickContext,
    ): Boolean {
        val playerFeetPos = context.playerFeetPos ?: return false
        val candidateCollisionState = placementCollisionReservationState(placementState)
        val collisionStateAt: (BlockPos) -> BlockState = { pos ->
            when {
                pos == worldPos -> candidateCollisionState
                inFlightByPos[pos] != null -> inFlightByPos.getValue(pos).collisionState
                removeInFlightByPos[pos] != null -> removeInFlightByPos.getValue(pos).collisionState
                sweepInFlightByPos[pos] != null -> sweepInFlightByPos.getValue(pos).collisionState
                else -> context.liveStateAt(pos)
            }
        }
        return !playerHasLocalEscapeRoute(playerFeetPos, collisionStateAt)
    }

    private fun dispatchRemove(cursorAction: CursorAction, worldPos: BlockPos, context: PlanRuntimeTickContext): Unit {
        val actionId = cursorAction.actionId
        val attemptId = cursorAction.attemptId
        val groupId = groupIdOf(cursorAction.action)
        val current = context.liveStateAt(worldPos)
        when {
            isReplaceableTarget(current) ->
                emit(CursorEvent.RemoveConfirmed(actionId, attemptId), groupId, context)
            current == SCAFFOLD_BLOCK_STATE -> {
                context.destroy(worldPos)
                removeTracker.attempt(
                    worldPos = worldPos,
                    expectedState = AIR_STATE,
                    baselineState = current,
                    sentTick = context.tick,
                    retryCount = 0,
                )
                removeInFlightByPos[worldPos.immutable()] =
                    InFlightAction(actionId, attemptId, groupId, current)
            }
            // Never break a cell that no longer contains this scaffold.
            else -> emit(CursorEvent.RemoveFailed(actionId, attemptId), groupId, context)
        }
    }

    private fun emit(event: CursorEvent.Correlated, groupId: Int, context: PlanRuntimeTickContext): Unit {
        cursor.onEvent(event)
        maybeCheckGroupCleanup(groupId, context)
    }

    private fun maybeCheckGroupCleanup(groupId: Int, context: PlanRuntimeTickContext): Unit {
        if (groupId in cleanupChecked) return
        val target = targetByGroup[groupId] ?: return
        if (!isTerminalActionState(cursor.stateOf(target.actionId))) return
        val removeIds = removeIdsByGroup[groupId] ?: emptyList()
        if (removeIds.any { id -> !isTerminalActionState(cursor.stateOf(id)) }) return

        cleanupChecked.add(groupId)
        val actual = context.liveStateAt(target.pos)
        if (!BlockStateEquivalence.matches(target.expected, actual, context.settings)) {
            cursor.onEvent(CursorEvent.PostCleanupMismatch(target.actionId))
        }
    }
}
