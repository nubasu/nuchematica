package com.nubasu.nuchematica.printer

// Non-terminal reason a submitted action could not proceed yet -- WAITING_FOR_REACH means
// the player is not close enough to click the target this tick, PLAYER_COLUMN_BLOCKED means
// the player's own hitbox occupies the column the action would place into. Both resolve on
// a later resubmission once the mover repositions the player; neither is a plan-time or
// world-content problem, so the action stays eligible for retry indefinitely (no counter).
// WAITING_FOR_SUPPORT means resolution found no support face, but a planned neighbor action
// (PlaceTarget/PlaceScaffold) has not yet reached a terminal state -- this cell's own plan
// classification guarantees support only under in-order execution, and multiple groups can be
// concurrently InFlight under strict plan-order dispatch, so a later group can legitimately
// settle before an earlier neighbor it depends on -- resolving on a later resubmission once
// the neighbor actually lands, never on the player moving. Deliberately kept out of
// PlanRuntimeAdapter's own frontierSnapshot waitingForReach list (see PlanFrontierSnapshot's
// own doc): unlike WAITING_FOR_REACH, being physically close to this position does nothing to
// help it resolve, so a mover steering off that list must never be sent to stand near it.
internal enum class WaitingReason { WAITING_FOR_REACH, PLAYER_COLUMN_BLOCKED, WAITING_FOR_SUPPORT }

// Terminal failure reason for an action PlanExecutionCursor will never resubmit again.
// REJECTED/TIMEOUT only ever reach this state after the cursor's own retry budget
// (see PlanExecutionCursor) is exhausted -- every other reason is terminal on first report.
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

// One action's own execution state as tracked by PlanExecutionCursor. Waiting and Failed
// carry the reason the runtime last reported; every other case is a singleton value. Done,
// Failed and Skipped are the only terminal states -- Pending, Waiting and InFlight are all
// still live (a Pending or Waiting action is resubmittable, an InFlight one is outstanding).
internal sealed interface ActionState {
    object Pending : ActionState
    data class Waiting(internal val reason: WaitingReason) : ActionState
    object InFlight : ActionState
    object Done : ActionState
    data class Failed(internal val reason: ActionFailureReason) : ActionState
    object Skipped : ActionState
}

// One submittable unit of work handed back by PlanExecutionCursor.submitSpecific -- the
// minimum a submitting side needs: which action, from which plan unit, under which
// cursor-assigned id (the id is what later correlates a CursorEvent back to this action).
// attemptId is a separate, monotonically increasing number stamped fresh on every submission
// (including resubmissions of the same actionId) -- see CursorEvent.Correlated.
internal data class CursorAction(
    internal val actionId: Long,
    internal val action: PlanAction,
    internal val unitIndex: Int,
    internal val attemptId: Long,
)

// Runtime -> cursor report for a previously submitted action, correlated by actionId.
// Waiting is the only non-terminal report (the action stays resubmittable); Rejected and
// Timeout are retried by the cursor itself up to a retry budget before becoming terminal,
// every other case is terminal immediately (see PlanExecutionCursor.onEvent).
internal sealed interface CursorEvent {
    val actionId: Long

    // Every CursorEvent that reports on an actual submission carries the attemptId that
    // submission was stamped with (see CursorAction.attemptId). PlanExecutionCursor.onEvent
    // only lets a Correlated event drive a state transition when it matches the action
    // record's own current outstanding attempt -- a stale attempt (superseded by a later
    // resubmission), a duplicate report for an attempt already decided, or any report
    // arriving after the action is already terminal are all discarded instead. PostCleanupMismatch
    // is the sole exception (see its own doc): it reports a fact observed after the whole
    // group already settled, not an outcome of one particular submission.
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

    // Reports that a PlaceTarget the cursor already considers Done turned out mismatched
    // once the surrounding scaffold cleanup was inspected after the fact -- a real-world
    // observation, not the outcome of a submission, so it carries no attemptId and is the
    // only event allowed to override an already-Done record (see PlanExecutionCursor.onEvent).
    data class PostCleanupMismatch(override val actionId: Long) : CursorEvent
}

// Snapshot of PlanExecutionCursor's own bookkeeping: how many actions currently sit in each
// state, which unit is the first not yet complete (equal to the plan's unit count once
// everything is done), and the cursor's own completion authority (mirrors
// PlanExecutionCursor.isComplete -- see there for what "complete" means). ignoredEventCount
// tallies every CursorEvent onEvent received but discarded (stale attemptId, duplicate report
// for an already-decided attempt, or any report against an already-terminal action).
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

// Product decision: a completed plan session that left any failed/skipped target action, or
// any reservation unresolved, gets exactly one automatic replan pass before the session counts
// as done -- a reserved cell whose support now stands, or a target whose blocker cleared during
// the first pass, becomes directly placeable once the world is reclassified against. retryUsed
// gates this to fire at most once per session; an incomplete session never qualifies (there is
// nothing to retry yet).
internal fun shouldAutoRetryPlanSession(
    status: CursorStatus,
    hasReservations: Boolean,
    retryUsed: Boolean,
): Boolean {
    return status.isComplete && !retryUsed && (status.failedCount > 0 || status.skippedCount > 0 || hasReservations)
}

// Result of evaluateNoProgressBackoff -- see its own doc.
internal data class NoProgressBackoffDecision(
    internal val noProgressCount: Int,
    internal val armBackoff: Boolean,
)

// Product decision: SchematicPrinter's own no-progress rebuild backoff (see
// SchematicPrinter.planNoProgressCount's own doc) for an infinite plan/rebuild loop pinned on
// a stale support cell -- a dirty discard whose own placed count is truly zero, not merely no
// better than the discard before it. A session that keeps placing something (10 -> 1 -> 1 -> 1)
// is making real, if slowing, progress and must never arm the backoff; only a discard that
// placed NOTHING at all counts toward the streak. Extracted as a pure function, independent of
// the (un-headless-testable) SchematicPrinter singleton, exactly like shouldAutoRetryPlanSession
// above. armBackoff becomes true the instant the returned noProgressCount reaches threshold, and
// stays true on every further no-progress discard after that (the caller re-arms its own
// countdown every time) until a discard that DID place something resets noProgressCount to 0 --
// the caller (teardownPlanSession, session completion, an identity/behavior change, or a REFUSED
// discard) resets the streak the same way for every OTHER reason this episode's history stops
// being relevant.
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

// Whether the no-progress backoff streak (evaluateNoProgressBackoff, whose count only ever
// grows from a dirty discard) is still scoped to the build about to start. discardedIdentity is
// captured at the exact discard that grew the streak (see SchematicPrinter.planDiscardedIdentity's
// own doc) -- a streak accumulated against one identity (schematic/transform/world/behavior) must
// never throttle a genuinely different one that has never itself made a no-progress attempt. A
// null discardedIdentity (nothing discarded yet, or already cleared) always resets: there is no
// streak left to scope in the first place.
internal fun noProgressBackoffAppliesToIdentity(discardedIdentity: PlanIdentity?, nextIdentity: PlanIdentity): Boolean {
    return discardedIdentity != null && discardedIdentity.matches(nextIdentity)
}
