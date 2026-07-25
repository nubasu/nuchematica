package com.nubasu.nuchematica.printer

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

// Everything one adapter.tick() call needs, injected -- no Minecraft singleton access, no
// live settings-holder read, so this stays headless-testable exactly like PrinterTickContext.
// settings/placementContext/predictPlacement/orientedPrediction must all be bound to the same
// PlacementBehaviorSettings snapshot the owning session's PrintPlanner.plan call used (see
// resolvePlacement's own purity doc) -- never SchematicPrinter's v3 callbacks, which read the
// live PrinterSettingsHolder.
//
// stateAt is the frozen world model (plan/resolve purpose only -- resolvePlacement's own
// replaceable/support-neighbor checks are its only reader, so a resolved position is always
// judged against the same snapshot the plan itself was classified against). liveStateAt
// (mirrors v3's own PrinterTickContext.stateAt, always level::getBlockState) is what actually
// confirms an outcome: attempt/remove ack observation, every dispatch's own baseline read for
// a NEW attempt (dispatchPlacement's baselineState and dispatchRemove's current alike -- a
// submitted attempt is always judged against what the LIVE world held right before submission,
// never the frozen snapshot), a RemoveScaffold's pre-break world check, and the post-cleanup
// mismatch check all read it. Wiring ack observation to the frozen model instead is a circular
// dead end -- that model is updated ONLY from a decided ack (recordWrite), so an ack that can
// only ever be decided by reading the model it alone would update never settles.
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
    internal val destroy: (BlockPos) -> Boolean,
    internal val placementIntervalTicks: Int,
    // Defaults to PrinterRateLimiter's own single-submission-per-tick default so callers that
    // predate burst dispatch (e.g. PlanCoordinatorTest's own context builder) keep their
    // existing one-action-per-tick behavior unmodified.
    internal val attemptsPerTick: Int = PrinterRateLimiter.DEFAULT_ATTEMPTS_PER_TICK,
    // Fired from dispatchPlacement when a retarget mismatch is detected (see its own doc)
    // -- the session's frozen-world assumption no longer holds for at least this one cell, so
    // the owning session should be flagged dirty the same way a manual reconcile would (see
    // SchematicPrinter.invalidatePlanSession's own doc). Defaults to a no-op so callers that
    // never expect a divergence (most of PlanRuntimeAdapterTest) need not wire it.
    internal val onFrozenLiveDivergence: () -> Unit = {},
    // Fired once a placement action's own submission reaches a terminal ack (Accepted/
    // Rejected/WrongState/Timeout) -- releases whatever click-reconcile bookkeeping the
    // submission itself registered for worldPos, exactly like PrinterRuntime's own completion
    // loop releases ClientBlockInteractHandler.pendingPlacePositions for a v3 attempt the
    // instant it settles. Without this, a plan-mode placement's own useItemOn click (which
    // fires the same PlayerInteractEvent.RightClickBlock a real manual click would) would
    // outlive isPlacementInFlight's own in-flight window: the very next tick's generic
    // click-reconcile loop would then see this worldPos as no longer printer-owned but already
    // holding a real, non-air block state, misreading it as a manual placement and invalidating
    // the very session that just placed it. Defaults to a no-op so callers that never wire a
    // click-reconcile set (all of PlanRuntimeAdapterTest) need not touch it.
    internal val clearPendingPlace: (BlockPos) -> Unit = {},
)

// Plan-mode variant of SchematicPrinter's own orientedPrediction callback (see its
// PrinterCandidateSelector construction site): identical rotation-trial mechanism
// (resolveOrientedRotation), but the match check is bound to a fixed PlacementBehaviorSettings
// snapshot instead of reading the live PrinterSettingsHolder -- required by resolvePlacement's
// own purity invariant so a plan session's decisions can never mix settings values mid-run.
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

// How many consecutive ticks the SAME actionId may sit as the strict dispatch frontier's own
// blocking action (see PlanRuntimeAdapter.applyStrictFrontierStallEscape's own doc) before it
// is forced to a terminal ResolveFailed instead of being retried forever.
internal const val STRICT_FRONTIER_STALL_TICKS: Long = 600L

// Hard safety cap on PlanRuntimeAdapter.frontierSnapshot's own plan-order lookahead (see
// waitingForReach's own doc). The lookahead itself stops at the current layer segment's own
// boundary (see planOrderLookahead's own doc) well before this ever binds -- a full 48x48
// layer plus scaffold overhead comfortably fits under it -- so this only guards a
// pathological plan (a single unit/layer segment holding far more actions than any real
// schematic layer would) from returning an unbounded list.
internal const val FRONTIER_LOOKAHEAD_CAP: Int = 2560

// Minimum tick gap between two log lines of the same diagnostic category (see the
// lastXDiagLogTick fields) -- keeps a churning branch from spamming latest.log while
// still surfacing the condition regularly enough to observe live.
private const val DIAG_LOG_INTERVAL_TICKS: Long = 100L

// One action currently outstanding for either the placement tracker or the remove tracker --
// enough to correlate that tracker's own by-position completion back to the cursor's
// actionId/attemptId/groupId, none of which PrinterAttemptTracker itself knows about.
private data class InFlightAction(val actionId: Long, val attemptId: Long, val groupId: Int)

// One group's PlaceTarget identity, cached at construction so the post-cleanup check (see
// maybeCheckGroupCleanup) never needs to re-derive it from the cursor.
private data class GroupTarget(val actionId: Long, val pos: BlockPos, val expected: BlockState)

// One scaffold cell's own PlaceScaffold/RemoveScaffold action pair, cached at construction so
// outstandingScaffoldCells (see its own doc) never needs to re-derive the pairing from the
// cursor -- the cursor only ever exposes per-action state, not which RemoveScaffold reverses
// which PlaceScaffold.
private data class ScaffoldCell(val pos: BlockPos, val placeActionId: Long, val removeActionId: Long)

// One placement action's most recently reported Waiting outcome -- see
// PlanRuntimeAdapter.waitingByActionId's own doc for the map this backs.
private data class WaitingRecord(val worldPos: BlockPos, val expectedState: BlockState, val reason: WaitingReason)

// One (unitIndex, y) layer identity -- the same pairing planOrderLookahead's own segment
// boundary check already groups actions by (see that function's own doc), just precomputed
// once here rather than re-derived from whichever action happens to be the live head.
private data class SweepSegmentKey(val unitIndex: Int, val y: Int)

// One contiguous run of PlanRuntimeAdapter.lookaheadEntries sharing one SweepSegmentKey --
// see PlanRuntimeAdapter.segments' own doc for why this partition can be computed once,
// up front, instead of re-walked from the live frontier on every tick.
private data class SweepSegment(val key: SweepSegmentKey, val startIndex: Int, val endIndexExclusive: Int)

// One PlaceTarget a sweep pass still owes its own single re-attempt -- worldPos/expected
// mirror the action's own LookaheadEntry so dispatchSweepTarget can drive the exact same
// resolve/gateway/attemptTracker pipeline ordinary dispatch uses; actionId is kept only so
// the sweep's own in-flight bookkeeping (sweepInFlightByPos) can name which action a
// settling attempt belongs to without a second lookup.
private data class SweepTarget(val actionId: Long, val pos: BlockPos, val expected: BlockState)

// Non-null exactly while a sweep re-visit pass is actively dispatching -- see
// PlanRuntimeAdapter.activeSweep's own doc. key is this phase's own segment identity,
// kept only for the phase-begin/phase-end diagnostic log (see detectSweepPhaseBoundary/
// tickSweep's own doc) -- no dispatch decision reads it. queue is this pass's remaining
// targets in plan order (front is dispatched next); headSinceTick is the tick the CURRENT
// front first became the queue's own head, reset every time the front actually changes --
// the same same-blocker-stuck-for-N-ticks shape STRICT_FRONTIER_STALL_TICKS already
// escapes for ordinary dispatch (see applyStrictFrontierStallEscape's own doc), just
// scoped to this queue instead. frontColumnBlocked is the front target's own most recently
// observed isInPlayerColumn verdict (see PlanRuntimeAdapter.frontierSnapshot's own sweep
// branch for why this needs to be cached rather than recomputed live) -- reset to false
// the instant the front changes, so a stale verdict from an already-dequeued target can
// never leak onto its successor.
private class SweepPhase(val key: SweepSegmentKey, val queue: ArrayDeque<SweepTarget>, var headSinceTick: Long) {
    var frontColumnBlocked: Boolean = false
}

// See PlanRuntimeAdapter.targetActionCounts. failedOrSkipped is additive (a PlaceTarget that
// has reached a terminal Failed or Skipped state -- it will never be placed, and (unlike
// remaining/placed) this is the only one of the three counts that tallies it).
internal data class PlanTargetCounts(
    internal val remaining: Int,
    internal val placed: Int,
    internal val failedOrSkipped: Int,
)

// See PlanRuntimeAdapter.frontierSnapshot. A lookahead or WAITING_FOR_SUPPORT action's own
// target position and expected state -- the mover's own steering target once it exists.
internal class PlanFrontierTarget(internal val pos: BlockPos, internal val expected: BlockState)

// Snapshot of PlanRuntimeAdapter.frontierSnapshot: every position this adapter currently wants
// the mover's help with, split by why. inFlight is every worldPos this adapter has an
// outstanding submission against right now (placement or removal alike, mirroring
// isPlacementInFlight's own union of the two trackers). waitingForReach is the PLAN-ORDER
// LOOKAHEAD (see frontierSnapshot's own doc) -- steering the player through it in order
// naturally visits every strict-order dispatch blocker as soon as it exists, never a position
// dispatch has already moved past. waitingForSupport/columnBlocked still come from the
// adapter's own waitingByActionId bookkeeping. waitingForSupport is deliberately its own list
// rather than folded into waitingForReach: a mover reading waitingForReach as "go stand near
// this" would otherwise permanently park on a WAITING_FOR_SUPPORT cell (its distance never
// improves by moving, since the player being close was never what it needed) while genuinely
// reach-blocked cells elsewhere go unvisited. totalWaitingForReach/totalColumnBlocked are the
// FULL list sizes at build time, mirrored onto MoverPlanFrontier unchanged (see its own doc) so
// a HUD has a stable field to read regardless of whatever a downstream consumer does with the
// lists themselves. totalRemainingActions is the cursor's own pending+waiting+inFlight action
// count --
// what a plan-mode HUD's "blocks left" reads (see MoverCore's own remainingMissing doc): it can
// only ever shrink as the run progresses, unlike a frontier list whose own size tracks how far
// dispatch has marched rather than how much work is actually left.
internal class PlanFrontierSnapshot(
    internal val waitingForReach: List<PlanFrontierTarget>,
    internal val waitingForSupport: List<PlanFrontierTarget>,
    internal val columnBlocked: List<BlockPos>,
    internal val inFlight: List<BlockPos>,
    internal val totalWaitingForReach: Int,
    internal val totalColumnBlocked: Int,
    internal val totalRemainingActions: Int,
)

// Headless runtime adapter consuming a PlanExecutionCursor: maps its submittable actions onto
// the same resolve/gateway/item-supplier/attempt-tracker machinery PrinterRuntime already uses
// for v3, and maps their outcomes back onto the cursor's own CursorEvent vocabulary. Built
// against the same PrintPlan the cursor itself was built from, replaying that plan's own
// unit-major/action-order traversal to derive a parallel, read-only registry of every group's
// PlaceTarget identity and RemoveScaffold actionIds -- the cursor never exposes group
// membership itself (orderedFrontier only ever returns the current unit's own frontier), and a
// RemoveScaffold the cursor cascade-skips before it is ever submitted is otherwise invisible to
// this adapter, so that registry is the only way maybeCheckGroupCleanup can still know a
// group's full remove set upfront.
internal class PlanRuntimeAdapter(
    plan: PrintPlan,
    internal val cursor: PlanExecutionCursor,
    // The same world-space bounding-box predicate the owning session's PrintPlanner.plan call
    // was built against (see SchematicPrinter.tickPlanMode's own boundsPredicate) -- kept on
    // the adapter, not just as a local at construction time, so a later manual reconcile can
    // ask isWithinBounds whether a disturbed position was ever this session's own concern at
    // all. Defaults to "everywhere" for callers (most of PlanRuntimeAdapterTest) that never
    // exercise bounds-gated behavior.
    private val bounds: (BlockPos) -> Boolean = { true },
) {
    private val attemptTracker: PrinterAttemptTracker = PrinterAttemptTracker()
    private val removeTracker: PrinterAttemptTracker = PrinterAttemptTracker()
    private val rateLimiter: PrinterRateLimiter = PrinterRateLimiter()

    private val inFlightByPos: MutableMap<BlockPos, InFlightAction> = mutableMapOf()
    private val removeInFlightByPos: MutableMap<BlockPos, InFlightAction> = mutableMapOf()
    private val cleanupChecked: MutableSet<Int> = mutableSetOf()

    // Incremental frontier tracking for frontierSnapshot(): holds one entry per placement action
    // (PlaceTarget or PlaceScaffold) whose most recent dispatchPlacement outcome was Waiting,
    // updated ONLY at that function's own three Waiting emission sites (put/overwrite) and
    // whenever a later dispatch of the same action resolves to anything else (remove) -- never
    // walked or rebuilt from the plan/cursor, so this stays exactly frontier-sized (at most one
    // entry per group) by construction. An action can also go stale here without ever being
    // dispatched again -- a cascade-skip (PlanExecutionCursor.cascadeSkipIfPlacement) can flip a
    // Waiting action straight to Skipped -- so frontierSnapshot() itself re-checks cursor.stateOf
    // on every read and self-prunes any entry that fails that check.
    private val waitingByActionId: LinkedHashMap<Long, WaitingRecord> = LinkedHashMap()

    // See applyStrictFrontierStallEscape's own doc -- the actionId currently tracked as the
    // strict dispatch frontier's own blocking action, and the tick it first became that
    // blocker. Null whenever nothing is currently blocked (every group's own frontier this
    // tick was either InFlight or resolved past Waiting).
    private var stalledFrontierActionId: Long? = null
    private var stalledFrontierSinceTick: Long = 0L

    // Diagnostic-only rate-limit gates (see logSweepPhaseDiagnostics/
    // logStallEscapeDiagnostics/logBlockedHeadDiagnostics's own doc): the tick each
    // category's log last actually fired, so a churning sweep phase or a persistently
    // blocked frontier cannot spam latest.log more than once per DIAG_LOG_INTERVAL_TICKS.
    // Never read for anything but logging.
    private var lastSweepPhaseDiagLogTick: Long = -DIAG_LOG_INTERVAL_TICKS
    private var lastStallEscapeDiagLogTick: Long = -DIAG_LOG_INTERVAL_TICKS
    private var lastBlockedHeadDiagLogTick: Long = -DIAG_LOG_INTERVAL_TICKS

    private val targetByGroup: Map<Int, GroupTarget>
    private val removeIdsByGroup: Map<Int, List<Long>>
    private val scaffoldCells: List<ScaffoldCell>

    // One action's own position/expected-material pair for frontierSnapshot's own plan-order
    // lookahead (see waitingForReach's own doc) -- one entry per PlaceTarget/PlaceScaffold/
    // RemoveScaffold action, in the exact same actionId order the cursor itself assigns (unit-
    // major, then action-declaration order within each unit, matching this constructor's own
    // nextId traversal below), so a forward scan over this list already walks in plan order.
    // groupId/unitIndex back planOrderLookahead's own layer-segment boundary check: every
    // action of one group is always emitted together within a single unit's own action list
    // (see PrintPlanner.appendScaffoldActions), so a group's unitIndex is fixed and its own
    // PlaceTarget's y is looked up through targetByGroup rather than duplicated per entry.
    // isTargetAction is true only for the PlaceTarget entry itself -- a PlaceScaffold/
    // RemoveScaffold entry's own cell can sit at any y relative to its target (a scaffold
    // chain routes around real obstacles, never confined to its target's own y), so only a
    // PlaceTarget's own y is ever tested against the segment boundary.
    private data class LookaheadEntry(
        val actionId: Long,
        val pos: BlockPos,
        val expected: BlockState,
        val groupId: Int,
        val unitIndex: Int,
        val isTargetAction: Boolean,
    )

    private val lookaheadEntries: List<LookaheadEntry>

    // How far into lookaheadEntries every leading, already-terminal run has been confirmed to
    // reach -- advanced (never rewound) only past an entry whose own cursor state has itself
    // become terminal, which can never revert (see PlanExecutionCursor's own state machine).
    // Without this, planOrderLookahead would have to re-walk an ever-growing terminal prefix
    // from index 0 on every single tick as the run progresses.
    private var lookaheadResumeIndex: Int = 0

    // One planned position's own actionId and the unit its action belongs to -- see
    // plannedPositionActionIds' own doc for why both are needed together.
    private data class PlannedPosition(val actionId: Long, val unitIndex: Int)

    // Every PlaceTarget/PlaceScaffold position this plan will ever place, mapped to that
    // action's own actionId and unit index -- read only by hasPendingPlannedSupportNeighbor's
    // own NoSupportFace defer check (see its doc), which needs the unit index to restrict a
    // defer to a neighbor still within the SAME unit as the dependent. A RemoveScaffold
    // position is never recorded here: a resolve never needs to know about removal, only about
    // whether SOME planned placement still owes this cell its support. If two actions across
    // different groups ever target the same position (a scaffold cell reused after its own
    // removal, for a later target), the later action in plan order wins -- the plan's own most
    // current expectation for that cell.
    private val plannedPositionActionIds: Map<BlockPos, PlannedPosition>

    init {
        val targets = HashMap<Int, GroupTarget>()
        val removes = HashMap<Int, MutableList<Long>>()
        val cells = mutableListOf<ScaffoldCell>()
        // A group's own PlaceScaffold cells always precede its RemoveScaffold cells (see
        // validateGroupShape), so a single pass can resolve each RemoveScaffold's pairing
        // against whatever PlaceScaffold of the same position this same group already saw.
        val pendingScaffoldsByGroup = HashMap<Int, MutableMap<BlockPos, Long>>()
        val plannedPositions = HashMap<BlockPos, PlannedPosition>()
        val lookahead = mutableListOf<LookaheadEntry>()
        var nextId = 0L
        for ((unitIndex, unit) in plan.units.withIndex()) {
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

    // The whole plan's own lookaheadEntries, partitioned once into contiguous
    // (unitIndex, y) layer segments -- the unit a one-pass sweep re-visit operates over: once
    // every action of one segment has reached a terminal state, every PlaceTarget in it that
    // ended up Failed or Skipped and still mismatches the live world gets exactly one more
    // placement attempt (see detectSweepPhaseBoundary's own doc) before dispatch moves on to
    // the next segment. Computed with the exact same boundary rule planOrderLookahead applies
    // at its own live head (unitIndex must match; a target action's own y must additionally match the
    // segment's), just swept once from index 0 here instead of re-derived from whichever
    // action happens to be non-terminal right now. This is a safe, static precompute (not
    // merely a cached snapshot of a moving target) because PrintPlanner's own build order
    // guarantees it: one unit's actions are always laid out y-then-x-then-z within its own
    // tile, and one group's own actions (its PlaceScaffold run, PlaceTarget, RemoveScaffold
    // run) are always emitted contiguously (see LookaheadEntry's own doc) -- so every entry
    // whose OWN group's target falls in one (unit, y) row is necessarily contiguous in
    // lookaheadEntries, regardless of which of those entries happen to be terminal yet.
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

    // Index into `segments` of the next layer this adapter's own phase detection has not yet
    // resolved -- advances by exactly one every time detectSweepPhaseBoundary finds that
    // segment fully terminal, whether or not it actually produced a sweep (an empty layer
    // still counts as resolved -- see that function's own doc). segments.size once every
    // layer in the whole plan has been resolved.
    private var sweepSegmentCursor: Int = 0

    // Every SweepSegmentKey this adapter has ever resolved -- a layer is only ever offered its
    // one re-visit pass, so once detectSweepPhaseBoundary has decided a layer's own sweep set
    // (empty or not), that decision must never be revisited. sweepSegmentCursor alone already
    // guarantees monotonic, forward-only progress through `segments`, so in the current design
    // this set can never actually gain a duplicate; it is kept as the authoritative, explicit
    // record anyway so a future defect in the index's own advancement can never silently
    // re-sweep a layer without also tripping this guard.
    private val consumedSegments: MutableSet<SweepSegmentKey> = mutableSetOf()

    // Non-null exactly while a sweep re-visit pass is actively dispatching -- see
    // SweepPhase's own doc. Null whenever no sweep is in progress: tick()'s own branch reads
    // this null-ness to decide sweep dispatch vs strict frontier dispatch for the whole tick.
    private var activeSweep: SweepPhase? = null

    // worldPos -> the SweepTarget actionId currently outstanding against attemptTracker for a
    // sweep-submitted attempt -- the sweep-side counterpart to inFlightByPos, kept separate so
    // tick()'s own attemptTracker.observe loop can tell a sweep attempt's settlement apart
    // from an ordinary dispatch's (see that loop's own doc): a sweep settlement is consumed
    // locally (recordWrite on ACCEPTED only) and never converted into a CursorEvent.
    private val sweepInFlightByPos: MutableMap<BlockPos, Long> = mutableMapOf()

    // Whether this adapter still owes at least one sweep re-visit before its own session can
    // be treated as truly finished -- true while a sweep pass is actively dispatching, or
    // while the plan's own final layer has not yet had its own boundary check run even though
    // the cursor already reports complete (segments not yet all resolved). Read by
    // SchematicPrinter's own completion guard (see its own call site's doc) so the one-pass
    // automatic replan / completion chat never fires while this session's own final-layer
    // re-visit is still outstanding.
    internal fun isSweepPending(): Boolean {
        return activeSweep != null || (cursor.isComplete && sweepSegmentCursor < segments.size)
    }

    // Whether worldPos's own NoSupportFace should defer (Waiting) rather than terminally fail --
    // true iff at least one of its six neighbors is itself a planned PlaceTarget/PlaceScaffold
    // position, in the SAME unit as dependentUnitIndex, whose own action has not yet reached a
    // terminal state. The plan's own classification (PrintPlanner) only ever guarantees a
    // cell's support under IN-ORDER execution WITHIN one unit; strict plan-order dispatch still
    // allows multiple groups to be concurrently InFlight (see tick()'s own dispatch loop), so a
    // later group's own action can legitimately settle before an earlier one it depends on has
    // -- in that case the neighbor's own support is not missing, it just has not landed YET. A
    // neighbor in a DIFFERENT unit was
    // never this cell's classified support in the first place (a unit only ever completes once
    // every one of its own actions -- including this dependent one -- reaches a terminal state,
    // so a later unit's action stays Pending for as long as this one keeps waiting on it):
    // deferring on it can never resolve, only deadlock the unit-serial cursor forever. A
    // same-unit neighbor already terminal (Done, Failed, or Skipped) is excluded too: Done
    // already means the frozen model reflects it (so it would not have produced NoSupportFace in
    // the first place unless the material was placed and later removed again), and Failed/
    // Skipped means that support is never coming -- waiting on it would stall this action forever
    // instead of correctly reporting the same permanent failure v3 would.
    private fun hasPendingPlannedSupportNeighbor(worldPos: BlockPos, dependentUnitIndex: Int): Boolean {
        for (direction in Direction.values()) {
            val neighbor = plannedPositionActionIds[worldPos.relative(direction)] ?: continue
            if (neighbor.unitIndex != dependentUnitIndex) continue
            if (!isTerminalActionState(cursor.stateOf(neighbor.actionId))) return true
        }
        return false
    }

    // Every scaffold cell this adapter's own plan slice may have actually landed in the world
    // (see mayHaveLanded) whose reverse RemoveScaffold has not itself reached Done -- covers a
    // RemoveScaffold that never ran yet (Pending/Waiting), is still in flight, or settled to
    // a terminal failure without actually confirming the break. Read by the owning session's
    // teardown sweep (see SchematicPrinter.tickPlanMode's doc) so a session discarded
    // mid-run never strands its own scaffold material.
    internal fun outstandingScaffoldCells(): List<BlockPos> {
        return scaffoldCells
            .filter { cell -> mayHaveLanded(cursor.stateOf(cell.placeActionId)) }
            .filterNot { cell -> cursor.stateOf(cell.removeActionId) is ActionState.Done }
            .map { cell -> cell.pos }
    }

    // A PlaceScaffold state proves its own cell MIGHT actually be standing in the world: Done
    // (confirmed placed), InFlight (submitted, ack not yet observed -- the real placement may
    // already have landed server-side), or Failed(TIMEOUT) (the runtime gave up waiting without
    // ever learning whether it landed -- mirrors isDefinitelyNotPlaced's own TIMEOUT exclusion
    // in PlanExecutionCursor). Every other terminal reason (SUBMIT_FAILED/ITEM_UNAVAILABLE/
    // RESOLVE_FAILED/REJECTED/WRONG_STATE) or Skipped proves the cell never made it, so sweeping
    // it would be a no-op the world-check-first destroy loop already skips on its own -- listed
    // here anyway so the sweep's own candidate set stays as small as it can honestly be.
    private fun mayHaveLanded(state: ActionState): Boolean {
        return state is ActionState.Done ||
            state is ActionState.InFlight ||
            (state is ActionState.Failed && state.reason == ActionFailureReason.TIMEOUT)
    }

    // Best-effort cleanup for every cell outstandingScaffoldCells still finds standing as
    // scaffold material in the live world: destroys it, then -- since destroyBlock is
    // synchronous in creative (no multi-tick ack cycle; see ScaffoldLedger.cleanupAll's own doc
    // for the same javap-verified fact) -- rereads the live state immediately after and, if it
    // now reads air/replaceable, writes it into the frozen model too. Without this, a session
    // torn down mid-run would leave the frozen model still believing scaffold material stands
    // at a cell this sweep just broke, so a LATER session built against the same frozen model
    // (recordWrite is never rolled back) would misjudge that cell's own support/replaceable
    // checks. No Minecraft singleton access here -- the live level/gameMode/frozen-model writer
    // are all injected by the caller (SchematicPrinter.sweepOutstandingScaffolds), keeping this
    // headless-testable like every other adapter entry point.
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

    // Tally of every group's own PlaceTarget action by cursor state, ignoring scaffold
    // place/remove actions entirely -- a plan-mode HUD reports progress on real schematic
    // targets only, the same thing v3's own missing-count HUD always meant. remaining counts
    // every PlaceTarget not yet terminal (Pending/Waiting/InFlight); placed counts Done ones;
    // a Failed or Skipped target counts toward failedOrSkipped instead (it will never be
    // placed, but it is also not "remaining" work this session can still make progress on).
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

    // Read by SchematicPrinter.ownsPendingPlacement (see its own doc) so a plan-mode
    // placement's own useItemOn click -- which fires the same PlayerInteractEvent.RightClickBlock
    // a manual player click would -- is recognized as printer-owned rather than reconciled as a
    // manual placement, which would otherwise invalidate the very session that just placed it.
    // Also true for a position this session currently has a RemoveScaffold action in flight for
    // (removeInFlightByPos) -- that submission is just as much this session's own outstanding
    // action against worldPos as a placement is, so it must be recognized as owned by the same
    // query rather than only ever covering the placement side. sweepInFlightByPos is the same
    // concern for a sweep-submitted attempt: it fires the exact same gateway submission
    // (dispatchSweepTarget reuses placementGateway.submit unchanged), so a click reconcile
    // running while that attempt is outstanding must recognize it as owned too.
    internal fun isPlacementInFlight(worldPos: BlockPos): Boolean {
        return inFlightByPos.containsKey(worldPos) ||
            removeInFlightByPos.containsKey(worldPos) ||
            sweepInFlightByPos.containsKey(worldPos)
    }

    // Read by SchematicPrinter.invalidatePlanSession (see its own doc) so a manual reconcile
    // outside this session's own bounding box -- a position this session never planned against
    // in the first place -- never discards a session that has nothing to do with it.
    internal fun isWithinBounds(worldPos: BlockPos): Boolean {
        return bounds(worldPos)
    }

    // Read by SchematicPrinter.planFrontierSnapshot (see its own doc) so a later mover can steer
    // the player toward whatever this session is currently blocked on. waitingForReach comes
    // from planOrderLookahead (see its own doc), never waitingByActionId -- it is a plan-order
    // walk, not a snapshot of what happens to be Waiting right now. waitingForSupport/
    // columnBlocked still walk waitingByActionId in insertion order (see that field's own doc
    // for why this stays cheap), filtering each entry through cursor.stateOf: only actions still
    // actually Waiting are kept, and every entry that fails that check -- a cascade-skip flipped
    // it to Skipped without ever passing back through dispatchPlacement -- is dropped from the
    // map in this same pass, keeping it self-pruning. inFlight is collected in insertion order
    // from inFlightByPos/removeInFlightByPos (both mutableMapOf(), i.e. LinkedHashMap, so this
    // needs no extra sort) -- no caller needs a stronger ordering guarantee than that.
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
                // Superseded by planOrderLookahead below -- WAITING_FOR_REACH bookkeeping is
                // still written (see dispatchPlacement's OutOfReach branch) because
                // applyStrictFrontierStallEscape's own PLAYER_COLUMN_BLOCKED exemption check
                // reads this map regardless of reason, but the reach list itself no longer
                // reads it.
                WaitingReason.WAITING_FOR_REACH -> Unit
                // Its own list, never folded into waitingForReach -- see PlanFrontierSnapshot's
                // own doc for why a mover steering off that list must not also see this reason.
                WaitingReason.WAITING_FOR_SUPPORT ->
                    waitingForSupport += PlanFrontierTarget(record.worldPos, record.expectedState)
                WaitingReason.PLAYER_COLUMN_BLOCKED -> columnBlocked += record.worldPos
            }
        }
        for (actionId in stale) clearWaiting(actionId)

        // While a sweep is active, it OWNS waitingForReach/columnBlocked -- see
        // PlanFrontierSnapshot's own doc for why a mover reading these two lists must be
        // steered at the sweep's own re-visit targets instead of the ordinary plan-order
        // lookahead/waitingByActionId, which are frozen (no longer being dispatched into)
        // for as long as the sweep itself is running. waitingForSupport above is left
        // untouched either way: a sweep never produces a WAITING_FOR_SUPPORT entry of its
        // own, but an ordinary one recorded before the sweep started (a later, not-yet-
        // dispatched group in this same unit) is still live and still worth reporting.
        val sweep = activeSweep
        val waitingForReach: List<PlanFrontierTarget>
        val sweepColumnBlocked: List<BlockPos>
        val sweepInFlight: List<BlockPos>
        if (sweep != null) {
            waitingForReach = sweep.queue.map { target -> PlanFrontierTarget(target.pos, target.expected) }
            sweepColumnBlocked = if (sweep.frontColumnBlocked) {
                sweep.queue.firstOrNull()?.let { front -> listOf(front.pos) } ?: emptyList()
            } else {
                emptyList()
            }
            sweepInFlight = sweepInFlightByPos.keys.toList()
        } else {
            waitingForReach = planOrderLookahead()
            sweepColumnBlocked = columnBlocked
            sweepInFlight = emptyList()
        }
        val inFlight = inFlightByPos.keys.toList() + removeInFlightByPos.keys.toList() + sweepInFlight
        val status = cursor.status()
        return PlanFrontierSnapshot(
            waitingForReach = waitingForReach,
            waitingForSupport = waitingForSupport,
            columnBlocked = sweepColumnBlocked,
            inFlight = inFlight,
            totalWaitingForReach = waitingForReach.size,
            totalColumnBlocked = sweepColumnBlocked.size,
            totalRemainingActions = status.pendingCount + status.waitingCount + status.inFlightCount,
        )
    }

    // Starting at the current strict frontier (the globally earliest non-terminal action across
    // the whole plan, by actionId -- every earlier action, and every earlier unit, is by
    // definition already terminal, or that earlier unit would still be the current one), walks
    // lookaheadEntries forward in plan order collecting every position through the END OF THE
    // HEAD'S OWN LAYER SEGMENT: the head's own group's PlaceTarget y (see LookaheadEntry's own
    // doc for why a PlaceScaffold/RemoveScaffold cell's own y never gates this) and the head's
    // own unit -- a later unit's action list can restart at the SAME y under a different tile
    // (see PrintPlanner's own band/tile-snake build order), so the unit boundary is checked
    // independently and always wins over a coincidentally-matching y. FRONTIER_LOOKAHEAD_CAP is
    // a hard safety cap only (see its own doc), never expected to bind on a real layer.
    // lookaheadResumeIndex (see its own doc) skips the ever-growing already-terminal leading run
    // in amortized O(1) per call; entries between the resume point and the segment's own end
    // that are ALREADY terminal (a sibling group can settle before an earlier one it does not
    // depend on -- see tick()'s own dispatch loop) are simply skipped rather than counted toward
    // the cap.
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
            // A sweep-submitted attempt settling is consumed right here, locally -- it never
            // drives a cursor transition, since PlanExecutionCursor already considers this
            // action terminal: the sweep's own one-attempt budget for this position is spent
            // either way, and only an ACCEPTED settlement needs to survive it, as a frozen-
            // model write.
            val sweepActionId = sweepInFlightByPos.remove(result.attempt.worldPos)
            if (sweepActionId != null) {
                context.clearPendingPlace(result.attempt.worldPos)
                if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                    context.recordWrite(result.attempt.worldPos, result.observedState)
                }
                continue
            }
            val inFlight = inFlightByPos.remove(result.attempt.worldPos) ?: continue
            // The generic click reconcile must never become a second progress owner after this
            // terminal result releases the attempt tracker (see clearPendingPlace's own doc).
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

        for (result in removeTracker.observe(context.tick, context.liveStateAt, matches)) {
            val inFlight = removeInFlightByPos.remove(result.attempt.worldPos) ?: continue
            val event: CursorEvent.Correlated = if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                context.recordWrite(result.attempt.worldPos, result.observedState)
                CursorEvent.RemoveConfirmed(inFlight.actionId, inFlight.attemptId)
            } else {
                // REJECTED (settled back to baseline, break never took) / WRONG_STATE (settled
                // to neither air nor baseline) / TIMEOUT (never settled by the deadline) all
                // mean the same thing here: the cell's own removal cannot be confirmed.
                CursorEvent.RemoveFailed(inFlight.actionId, inFlight.attemptId)
            }
            emit(event, inFlight.groupId, context)
        }

        // Sweep dispatch replaces strict frontier dispatch outright for the whole tick --
        // never both in the same call (see dispatchSweepTarget's own one-attempt-per-target
        // doc). An already-active sweep keeps draining its own queue; otherwise this tick's first
        // job is to check whether the layer strict dispatch just finished has any own
        // sweep-worthy targets (detectSweepPhaseBoundary), and only fall through to ordinary
        // strict dispatch once that check finds nothing left to sweep right now.
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

        // Strict plan-order dispatch: orderedFrontier() is one actionId per group, in group
        // first-appearance (= plan) order, snapshotted once for this tick. An already-InFlight
        // frontier is skipped without spending rate-limiter budget (its own outcome is still
        // pending from an earlier tick; some other group may still dispatch this tick). A
        // Pending/Waiting frontier is retried in-order, budget permitting -- but the instant one
        // comes back Waiting again, dispatch for this tick STOPS entirely: nothing later in
        // plan order may ever be attempted while an earlier action in the same unit is still
        // Pending or Waiting, which is exactly the ordering the plan's own correctness model
        // assumes. Groups that resolve immediately (InFlight via a real submission, or a
        // terminal failure) let the loop continue on to the next group, so up to
        // attemptsPerTick submissions can land in a single tick whenever consecutive groups
        // resolve without blocking.
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

    // Diagnostic only, rate-limited (see DIAG_LOG_INTERVAL_TICKS): identifies the strict
    // dispatch frontier's own current blocker while it stays Waiting -- kind/pos come
    // straight from the blocked action itself, reason from its own waitingByActionId
    // record (see dispatchPlacement's own Waiting sites -- "unknown" only if a cascade-
    // skip or other bookkeeping edge left no record for this actionId), and stallTicks is
    // how long applyStrictFrontierStallEscape has been counting THIS SAME action as the
    // blocker (0 the tick a new blocker first appears).
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

    // Checks the plan's own next un-resolved layer (segments[sweepSegmentCursor]) for full
    // termination -- see `segments`' own doc for why this partition never needs re-deriving.
    // Only ever called from tick() while no sweep is currently active. A layer whose own sweep
    // candidate set turns out empty is resolved immediately, in the SAME call, so a run of
    // clean layers advances sweepSegmentCursor past all of them in one tick rather than
    // trickling one layer per tick. Stops the moment it either finds a layer not yet fully
    // terminal (nothing to do this tick -- strict dispatch is still working through it) or a
    // fully terminal layer with at least one sweep candidate (activates activeSweep and
    // returns without checking any further layer this same call).
    private fun detectSweepPhaseBoundary(context: PlanRuntimeTickContext): Unit {
        while (sweepSegmentCursor < segments.size) {
            val segment = segments[sweepSegmentCursor]
            if (segment.key in consumedSegments) {
                // Structurally unreachable under normal monotonic advancement -- see
                // consumedSegments' own doc for why this branch exists anyway.
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

    // Drives the currently active sweep phase exactly one tick: dispatches from the queue's
    // own front until either the rate limiter's budget for this tick is spent, the queue
    // itself empties (closing this sweep phase -- see activeSweep's own doc), or the front
    // target is genuinely blocked (reach/player-column, not yet past its own stall bound) --
    // mirroring the strict frontier loop's own "stop the instant one blocks" rule, just
    // scoped to this queue instead of cursor.orderedFrontier(). The stall check itself never
    // spends rate-limiter budget (mirroring applyStrictFrontierStallEscape's own budget-free
    // forced transition) -- only a genuine resolve attempt does.
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
            val consumed = dispatchSweepTarget(sweep, front, context)
            if (!consumed) break
            sweep.queue.removeFirst()
            sweep.headSinceTick = context.tick
            sweep.frontColumnBlocked = false
        }
        if (sweep.queue.isEmpty()) {
            logSweepPhaseDiagnostics(context.tick, "phase-end unit={} y={}", sweep.key.unitIndex, sweep.key.y)
            activeSweep = null
        }
    }

    // Diagnostic only, rate-limited (see DIAG_LOG_INTERVAL_TICKS): a sweep phase's own
    // begin/end boundary, at most one line per segment either way -- naturally rare
    // (bounded by segments.size, never per-tick), rate-limited anyway for consistency with
    // every other diagnostic log in this file. messageSuffix is appended after the fixed
    // "C3DBG[sweep] " prefix, formatted through the same {}-placeholder convention every
    // other log call in this codebase uses.
    private fun logSweepPhaseDiagnostics(tick: Long, messageSuffix: String, vararg args: Any?): Unit {
        if (tick - lastSweepPhaseDiagLogTick < DIAG_LOG_INTERVAL_TICKS) return
        lastSweepPhaseDiagLogTick = tick
        LogUtils.getLogger().info("C3DBG[sweep] $messageSuffix", *args)
    }

    // One sweep target's own single re-attempt, reusing the exact same resolve/gateway/
    // attemptTracker pipeline dispatchPlacement uses. Every resolution outcome consumes this
    // target's one-and-only sweep attempt immediately EXCEPT OutOfReach/player-column, which
    // stay queued for the mover to resolve (see tickSweep's own stall escape for the bound on
    // how long that can persist); a successful submission is also consumed immediately from
    // the queue's own perspective, its outcome settling later, asynchronously, through
    // sweepInFlightByPos. Returns
    // whether target is now fully spent (true -- the caller dequeues it) or still needs the
    // mover's help before it can be tried again (false -- left at the queue's own front).
    // Never emits a CursorEvent and never touches hasPendingPlannedSupportNeighbor's own
    // same-unit deferral: a sweep pass gets exactly one attempt per target, no deferral.
    private fun dispatchSweepTarget(sweep: SweepPhase, target: SweepTarget, context: PlanRuntimeTickContext): Boolean {
        if (isInPlayerColumn(target.pos, context.playerFeetPos)) {
            sweep.frontColumnBlocked = true
            return false
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
        val consumed = when (resolution) {
            is PlacementResolution.OutOfReach -> false
            PlacementResolution.NoSupportFace,
            PlacementResolution.PredictionMismatch,
            PlacementResolution.CategoryExcluded,
            PlacementResolution.TargetNotReplaceable,
            -> true
            is PlacementResolution.Resolved -> {
                if (!context.itemSupplier.ensureHolding(resolution.placementState)) {
                    true
                } else {
                    // Mirrors dispatchPlacement's own retarget guard: re-derive the clicked
                    // position against the LIVE world and never submit when it disagrees with
                    // target.pos, or the click would land on the wrong cell. A sweep pass gets
                    // exactly one attempt per target and is never revisited, so unlike ordinary
                    // dispatch there is no dirty flag / Waiting requeue to arrange for a later
                    // retry -- the divergence simply consumes this target's one attempt.
                    val liveClickedPos = context.placementContext(resolution.placementState, resolution.hit).clickedPos
                    if (liveClickedPos != target.pos) {
                        true
                    } else {
                        val baselineState = context.liveStateAt(target.pos)
                        if (!context.placementGateway.submit(resolution.hit, resolution.requiredRotation)) {
                            // Mirrors dispatchPlacement's own SubmitFailed branch: a refused
                            // submission can still have registered a pending-place entry for
                            // worldPos as a side effect of the same gateway call (see
                            // clearPendingPlace's own doc), so it must be released here too.
                            context.clearPendingPlace(target.pos)
                            true
                        } else {
                            val tracked = attemptTracker.attempt(
                                worldPos = target.pos,
                                expectedState = resolution.placementState,
                                baselineState = baselineState,
                                sentTick = context.tick,
                                retryCount = 0,
                            )
                            if (tracked) {
                                sweepInFlightByPos[target.pos.immutable()] = target.actionId
                            }
                            // Defensive only, mirroring dispatchPlacement's own else branch: the
                            // sweep queue never dispatches the same worldPos twice, so tracked
                            // should always be true here. Either way the target's own single
                            // attempt has now been spent.
                            true
                        }
                    }
                }
            }
        }
        // Diagnostic only, never rate-limited (see tickSweep's own consume budget -- at
        // most attemptsPerTick consumes land per tick, never enough to spam latest.log):
        // a consumed target leaves the queue for good, so every consume is worth its own
        // line, unlike the higher-frequency per-tick logs elsewhere in this file.
        if (consumed) {
            LogUtils.getLogger().info(
                "C3DBG[sweep] consume unit={} y={} pos={} outcome={}",
                sweep.key.unitIndex, sweep.key.y, target.pos.toShortString(), resolution::class.simpleName,
            )
        }
        return consumed
    }

    // Forces a strict frontier action that has been permanently stuck to a terminal
    // ResolveFailed, so its own group's cascade skips the rest of its chain and the unit's
    // completion can advance past it instead of retrying forever. blockedActionId is this
    // tick's own strict-frontier blocker -- null on a tick that dispatched without ever
    // reaching a Waiting head (nothing blocked dispatch at all), but ALSO null on a tick
    // that never even reached the frontier's own head to begin with (e.g. the rate
    // limiter's budget ran out earlier in tick()'s own dispatch loop, before this tick's
    // strict-frontier for-loop got far enough to observe it). Only the second case is
    // "no observation" -- the streak below must survive it unchanged, or a budget of 1
    // dispatch attempt per N>1 ticks would never let a genuine 600-tick stall accumulate:
    // every other tick's null would reset it back to zero before it ever reached the
    // threshold. A genuinely DIFFERENT action becoming the blocker (or dispatch resolving
    // the previous blocker and moving on) is still a real reset -- only a null observation
    // is skipped. Forcing on a stale streak can never misfire even so: cursor.submitSpecific
    // (below) already self-guards on its own -- it returns null unless actionId is still
    // literally the live frontier head of its own unit AND still Pending/Waiting (see its
    // own doc) -- so a streak that carried across unobserved ticks can still only ever force
    // an action that is, at the moment of firing, genuinely still stuck. PLAYER_COLUMN_BLOCKED
    // is exempt: that is a transient player-positioning problem the mover is expected to
    // resolve by moving the player away, not a resolution failure, so it must never be
    // forced to a terminal failure by this escape.
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
        // A fresh, matching attemptId is required for the cursor's own correlation gate to
        // accept this report (see PlanExecutionCursor.onEvent) -- the attempt this tick's own
        // dispatch already submitted was already decided (Waiting cleared its own
        // awaitingAttemptId). No call to dispatch/resolvePlacement here: the stall itself, not
        // a fresh resolution outcome, is what is being reported.
        val forced = cursor.submitSpecific(actionId) ?: return
        val stalledPos = waitingByActionId[actionId]?.worldPos
        clearWaiting(actionId)
        emit(CursorEvent.ResolveFailed(forced.actionId, forced.attemptId), groupIdOf(forced.action), context)
        stalledFrontierActionId = null
        logStallEscapeDiagnostics(context.tick, actionId, stalledPos)
    }

    // Diagnostic only, rate-limited (see DIAG_LOG_INTERVAL_TICKS): the strict frontier
    // stall escape actually fired -- actionId/pos identify which action was permanently
    // stuck and forced to ResolveFailed above.
    private fun logStallEscapeDiagnostics(tick: Long, actionId: Long, pos: BlockPos?): Unit {
        if (tick - lastStallEscapeDiagLogTick < DIAG_LOG_INTERVAL_TICKS) return
        lastStallEscapeDiagLogTick = tick
        LogUtils.getLogger().info(
            "C3DBG[stall-escape] fired t={} actionId={} pos={}",
            tick, actionId, pos?.toShortString(),
        )
    }

    // Removes a WAITING_FOR_REACH/WAITING_FOR_SUPPORT/PLAYER_COLUMN_BLOCKED bookkeeping entry.
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

        // Mirrors PrinterCandidateSelector.select's own player-column guard -- a transient,
        // non-structural obstruction the mover resolves by moving the player away, not a
        // resolve failure.
        if (isInPlayerColumn(worldPos, context.playerFeetPos)) {
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
        when (resolution) {
            is PlacementResolution.OutOfReach -> {
                waitingByActionId[actionId] = WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_REACH)
                emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_REACH), groupId, context)
            }
            PlacementResolution.NoSupportFace -> {
                // The support this cell needs may simply not have been dispatched yet (see
                // hasPendingPlannedSupportNeighbor's own doc) -- a dedicated WAITING_FOR_SUPPORT
                // reason rather than reusing WAITING_FOR_REACH, because a mover reading the
                // frontier must treat the two differently: a reach-blocked target resolves BY
                // moving the player closer, so steering a mover toward it is exactly right, but
                // a support-blocked target's own position may already be in reach right now --
                // moving the player there again accomplishes nothing. frontierSnapshot keeps
                // this reason out of waitingForReach for exactly that reason (see
                // PlanFrontierSnapshot's own doc).
                if (hasPendingPlannedSupportNeighbor(worldPos, cursorAction.unitIndex)) {
                    waitingByActionId[actionId] = WaitingRecord(worldPos, expectedState, WaitingReason.WAITING_FOR_SUPPORT)
                    emit(CursorEvent.Waiting(actionId, attemptId, WaitingReason.WAITING_FOR_SUPPORT), groupId, context)
                } else {
                    clearWaiting(actionId)
                    emit(CursorEvent.ResolveFailed(actionId, attemptId), groupId, context)
                }
            }
            PlacementResolution.PredictionMismatch,
            PlacementResolution.CategoryExcluded,
            PlacementResolution.TargetNotReplaceable,
            -> {
                clearWaiting(actionId)
                emit(CursorEvent.ResolveFailed(actionId, attemptId), groupId, context)
            }
            is PlacementResolution.Resolved -> {
                if (!context.itemSupplier.ensureHolding(resolution.placementState)) {
                    clearWaiting(actionId)
                    emit(CursorEvent.ItemUnavailable(actionId, attemptId), groupId, context)
                    return
                }
                // Retarget guard: BlockPlaceContext.getClickedPos() resolves against the LIVE
                // world (its constructor's own replaceClicked check reads context.level, always
                // the real level -- see resolvePlacement's placementContext wiring), so asking
                // it again here re-derives the placement position the REAL client would land on
                // right now, independent of whatever the frozen model assumed when this hit was
                // resolved. A mismatch against worldPos means this cell's own support neighbor
                // has diverged between the frozen model and the live world since classification
                // (e.g. the frozen model still sees a support block the live world no longer
                // has) -- submitting anyway would place the wrong block at the wrong cell. Never
                // submit in that state: flag the session dirty (the next tick reclassifies
                // against the live world) and report Waiting rather than a terminal failure so
                // the cursor keeps this cell eligible for retry in the meantime.
                val liveClickedPos = context.placementContext(resolution.placementState, resolution.hit).clickedPos
                if (liveClickedPos != worldPos) {
                    // The frozen model itself must be repaired before the dirty flag is raised,
                    // or the next rebuild reclassifies against the SAME stale cell, resolves the
                    // SAME now-vanished support, and diverges again forever -- nothing else ever
                    // rewrites this cell once its own placement ack (if any) already released its
                    // reconcile bookkeeping. The live reads this guard itself just consulted are
                    // server-confirmed observations, the same legitimacy an ack's own recordWrite
                    // requires, for both the support cell the resolver clicked (hitSupportPos)
                    // and worldPos itself. External (non-automode) block removal is outside the
                    // frozen-world premise this repair assumes; what it actually handles is a
                    // cell whose own placement was acknowledged and then popped back off, since
                    // that cell's continued membership in the missing set is exactly what lets a
                    // later rebuild re-include it.
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
                val baselineState = context.liveStateAt(worldPos)
                if (!context.placementGateway.submit(resolution.hit, resolution.requiredRotation)) {
                    // The real gateway's own submit (RealPlacementGateway.submit -> gameMode.
                    // useItemOn) fires PlayerInteractEvent.RightClickBlock -- and therefore
                    // registers a pending-place entry for worldPos -- as a side effect of that
                    // same call, before useItemOn's own result is known. A refused submission
                    // (this branch) can thus still have left that entry behind even though this
                    // attempt itself never reaches attemptTracker/inFlightByPos to release it
                    // later. Clear it here too, exactly like a terminal ack does (see
                    // clearPendingPlace's own doc), or the next tick's generic click-reconcile
                    // loop would see this worldPos as no longer printer-owned but already
                    // holding whatever live state that click left behind, misreading it as a
                    // manual placement.
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
                    inFlightByPos[worldPos.immutable()] = InFlightAction(actionId, attemptId, groupId)
                    emit(CursorEvent.Submitted(actionId, attemptId), groupId, context)
                } else {
                    // Defensive only: the cursor's own single-in-flight-per-unit invariant
                    // means worldPos should never already be tracked here. If it somehow
                    // were, nothing would ever observe this submission's outcome, so treat it
                    // as a submit failure rather than leaving the action stuck InFlight.
                    emit(CursorEvent.SubmitFailed(actionId, attemptId), groupId, context)
                }
            }
        }
    }

    private fun dispatchRemove(cursorAction: CursorAction, worldPos: BlockPos, context: PlanRuntimeTickContext): Unit {
        val actionId = cursorAction.actionId
        val attemptId = cursorAction.attemptId
        val groupId = groupIdOf(cursorAction.action)
        val current = context.liveStateAt(worldPos)
        when {
            // Already gone (broken by something else, or never actually landed) -- nothing to
            // break, report success as a no-op.
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
                removeInFlightByPos[worldPos.immutable()] = InFlightAction(actionId, attemptId, groupId)
            }
            // Some other, unrelated block occupies the cell -- never break a block the printer
            // did not itself place there.
            else -> emit(CursorEvent.RemoveFailed(actionId, attemptId), groupId, context)
        }
    }

    private fun emit(event: CursorEvent.Correlated, groupId: Int, context: PlanRuntimeTickContext): Unit {
        cursor.onEvent(event)
        maybeCheckGroupCleanup(groupId, context)
    }

    // Fires once per group, the first tick its own PlaceTarget and every one of its
    // RemoveScaffold actions (if any) are all terminal -- for a scaffold-free group that is
    // simply the tick its lone PlaceTarget itself settles (removeIdsByGroup has no entry, so
    // the vacuous all-of-empty check passes immediately). Uses cursor.stateOf rather than this
    // adapter's own dispatch/observe bookkeeping because a RemoveScaffold the cursor
    // cascade-skips (see PlanExecutionCursor.cascadeSkipIfPlacement) never passes through
    // dispatch/emit at all.
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
