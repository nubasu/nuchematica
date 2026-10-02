package com.nubasu.nuchematica.printer

/** Transient conditions that keep an action eligible for resubmission. */
internal enum class WaitingReason {
    WAITING_FOR_REACH,
    PLAYER_COLUMN_BLOCKED,
    WAITING_FOR_SUPPORT,
    WAITING_FOR_ROTATION,
}

/** Terminal causes after which [PlanExecutionCursor] will not resubmit an action. */
internal enum class ActionFailureReason {
    RESOLVE_FAILED,
    ITEM_UNAVAILABLE,
    SUBMIT_FAILED,
    REJECTED,
    WRONG_STATE,
    TIMEOUT,
    REMOVE_FAILED,
    POST_CLEANUP_MISMATCH,
}

/**
 * Cursor-owned state for one action.
 *
 * [Pending] and [Waiting] are resubmittable, [InFlight] is outstanding, and the
 * remaining states are terminal.
 */
internal sealed interface ActionState {
    object Pending : ActionState
    data class Waiting(internal val reason: WaitingReason) : ActionState
    object InFlight : ActionState
    object Done : ActionState
    data class Failed(internal val reason: ActionFailureReason) : ActionState
    object Skipped : ActionState
}

/**
 * A legal frontier submission returned by [PlanExecutionCursor].
 *
 * [actionId] is stable for the plan; [attemptId] changes on every submission.
 */
internal data class CursorAction(
    internal val actionId: Long,
    internal val action: PlanAction,
    internal val unitIndex: Int,
    internal val attemptId: Long,
)

/** Runtime outcomes consumed by [PlanExecutionCursor]. */
internal sealed interface CursorEvent {
    val actionId: Long

    /** Applies only when [attemptId] matches the action's outstanding submission. */
    sealed interface Correlated : CursorEvent {
        val attemptId: Long
    }

    data class Submitted(override val actionId: Long, override val attemptId: Long) : Correlated
    data class SubmitFailed(override val actionId: Long, override val attemptId: Long) : Correlated
    data class ItemUnavailable(override val actionId: Long, override val attemptId: Long) : Correlated
    data class Waiting(
        override val actionId: Long,
        override val attemptId: Long,
        internal val reason: WaitingReason,
    ) : Correlated
    data class ResolveFailed(override val actionId: Long, override val attemptId: Long) : Correlated
    data class Accepted(override val actionId: Long, override val attemptId: Long) : Correlated
    data class Rejected(override val actionId: Long, override val attemptId: Long) : Correlated
    data class WrongState(override val actionId: Long, override val attemptId: Long) : Correlated
    data class Timeout(override val actionId: Long, override val attemptId: Long) : Correlated
    data class RemoveConfirmed(override val actionId: Long, override val attemptId: Long) : Correlated
    data class RemoveFailed(override val actionId: Long, override val attemptId: Long) : Correlated

    /**
     * Invalidates a completed target after cleanup exposes a live-world mismatch.
     *
     * This observation is not tied to an attempt and may replace [ActionState.Done].
     */
    data class PostCleanupMismatch(override val actionId: Long) : CursorEvent
}

internal data class CursorStatus(
    internal val pendingCount: Int,
    internal val waitingCount: Int,
    internal val inFlightCount: Int,
    internal val doneCount: Int,
    internal val failedCount: Int,
    internal val skippedCount: Int,
    internal val currentUnitIndex: Int,
    internal val isComplete: Boolean,
    internal val ignoredEventCount: Int,
)

/** Allows one replan after terminal failures, reservations, or live mismatches. */
internal fun shouldAutoRetryPlanSession(
    status: CursorStatus,
    hasReservations: Boolean,
    retryUsed: Boolean,
    hasLiveMismatches: Boolean = false,
): Boolean {
    return status.isComplete &&
        !retryUsed &&
        (status.failedCount > 0 || status.skippedCount > 0 || hasReservations || hasLiveMismatches)
}

internal data class NoProgressBackoffDecision(
    internal val noProgressCount: Int,
    internal val armBackoff: Boolean,
)

/** Counts zero-placement discards and arms backoff when [threshold] is reached. */
internal fun evaluateNoProgressBackoff(
    placedAtDiscard: Int,
    currentNoProgressCount: Int,
    threshold: Int,
): NoProgressBackoffDecision {
    if (placedAtDiscard == 0) {
        val count = currentNoProgressCount + 1
        return NoProgressBackoffDecision(noProgressCount = count, armBackoff = count >= threshold)
    }
    return NoProgressBackoffDecision(noProgressCount = 0, armBackoff = false)
}

internal fun noProgressBackoffAppliesToIdentity(discardedIdentity: PlanIdentity?, nextIdentity: PlanIdentity): Boolean {
    return discardedIdentity != null && discardedIdentity.matches(nextIdentity)
}
