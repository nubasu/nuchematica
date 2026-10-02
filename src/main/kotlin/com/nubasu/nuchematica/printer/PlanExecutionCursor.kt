package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos

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

internal fun groupIdOf(action: PlanAction): Int {
    return when (action) {
        is PlanAction.PlaceTarget -> action.groupId
        is PlanAction.PlaceScaffold -> action.groupId
        is PlanAction.RemoveScaffold -> action.groupId
    }
}

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

/**
 * Executes plan units in order while advancing each action group independently.
 *
 * Action IDs are deterministic for the expanded plan, and state changes only through
 * submissions and [onEvent].
 */
internal class PlanExecutionCursor(plan: PrintPlan) {
    private val records: List<ActionRecord>
    private val unitActionIds: List<List<Long>>
    private val unitGroups: List<LinkedHashMap<Int, MutableList<Long>>>

    init {
        val builtRecords = mutableListOf<ActionRecord>()
        val builtUnitActionIds = mutableListOf<List<Long>>()
        val builtUnitGroups = mutableListOf<LinkedHashMap<Int, MutableList<Long>>>()
        var nextId = 0L
        for ((unitIndex, unit) in executablePlanUnits(plan).withIndex()) {
            val actionIdsForUnit = mutableListOf<Long>()
            val groupsForUnit = LinkedHashMap<Int, MutableList<Long>>()
            for (action in unit.actions) {
                check(unit.isReservation || dependsOnOf(action).isEmpty()) {
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

    internal fun currentUnitIndex(): Int {
        for (unitIndex in unitActionIds.indices) {
            if (!isUnitComplete(unitIndex)) return unitIndex
        }
        return unitActionIds.size
    }

    internal val isComplete: Boolean
        get() = currentUnitIndex() == unitActionIds.size

    /**
     * Lists each current-unit group's first nonterminal action in plan order.
     *
     * Waiting and in-flight actions remain in this listing; it is not a submittability filter.
     */
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

    /** Submits a pending or waiting frontier action with a fresh attempt ID. */
    internal fun submitSpecific(actionId: Long): CursorAction? {
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

    internal fun stateOf(actionId: Long): ActionState {
        require(actionId >= 0L && actionId < records.size.toLong()) { "unknown actionId $actionId" }
        return records[actionId.toInt()].state
    }

    private var ignoredEventCount = 0

    /** Applies matching outcomes and ignores stale or duplicate correlated events. */
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
            ignoredEventCount++
            return
        }
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

    private fun applyPostCleanupMismatch(record: ActionRecord): Unit {
        if (record.state is ActionState.Done) {
            record.state = ActionState.Failed(ActionFailureReason.POST_CLEANUP_MISMATCH)
            return
        }
        if (isTerminal(record.state)) {
            ignoredEventCount++
            return
        }
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
