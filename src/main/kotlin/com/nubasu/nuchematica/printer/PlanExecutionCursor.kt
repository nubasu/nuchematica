package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos

// One action's cursor-owned bookkeeping: its assigned id, which unit/group it belongs to,
// the original PlanAction, and the live mutable state/retry count PlanExecutionCursor
// advances as events arrive. awaitingAttemptId is the attemptId of the submission this
// record is currently outstanding for (null when nothing is outstanding); onEvent clears it
// the moment a Correlated event actually drives a transition, so a later duplicate of that
// same attempt no longer matches and is discarded.
private class ActionRecord(
    internal val actionId: Long,
    internal val unitIndex: Int,
    internal val groupId: Int,
    internal val action: PlanAction,
    internal var state: ActionState,
    internal var retryCount: Int = 0,
    internal var awaitingAttemptId: Long? = null,
)

private fun dependsOnOf(action: PlanAction): List<BlockPos> {
    return when (action) {
        is PlanAction.PlaceTarget -> action.dependsOn
        is PlanAction.PlaceScaffold -> action.dependsOn
        is PlanAction.RemoveScaffold -> emptyList()
    }
}

// Visible beyond this file so PlanRuntimeAdapter can replay the exact same groupId
// derivation while building its own action registry (see that class's doc for why it needs
// one independent of this cursor's own internal bookkeeping).
internal fun groupIdOf(action: PlanAction): Int {
    return when (action) {
        is PlanAction.PlaceTarget -> action.groupId
        is PlanAction.PlaceScaffold -> action.groupId
        is PlanAction.RemoveScaffold -> action.groupId
    }
}

// Every group PrintPlanner emits (see appendScaffoldActions) is PlaceScaffold*n -> PlaceTarget
// -> RemoveScaffold*n, the removals in exact reverse of the placements' own order and over
// the same position set with no duplicates. PlanExecutionCursor's own cascade logic
// (findPlaceScaffoldRecord, cascadeSkipIfPlacement) relies on that shape holding, so a
// malformed group is rejected here rather than silently mishandled later.
private fun validateGroupShape(groupId: Int, actions: List<PlanAction>): Unit {
    var index = 0
    val placedPositions = mutableListOf<BlockPos>()
    while (index < actions.size && actions[index] is PlanAction.PlaceScaffold) {
        placedPositions += (actions[index] as PlanAction.PlaceScaffold).pos
        index++
    }
    check(index < actions.size && actions[index] is PlanAction.PlaceTarget) {
        "group $groupId must have exactly one PlaceTarget right after its PlaceScaffold run: $actions"
    }
    index++
    val removedPositions = mutableListOf<BlockPos>()
    while (index < actions.size && actions[index] is PlanAction.RemoveScaffold) {
        removedPositions += (actions[index] as PlanAction.RemoveScaffold).pos
        index++
    }
    check(index == actions.size) {
        "group $groupId has actions after its RemoveScaffold run: $actions"
    }
    check(removedPositions == placedPositions.asReversed()) {
        "group $groupId RemoveScaffold positions must exactly reverse its PlaceScaffold positions: $actions"
    }
    check(placedPositions.toSet().size == placedPositions.size) {
        "group $groupId has duplicate PlaceScaffold positions: $actions"
    }
}

// Headless, main-thread, synchronous consumer of a PrintPlan's units: assigns every unit
// action a deterministic id at construction, then exposes orderedFrontier/submitSpecific/
// onEvent/status as the only way its own state ever changes -- see PlanExecutionModels.kt
// for the state/event vocabulary. Only ever executes PrintPlan.units; PrintPlan.reservations
// is out of scope (never executed, never counted toward completion).
internal class PlanExecutionCursor(plan: PrintPlan) {
    private val records: List<ActionRecord>
    private val unitActionIds: List<List<Long>>
    private val unitGroups: List<LinkedHashMap<Int, MutableList<Long>>>

    init {
        val builtRecords = mutableListOf<ActionRecord>()
        val builtUnitActionIds = mutableListOf<List<Long>>()
        val builtUnitGroups = mutableListOf<LinkedHashMap<Int, MutableList<Long>>>()
        var nextId = 0L
        for ((unitIndex, unit) in plan.units.withIndex()) {
            val actionIdsForUnit = mutableListOf<Long>()
            val groupsForUnit = LinkedHashMap<Int, MutableList<Long>>()
            for (action in unit.actions) {
                check(dependsOnOf(action).isEmpty()) {
                    "unit action dependsOn must be empty outside reservation resolution: $action"
                }
                val actionId = nextId++
                val groupId = groupIdOf(action)
                builtRecords += ActionRecord(
                    actionId = actionId,
                    unitIndex = unitIndex,
                    groupId = groupId,
                    action = action,
                    state = ActionState.Pending,
                )
                actionIdsForUnit += actionId
                groupsForUnit.getOrPut(groupId) { mutableListOf() }.add(actionId)
            }
            for ((groupId, actionIds) in groupsForUnit) {
                validateGroupShape(groupId, actionIds.map { builtRecords[it.toInt()].action })
            }
            builtUnitActionIds += actionIdsForUnit
            builtUnitGroups += groupsForUnit
        }
        records = builtRecords
        unitActionIds = builtUnitActionIds
        unitGroups = builtUnitGroups
    }

    // Index of the first unit not yet complete, or unitActionIds.size once every unit is.
    internal fun currentUnitIndex(): Int {
        for (unitIndex in unitActionIds.indices) {
            if (!isUnitComplete(unitIndex)) return unitIndex
        }
        return unitActionIds.size
    }

    internal val isComplete: Boolean
        get() = currentUnitIndex() == unitActionIds.size

    // The current unit's own frontier actionIds, one per group, in group first-appearance
    // (= plan) order -- a group's frontier is its own first not-yet-terminal action (strict
    // in-group order). A group whose every action is already terminal contributes nothing (it
    // is simply absent from the result, not represented by any placeholder). Every state
    // (Pending, Waiting, InFlight) is included here -- callers that need to skip an
    // already-outstanding frontier do so themselves (see PlanRuntimeAdapter's tick loop); this
    // is a plan-order listing, not a submittability filter. Empty once the unit itself is
    // complete (currentUnitIndex has advanced past every unit, or the plan is empty).
    internal fun orderedFrontier(): List<Long> {
        val unitIndex = currentUnitIndex()
        if (unitIndex >= unitGroups.size) return emptyList()
        val frontier = mutableListOf<Long>()
        for (actionIds in unitGroups[unitIndex].values) {
            val frontierId = frontierActionId(actionIds) ?: continue
            frontier += frontierId
        }
        return frontier
    }

    // Lets a caller submit a SPECIFIC action out of orderedFrontier's own plan-order listing.
    // Submits (same attempt-id bump/record path submit() uses) iff actionId names the CURRENT
    // unit's own frontier action for its group and that action is Pending or Waiting. Multiple
    // groups within the same unit may be InFlight at once -- strict plan-order dispatch (see
    // PlanRuntimeAdapter's tick loop) is what keeps execution ordered, not a per-unit
    // single-outstanding-action gate here. Returns null with no state change otherwise: an
    // unknown id, an action in a later/earlier unit, a non-frontier (already-terminal-ahead-
    // of-it, so unreachable) action, or a terminal/already-InFlight action. The caller owns
    // deciding WHICH frontier action to try next; this only enforces that the try is legal.
    internal fun submitSpecific(actionId: Long): CursorAction? {
        // Long-domain bounds check BEFORE toInt(): an id outside the actual record range
        // (e.g. records.size + 2^32) must never truncate down into an in-range index and
        // alias some other action's own record.
        if (actionId < 0L || actionId >= records.size.toLong()) return null
        val record = records[actionId.toInt()]
        val unitIndex = currentUnitIndex()
        if (record.unitIndex != unitIndex || unitIndex >= unitGroups.size) return null
        if (frontierActionId(unitGroups[unitIndex].getValue(record.groupId)) != actionId) return null
        return when (record.state) {
            is ActionState.Pending, is ActionState.Waiting -> submit(record)
            else -> null
        }
    }

    // Read-only view of one action's current state, keyed by the same actionId
    // orderedFrontier/submitSpecific/onEvent already use. Lets an external consumer
    // (PlanRuntimeAdapter) detect a cascade-driven transition it never directly witnessed
    // itself -- e.g. a scaffold failure skipping a sibling RemoveScaffold via
    // cascadeSkipIfPlacement -- without this cursor needing to notify anyone of that internal
    // side effect as a CursorEvent.
    internal fun stateOf(actionId: Long): ActionState {
        require(actionId >= 0L && actionId < records.size.toLong()) { "unknown actionId $actionId" }
        return records[actionId.toInt()].state
    }

    private var ignoredEventCount = 0

    internal fun onEvent(event: CursorEvent): Unit {
        val id = event.actionId
        require(id >= 0L && id < records.size.toLong()) { "unknown actionId $id" }
        val record = records[id.toInt()]
        if (event is CursorEvent.PostCleanupMismatch) {
            applyPostCleanupMismatch(record)
            return
        }
        event as CursorEvent.Correlated
        if (record.awaitingAttemptId != event.attemptId) {
            // Stale (superseded by a later resubmission), a duplicate report for an attempt
            // already decided, or any report against an action that is already terminal --
            // record.awaitingAttemptId is cleared the instant a Correlated event actually
            // drives a transition below, so all three collapse into this one mismatch check.
            ignoredEventCount++
            return
        }
        // Submitted only ever confirms a state submit() already set (InFlight); it carries no
        // transition of its own, so the attempt stays outstanding for whatever report actually
        // decides it next.
        if (event !is CursorEvent.Submitted) record.awaitingAttemptId = null
        when (event) {
            is CursorEvent.Submitted -> Unit
            is CursorEvent.Waiting -> applyWaiting(record, event.reason)
            is CursorEvent.SubmitFailed -> applyTerminalFailure(record, ActionFailureReason.SUBMIT_FAILED)
            is CursorEvent.ItemUnavailable -> applyTerminalFailure(record, ActionFailureReason.ITEM_UNAVAILABLE)
            is CursorEvent.ResolveFailed -> applyTerminalFailure(record, ActionFailureReason.RESOLVE_FAILED)
            is CursorEvent.Accepted -> applyTerminalSuccess(record)
            is CursorEvent.Rejected -> applyRetryableFailure(record, ActionFailureReason.REJECTED)
            is CursorEvent.WrongState -> applyTerminalFailure(record, ActionFailureReason.WRONG_STATE)
            is CursorEvent.Timeout -> applyRetryableFailure(record, ActionFailureReason.TIMEOUT)
            is CursorEvent.RemoveConfirmed -> applyTerminalSuccess(record)
            is CursorEvent.RemoveFailed -> applyTerminalFailure(record, ActionFailureReason.REMOVE_FAILED)
        }
    }

    // PostCleanupMismatch reports a fact observed after the whole group already settled, not
    // the outcome of one particular submission -- it carries no attemptId and is exempt from
    // the Correlated gate above. Against an already-Done PlaceTarget it is the one event
    // allowed to override that Done into a terminal failure, with no cascade (the group's
    // RemoveScaffold actions are already decided by the time cleanup can be inspected). Against
    // any other terminal state it is just another post-terminal report and is discarded like
    // any other. Against a still-live action it behaves like an ordinary terminal failure,
    // cascade included.
    private fun applyPostCleanupMismatch(record: ActionRecord): Unit {
        if (record.state is ActionState.Done) {
            record.state = ActionState.Failed(ActionFailureReason.POST_CLEANUP_MISMATCH)
            return
        }
        if (isTerminal(record.state)) {
            ignoredEventCount++
            return
        }
        // The record may still be outstanding for a real submission (InFlight) when this
        // fires -- clear that attempt now, same as the ordinary Correlated path already does
        // before every ordinary transition. Without this, that submission's real (later)
        // outcome would still match awaitingAttemptId, so onEvent's stale-attempt check would
        // let it through as if it were a live report and silently no-op inside the terminal
        // apply* call below instead of being counted via ignoredEventCount -- undercounting a
        // report against an action that is, by then, already terminal.
        record.awaitingAttemptId = null
        applyTerminalFailure(record, ActionFailureReason.POST_CLEANUP_MISMATCH)
    }

    internal fun status(): CursorStatus {
        var pending = 0
        var waiting = 0
        var inFlight = 0
        var done = 0
        var failed = 0
        var skipped = 0
        for (record in records) {
            when (record.state) {
                is ActionState.Pending -> pending++
                is ActionState.Waiting -> waiting++
                is ActionState.InFlight -> inFlight++
                is ActionState.Done -> done++
                is ActionState.Failed -> failed++
                is ActionState.Skipped -> skipped++
            }
        }
        return CursorStatus(
            pendingCount = pending,
            waitingCount = waiting,
            inFlightCount = inFlight,
            doneCount = done,
            failedCount = failed,
            skippedCount = skipped,
            currentUnitIndex = currentUnitIndex(),
            isComplete = isComplete,
            ignoredEventCount = ignoredEventCount,
        )
    }

    private var nextAttemptId = 0L

    private fun submit(record: ActionRecord): CursorAction {
        val attemptId = nextAttemptId++
        record.state = ActionState.InFlight
        record.awaitingAttemptId = attemptId
        return CursorAction(record.actionId, record.action, record.unitIndex, attemptId)
    }

    private fun applyWaiting(record: ActionRecord, reason: WaitingReason): Unit {
        if (isTerminal(record.state)) return
        record.state = ActionState.Waiting(reason)
    }

    private fun applyTerminalSuccess(record: ActionRecord): Unit {
        if (isTerminal(record.state)) return
        record.state = ActionState.Done
    }

    private fun applyTerminalFailure(record: ActionRecord, reason: ActionFailureReason): Unit {
        if (isTerminal(record.state)) return
        record.state = ActionState.Failed(reason)
        cascadeSkipIfPlacement(record)
    }

    // Rejected/Timeout are retried up to PrinterRuntime.MAX_RETRIES (the cursor owns this
    // budget itself -- see contract) before becoming a terminal Failed; every retry just
    // sends the action back to Pending so it becomes this action's own frontier's turn to
    // submit again.
    private fun applyRetryableFailure(record: ActionRecord, reason: ActionFailureReason): Unit {
        if (isTerminal(record.state)) return
        if (record.retryCount >= PrinterRuntime.MAX_RETRIES) {
            record.state = ActionState.Failed(reason)
            cascadeSkipIfPlacement(record)
        } else {
            record.retryCount += 1
            record.state = ActionState.Pending
        }
    }

    // A terminal PlaceScaffold/PlaceTarget failure breaks the rest of its own group's
    // placement chain: every later PlaceScaffold/PlaceTarget in the group (never reachable
    // now, in-group order is strict) is skipped. RemoveScaffold cleanup is still mandatory,
    // but only for the scaffold cells that actually made it into the world -- a
    // RemoveScaffold whose own PlaceScaffold never reached Done has nothing to remove, so it
    // is skipped too. A RemoveScaffold action itself failing does not cascade (removal
    // failures are independent per cell, and cleanup of the others must still be attempted).
    private fun cascadeSkipIfPlacement(record: ActionRecord): Unit {
        if (record.action is PlanAction.RemoveScaffold) return
        val groupActionIds = unitGroups[record.unitIndex].getValue(record.groupId)
        val failedIndex = groupActionIds.indexOf(record.actionId)
        for (index in (failedIndex + 1) until groupActionIds.size) {
            val laterRecord = records[groupActionIds[index].toInt()]
            if (laterRecord.action !is PlanAction.RemoveScaffold && !isTerminal(laterRecord.state)) {
                laterRecord.state = ActionState.Skipped
            }
        }
        for (id in groupActionIds) {
            val removeRecord = records[id.toInt()]
            val removeAction = removeRecord.action
            if (removeAction is PlanAction.RemoveScaffold && !isTerminal(removeRecord.state)) {
                val placedScaffold = findPlaceScaffoldRecord(groupActionIds, removeAction.pos)
                if (placedScaffold == null || isDefinitelyNotPlaced(placedScaffold.state)) {
                    removeRecord.state = ActionState.Skipped
                }
            }
        }
    }

    private fun findPlaceScaffoldRecord(groupActionIds: List<Long>, pos: BlockPos): ActionRecord? {
        for (id in groupActionIds) {
            val record = records[id.toInt()]
            val action = record.action
            if (action is PlanAction.PlaceScaffold && action.pos == pos) return record
        }
        return null
    }

    // Whether a scaffold cell's own terminal state proves it never actually reached the
    // world, so its removal has nothing to clean up and can be skipped. TIMEOUT is
    // deliberately excluded: the runtime never learned whether the block landed before the
    // wait elapsed, so a cell reported TIMEOUT might still be standing and its removal must
    // stay mandatory. REMOVE_FAILED/POST_CLEANUP_MISMATCH never apply to a PlaceScaffold's own
    // terminal state under this cursor's state machine (they are only ever assigned to a
    // RemoveScaffold's or a PlaceTarget's own record respectively); both default to false here
    // for the same reason TIMEOUT does -- an unrecognized reason must never assume placement
    // failed and skip mandatory cleanup.
    private fun isDefinitelyNotPlaced(state: ActionState): Boolean {
        return when (state) {
            is ActionState.Skipped -> true
            is ActionState.Failed -> when (state.reason) {
                ActionFailureReason.SUBMIT_FAILED,
                ActionFailureReason.ITEM_UNAVAILABLE,
                ActionFailureReason.RESOLVE_FAILED,
                ActionFailureReason.REJECTED,
                ActionFailureReason.WRONG_STATE,
                -> true
                ActionFailureReason.TIMEOUT,
                ActionFailureReason.REMOVE_FAILED,
                ActionFailureReason.POST_CLEANUP_MISMATCH,
                -> false
            }
            else -> false
        }
    }

    private fun frontierActionId(actionIds: List<Long>): Long? {
        for (id in actionIds) {
            if (!isTerminal(records[id.toInt()].state)) return id
        }
        return null
    }

    private fun isUnitComplete(unitIndex: Int): Boolean {
        return unitActionIds[unitIndex].all { id -> isTerminal(records[id.toInt()].state) }
    }

    private fun isTerminal(state: ActionState): Boolean {
        return state is ActionState.Done || state is ActionState.Failed || state is ActionState.Skipped
    }
}
