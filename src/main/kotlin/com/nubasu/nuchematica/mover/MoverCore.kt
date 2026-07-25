package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.PrinterAttemptTracker
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.PrinterRateLimiter
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

public class MoverCore {
    private var moverState: MoverState = MoverState.IDLE
    private var moverAbortReason: MoverAbortReason? = null
    private var activeSessionKey: MoverSessionKey? = null
    private var observedQueueRevision: Long? = null
    private var takeoffTicks: Int = 0
    private var flightLossCount: Int = 0
    private var route: List<PlannedWorkPosition> = emptyList()
    private var routeIndex: Int = 0
    private var routeStartRevision: Long? = null
    private var routeGateSignature: Int? = null
    private var frozenLayerMembers: Set<BlockPos> = emptySet()
    private var remainingMissing: Int = 0
    private var movementTarget: Vec3? = null
    private var pathProbePending: Boolean = false
    private val pathWaypointQueue: ArrayDeque<Vec3> = ArrayDeque()
    private var followingFlightPath: Boolean = false
    private var pathRecoveryAvailable: Boolean = false
    private var pathSearchCount: Int = 0
    private var aStarInvocations: Int = 0
    private var aStarSuccesses: Int = 0
    private var aStarFailVolumeClamped: Int = 0
    private var aStarFailBudgetExhausted: Int = 0
    private var aStarFailNoPath: Int = 0
    private var aStarFailStartOrGoalBlocked: Int = 0
    // Endpoints resolveEnterableCell rescued from a naive-floor cell the
    // player's AABB merely touched onto an actually-enterable neighbor.
    private var aStarResolveStartRescues: Int = 0
    private var aStarResolveGoalRescues: Int = 0
    private var smoothingWaypointsIn: Int = 0
    private var smoothingWaypointsOut: Int = 0
    private var lastCruisePlayerPos: Vec3? = null
    private var stationaryCruiseTicks: Int = 0
    private var holdMode: HoldMode? = null
    private var chunkWaitTicks: Int = 0
    private var arrivalHoldTicks: Int = 0
    private var fedEmptyHoldTicks: Int = 0
    private var completePending: Boolean = false
    private var completePendingRevision: Long? = null
    private var lastFinalSweepRevision: Long? = null
    private val bannedTargets: MutableSet<Vec3> = LinkedHashSet()
    private val unproductiveTargetsByPosition: HashMap<BlockPos, MutableSet<Vec3>> = HashMap()

    // Plan mode's own candidate-abandonment bookkeeping (skipCurrentWorkPosition/
    // handleUnproductiveWorkPosition/handlePlanCorrection's own escape), keyed by the
    // stand's CELL rather than bannedTargets' Vec3s: a replanned candidate for the same
    // cell always re-derives the same raw (un-fitted) LayerRoutePlanner Vec3 first, but its
    // FITTED pose (standFittedTarget) can differ tick to tick as the collision profile
    // refits it, so a Vec3-keyed ban would never match again and the same stand would loop
    // forever. Value is the mover tick the ban was recorded at -- ages out after
    // PLAN_BAN_MAX_AGE_TICKS (see prunePlanBannedCells), so a stand rejected for a
    // transient reason is not excluded for the rest of the run.
    private val planBannedCells: MutableMap<BlockPos, Long> = LinkedHashMap()

    // The un-fitted ban cell (see standBanCell) for each entry in `route`, index-aligned
    // with it -- captured from the RAW candidate BEFORE standFittedTarget's collision fit
    // touches its height, since that fit can lift a candidate's Y across an integer
    // boundary into a DIFFERENT cell than the one isStandAcceptable/the rejection filter
    // actually validated. skipCurrentWorkPosition/handleUnproductiveWorkPosition read
    // this instead of re-deriving a cell from the (already fitted) route entry, so every
    // ban site agrees on the exact same cell identity for the exact same candidate.
    private var planRouteBanCells: List<BlockPos> = emptyList()

    // Lane mode (main pass): while the gate layer has placeable work, the mover
    // sweeps serpentine lanes instead of building a discrete work-position route. See
    // buildRoute for the switch and finishLanePass for the full-pass progress rule.
    private var laneModeActive: Boolean = false
    private var laneWaypoints: List<Vec3> = emptyList()
    private var laneWaypointIndex: Int = 0
    private var lanePassStartRevision: Long? = null
    private var lanePasses: Int = 0
    private var laneSegmentsTotal: Int = 0
    private var laneTicksTotal: Int = 0

    // TRAPPED watchdog (batch/discrete mode only): see
    // trappedThisTick's doc.
    private var trappedPreviousPos: Vec3? = null
    private var trappedStationaryTicks: Int = 0

    // Plan mode (v4, see buildRoute's context.planMode branch): route targets come
    // from the printer's PlanFrontierSnapshot instead of missingWorld/lane sweep.
    // previousPlanMode tracks the last tick's mode so a flip (either direction) can
    // reset route/waypoint/hold state once, immediately, rather than leaving
    // stale v3/plan targets in place for the state machine to keep chasing.
    private var previousPlanMode: Boolean = false
    // Incremented once per tick() call regardless of mode -- plan mode's only use for
    // an elapsed-tick counter (planBannedCells aging); inert for v3.
    private var moverTickCounter: Long = 0L
    // context.planSessionFinal as observed on the call that most recently armed (or
    // re-armed) the empty-route terminal confirmation -- see finishPlanRoute's own doc
    // for why completion requires this AND the confirming tick's own value to both be
    // true, rather than just the confirming tick's.
    private var planFinalAtArm: Boolean = false
    // The frontier's waiting-for-reach position set the current plan route was BUILT
    // from (see buildPlanRoute), re-baselined to the CURRENT set on every arrival-hold
    // tick where the stand is still valid (see tickPlanArrivalHold/
    // checkCurrentPlanWorkPosition) -- release itself is decided by whether the
    // frontier's own head is still covered, not by comparing against this baseline;
    // buildPlanRoute is what actually consumes it, both to build the next route and to
    // validate/age its own no-routable-stand latch.
    private var planRouteWaitingPositions: Set<BlockPos> = emptySet()
    // The frontier's columnBlocked position set captured at the same sites as
    // planRouteWaitingPositions, kept in step with it for the same reasons.
    private var planRouteColumnBlockedPositions: Set<BlockPos> = emptySet()
    // The working-set position set (and mover tick) the most recent EMPTY plan-route
    // attempt -- the LayerRoutePlanner-scored branch of buildPlanRoute, not the empty-
    // frontier/evacuation branches -- was built from. Null whenever the most recent attempt
    // was not empty (a successful build always clears it -- including an evacuation
    // route). Lets a later call skip re-invoking planRoute() entirely while the working
    // set has not moved, only reattempting once it has or PLAN_EMPTY_ROUTE_REBUILD_TICKS
    // have elapsed -- see buildPlanRoute's own gate. This doubles as the latch
    // planModeIntentionalHold reads to tell a genuinely unroutable frontier apart from
    // entombment: buildPlanRoute clears it the instant either captured set no longer
    // matches the CURRENT frontier (see buildPlanRoute's own staleness check), so a
    // latch armed against a since-changed frontier never survives to suppress the
    // trapped watchdog for a NEW situation it was never actually evaluated against.
    private var lastEmptyPlanRoutePositions: Set<BlockPos>? = null
    // The columnBlocked set captured alongside lastEmptyPlanRoutePositions at the same
    // no-routable-stand attempt -- compared together so a columnBlocked-only change (the
    // waiting set itself unchanged) also invalidates the latch, not just a waitingForReach
    // change.
    private var lastEmptyPlanRouteColumnBlockedPositions: Set<BlockPos> = emptySet()
    private var lastEmptyPlanRouteTick: Long = 0L
    // The player position tick() itself last observed, updated at the very top of every
    // tick before this one's own correction handling reads it -- used only to compute
    // the correction-diagnostics delta (logPlanCorrectionDiagnostics), the "client
    // position" side of "client position vs corrected position". Not used for any
    // routing decision.
    private var lastObservedPlayerPos: Vec3? = null
    // Mover ticks (moverTickCounter) a plan-mode correction landed at, oldest first,
    // aged out of the rolling PLAN_CORRECTION_WINDOW_TICKS window on each new event --
    // see handlePlanCorrection.
    private val planCorrectionTicks: ArrayDeque<Long> = ArrayDeque()
    // The active work position (route[routeIndex]'s own stand cell) a plan-mode
    // correction most recently landed against, and how many corrections have landed
    // against it in a row -- see handlePlanCorrection. Null/0 once the work position
    // changes (a different stand is now active), the tracked position is actually
    // ARRIVED at (see enterArrivalHold), its last correction has aged out past
    // PLAN_BAN_MAX_AGE_TICKS, or it resolves immediately (the escape below fires and
    // clears it itself, rather than waiting for the next correction to notice the
    // stand changed).
    private var planCorrectionWorkPositionCell: BlockPos? = null
    private var planCorrectionCountForWorkPosition: Int = 0
    // Mover tick the most recent correction against planCorrectionWorkPositionCell
    // landed at -- ages the per-work-position count exactly like planBannedCells ages
    // its own entries (see handlePlanCorrection).
    private var planCorrectionWorkPositionTick: Long = 0L
    // Set when the escape below bans a stand instead of resyncing: the actual rebuild
    // is deferred to the NEXT tick (see tick()'s own consuming check) rather than run
    // synchronously here, mirroring the "discard this tick, rebuild next tick"
    // convention markCompletePending/confirmCompletion already use elsewhere -- a
    // synchronous rebuild here could otherwise arm AND confirm the two-phase plan
    // completion gate within this same tick, collapsing it to one phase.
    private var planCorrectionRebuildPending: Boolean = false

    // Diagnostic-only rate-limit gates (see logIfDue's own doc): the mover tick each
    // category's log last actually fired, so a churning branch cannot spam latest.log
    // more than once per PLAN_DIAG_LOG_INTERVAL_TICKS. Never read for anything but
    // logging -- none of these influence a routing decision.
    private var lastEmptyPlanRouteDiagLogTick: Long = -PLAN_DIAG_LOG_INTERVAL_TICKS
    private var lastMarkCompletePendingDiagLogTick: Long = -PLAN_DIAG_LOG_INTERVAL_TICKS
    private var lastMovementTargetDiagLogTick: Long = -PLAN_DIAG_LOG_INTERVAL_TICKS
    private var lastFlightBeginFailDiagLogTick: Long = -PLAN_DIAG_LOG_INTERVAL_TICKS
    private var lastHeadNotCoveredDiagLogTick: Long = -PLAN_DIAG_LOG_INTERVAL_TICKS

    public fun toggleRequested(
        mayfly: Boolean,
        isCreative: Boolean,
        hasMissing: Boolean,
    ): MoverState {
        if (moverState.isActive()) {
            abort(MoverAbortReason.TOGGLED_OFF)
            return moverState
        }

        resetRun()
        moverState = MoverState.IDLE
        moverAbortReason = when {
            !isCreative -> MoverAbortReason.GAMEMODE_LOST
            !mayfly -> MoverAbortReason.MAYFLY_REQUIRED
            !hasMissing -> null
            else -> {
                moverState = MoverState.TAKEOFF
                null
            }
        }
        return moverState
    }

    public fun tick(context: MoverTickContext): MoverCommand {
        moverTickCounter++
        // Feeds status().remainingMissing (the HUD's "Blocks left" line): v3 counts
        // missingWorld directly, while plan mode counts the printer cursor's own
        // pending+waiting+inFlight action total instead -- monotone-decreasing as the run
        // progresses, unlike a frontier list's own size, which tracks how far dispatch has
        // marched rather than how much work is actually left.
        remainingMissing = if (context.planMode) {
            context.planFrontier?.totalRemainingActions ?: 0
        } else {
            context.missingWorld.size
        }
        if (moverState == MoverState.ABORTED || moverState == MoverState.COMPLETE) {
            return STOP_COMMAND
        }
        if (moverState == MoverState.IDLE) return IDLE_COMMAND

        val previousPlayerPos = lastObservedPlayerPos
        lastObservedPlayerPos = context.playerPos
        if (context.correctionReceived && context.planMode) {
            val desyncReason = handlePlanCorrection(context, previousPlayerPos)
            if (desyncReason != null) {
                abort(desyncReason)
                return STOP_COMMAND
            }
            // The escape branch above banned a stand and armed a deferred rebuild
            // instead of rebuilding synchronously -- discard this tick (no movement,
            // no route change) and let the pending-rebuild check below handle it next
            // tick, same as any other "discard this tick, rebuild next tick" trigger.
            if (planCorrectionRebuildPending) {
                return STOP_COMMAND
            }
        }

        val immediateAbortReason = immediateAbortReason(context)
        if (immediateAbortReason != null) {
            abort(immediateAbortReason)
            return STOP_COMMAND
        }

        if (activeSessionKey == null) {
            activeSessionKey = context.sessionKey
            observedQueueRevision = context.queueRevision
        }

        if (laneModeActive) laneTicksTotal++

        // A mode flip (either direction) means the route this state machine was
        // steering has nothing to do with the new target source -- reset it once here
        // rather than leaving the state dispatch below chase a stale v3/plan target.
        // Ahead of the trapped/abort evaluation below on purpose: a flip landing on the
        // very tick the watchdog would fire must let this rebuild run first, so the
        // check sees the new mode's fresh route instead of the outgoing mode's stale
        // one.
        val planModeChanged = context.planMode != previousPlanMode
        previousPlanMode = context.planMode
        if (planModeChanged) {
            resetRouteStateForModeSwitch()
            if (moverState != MoverState.TAKEOFF) {
                if (!buildRoute(context, clearBannedTargets = true)) return STOP_COMMAND
            }
        }

        // A correction-triggered stand ban (handlePlanCorrection's escape) deferred its
        // own rebuild to here -- the same early-return point a mode-switch rebuild uses
        // -- rather than rebuilding synchronously inside handlePlanCorrection, which
        // could otherwise arm and confirm the two-phase plan completion gate within the
        // same tick as the ban itself.
        if (planCorrectionRebuildPending) {
            planCorrectionRebuildPending = false
            if (!buildRoute(context)) return STOP_COMMAND
        }

        if (planModeIntentionalHold(context)) {
            trappedPreviousPos = null
            trappedStationaryTicks = 0
            resetCruiseWatchdog()
        } else if (trappedThisTick(context)) {
            abort(MoverAbortReason.TRAPPED)
            return STOP_COMMAND
        }

        // A drained route is never terminal in the tick that first observes it. The
        // printer runs before the mover on the next client tick, so this fresh rebuild
        // sees every gate/deferral change produced by the drain tick.
        if (completePending) return confirmCompletion(context)

        if (
            !context.planMode &&
            routeStartRevision != null &&
            context.gateY != routeGateSignature &&
            moverState != MoverState.TAKEOFF
        ) {
            if (!buildRoute(context, clearBannedTargets = true)) return STOP_COMMAND
        }

        return when (moverState) {
            MoverState.TAKEOFF -> tickTakeoff(context)
            MoverState.CRUISE -> tickCruise(context)
            MoverState.HOLD -> tickHold(context)
            MoverState.IDLE -> IDLE_COMMAND
            MoverState.COMPLETE,
            MoverState.ABORTED,
            -> STOP_COMMAND
        }
    }

    public fun status(): MoverStatus {
        return MoverStatus(
            state = moverState,
            abortReason = moverAbortReason,
            target = movementTarget,
            remainingMissing = remainingMissing,
        )
    }

    internal fun pathTelemetry(): MoverPathTelemetry {
        return MoverPathTelemetry(
            aStarInvocations = aStarInvocations,
            aStarSuccesses = aStarSuccesses,
            aStarFailVolumeClamped = aStarFailVolumeClamped,
            aStarFailBudgetExhausted = aStarFailBudgetExhausted,
            aStarFailNoPath = aStarFailNoPath,
            aStarFailStartOrGoalBlocked = aStarFailStartOrGoalBlocked,
            aStarResolveStartRescues = aStarResolveStartRescues,
            aStarResolveGoalRescues = aStarResolveGoalRescues,
            smoothingWaypointsIn = smoothingWaypointsIn,
            smoothingWaypointsOut = smoothingWaypointsOut,
        )
    }

    // Passes/segments planned and ticks spent following them, for the
    // C3MOV terminal summary line.
    internal fun laneTelemetry(): MoverLaneTelemetry {
        return MoverLaneTelemetry(
            passes = lanePasses,
            segments = laneSegmentsTotal,
            laneTicks = laneTicksTotal,
        )
    }

    // Batch/discrete-mode entombment safety net. `route` is
    // empty ONLY while the two-phase terminal protocol is deciding whether the run is
    // genuinely done (markCompletePending / confirmCompletion) -- i.e. exactly "a full
    // replan yielded nothing reachable" -- so gating on it (rather than on moverState
    // alone) leaves ordinary CRUISE/HOLD/CHUNK_WAIT waiting, which legitimately holds a
    // non-empty route/target while the player stands still, untouched. TAKEOFF is
    // excluded too: `route` starts out empty before the first build ever runs, and
    // TAKEOFF already has its own dedicated TAKEOFF_TIMEOUT abort. If the player's
    // world position genuinely does not move more than half a block for 100 straight
    // ticks while that terminal-protocol loop keeps re-arming, the discrete
    // candidate-retry/replan machinery has been spinning without ever actually moving
    // the player -- almost always because the player's OWN cell is not passable
    // (entombed), so every leg probe and every A* search fails identically no matter
    // which candidate or anchor is tried next. Left unchecked this would eventually
    // grind through deferring the entire remaining structure one position at a time
    // before "completing" -- silently standing still instead of telling the user they
    // are physically stuck -- so this aborts loudly well before that. Lane mode is
    // exempt: its own full-pass progress check (finishLanePass) already guarantees
    // deterministic termination without needing a player-stationary signal.
    private fun trappedThisTick(context: MoverTickContext): Boolean {
        val trackingEligible = !laneModeActive &&
            moverState != MoverState.TAKEOFF &&
            route.isEmpty()
        if (!trackingEligible) {
            trappedPreviousPos = null
            trappedStationaryTicks = 0
            return false
        }
        val previous = trappedPreviousPos
        trappedPreviousPos = context.playerPos
        if (previous == null || previous.distanceToSqr(context.playerPos) > TRAPPED_MOVEMENT_SQUARED) {
            trappedStationaryTicks = 0
            return false
        }
        trappedStationaryTicks++
        return trappedStationaryTicks >= TRAPPED_STATIONARY_TICKS
    }

    // Plan mode has its own deliberate holds -- an empty frontier awaiting the
    // printer's next build/replan/ack, or the post-arrival hold at a stand position --
    // that are legitimate stillness, not entombment (trappedThisTick) or a genuine
    // cruise stall. Suppression requires the mover to genuinely not be commanding
    // movement: an empty waitingForReach only counts while there is also no active
    // route (a non-empty route with an empty waitingForReach still happens, e.g. the
    // columnBlocked evacuation leg) -- a route ACTIVELY being flown must run both
    // watchdogs exactly as v3 does, same as a non-empty frontier the planner genuinely
    // cannot route to (which still counts toward TRAPPED exactly like v3's own
    // entombment case). columnBlocked must also be empty: when the printer needs the
    // player's own column cleared but no evacuation candidate routes, the mover is stuck
    // while movement is genuinely required, so this falls through to the trapped
    // watchdog exactly like an unroutable waitingForReach does, rather than holding
    // forever on the strength of an empty waitingForReach alone.
    private fun planModeIntentionalHold(context: MoverTickContext): Boolean {
        if (!context.planMode) return false
        if (holdMode == HoldMode.ARRIVAL) return true
        if (!route.isEmpty()) return false
        val emptyFrontier = context.planFrontier?.waitingForReach.isNullOrEmpty() &&
            context.planFrontier?.columnBlocked.isNullOrEmpty()
        if (emptyFrontier) return true
        // buildPlanRoute latches lastEmptyPlanRoutePositions whenever its most recent
        // real attempt against a non-empty waitingForReach came up with no routable
        // stand at all (every candidate rejected) -- as long as that outcome is still
        // latched, the same periodic (PLAN_EMPTY_ROUTE_REBUILD_TICKS-paced) rebuild that
        // set it keeps retrying on its own, so this is intentional stillness too, not
        // entombment. A genuine movement-commanded stall (an active, non-empty route)
        // never reaches this branch at all (the route.isEmpty() guard above).
        return lastEmptyPlanRoutePositions != null
    }

    private fun tickTakeoff(context: MoverTickContext): MoverCommand {
        if (context.flying) {
            // gateY is plan mode's stale, frozen-at-flag-flip v3 gate (see
            // buildPlanRoute's own doc) -- never read it there, so a session whose flag
            // turns ON mid-run takes the exact same resume branch a fresh gateY-null
            // v3 run would.
            val gateChanged = !context.planMode &&
                routeStartRevision != null &&
                context.gateY != routeGateSignature
            if (routeStartRevision == null || gateChanged) {
                if (!buildRoute(context, clearBannedTargets = gateChanged)) return STOP_COMMAND
            } else {
                val resumed = if (laneModeActive) prepareLaneCruise() else prepareCurrentWorkPosition(context)
                if (!resumed) return stopForDrain(context)
            }
            return tickCruise(context)
        }

        takeoffTicks++
        if (takeoffTicks >= TAKEOFF_TIMEOUT_TICKS) {
            abort(MoverAbortReason.TAKEOFF_TIMEOUT)
            return STOP_COMMAND
        }
        if (context.onGround) return JUMP_COMMAND
        if (context.mayfly) return ENABLE_FLIGHT_COMMAND
        return IDLE_COMMAND
    }

    private fun tickCruise(context: MoverTickContext): MoverCommand {
        recoverFlightIfNeeded(context)?.let { command -> return command }

        while (true) {
            val target = movementTarget ?: return stopForDrain(context)
            if (pathProbePending) {
                val probe = context.pathProbe(context.playerPos, target)
                if (!probe.chunkLoaded) {
                    enterChunkWait()
                    return STOP_COMMAND
                }
                if (!probe.clear) {
                    val recovered = if (laneModeActive) {
                        recoverBlockedLaneLeg(context)
                    } else if (context.planMode) {
                        recoverPlanLeg(context)
                    } else {
                        recoverBlockedLeg(context)
                    }
                    if (!recovered) return STOP_COMMAND
                    continue
                }
                pathProbePending = false
            }

            val currentTarget = movementTarget ?: return stopForDrain(context)
            if (context.playerPos.distanceToSqr(currentTarget) <= ARRIVAL_DISTANCE_SQUARED) {
                if (followingFlightPath) {
                    val nextWaypoint = pathWaypointQueue.pollFirst()
                    if (nextWaypoint != null) {
                        movementTarget = nextWaypoint
                        pathProbePending = true
                        resetCruiseWatchdog()
                        continue
                    }
                    followingFlightPath = false
                    if (laneModeActive) {
                        if (!advanceLaneWaypointOrFinish(context)) return STOP_COMMAND
                        continue
                    }
                    enterArrivalHold(context)
                    return STOP_COMMAND
                }
                if (laneModeActive) {
                    if (!advanceLaneWaypointOrFinish(context)) return STOP_COMMAND
                    continue
                }
                enterArrivalHold(context)
                return STOP_COMMAND
            }
            val speedMagnitude = when {
                laneModeActive -> laneSpeedMagnitude(context)
                context.planMode -> planCornerSpeedMagnitude(context, currentTarget)
                else -> 1.0
            }
            // A zero lane speed is a deliberate hold, not a stall: the stationary-ticks
            // watchdog exists to catch an UNINTENDED blockage, and would otherwise
            // eventually treat this intentional stillness the same way, forcing a
            // pathfind or even abandoning the current leg once flagged twice. Resetting
            // the watchdog here keeps it from ever accumulating while zero speed is in
            // effect, mirroring how it never runs during an explicit hold state.
            if (speedMagnitude == 0.0) {
                resetCruiseWatchdog()
            } else if (cruiseStalled(context.playerPos)) {
                val recovered = if (context.planMode) {
                    recoverPlanLeg(context)
                } else if (laneModeActive) {
                    recoverBlockedLaneLeg(context)
                } else {
                    recoverBlockedLeg(context)
                }
                if (!recovered) return STOP_COMMAND
                continue
            }
            return movementCommand(context.playerPos, currentTarget, speedMagnitude)
        }
    }

    private fun tickHold(context: MoverTickContext): MoverCommand {
        recoverFlightIfNeeded(context)?.let { command -> return command }

        return when (holdMode) {
            HoldMode.ARRIVAL -> tickArrivalHold(context)
            HoldMode.CHUNK_WAIT -> tickChunkWait(context)
            null -> STOP_COMMAND
        }
    }

    // Lane mode never enters ARRIVAL hold (the speed controller replaces
    // arrival holds), so this remains reachable only from batch/discrete mode.
    private fun tickArrivalHold(context: MoverTickContext): MoverCommand {
        if (context.planMode) return tickPlanArrivalHold(context)

        arrivalHoldTicks++
        if (arrivalHoldTicks >= HOLD_HARD_CAP_TICKS) {
            context.onHoldHardCap()
            releaseArrivalHold(context)
            return STOP_COMMAND
        }

        val snapshot = context.feedSnapshot
        if (snapshot == null) {
            fedEmptyHoldTicks = 0
            return STOP_COMMAND
        }
        if (snapshot.candidateCount == 0 && snapshot.inFlightCount == 0) {
            fedEmptyHoldTicks++
        } else {
            fedEmptyHoldTicks = 0
        }
        if (fedEmptyHoldTicks < FED_EMPTY_ADVANCE_TICKS) return STOP_COMMAND

        releaseArrivalHold(context)
        return STOP_COMMAND
    }

    // Plan-mode counterpart of the v3 body above: releases once the current work
    // position has actually resolved (see checkCurrentPlanWorkPosition), rather than
    // draining via feedSnapshot -- plan mode always clears the v3 feed (see
    // MoverTickContext.feedSnapshot's own doc), so the v3 drain signal this hold
    // otherwise relies on never fires there. The validity check runs every tick,
    // never gated on the waiting/columnBlocked sets having changed since the last
    // baseline -- waitingForReach is an ordered lookahead, so dispatch can advance
    // its own head past this stand's coverage while the SET of positions it contains
    // stays identical (e.g. the head resolves and a position already in the list
    // becomes the new head), and a static-looking frontier must not suppress the
    // release that advance requires. A frontier change that leaves the current stand
    // still valid re-baselines the comparison set (planRouteWaitingPositions/
    // planRouteColumnBlockedPositions) instead of releasing, so a LATER, genuinely
    // relevant change is still caught against a fresh comparison instead of the stale
    // build-time one. The hard cap stays the fallback for a frontier whose head never
    // moves at all. The route is now planned once for a whole layer segment (see
    // buildPlanRoute's own doc), so a head that left this entry's own coverage is not
    // necessarily a stale route -- a LATER entry already in the route can already cover
    // it (see checkCurrentPlanWorkPosition's own HeadLeftCoverage case); only when no
    // later entry does, or a ban/column invalidation fired, does this fall through
    // to a full rebuild.
    private fun tickPlanArrivalHold(context: MoverTickContext): MoverCommand {
        arrivalHoldTicks++
        if (arrivalHoldTicks >= HOLD_HARD_CAP_TICKS) {
            context.onHoldHardCap()
            releaseArrivalHold(context)
            return STOP_COMMAND
        }

        val currentWaiting = context.planFrontier?.waitingForReach.orEmpty()
            .mapTo(LinkedHashSet<BlockPos>()) { target -> target.pos.immutable() }
        val currentColumnBlocked = context.planFrontier?.columnBlocked.orEmpty()
            .mapTo(LinkedHashSet<BlockPos>()) { pos -> pos.immutable() }

        // Work-position hysteresis: a frontier change is only a real advance for THIS
        // stand once its own covered target is actually gone (drained/changed) or the
        // stand itself has since been banned -- see
        // checkCurrentPlanWorkPosition's own doc. A change elsewhere (e.g.
        // columnBlocked toggling for an unrelated position) still re-baselines so a
        // LATER, genuinely relevant change is not missed against a stale comparison,
        // but must not release and re-select a different stand.
        // releaseBranch is diagnostic only (see markCompletePending's own doc) --
        // identifies which of the two release paths below actually fired, without
        // changing which one does.
        val releaseBranch: String
        when (val check = checkCurrentPlanWorkPosition(currentWaiting, currentColumnBlocked)) {
            PlanWorkPositionCheck.Valid -> {
                planRouteWaitingPositions = currentWaiting
                planRouteColumnBlockedPositions = currentColumnBlocked
                return STOP_COMMAND
            }
            is PlanWorkPositionCheck.HeadLeftCoverage -> {
                val advanceIndex = ((routeIndex + 1) until route.size).firstOrNull { index ->
                    check.head in route[index].covered
                }
                if (advanceIndex != null) {
                    advanceRouteToCoveringEntry(advanceIndex, context)
                    return STOP_COMMAND
                }
                releaseBranch = "head-left-coverage-no-forward-cover"
            }
            is PlanWorkPositionCheck.Invalid -> releaseBranch = check.branch
        }
        releaseArrivalHold(context, frontierChanged = true, releaseBranch = releaseBranch)
        return STOP_COMMAND
    }

    // Outcome of checking whether the current plan-mode work position is still worth
    // holding for -- see checkCurrentPlanWorkPosition's own doc for how each case is
    // reached. HeadLeftCoverage is the only outcome that ever permits an in-place route
    // advance (see tickPlanArrivalHold): ban/column invalidation (and the head-
    // absent/route-empty cases folded into Invalid) mean the whole route's own premise no
    // longer holds, not just this one entry's own coverage, so only a full rebuild can fix
    // it. Invalid's branch is a diagnostic-only label identifying which of
    // checkCurrentPlanWorkPosition's own return sites produced it -- never read by any
    // routing decision, only by the release-frontier-changed markCompletePending log.
    private sealed interface PlanWorkPositionCheck {
        object Valid : PlanWorkPositionCheck
        data class HeadLeftCoverage(val head: BlockPos) : PlanWorkPositionCheck
        data class Invalid(val branch: String) : PlanWorkPositionCheck
    }

    // True (Valid) while the current route entry's own stand is still worth holding
    // for: not ban-listed (rule (b)), the stand's own column has not itself
    // become one the printer needs clear (also rule (b) -- parking on a now-blocked
    // column is no longer usable, not merely stale), and the frontier's own CURRENT
    // head -- the strict lookahead's first entry, the only position dispatch can
    // actually act on next -- is one of this stand's covered targets (rule (a)/(c)).
    // waitingForReach is an ordered plan-dispatch lookahead, not an accumulated
    // "everything still pending" set: a later, merely-still-covered target sitting
    // behind the head in plan order can never be placed before the head resolves, so
    // it is never a reason to keep holding once the head itself has moved past this
    // stand's coverage. currentWaiting must be an order-preserving set (its own build
    // site maps the frontier's list in order) for "head" to mean anything here.
    // HeadLeftCoverage (route non-empty, no ban/column hit, a head exists, but
    // it is not covered) carries that head so a later route entry can be searched for
    // it without re-deriving it. Invalid (route empty, banned, evacuation-worthy, or
    // the head is absent) means the position has genuinely resolved with nothing later
    // in this same route to fall back on, so only a rebuild trigger may proceed.
    private fun checkCurrentPlanWorkPosition(
        currentWaiting: Set<BlockPos>,
        currentColumnBlocked: Set<BlockPos>,
    ): PlanWorkPositionCheck {
        val current = route.getOrNull(routeIndex)
            ?: return PlanWorkPositionCheck.Invalid("invalid-no-route")
        prunePlanBannedCells()
        val banCell = planRouteBanCells.getOrNull(routeIndex) ?: standBanCell(current.target)
        if (banCell in planBannedCells) return PlanWorkPositionCheck.Invalid("invalid-banned")
        val standColumn = planColumnOf(current.target)
        if (currentColumnBlocked.any { position -> packColumn(position.x, position.z) == standColumn }) {
            return PlanWorkPositionCheck.Invalid("invalid-column")
        }
        val head = currentWaiting.firstOrNull() ?: return PlanWorkPositionCheck.Invalid("invalid-no-head")
        return if (head in current.covered) {
            PlanWorkPositionCheck.Valid
        } else {
            PlanWorkPositionCheck.HeadLeftCoverage(head)
        }
    }

    // Advances routeIndex directly to a later entry already confirmed to cover the
    // frontier's current head, without touching the route list itself or re-invoking
    // planRoute -- the route was planned as a whole layer segment's worth of stands in
    // one pass (see buildPlanRoute), so any later entry still describes a valid,
    // already-scored stand for whatever it covers; only the "which entry is active"
    // bookkeeping and the leg/movement state that depends on it need to catch up.
    // Mirrors advanceRoute's own refresh (flightLossCount reset, prepareCurrentWorkPosition),
    // jumping straight to targetIndex instead of a single +1 step.
    private fun advanceRouteToCoveringEntry(targetIndex: Int, context: MoverTickContext): Unit {
        if (targetIndex != routeIndex) {
            flightLossCount = 0
        }
        routeIndex = targetIndex
        prepareCurrentWorkPosition(context, cause = "advance-in-place")
    }

    // frontierChanged marks a plan-mode release caused by the frontier's own waiting
    // set moving on (see tickPlanArrivalHold): the world genuinely progressed, so this
    // never runs the v3 unproductive-target bookkeeping (bans / NO_PROGRESS deferral
    // callbacks) that bookkeeping assumes a stalled, not moved-on, target. It also never
    // advances to the route's next leg: that leg was planned against the frontier set
    // BEFORE the change, so it can target a position the current frontier no longer
    // cares about. Clearing to a fresh rebuild instead lets the very next tick replan
    // entirely from the CURRENT frontier and re-capture tickPlanArrivalHold's own
    // baseline (planRouteWaitingPositions) against it. v3's own release path is
    // untouched (frontierChanged defaults false there). releaseBranch is diagnostic only
    // (see tickPlanArrivalHold's own doc) -- forwarded into markCompletePending's log,
    // never read by any release decision here.
    private fun releaseArrivalHold(
        context: MoverTickContext,
        frontierChanged: Boolean = false,
        releaseBranch: String? = null,
    ): Unit {
        if (frontierChanged) {
            markCompletePending(context, cause = "release-frontier-changed", releaseBranch = releaseBranch)
        } else if (observedQueueRevision == context.queueRevision) {
            handleUnproductiveWorkPosition(context)
        } else {
            advanceRoute(context)
        }
    }

    private fun tickChunkWait(context: MoverTickContext): MoverCommand {
        val target = movementTarget ?: return stopForDrain(context)
        val probe = context.pathProbe(context.playerPos, target)
        if (!probe.chunkLoaded) {
            chunkWaitTicks++
            if (chunkWaitTicks >= CHUNK_WAIT_TIMEOUT_TICKS) {
                if (laneModeActive) skipToNextLaneSegment(context) else skipCurrentWorkPosition(context)
            }
            return STOP_COMMAND
        }

        moverState = MoverState.CRUISE
        holdMode = null
        chunkWaitTicks = 0
        if (probe.clear) {
            pathProbePending = false
        } else {
            val recovered = if (laneModeActive) recoverBlockedLaneLeg(context) else recoverBlockedLeg(context)
            if (!recovered) return STOP_COMMAND
        }
        return tickCruise(context)
    }

    // Builds from the current printer context and the run-scoped candidate bans. Empty
    // is only terminal when confirmEmpty is set by the NEXT-tick drain confirmation;
    // every first empty observation merely arms completePending and stops for one tick.
    //
    // While the gate layer (the frozen layer) still has placeable work,
    // this dispatches to the lane sweep (buildLanePass) instead of the discrete
    // work-position planner below. Once the frozen layer drains, gateLayerMissing is
    // empty and control falls through to the unchanged discrete/batch path -- this is
    // the SAME fallback sparse/batch recovery has always used, so batch
    // mode, the two-phase terminal protocol, the final sweep, and the canComplete
    // invariant are untouched.
    private fun buildRoute(
        context: MoverTickContext,
        clearBannedTargets: Boolean = false,
        confirmEmpty: Boolean = false,
        completeAfterEmpty: Boolean = false,
    ): Boolean {
        if (context.planMode) {
            return buildPlanRoute(context, clearBannedTargets, confirmEmpty)
        }
        if (clearBannedTargets) bannedTargets.clear()
        val placeableMissing = context.missingWorld.filter(context.isPlaceable)
        val newGateSignature = routeStartRevision == null ||
            context.gateY != routeGateSignature
        if (newGateSignature) {
            frozenLayerMembers = context.gateY?.let { gateY ->
                placeableMissing
                    .filter { worldPos -> worldPos.y == gateY }
                    .mapTo(LinkedHashSet()) { worldPos -> worldPos.immutable() }
            }.orEmpty()
        }
        val placeableSet = placeableMissing.toHashSet()
        // Routes are layer-scoped per the user's plan-then-place request. Recovered
        // stragglers batch only after the gate layer drains; measurements showed
        // rebuild-dive churn growing with the straggler population.
        val gateLayerMissing = frozenLayerMembers.filter { worldPos ->
            worldPos in placeableSet
        }
        routeStartRevision = context.queueRevision
        routeGateSignature = context.gateY

        if (gateLayerMissing.isNotEmpty()) {
            return buildLanePass(context, gateLayerMissing)
        }
        laneModeActive = false

        // The frozen gate layer above just drained. During
        // ASCENT, below-gate stragglers are not this fallback's job -- they wait for
        // the top-down RECOVERY pass -- otherwise the mover would dive for them on
        // THIS tick, before the printer's own gate has had a chance to ratchet
        // upward on its next tick (the printer and this rebuild share the same
        // "layer just drained" moment, but the printer only reacts to it on its
        // NEXT tick). Same-layer stragglers that became placeable after the layer
        // froze are unaffected: they still satisfy worldPos.y >= gateY.
        // RECOVERY has no such restriction -- it already IS the top-down straggler
        // pass, so the fallback works exactly as it always has.
        val gateY = context.gateY
        val fallbackMissing = if (context.gatePhase == PrinterLayerGatePhase.ASCENT && gateY != null) {
            placeableMissing.filter { worldPos -> worldPos.y >= gateY }
        } else {
            placeableMissing
        }

        val planned = planRoute(
            placeableMissing = fallbackMissing,
            reach = context.reach,
            bannedTargets = bannedTargets,
            expectedStateAt = context.expectedStateAt,
        )
        reportUncoverable(context, planned.uncoverable)
        val previousRoute = route
        val previousIndex = routeIndex
        route = planned.route
        routeIndex = 0
        if (route != previousRoute || routeIndex != previousIndex) {
            flightLossCount = 0
        }
        if (route.isEmpty()) {
            if (completeAfterEmpty) {
                complete()
            } else if (confirmEmpty && context.canComplete()) {
                if (lastFinalSweepRevision != context.queueRevision) {
                    lastFinalSweepRevision = context.queueRevision
                    context.onFinalSweepBackoffBypass()
                    return buildRoute(
                        context = context,
                        clearBannedTargets = true,
                        completeAfterEmpty = true,
                    )
                }
                complete()
            } else {
                markCompletePending(context, cause = "empty-route")
            }
            return false
        }
        completePending = false
        completePendingRevision = null
        return prepareCurrentWorkPosition(context)
    }

    // Plan-mode (v4) counterpart of the block above: targets come from the printer's
    // own PlanFrontierSnapshot instead of missingWorld, laneModeActive never turns on,
    // and gateY/feedSnapshot/missingWorld are never read. Stand positions still go
    // through the exact same LayerRoutePlanner.planRoute machinery, treating each
    // waiting-for-reach frontier position as one "missing" anchor -- only the input
    // list and its expected-state lookup differ from the v3 branch above.
    private fun buildPlanRoute(
        context: MoverTickContext,
        clearBannedTargets: Boolean,
        confirmEmpty: Boolean,
    ): Boolean {
        if (clearBannedTargets) {
            bannedTargets.clear()
            planBannedCells.clear()
        }
        laneModeActive = false
        routeStartRevision = context.queueRevision

        val frontier = context.planFrontier
        val waitingForReach = frontier?.waitingForReach.orEmpty()
        val columnBlocked = frontier?.columnBlocked.orEmpty()
        val inFlight = frontier?.inFlight.orEmpty()
        planRouteWaitingPositions = waitingForReach.mapTo(LinkedHashSet()) { target -> target.pos.immutable() }
        planRouteColumnBlockedPositions = columnBlocked.mapTo(LinkedHashSet()) { pos -> pos.immutable() }
        prunePlanBannedCells()

        // The no-routable-stand latch (lastEmptyPlanRoutePositions) is only valid for the
        // EXACT waitingForReach/columnBlocked pair it was captured against -- once either
        // one has moved on from that capture, the latch describes a situation that no
        // longer exists and must not keep suppressing the trapped watchdog (or gating the
        // rebuild-pacing shortcut below) for whatever the frontier looks like now.
        if (
            lastEmptyPlanRoutePositions != null &&
            (
                lastEmptyPlanRoutePositions != planRouteWaitingPositions ||
                    lastEmptyPlanRouteColumnBlockedPositions != planRouteColumnBlockedPositions
                )
        ) {
            lastEmptyPlanRoutePositions = null
        }

        if (waitingForReach.isEmpty()) {
            route = emptyList()
            planRouteBanCells = emptyList()
            routeIndex = 0
            return if (columnBlocked.isNotEmpty()) {
                buildPlanEvacuationRoute(context, waitingForReach, columnBlocked, inFlight)
            } else {
                finishPlanRoute(context, confirmEmpty)
            }
        }

        // Rebuild pacing: the LayerRoutePlanner scoring below is the expensive part of this
        // branch. If the most recent attempt against this EXACT working set already came up
        // empty, and not enough ticks have passed since, replanning again would (almost
        // certainly) just re-derive the same empty result -- skip straight to the same
        // empty-route handling that attempt already went through, without re-scoring.
        val lastEmptyPositions = lastEmptyPlanRoutePositions
        if (
            lastEmptyPositions == planRouteWaitingPositions &&
            moverTickCounter - lastEmptyPlanRouteTick < PLAN_EMPTY_ROUTE_REBUILD_TICKS
        ) {
            return finishPlanRoute(context, confirmEmpty)
        }

        val expectedByPos = waitingForReach.associate { target -> target.pos to target.expected }
        // Columns the printer needs clear for a reason THIS rebuild cannot route around by
        // waiting: columnBlocked (the player's own column is physically in the way) and
        // inFlight (a placement already dispatched into that column). A plain
        // waitingForReach position's column is NOT included here -- LayerRoutePlanner's own
        // temporal column rule (excludeOwnColumnFromCoverage) already rejects parking on a
        // column that still has pending work at the moment a candidate is scored, and
        // re-admits it the instant an earlier route entry in this SAME call has consumed
        // it, which a static pre-union of every waiting column could never do.
        val externalBlockedColumns = planColumnsOf(emptyList(), columnBlocked, inFlight)
        val planIsCandidateBanned: (Vec3) -> Boolean = { target ->
            standBanCell(target) in planBannedCells ||
                target in bannedTargets ||
                planColumnOf(target) in externalBlockedColumns
        }
        val planIsStandUsable: (Vec3, List<BlockPos>) -> Boolean = { target, covered ->
            isStandAcceptable(target, covered, context)
        }
        // A single planRoute call now does what the old discover-bad-entry-then-reban loop
        // used to need many calls for: bad candidates (a banned cell, a column the printer
        // still needs clear for a reason above, or a stand that fails isStandAcceptable) are
        // excluded from scoring up front instead of being discovered on the resulting route
        // and re-planned around. A target left with every candidate rejected simply has no
        // route entry (LayerRoutePlanner's own uncoverable list, silently dropped here
        // exactly as it always has been) -- it stays in waitingForReach and the mover holds
        // rather than terminating.
        val planned = planRoute(
            placeableMissing = waitingForReach.map { target -> target.pos },
            reach = context.reach,
            expectedStateAt = { pos -> expectedByPos[pos] },
            centerCandidateForScoring = true,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
            isCandidateBanned = planIsCandidateBanned,
            isStandUsable = planIsStandUsable,
            // A plan-order-contiguous coverage prefix (see planRoute's own doc) keeps the
            // covering-route-entry index non-decreasing along waitingForReach's own plan
            // order, so tickPlanArrivalHold's forward-only in-place advance can always find
            // whichever later entry now covers the frontier's head instead of needing a
            // full rebuild every time dispatch crosses a snake column boundary.
            coverContiguousPrefix = true,
        )

        val previousRoute = route
        val previousIndex = routeIndex
        // Every surviving entry already passed isStandAcceptable above, so
        // standFittedHeight is non-null here -- fitted just once more per entry, on the
        // final (already-filtered) list, to set the leg's own target height to the exact
        // pose that filter validated. planRouteBanCells is captured from the SAME
        // planned.route entries, before fitting -- see its own doc.
        planRouteBanCells = planned.route.map { entry -> standBanCell(entry.target) }
        route = planned.route.map { entry -> entry.copy(target = standFittedTarget(entry, context)) }
        routeIndex = 0
        if (route != previousRoute || routeIndex != previousIndex) {
            flightLossCount = 0
        }
        if (route.isEmpty()) {
            logEmptyPlanRouteDiagnostics(
                waitingForReach = waitingForReach,
                reach = context.reach,
                expectedByPos = expectedByPos,
                isCandidateBanned = planIsCandidateBanned,
                isStandUsable = planIsStandUsable,
            )
            lastEmptyPlanRoutePositions = planRouteWaitingPositions
            lastEmptyPlanRouteColumnBlockedPositions = planRouteColumnBlockedPositions
            lastEmptyPlanRouteTick = moverTickCounter
            return finishPlanRoute(context, confirmEmpty)
        }
        // A non-empty route that still cannot cover the frontier's dispatch head is the
        // exact shape tickPlanArrivalHold's head-left-coverage-no-forward-cover release
        // loops on -- the head was this rebuild's own first anchor and lost all 9
        // candidates (planned.uncoverable). The route below is real work for OTHER
        // positions, so this stays diagnostic only and the build proceeds unchanged.
        val dispatchHead = waitingForReach.first().pos.immutable()
        if (dispatchHead in planned.uncoverable) {
            logHeadNotCoveredDiagnostics(
                head = dispatchHead,
                waitingForReach = waitingForReach,
                routeSize = route.size,
                uncoverableSize = planned.uncoverable.size,
                reach = context.reach,
                isCandidateBanned = planIsCandidateBanned,
                isStandUsable = planIsStandUsable,
            )
        }
        // A route was successfully built: an armed confirmation from an earlier empty
        // observation must never survive into this (or a later) episode as a stale skip
        // of the two-phase terminal sequence.
        lastEmptyPlanRoutePositions = null
        planFinalAtArm = false
        completePending = false
        completePendingRevision = null
        return prepareCurrentWorkPosition(context)
    }

    // Diagnostic only, rate-limited (see PLAN_DIAG_LOG_INTERVAL_TICKS): buildPlanRoute's
    // planRoute call came back with an empty route despite a non-empty waitingForReach --
    // this is the empty-route branch that latches lastEmptyPlanRoutePositions and holds
    // instead of rebuilding again until it ages out or the frontier moves. Re-evaluates
    // only the first anchor's own 9 candidates (diagnosePlanRouteFirstAnchorRejections)
    // to break down why none of them won, without touching route/routeIndex or any other
    // state this call's own outcome already decided.
    private fun logEmptyPlanRouteDiagnostics(
        waitingForReach: List<MoverPlanFrontierTarget>,
        reach: Double,
        expectedByPos: Map<BlockPos, BlockState>,
        isCandidateBanned: (Vec3) -> Boolean,
        isStandUsable: (Vec3, List<BlockPos>) -> Boolean,
    ): Unit {
        if (moverTickCounter - lastEmptyPlanRouteDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastEmptyPlanRouteDiagLogTick = moverTickCounter
        val breakdown = diagnosePlanRouteFirstAnchorRejections(
            placeableMissing = waitingForReach.map { target -> target.pos },
            reach = reach,
            expectedStateAt = { pos -> expectedByPos[pos] },
            bannedTargets = bannedTargets,
            isCandidateBanned = isCandidateBanned,
            isStandUsable = isStandUsable,
        )
        com.mojang.logging.LogUtils.getLogger().info(
            "C3DBG[plan-route] empty route t={} waitingSize={} heads={} " +
                "rejections[banned={} candidateBanned={} temporalColumn={} standUnusable={} emptyCoverage={}]",
            moverTickCounter,
            waitingForReach.size,
            waitingForReach.take(3).joinToString(",") { target -> target.pos.toShortString() },
            breakdown?.bannedTargetsCount,
            breakdown?.candidateBannedCount,
            breakdown?.temporalColumnCount,
            breakdown?.standUnusableCount,
            breakdown?.emptyCoverageCount,
        )
    }

    // Diagnostic only, rate-limited: a rebuilt (non-empty) route cannot cover the
    // frontier's dispatch head -- the head was this rebuild's own first anchor and lost
    // all 9 candidates (see the call site in buildPlanRoute). The rejection tally
    // re-evaluates the head anchor (diagnosePlanRouteFirstAnchorRejections) to break
    // down which filter eliminated each candidate, mirroring what
    // logEmptyPlanRouteDiagnostics does for the fully-empty case.
    private fun logHeadNotCoveredDiagnostics(
        head: BlockPos,
        waitingForReach: List<MoverPlanFrontierTarget>,
        routeSize: Int,
        uncoverableSize: Int,
        reach: Double,
        isCandidateBanned: (Vec3) -> Boolean,
        isStandUsable: (Vec3, List<BlockPos>) -> Boolean,
    ): Unit {
        if (moverTickCounter - lastHeadNotCoveredDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastHeadNotCoveredDiagLogTick = moverTickCounter
        val expectedByPos = waitingForReach.associate { target -> target.pos.immutable() to target.expected }
        val breakdown = diagnosePlanRouteFirstAnchorRejections(
            placeableMissing = waitingForReach.map { target -> target.pos },
            reach = reach,
            expectedStateAt = { pos -> expectedByPos[pos] },
            bannedTargets = bannedTargets,
            isCandidateBanned = isCandidateBanned,
            isStandUsable = isStandUsable,
        )
        com.mojang.logging.LogUtils.getLogger().info(
            "C3DBG[plan-route] head-not-covered t={} head={} banSize={} routeSize={} uncoverableSize={} " +
                "rejections[banned={} candidateBanned={} temporalColumn={} standUnusable={} emptyCoverage={}]",
            moverTickCounter,
            head.toShortString(),
            planBannedCells.size,
            routeSize,
            uncoverableSize,
            breakdown?.bannedTargetsCount,
            breakdown?.candidateBannedCount,
            breakdown?.temporalColumnCount,
            breakdown?.standUnusableCount,
            breakdown?.emptyCoverageCount,
        )
    }

    // Diagnostic only: one line per planBannedCells insertion, tagged with the inserting
    // site (skip / unproductive / correction-escape) -- entries age out silently
    // (prunePlanBannedCells), so the insertion moment is the only anchor a field log has
    // for a ban's own lifetime when reading why a stand candidate kept losing.
    private fun logPlanBanAdd(site: String, cell: BlockPos): Unit {
        com.mojang.logging.LogUtils.getLogger().info(
            "C3DBG[ban] add t={} site={} cell={} banSize={}",
            moverTickCounter,
            site,
            cell.toShortString(),
            planBannedCells.size,
        )
    }

    // Diagnostic only, rate-limited: markCompletePending's own entry, plan mode only --
    // cause identifies which caller drove this HOLD (release-frontier-changed /
    // empty-route / finish-route / stop-for-drain), and waitingSize is the frontier's
    // CURRENT waitingForReach count at the moment this fired. releaseBranch is non-null
    // only for the release-frontier-changed cause (see tickPlanArrivalHold's own doc) --
    // when present, the line also carries the release-decision inputs (which
    // checkCurrentPlanWorkPosition branch fired, the frontier's current head, the
    // columnBlocked set, and the stand this HOLD was released from) so a persistent
    // release-frontier-changed HOLD can be told apart from a route/frontier desync
    // without re-deriving either from a raw waitingSize alone.
    private fun logMarkCompletePendingDiagnostics(
        context: MoverTickContext,
        cause: String,
        releaseBranch: String?,
    ): Unit {
        if (!context.planMode) return
        if (moverTickCounter - lastMarkCompletePendingDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastMarkCompletePendingDiagLogTick = moverTickCounter
        val logger = com.mojang.logging.LogUtils.getLogger()
        if (releaseBranch == null) {
            logger.info(
                "C3DBG[plan-route] markCompletePending t={} cause={} waitingSize={}",
                moverTickCounter,
                cause,
                context.planFrontier?.waitingForReach?.size ?: 0,
            )
            return
        }
        val columnBlocked = context.planFrontier?.columnBlocked.orEmpty()
        val standTarget = route.getOrNull(routeIndex)?.target
        logger.info(
            "C3DBG[plan-route] markCompletePending t={} cause={} waitingSize={} branch={} head={} " +
                "columnBlocked={}({}) stand={} routeIndex={}/{}",
            moverTickCounter,
            cause,
            context.planFrontier?.waitingForReach?.size ?: 0,
            releaseBranch,
            context.planFrontier?.waitingForReach?.firstOrNull()?.pos?.toShortString() ?: "none",
            columnBlocked.size,
            columnBlocked.take(2).joinToString(",") { pos -> pos.toShortString() },
            standTarget?.let { target -> floorCell(target).toShortString() } ?: "none",
            routeIndex,
            route.size,
        )
    }

    // The single assignment site for movementTarget while plan mode is active -- every
    // plan-mode caller that used to write the field directly now routes through here so
    // the diagnostic log (rate-limited, plan mode only) always sees the same before/after
    // pair the field itself transitioned through. cause is one of the fixed vocabulary
    // documented at each call site (prepare / advance-in-place / mark-complete-pending /
    // evacuation) -- never invented ad hoc at the log line itself. v3/lane assignment
    // sites are untouched and keep writing the field directly, exactly as before.
    private fun setPlanMovementTarget(context: MoverTickContext, cause: String, value: Vec3?): Unit {
        val previous = movementTarget
        movementTarget = value
        if (!context.planMode || previous == value) return
        if (moverTickCounter - lastMovementTargetDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastMovementTargetDiagLogTick = moverTickCounter
        val logger = com.mojang.logging.LogUtils.getLogger()
        if (value != null) {
            logger.info(
                "C3DBG[flight] movementTarget set t={} cause={} target=({}, {}, {})",
                moverTickCounter, cause, value.x, value.y, value.z,
            )
        } else {
            logger.info("C3DBG[flight] movementTarget clear t={} cause={}", moverTickCounter, cause)
        }
    }

    // Diagnostic only, rate-limited: beginPlanFlightPath found no active target
    // (baseTarget() null) and returned false before ever attempting A* -- the only path
    // that can return false (a genuine A* failure always falls back to
    // beginOverTheTopLeg, which still returns true), so this is always the early-reject
    // case, never a masked A* failure. cause is the same tag the caller passed into
    // beginPlanFlightPath, identifying which route-level action triggered this attempt.
    private fun logFlightBeginFailDiagnostics(context: MoverTickContext, cause: String): Unit {
        if (!context.planMode) return
        if (moverTickCounter - lastFlightBeginFailDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastFlightBeginFailDiagLogTick = moverTickCounter
        com.mojang.logging.LogUtils.getLogger().info(
            "C3DBG[flight] beginPlanFlightPath early-reject t={} cause={} routeIndex={} routeSize={} laneModeActive={}",
            moverTickCounter, cause, routeIndex, route.size, laneModeActive,
        )
    }

    // Shared empty-route terminal handling for plan mode: canComplete() is never
    // consulted (contract: completion authority is context.planSessionFinal alone).
    // planSessionFinal alone is not enough, though -- it can flip true and false again
    // within the same queue revision (a same-tick manual reconcile racing the mover's
    // own tick, for instance) -- so completion also requires it to have already been
    // true on the call that armed this confirmation (planFinalAtArm), not merely on the
    // confirming tick itself. Any call observed with it false resets that memory, so a
    // later flip back to true has to hold across two consecutive empty-route calls all
    // over again before COMPLETE is reachable. Both the arm and the confirm additionally
    // require the CURRENT waitingForReach to be empty: the real printer can never report
    // final with waiting work, but this must not depend on a cross-module invariant it
    // cannot see -- without this, a non-empty frontier the planner cannot route to (see
    // buildPlanRoute's own empty-route fallback into here) could otherwise arm or even
    // complete the run despite real, unreachable work still pending.
    private fun finishPlanRoute(context: MoverTickContext, confirmEmpty: Boolean): Boolean {
        val frontierEmpty = context.planFrontier?.waitingForReach.isNullOrEmpty()
        if (confirmEmpty && frontierEmpty && planFinalAtArm && context.planSessionFinal) {
            complete()
        } else {
            planFinalAtArm = frontierEmpty && context.planSessionFinal
            markCompletePending(context, cause = "empty-route")
        }
        return false
    }

    // EVACUATE: waitingForReach is empty but the printer still has work blocked on the
    // player's own column -- route to the nearest enterable cell outside every frontier
    // column so the printer can proceed, instead of holding forever in the very column
    // it is waiting on. Cells already in planBannedCells (an earlier evacuation attempt
    // through this exact cell that failed and got banned via the same generic
    // skipCurrentWorkPosition/handleUnproductiveWorkPosition machinery discrete stands
    // use) are excluded too, so a failed evacuation leg does not just loop back onto the
    // same cell on the very next rebuild. No candidate found within the ring is not
    // terminal: hold instead (there is still real work: columnBlocked is non-empty).
    private fun buildPlanEvacuationRoute(
        context: MoverTickContext,
        waitingForReach: List<MoverPlanFrontierTarget>,
        columnBlocked: List<BlockPos>,
        inFlight: List<BlockPos>,
    ): Boolean {
        prunePlanBannedCells()
        val blockedColumns = planColumnsOf(waitingForReach, columnBlocked, inFlight)
        val playerCell = floorCell(context.playerPos)
        val isEnterable = evacuationCandidatePredicate(context)
        val target = evacuationRingCandidates(playerCell)
            .filter { cell -> packColumn(cell.x, cell.z) !in blockedColumns }
            .filter { cell -> cell !in planBannedCells }
            .filter { cell -> isEnterable(cell) }
            .minByOrNull { cell -> blockPosDistanceSquared(cell, playerCell) }
            ?: return finishPlanRoute(context, confirmEmpty = false)

        // Centered on the cell (x+0.5, z+0.5) at the SAME collision-aware fitted height
        // evacuationCandidatePredicate just validated this cell against -- not a flat
        // HOVER_CLEARANCE offset on the cell's raw corner, which ignores whatever real
        // obstruction geometry the predicate actually checked (see
        // evacuationFittedHeight's own doc).
        val evacuationTarget = Vec3(target.x + 0.5, evacuationFittedHeight(context, target), target.z + 0.5)
        route = listOf(
            PlannedWorkPosition(
                target = evacuationTarget,
                covered = columnBlocked.toSet(),
            ),
        )
        planRouteBanCells = listOf(standBanCell(evacuationTarget))
        routeIndex = 0
        // A route was successfully built here too: see buildPlanRoute's own matching
        // comment for why a stale arm must not survive a successful build -- the same
        // holds for the no-routable-stand latch itself, since an evacuation route is a
        // plan route building successfully exactly like the discrete-candidate branch is.
        lastEmptyPlanRoutePositions = null
        planFinalAtArm = false
        completePending = false
        completePendingRevision = null
        return prepareCurrentWorkPosition(context, cause = "evacuation")
    }

    // Evacuation candidates go through the SAME collision-aware profile plan legs
    // actually fly through (doors passable, slab-top hovers legal), not the legacy
    // full-cell isPassableCell check -- a ring cell whose only obstruction is a door or a
    // sub-block-height slab is a perfectly good place to evacuate to, and the legacy
    // check would wrongly reject it. Falls back to the legacy check (skipped exactly when
    // isStandAcceptable/beginPlanFlightPath also fall back) when the context carries no
    // world/bounds to evaluate against.
    private fun evacuationCandidatePredicate(context: MoverTickContext): (BlockPos) -> Boolean {
        val stateAt = context.planStateAt
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) {
            return { cell -> context.isPassableCell(cell) && context.isPassableCell(cell.above()) }
        }
        val profile = FlightPassabilityProfile.collisionAware(travelAabb(travelBounds), stateAt)
        return { cell -> profile.feetHeightAt(cell) != null }
    }

    // The evacuation cell's own fitted feet height, per the SAME collision-aware profile
    // evacuationCandidatePredicate validates candidates against (see its own doc) -- the
    // exact pose that filter approved, not a flat HOVER_CLEARANCE offset that ignores
    // real obstruction geometry (a slab top, a fence-top hover) the way a raw-corner
    // target would. Only ever called on a cell the predicate has already accepted, so
    // the ?: fallback below is defensive only, never actually exercised on the
    // accepted-candidate path -- mirrors standFittedTarget's own doc.
    private fun evacuationFittedHeight(context: MoverTickContext, cell: BlockPos): Double {
        val stateAt = context.planStateAt
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) {
            return cell.y.toDouble() + HOVER_CLEARANCE
        }
        val profile = FlightPassabilityProfile.collisionAware(travelAabb(travelBounds), stateAt)
        return profile.feetHeightAt(cell) ?: (cell.y.toDouble() + HOVER_CLEARANCE)
    }

    // Ring (not disc) of horizontal candidates at Chebyshev distance 2-3 from center,
    // same altitude -- deliberately excludes distance 0-1 (still inside/adjacent to a
    // blocked column) and stays small enough to search exhaustively every rebuild.
    private fun evacuationRingCandidates(center: BlockPos): List<BlockPos> {
        val candidates = mutableListOf<BlockPos>()
        for (radius in PLAN_EVACUATION_RING_MIN_RADIUS..PLAN_EVACUATION_RING_MAX_RADIUS) {
            for (deltaX in -radius..radius) {
                for (deltaZ in -radius..radius) {
                    if (maxOf(abs(deltaX), abs(deltaZ)) != radius) continue
                    candidates += center.offset(deltaX, 0, deltaZ)
                }
            }
        }
        return candidates
    }

    private fun blockPosDistanceSquared(first: BlockPos, second: BlockPos): Long {
        val deltaX = (first.x - second.x).toLong()
        val deltaY = (first.y - second.y).toLong()
        val deltaZ = (first.z - second.z).toLong()
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
    }

    // Ages out planBannedCells entries older than PLAN_BAN_MAX_AGE_TICKS -- a stand
    // rejected long ago (a transient obstruction, an overzealous flight-loss ban)
    // deserves a fresh chance rather than staying excluded for the rest of the run.
    // Called at every read site
    // (buildPlanRoute/buildPlanEvacuationRoute/checkCurrentPlanWorkPosition) so an
    // expired ban never has to wait for an unrelated rebuild to actually clear.
    private fun prunePlanBannedCells(): Unit {
        val cutoffTick = moverTickCounter - PLAN_BAN_MAX_AGE_TICKS
        val stale = planBannedCells.entries.filter { entry -> entry.value < cutoffTick }.map { entry -> entry.key }
        for (cell in stale) planBannedCells.remove(cell)
    }

    private fun planColumnsOf(
        waitingForReach: List<MoverPlanFrontierTarget>,
        columnBlocked: List<BlockPos>,
        inFlight: List<BlockPos>,
    ): Set<Long> {
        val columns = HashSet<Long>()
        for (target in waitingForReach) columns.add(packColumn(target.pos.x, target.pos.z))
        for (position in columnBlocked) columns.add(packColumn(position.x, position.z))
        for (position in inFlight) columns.add(packColumn(position.x, position.z))
        return columns
    }

    private fun planColumnOf(target: Vec3): Long {
        return packColumn(floor(target.x).toInt(), floor(target.z).toInt())
    }

    private fun packColumn(blockX: Int, blockZ: Int): Long {
        return (blockX.toLong() shl 32) xor (blockZ.toLong() and 0xFFFFFFFFL)
    }

    // Stand rules: (a) a stand may never sit at or below the highest target it itself covers
    // -- strictly above only, since the player's own 1.8-tall body at a same-layer or lower
    // feet position would occupy the very cell the printer needs clear to place into. (b) the
    // stand's own feet cell must have a free interval of at least PLAYER_HEIGHT +
    // STAND_HEADROOM_CLEARANCE (2.0, not the leg's own 1.8) per the SAME collision profile
    // plan legs travel through -- a stand needs headroom to actually work from, not merely
    // enough to fly through, so a 1.9-tall gap a leg could pass is still rejected as a place
    // to park. Constrains the feet position only, so a strictly-above stand covering a fence/
    // wall target (whose own collision extends upward from a DIFFERENT cell) stays accepted.
    // Gracefully accepts (skips check (b) only) when the context carries no world/bounds to
    // evaluate against, matching beginPlanFlightPath's own null-context fallback -- an older
    // fixture must never crash or spuriously reject here.
    private fun isStandAcceptable(target: Vec3, covered: List<BlockPos>, context: MoverTickContext): Boolean {
        val standFeetY = floor(target.y).toInt()
        val highestCoveredY = covered.maxOfOrNull { position -> position.y } ?: standFeetY
        if (standFeetY < highestCoveredY + 1) return false
        return standFittedHeight(target, context) != null
    }

    // The stand's own fitted feet height at its exact (integer x/z, floored y) cell, per
    // the same stand-headroom-gated collision profile isStandAcceptable validates against
    // -- null only when that validation itself would reject the cell. A null context (no
    // world/bounds to evaluate against) reports the stand's own un-fitted target height
    // instead, the same graceful fallback isStandAcceptable(b) uses.
    private fun standFittedHeight(target: Vec3, context: MoverTickContext): Double? {
        val stateAt = context.planStateAt
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) return target.y

        val standFeetY = floor(target.y).toInt()
        val standCell = BlockPos(floor(target.x).toInt(), standFeetY, floor(target.z).toInt())
        val profile = FlightPassabilityProfile.collisionAware(
            travelAabb(travelBounds),
            stateAt,
            requiredHeight = PLAYER_HEIGHT + STAND_HEADROOM_CLEARANCE,
        )
        return profile.feetHeightAt(standCell)
    }

    // The stand's own validated pose (see isStandAcceptable): every caller reaches this
    // only after that check already passed, so the raw target.y fallback below is
    // defensive only, never actually exercised on the accepted-route path. x/z are
    // centered on the stand's own cell (LayerRoutePlanner's raw candidate target sits on
    // the cell's integer corner, not its center) so the pose the mover actually produces,
    // validates, flies to, and bans is the exact hover position the cell-centered
    // collision footprint (see collisionFittingFeetHeight's own doc) assumes.
    private fun standFittedTarget(entry: PlannedWorkPosition, context: MoverTickContext): Vec3 {
        val fittedHeight = standFittedHeight(entry.target, context) ?: entry.target.y
        val cellX = floor(entry.target.x)
        val cellZ = floor(entry.target.z)
        return Vec3(cellX + 0.5, fittedHeight, cellZ + 0.5)
    }

    // The single source of truth for "which cell does this stand candidate ban" -- always
    // the candidate's own un-lifted cell identity, never a collision-fitted/epsilon-lifted
    // height (see planRouteBanCells' own doc). Every consumer -- the rejection filter and
    // insertion sites in buildPlanRoute/skipCurrentWorkPosition/handleUnproductiveWorkPosition,
    // the escape ban and work-position identity in handlePlanCorrection, the evacuation
    // ban in buildPlanEvacuationRoute, and the read in checkCurrentPlanWorkPosition --
    // must derive its cell through this function (directly, or via planRouteBanCells for
    // an already-routed entry), fed the same (RAW, pre-fit) target, so a candidate
    // rejected once is recognized as the same candidate everywhere else that checks
    // planBannedCells.
    private fun standBanCell(rawTarget: Vec3): BlockPos = floorCell(rawTarget)

    // Resets every route/waypoint/hold-related field to its fresh-build starting
    // point, WITHOUT touching moverState -- called only on a planMode flip (see tick's
    // own planModeChanged branch), which must leave TAKEOFF/CRUISE/HOLD exactly as they
    // are and let the immediately-following buildRoute call repopulate targets from
    // whichever source (v3 or plan) is now active. Also resets the trapped-stationary
    // and cruise-stall watchdogs: if that immediately-following build comes up empty
    // (an early return, never reaching this tick's watchdog check), a stale count
    // carried over from the outgoing mode must not abort on the very next tick.
    private fun resetRouteStateForModeSwitch(): Unit {
        route = emptyList()
        planRouteBanCells = emptyList()
        routeIndex = 0
        routeStartRevision = null
        routeGateSignature = null
        frozenLayerMembers = emptySet()
        movementTarget = null
        pathProbePending = false
        clearFlightPath()
        pathRecoveryAvailable = false
        pathSearchCount = 0
        resetCruiseWatchdog()
        trappedPreviousPos = null
        trappedStationaryTicks = 0
        holdMode = null
        chunkWaitTicks = 0
        arrivalHoldTicks = 0
        fedEmptyHoldTicks = 0
        completePending = false
        completePendingRevision = null
        bannedTargets.clear()
        unproductiveTargetsByPosition.clear()
        planBannedCells.clear()
        laneModeActive = false
        laneWaypoints = emptyList()
        laneWaypointIndex = 0
        lanePassStartRevision = null
        planFinalAtArm = false
        planRouteWaitingPositions = emptySet()
        planRouteColumnBlockedPositions = emptySet()
        lastEmptyPlanRoutePositions = null
        lastEmptyPlanRouteColumnBlockedPositions = emptySet()
        lastEmptyPlanRouteTick = 0L
        // A mode flip means any corrections observed under the OUTGOING mode are no
        // longer meaningful history for the incoming one.
        planCorrectionTicks.clear()
        planCorrectionWorkPositionCell = null
        planCorrectionCountForWorkPosition = 0
        planCorrectionWorkPositionTick = 0L
        planCorrectionRebuildPending = false
    }

    // Plans and starts a fresh lane pass over the (still-placeable subset of the)
    // frozen gate layer. gateLayerMissing is non-empty here by construction, and
    // planLanes always emits at least one segment for a non-empty input (the first lane
    // it walks is anchored at the input's own minimum X), so laneWaypoints is never
    // empty in practice.
    private fun buildLanePass(context: MoverTickContext, gateLayerMissing: List<BlockPos>): Boolean {
        val gateY = requireNotNull(context.gateY) {
            "gateLayerMissing is only ever populated when gateY != null"
        }
        val plan = planLanes(gateLayerMissing, gateY)
        lanePasses++
        laneSegmentsTotal += plan.segments.size
        laneModeActive = true
        laneWaypoints = plan.segments.flatMap { segment -> listOf(segment.start, segment.end) }
        laneWaypointIndex = 0
        lanePassStartRevision = context.queueRevision
        completePending = false
        completePendingRevision = null
        return prepareLaneCruise()
    }

    private fun prepareLaneCruise(): Boolean {
        val target = laneWaypoints.getOrNull(laneWaypointIndex) ?: return false
        moverState = MoverState.CRUISE
        flightLossCount = 0
        movementTarget = target
        pathProbePending = true
        clearFlightPath()
        pathRecoveryAvailable = true
        pathSearchCount = 0
        resetCruiseWatchdog()
        holdMode = null
        chunkWaitTicks = 0
        return true
    }

    private fun beginLaneLeg(): Unit {
        movementTarget = laneWaypoints[laneWaypointIndex]
        pathProbePending = true
        clearFlightPath()
        pathRecoveryAvailable = true
        pathSearchCount = 0
        flightLossCount = 0
        resetCruiseWatchdog()
    }

    private fun advanceLaneWaypointOrFinish(context: MoverTickContext): Boolean {
        laneWaypointIndex++
        if (laneWaypointIndex < laneWaypoints.size) {
            beginLaneLeg()
            return true
        }
        return finishLanePass(context)
    }

    // On an unrecoverable A* failure mid-segment, abandon the rest of that
    // segment's sweep (or the junction leg into it) and go straight to the next
    // segment's start rather than banning individual candidates the way discrete mode
    // does -- lane mode has no per-anchor candidates to ban.
    private fun skipToNextLaneSegment(context: MoverTickContext): Boolean {
        val nextSegmentStartIndex = (laneWaypointIndex / 2 + 1) * 2
        laneWaypointIndex = nextSegmentStartIndex
        if (laneWaypointIndex < laneWaypoints.size) {
            beginLaneLeg()
            return true
        }
        return finishLanePass(context)
    }

    // End of a full lane pass over the gate layer. If the pass made progress
    // (queue revision changed -- something got placed while sweeping), replan lanes
    // from the CURRENT still-placeable subset of the frozen gate layer (same
    // recompute buildRoute always does; the frozen membership itself does not widen
    // mid-layer) and run another pass. Otherwise nothing was placed
    // across the ENTIRE pass: defer whatever of the frozen layer is still placeable as
    // NO_PROGRESS through the existing mover-deferral entry point -- the same
    // live-defer-then-rebuild-sees-it-immediately pattern skipCurrentWorkPosition and
    // handleUnproductiveWorkPosition already rely on below -- so the layer drains
    // deterministically instead of sweeping the same unproductive lanes forever.
    private fun finishLanePass(context: MoverTickContext): Boolean {
        val progressed = lanePassStartRevision?.let { revision ->
            context.queueRevision != revision
        } == true
        if (!progressed) {
            val remaining = frozenLayerMembers.filterTo(LinkedHashSet()) { position ->
                context.isPlaceable(position)
            }
            if (remaining.isNotEmpty()) {
                context.onPositionsUncoverable(remaining, MoverDeferralCause.NO_PROGRESS)
            }
        }
        return buildRoute(context)
    }

    // Three-tier demand-driven speed: an empty fresh snapshot means nothing nearby
    // needs placing, so cruise at full speed regardless of any other signal. Otherwise,
    // with candidates pending, a placement accepted within the last
    // RECENT_PROGRESS_WINDOW_TICKS means the backlog at the current position is
    // actively draining, so stop and let it finish rather than crawling past it faster
    // than the rate-limited printer can keep up. Once that long without an accepted
    // placement, the backlog is stuck (prediction mismatches, ack timeouts, or a
    // deferral ban) rather than merely slow, so fall back to a crawl instead of holding
    // forever -- this is what lets the head eventually move on from unproductive work.
    // A stale or absent snapshot is treated the same as "still feeding, no acceptance
    // data": SchematicMover's freshFeedSnapshot only returns null when the snapshot
    // doesn't match the current tick/queue revision, i.e. exactly when there is no live
    // read on the printer's state, so the conservative choice is to crawl rather than
    // stop the head on stale information.
    private fun laneSpeedMagnitude(context: MoverTickContext): Double {
        val snapshot = context.feedSnapshot ?: return LANE_SPEED_SLOW
        if (snapshot.candidateCount == 0) return LANE_SPEED_FULL
        val ticksSinceAccept = snapshot.ticksSinceLastAccept
        return if (ticksSinceAccept != null && ticksSinceAccept <= RECENT_PROGRESS_WINDOW_TICKS) {
            LANE_SPEED_STOP
        } else {
            LANE_SPEED_SLOW
        }
    }

    // Plan mode only: slows down (never stops -- that is cruiseStalled's own job) over
    // the final CORNER_DECELERATION_DISTANCE blocks before a waypoint whose outgoing
    // leg (to the NEXT queued waypoint) turns sharply from the incoming one (the
    // player's own current direction of travel, i.e. target minus playerPos -- exactly
    // the direction this leg is already flying). Inertia carrying the player past a
    // corner at full speed is what lets it clip whatever the turn exists to route
    // around; approaching slower gives the direction change time to actually happen.
    // No next waypoint (the final hop of the leg, or a non-multi-waypoint leg) means
    // there is no corner to decelerate for.
    private fun planCornerSpeedMagnitude(context: MoverTickContext, target: Vec3): Double {
        if (!followingFlightPath) return 1.0
        val next = pathWaypointQueue.peekFirst() ?: return 1.0
        if (context.playerPos.distanceTo(target) > CORNER_DECELERATION_DISTANCE) return 1.0
        val incoming = target.subtract(context.playerPos)
        val outgoing = next.subtract(target)
        if (incoming.lengthSqr() < COLLISION_EPSILON_SQUARED || outgoing.lengthSqr() < COLLISION_EPSILON_SQUARED) {
            return 1.0
        }
        val cosAngle = incoming.normalize().dot(outgoing.normalize())
        return if (cosAngle < CORNER_TURN_COS_THRESHOLD) CORNER_DECELERATION_FACTOR else 1.0
    }

    private fun reportUncoverable(
        context: MoverTickContext,
        uncoverable: List<BlockPos>,
    ): Unit {
        if (uncoverable.isEmpty()) return
        val noProgress = uncoverable.filterTo(LinkedHashSet()) { position ->
            unproductiveTargetsByPosition[position]?.isNotEmpty() == true
        }
        val unreachable = uncoverable.filterTo(LinkedHashSet()) { position ->
            position !in noProgress
        }
        if (unreachable.isNotEmpty()) {
            context.onPositionsUncoverable(
                unreachable,
                MoverDeferralCause.MOVER_UNREACHABLE,
            )
        }
        if (noProgress.isNotEmpty()) {
            context.onPositionsUncoverable(noProgress, MoverDeferralCause.NO_PROGRESS)
        }
    }

    private fun confirmCompletion(context: MoverTickContext): MoverCommand {
        val pendingRevision = completePendingRevision
        // gateY is plan mode's stale, frozen-at-flag-flip v3 gate (see
        // tickTakeoff's own doc) -- never read it there.
        val gateChanged = !context.planMode && context.gateY != routeGateSignature
        completePending = false
        completePendingRevision = null
        val rebuilt = buildRoute(
            context = context,
            clearBannedTargets = gateChanged,
            confirmEmpty = pendingRevision == context.queueRevision,
        )
        return if (rebuilt) tickCruise(context) else STOP_COMMAND
    }

    // Plan mode routes its very first movement command through the collision-aware A*
    // profile too (beginPlanFlightPath), not just its reactive blocked/stalled recovery --
    // production travel must avoid real obstacles from the start of a leg instead of only
    // after already snagging on one. v3/lane legs are unaffected: they still start as a
    // straight hop, exactly as before.
    private fun prepareCurrentWorkPosition(context: MoverTickContext, cause: String = "prepare"): Boolean {
        val current = route.getOrNull(routeIndex) ?: return false
        moverState = MoverState.CRUISE
        clearFlightPath()
        pathRecoveryAvailable = true
        pathSearchCount = 0
        resetCruiseWatchdog()
        holdMode = null
        chunkWaitTicks = 0
        arrivalHoldTicks = 0
        fedEmptyHoldTicks = 0
        if (context.planMode) {
            return beginPlanFlightPath(context, cause)
        }
        movementTarget = current.target
        pathProbePending = true
        return true
    }

    // A* subsumes vertical detouring entirely. A genuine A* failure (after
    // the one blocked-segment re-search below, when already following a computed
    // path) bans the candidate and rebuilds the route via the existing
    // candidate-retry machinery -- nothing blind-climbs anymore.
    private fun recoverBlockedLeg(context: MoverTickContext): Boolean {
        if (followingFlightPath) {
            if (
                pathSearchCount < MAX_PATH_SEARCHES_PER_LEG &&
                beginFlightPath(context)
            ) {
                return true
            }
            skipCurrentWorkPosition(context)
            return false
        }
        if (pathRecoveryAvailable) {
            pathRecoveryAvailable = false
            if (beginFlightPath(context)) return true
        }
        skipCurrentWorkPosition(context)
        return false
    }

    // Lane-mode counterpart of recoverBlockedLeg -- same A* recovery attempt,
    // but an unrecoverable failure skips to the next segment instead of
    // banning a candidate.
    private fun recoverBlockedLaneLeg(context: MoverTickContext): Boolean {
        if (followingFlightPath) {
            if (
                pathSearchCount < MAX_PATH_SEARCHES_PER_LEG &&
                beginFlightPath(context)
            ) {
                return true
            }
            return skipToNextLaneSegment(context)
        }
        if (pathRecoveryAvailable) {
            pathRecoveryAvailable = false
            if (beginFlightPath(context)) return true
        }
        return skipToNextLaneSegment(context)
    }

    // Plan-mode counterpart of recoverBlockedLeg, covering BOTH triggers a plan leg can hit
    // (the path-probe-blocked case above and the cruise-stall watchdog): every retry
    // re-invokes beginPlanFlightPath (the collision-aware A* profile, falling back to the
    // over-the-top shape on failure) up to MAX_PATH_SEARCHES_PER_LEG times total, same bound
    // v3's own recoverBlockedLeg uses. Exhausting that bound bans the failing stand's own
    // CELL and rebuilds (skipCurrentWorkPosition's plan branch), so the SAME frontier head
    // is retried immediately from its next-best candidate -- exactly v3's recovery
    // semantics. The frontier positions themselves are never excluded from the rebuild:
    // dispatch is strictly plan-ordered, so dropping the head from the input would stall
    // every action behind it for the exclusion's whole lifetime, while a banned stand
    // costs one candidate out of nine and ages out on its own.
    private fun recoverPlanLeg(context: MoverTickContext): Boolean {
        if (followingFlightPath) {
            if (pathSearchCount < MAX_PATH_SEARCHES_PER_LEG && beginPlanFlightPath(context)) {
                return true
            }
            skipCurrentWorkPosition(context)
            return false
        }
        if (pathRecoveryAvailable) {
            pathRecoveryAvailable = false
            if (beginPlanFlightPath(context)) return true
        }
        skipCurrentWorkPosition(context)
        return false
    }

    // Plan-mode counterpart of beginFlightPath: routes through the collision-aware profile
    // (real player-AABB geometry -- indoor obstacles, fence/wall overhangs) instead of the
    // legacy per-cell predicate, since plan-mode travel must avoid actual structure rather
    // than just solid full blocks. Falls back to the over-the-top shape whenever A* cannot
    // even be attempted (missing context) or fails outright (no path / budget / blocked
    // endpoint) -- every branch returns true (a leg was produced) except the shared
    // route-empty guard, matching beginFlightPath's own single-false case.
    private fun beginPlanFlightPath(context: MoverTickContext, cause: String = "prepare"): Boolean {
        val target = baseTarget() ?: run {
            logFlightBeginFailDiagnostics(context, cause)
            return false
        }
        pathSearchCount++
        val stateAt = context.planStateAt
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) {
            return beginOverTheTopLeg(context, target, cause)
        }

        aStarInvocations++
        val searchBounds = localSearchAabb(travelBounds, floorCell(context.playerPos), floorCell(target))
        val profile = FlightPassabilityProfile.collisionAware(searchBounds, stateAt)
        val isEnterableCell = { position: BlockPos -> profile.feetHeightAt(position) != null }
        val startCell = resolveEnterableCell(context.playerPos, PLAYER_HALF_WIDTH, isEnterableCell)
        val goalCell = resolveEnterableCell(target, PLAYER_HALF_WIDTH, isEnterableCell)
        if (startCell == null || goalCell == null) {
            recordAStarFailure(FlightPathFailureReason.START_OR_GOAL_BLOCKED)
            return beginOverTheTopLeg(context, target, cause)
        }
        if (startCell != floorCell(context.playerPos)) aStarResolveStartRescues++
        if (goalCell != floorCell(target)) aStarResolveGoalRescues++

        val result = findFlightPath(
            start = startCell,
            goal = goalCell,
            isPassableCell = context.isPassableCell,
            profile = profile,
        )
        val path = result.path
        val feetHeights = result.feetHeights
        if (path == null || feetHeights == null) {
            recordAStarFailure(result.failureReason)
            return beginOverTheTopLeg(context, target, cause)
        }
        aStarSuccesses++

        clearFlightPath()
        val unsmoothed = if (path.isEmpty()) {
            listOf(target)
        } else {
            path.mapIndexed { index, cell ->
                if (index == path.lastIndex) {
                    // The goal cell's own fitted height, not the raw (un-fitted) work-
                    // position target -- overwriting it with target's height here would
                    // silently discard whatever sub-block fit A* actually landed the leg
                    // on (a slab top, a fence-top hover), reintroducing exactly the
                    // clipping this collision-aware path exists to avoid. Horizontal x/z
                    // still comes from target: the intended stand/evacuation pose, not
                    // necessarily this cell's own center.
                    Vec3(target.x, feetHeights[index], target.z)
                } else {
                    Vec3(cell.x + 0.5, feetHeights[index], cell.z + 0.5)
                }
            }
        }
        val smoothed = smoothFlightPath(
            start = context.playerPos,
            waypoints = unsmoothed,
            // A straight hop between two plan-mode waypoints is only safe to collapse
            // to if the SWEPT VOLUME along it clears real collision geometry --
            // context.pathProbe alone (engine-slide collision resolution) can approve a
            // diagonal that actually clips a corner's shape, since axis-separated
            // sliding can "hug" around it.
            lineClear = { from, to ->
                val probe = context.pathProbe(from, to)
                probe.chunkLoaded && probe.clear &&
                    sweptVolumeCollisionFree(
                        from,
                        to,
                        stateAt,
                        requiredHeight = PLAYER_HEIGHT + SMOOTHING_CLEARANCE_MARGIN,
                        halfWidth = PLAYER_HALF_WIDTH + SMOOTHING_CLEARANCE_MARGIN,
                    )
            },
        )
        smoothingWaypointsIn += unsmoothed.size
        smoothingWaypointsOut += smoothed.size
        pathWaypointQueue.addAll(smoothed)
        setPlanMovementTarget(context, cause, pathWaypointQueue.removeFirst())
        followingFlightPath = true
        pathProbePending = true
        resetCruiseWatchdog()
        return true
    }

    // Blind (no collision awareness) three-waypoint climb-over used whenever the
    // collision-aware A* cannot produce a leg: fly straight up to travelY, across, then
    // straight down onto the target -- clearing structure by altitude alone. travelY is the
    // highest of the current working set's own targets, the stand's feet, and the player's
    // feet, plus a fixed clearance, so the cross leg passes over everything currently being
    // routed to, not just this one leg's own two endpoints. Waypoints that would coincide
    // with an endpoint already at travelY (or already in the target's own column) collapse
    // away instead of adding a redundant hop.
    private fun beginOverTheTopLeg(context: MoverTickContext, target: Vec3, cause: String = "prepare"): Boolean {
        val playerFeetY = floor(context.playerPos.y)
        val standFeetY = floor(target.y)
        val workingSetMaxY = planWorkingSetMaxY(context)?.toDouble() ?: standFeetY
        val travelY = maxOf(workingSetMaxY, standFeetY, playerFeetY) + OVER_THE_TOP_CLEARANCE

        val waypoints = ArrayList<Vec3>(3)
        val ascend = Vec3(context.playerPos.x, travelY, context.playerPos.z)
        if (ascend.y != context.playerPos.y) waypoints.add(ascend)
        val cross = Vec3(target.x, travelY, target.z)
        if (cross.x != ascend.x || cross.z != ascend.z) waypoints.add(cross)
        waypoints.add(target)

        clearFlightPath()
        pathWaypointQueue.addAll(waypoints)
        setPlanMovementTarget(context, cause, pathWaypointQueue.removeFirst())
        followingFlightPath = true
        pathProbePending = true
        resetCruiseWatchdog()
        return true
    }

    // The highest Y among the frontier positions this rebuild actually cares about --
    // waitingForReach (the discrete-candidate path) and columnBlocked (the evacuation
    // path) -- so a blind climb-over clears every target currently being routed to, not
    // merely this one leg's own endpoints. Null when the frontier itself carries nothing
    // (an evacuation leg with an already-empty columnBlocked, structurally unreachable
    // through buildPlanEvacuationRoute's own guard, but kept null-safe here regardless).
    private fun planWorkingSetMaxY(context: MoverTickContext): Int? {
        val waitingMaxY = context.planFrontier?.waitingForReach.orEmpty().maxOfOrNull { target -> target.pos.y }
        val blockedMaxY = context.planFrontier?.columnBlocked.orEmpty().maxOfOrNull { position -> position.y }
        return maxOf(waitingMaxY ?: Int.MIN_VALUE, blockedMaxY ?: Int.MIN_VALUE).takeIf { value ->
            value != Int.MIN_VALUE
        }
    }

    private fun travelAabb(bounds: Pair<BlockPos, BlockPos>): AABB {
        val (min, max) = bounds
        return AABB(
            min.x.toDouble(),
            min.y.toDouble(),
            min.z.toDouble(),
            (max.x + 1).toDouble(),
            (max.y + 1).toDouble(),
            (max.z + 1).toDouble(),
        )
    }

    // A plan leg's own A* search space: the endpoint pair's bounding box inflated by
    // LOCAL_SEARCH_MARGIN on every axis, intersected with the global travel bounds --
    // never the global bounds directly. The global travel volume routinely dwarfs
    // findFlightPath's own MAX_AXIS_CELLS/MAX_VOLUME_CELLS caps (an entire schematic's
    // footprint), so handing it straight to the profile would fail every leg with
    // VOLUME_CLAMPED before a single cell is even searched -- collision A* would be
    // silently disabled at that scale regardless of how short the actual leg is. Each
    // axis clamps its own inflated max to at least its own clamped min, so an endpoint
    // that sits outside the travel volume degenerates to a thin (never inverted/negative-
    // sized) box on that axis instead of producing an invalid SearchBounds.
    private fun localSearchAabb(travelBounds: Pair<BlockPos, BlockPos>, first: BlockPos, second: BlockPos): AABB {
        val (travelMin, travelMax) = travelBounds
        val minX = maxOf(minOf(first.x, second.x) - LOCAL_SEARCH_MARGIN, travelMin.x)
        val minY = maxOf(minOf(first.y, second.y) - LOCAL_SEARCH_MARGIN, travelMin.y)
        val minZ = maxOf(minOf(first.z, second.z) - LOCAL_SEARCH_MARGIN, travelMin.z)
        val maxX = minOf(maxOf(first.x, second.x) + LOCAL_SEARCH_MARGIN, travelMax.x).coerceAtLeast(minX)
        val maxY = minOf(maxOf(first.y, second.y) + LOCAL_SEARCH_MARGIN, travelMax.y).coerceAtLeast(minY)
        val maxZ = minOf(maxOf(first.z, second.z) + LOCAL_SEARCH_MARGIN, travelMax.z).coerceAtLeast(minZ)
        return AABB(
            minX.toDouble(),
            minY.toDouble(),
            minZ.toDouble(),
            (maxX + 1).toDouble(),
            (maxY + 1).toDouble(),
            (maxZ + 1).toDouble(),
        )
    }

    private fun beginFlightPath(context: MoverTickContext): Boolean {
        val target = baseTarget() ?: return false
        pathSearchCount++
        aStarInvocations++
        // Resolve both endpoints against the player's actual AABB instead
        // of a naive floor -- see resolveEnterableCell's doc for why the floored cell
        // can be a solid neighbor the AABB merely touches (pressed against a wall,
        // sitting on a cell boundary, hovering at a fractional height). A resolved-
        // null endpoint is recorded as
        // START_OR_GOAL_BLOCKED without ever calling findFlightPath.
        val isEnterableCell = { position: BlockPos ->
            context.isPassableCell(position) && context.isPassableCell(position.above())
        }
        val startCell = resolveEnterableCell(context.playerPos, PLAYER_HALF_WIDTH, isEnterableCell)
        val goalCell = resolveEnterableCell(target, PLAYER_HALF_WIDTH, isEnterableCell)
        if (startCell == null || goalCell == null) {
            recordAStarFailure(FlightPathFailureReason.START_OR_GOAL_BLOCKED)
            return false
        }
        if (startCell != floorCell(context.playerPos)) aStarResolveStartRescues++
        if (goalCell != floorCell(target)) aStarResolveGoalRescues++

        val result = findFlightPath(
            start = startCell,
            goal = goalCell,
            isPassableCell = context.isPassableCell,
        )
        val path = result.path
        if (path == null) {
            recordAStarFailure(result.failureReason)
            return false
        }
        aStarSuccesses++

        clearFlightPath()
        val unsmoothed = if (path.isEmpty()) {
            listOf(target)
        } else {
            path.mapIndexed { index, cell ->
                if (index == path.lastIndex) {
                    target
                } else {
                    Vec3(
                        cell.x + 0.5,
                        cell.y.toDouble(),
                        cell.z + 0.5,
                    )
                }
            }
        }
        val smoothed = smoothFlightPath(
            start = context.playerPos,
            waypoints = unsmoothed,
            lineClear = { from, to ->
                val probe = context.pathProbe(from, to)
                probe.chunkLoaded && probe.clear
            },
        )
        smoothingWaypointsIn += unsmoothed.size
        smoothingWaypointsOut += smoothed.size
        pathWaypointQueue.addAll(smoothed)
        movementTarget = pathWaypointQueue.removeFirst()
        followingFlightPath = true
        pathProbePending = true
        resetCruiseWatchdog()
        return true
    }

    private fun recordAStarFailure(reason: FlightPathFailureReason?): Unit {
        when (reason) {
            FlightPathFailureReason.VOLUME_CLAMPED -> aStarFailVolumeClamped++
            FlightPathFailureReason.BUDGET_EXHAUSTED -> aStarFailBudgetExhausted++
            FlightPathFailureReason.NO_PATH -> aStarFailNoPath++
            FlightPathFailureReason.START_OR_GOAL_BLOCKED -> aStarFailStartOrGoalBlocked++
            null -> Unit
        }
    }

    private fun clearFlightPath(): Unit {
        pathWaypointQueue.clear()
        followingFlightPath = false
    }

    private fun advanceRoute(context: MoverTickContext): Boolean {
        val previousIndex = routeIndex
        routeIndex++
        if (routeIndex != previousIndex) {
            flightLossCount = 0
        }
        if (routeIndex >= route.size) {
            return finishRoute(context)
        }
        return prepareCurrentWorkPosition(context)
    }

    private fun finishRoute(context: MoverTickContext): Boolean {
        val progressed = routeStartRevision?.let { revision ->
            context.queueRevision != revision
        } == true
        // gateY is plan mode's stale, frozen-at-flag-flip v3 gate (see
        // tickTakeoff's own doc) -- never read it there.
        val gateChanged = !context.planMode && context.gateY != routeGateSignature
        if (progressed || gateChanged) {
            return buildRoute(context, clearBannedTargets = gateChanged)
        }
        markCompletePending(context, cause = "finish-route")
        return false
    }

    private fun enterArrivalHold(context: MoverTickContext): Unit {
        moverState = MoverState.HOLD
        holdMode = HoldMode.ARRIVAL
        resetCruiseWatchdog()
        observedQueueRevision = context.queueRevision
        arrivalHoldTicks = 0
        fedEmptyHoldTicks = 0
        // The work position this hold is arriving at has genuinely been reached -- any
        // corrections that landed against it while flying there are no longer evidence
        // of an unresolvable desync for THIS stand; a later correction against it (or
        // its replacement) starts a fresh count, mirroring the age-based reset above.
        planCorrectionWorkPositionCell = null
        planCorrectionCountForWorkPosition = 0
    }

    private fun enterChunkWait(): Unit {
        moverState = MoverState.HOLD
        holdMode = HoldMode.CHUNK_WAIT
        resetCruiseWatchdog()
        chunkWaitTicks = 1
    }

    private fun cruiseStalled(playerPos: Vec3): Boolean {
        val previous = lastCruisePlayerPos
        lastCruisePlayerPos = playerPos
        if (previous == null) return false

        if (previous.distanceToSqr(playerPos) < MIN_CRUISE_MOVEMENT_SQUARED) {
            stationaryCruiseTicks++
        } else {
            stationaryCruiseTicks = 0
        }
        return stationaryCruiseTicks >= CRUISE_STALL_TICKS
    }

    private fun resetCruiseWatchdog(): Unit {
        lastCruisePlayerPos = null
        stationaryCruiseTicks = 0
    }

    private fun recoverFlightIfNeeded(context: MoverTickContext): MoverCommand? {
        if (context.flying) return null

        flightLossCount++
        if (flightLossCount >= MAX_FLIGHT_LOSSES_PER_WORK_POSITION) {
            return if (laneModeActive) {
                skipToNextLaneSegment(context)
                STOP_COMMAND
            } else {
                skipCurrentWorkPosition(context)
            }
        }

        moverState = MoverState.TAKEOFF
        takeoffTicks = 0
        return tickTakeoff(context)
    }

    // Candidate-level abandonment bans only the failed target and immediately replans.
    // Covered blocks are deferred later only if the planner reports that every target
    // is unusable; this lets the same anchor retry its next-best candidate first. Plan
    // mode bans by CELL (see planBannedCells' own doc), read from planRouteBanCells
    // (the un-fitted identity -- see standBanCell's own doc), never re-derived from the
    // fitted route entry.
    private fun skipCurrentWorkPosition(context: MoverTickContext): MoverCommand {
        val current = route.getOrNull(routeIndex) ?: return stopForDrain(context)
        context.onWorkPositionAbandoned(current.covered)
        if (context.planMode) {
            val banCell = planRouteBanCells.getOrNull(routeIndex) ?: standBanCell(current.target)
            planBannedCells[banCell] = moverTickCounter
            logPlanBanAdd("skip", banCell)
        } else {
            bannedTargets.add(current.target)
        }
        buildRoute(context)
        return STOP_COMMAND
    }

    // A fallback with no revision change is not progress. Report it before rebuilding,
    // ban that target, and bound retry thrash by deferring a covered position after its
    // second distinct unproductive target. If the first ban exhausts all candidates,
    // reportUncoverable assigns NO_PROGRESS immediately instead.
    private fun handleUnproductiveWorkPosition(context: MoverTickContext): MoverCommand {
        val current = route.getOrNull(routeIndex) ?: return stopForDrain(context)
        val deferPositions = LinkedHashSet<BlockPos>()
        for (position in current.covered) {
            val immutable = position.immutable()
            val targets = unproductiveTargetsByPosition.getOrPut(immutable) { LinkedHashSet() }
            if (targets.add(current.target) && targets.size >= UNPRODUCTIVE_TARGET_LIMIT) {
                deferPositions.add(immutable)
            }
        }
        context.onWorkPositionUnproductive(current.covered, deferPositions)
        if (context.planMode) {
            val banCell = planRouteBanCells.getOrNull(routeIndex) ?: standBanCell(current.target)
            planBannedCells[banCell] = moverTickCounter
            logPlanBanAdd("unproductive", banCell)
        } else {
            bannedTargets.add(current.target)
        }
        buildRoute(context)
        return STOP_COMMAND
    }

    private fun movementCommand(from: Vec3, to: Vec3, speedMagnitude: Double): MoverCommand {
        val deltaX = to.x - from.x
        val deltaY = to.y - from.y
        val deltaZ = to.z - from.z
        val horizontalLength = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val unitX = if (horizontalLength > 0.0) deltaX / horizontalLength else 0.0
        val unitZ = if (horizontalLength > 0.0) deltaZ / horizontalLength else 0.0
        val vertical = when {
            deltaY > VERTICAL_DEAD_ZONE -> 1
            deltaY < -VERTICAL_DEAD_ZONE -> -1
            else -> 0
        }
        return MoverCommand(
            jump = false,
            enableFlight = false,
            horizontalX = unitX * speedMagnitude,
            horizontalZ = unitZ * speedMagnitude,
            vertical = vertical,
            stopMovement = false,
        )
    }

    private fun immediateAbortReason(context: MoverTickContext): MoverAbortReason? {
        return when {
            context.manualInput -> MoverAbortReason.MANUAL_INPUT
            context.guiOpen -> MoverAbortReason.GUI_OPEN
            context.hurt -> MoverAbortReason.DAMAGED
            // Plan mode handles its own correction upstream in tick() (resync below the
            // rolling-window threshold, abort above it) -- never here, since v3's blanket
            // any-correction-aborts response stays byte-identical for v3 only.
            context.correctionReceived && !context.planMode -> MoverAbortReason.SERVER_CORRECTION
            !context.isCreative -> MoverAbortReason.GAMEMODE_LOST
            activeSessionKey?.matches(context.sessionKey) == false ->
                MoverAbortReason.SESSION_CHANGED
            else -> null
        }
    }

    // Rolling correction-count window (plan mode only): an isolated server position
    // nudge is resynced rather than aborted, but more than PLAN_CORRECTION_ABORT_THRESHOLD
    // corrections within PLAN_CORRECTION_WINDOW_TICKS is treated as a genuine desync and
    // still aborts, exactly like v3's own any-correction response. That global window is
    // the outer safety net; per-leg escape below fires well before it normally would.
    //
    // Per-leg escape: resyncing the identical leg to the identical stand is only useful
    // once -- a SECOND correction against the SAME active work position means resyncing
    // alone did not fix whatever keeps desyncing this stand, so it is treated exactly
    // like a cruise stall on that leg: ban the stand cell and rebuild through the
    // existing candidate-retry machinery (skipCurrentWorkPosition), landing on an
    // alternative stand instead of resyncing the identical leg forever. The count resets
    // the moment the active work position itself changes (a different stand cell is now
    // current) -- see planCorrectionWorkPositionCell's own doc.
    private fun handlePlanCorrection(context: MoverTickContext, previousPlayerPos: Vec3?): MoverAbortReason? {
        planCorrectionTicks.addLast(moverTickCounter)
        while (
            planCorrectionTicks.isNotEmpty() &&
            moverTickCounter - planCorrectionTicks.first() > PLAN_CORRECTION_WINDOW_TICKS
        ) {
            planCorrectionTicks.removeFirst()
        }
        logPlanCorrectionDiagnostics(context, previousPlayerPos)
        if (planCorrectionTicks.size > PLAN_CORRECTION_ABORT_THRESHOLD) {
            return MoverAbortReason.SERVER_CORRECTION
        }

        val current = route.getOrNull(routeIndex)
        val activeWorkPositionCell = current?.let { position ->
            planRouteBanCells.getOrNull(routeIndex) ?: standBanCell(position.target)
        }
        val staleCorrectionHistory = moverTickCounter - planCorrectionWorkPositionTick > PLAN_BAN_MAX_AGE_TICKS
        if (activeWorkPositionCell != planCorrectionWorkPositionCell || staleCorrectionHistory) {
            planCorrectionWorkPositionCell = activeWorkPositionCell
            planCorrectionCountForWorkPosition = 0
        }
        if (activeWorkPositionCell != null) {
            planCorrectionCountForWorkPosition++
            planCorrectionWorkPositionTick = moverTickCounter
            if (planCorrectionCountForWorkPosition >= PLAN_LEG_CORRECTION_ESCAPE_THRESHOLD) {
                planCorrectionWorkPositionCell = null
                planCorrectionCountForWorkPosition = 0
                // Bans/skips exactly like skipCurrentWorkPosition, but the rebuild
                // itself is deferred to the next tick -- see planCorrectionRebuildPending's
                // own doc for why a synchronous rebuild here is unsafe.
                context.onWorkPositionAbandoned(current.covered)
                planBannedCells[activeWorkPositionCell] = moverTickCounter
                logPlanBanAdd("correction-escape", activeWorkPositionCell)
                planCorrectionRebuildPending = true
                return null
            }
        }

        resyncPlanLegAfterCorrection(context)
        return null
    }

    // One INFO line per correction event while plan mode is active: client position
    // (this tick's own previous observation -- the last position this state machine
    // itself acted on, before the server's correction lands) vs the corrected position
    // (context.playerPos, already reflecting the correction by the time tick() reads
    // it) and the waypoint the mover was steering toward. Pins the remaining causes if
    // corrections persist in the field.
    private fun logPlanCorrectionDiagnostics(context: MoverTickContext, previousPlayerPos: Vec3?): Unit {
        val delta = if (previousPlayerPos != null) {
            context.playerPos.subtract(previousPlayerPos)
        } else {
            Vec3.ZERO
        }
        com.mojang.logging.LogUtils.getLogger().info(
            "C3MOV plan-correction t={} delta=({}, {}, {}) waypoint={}",
            moverTickCounter,
            String.format("%.3f", delta.x),
            String.format("%.3f", delta.y),
            String.format("%.3f", delta.z),
            movementTarget,
        )
    }

    // Accepts the server's corrected position as truth: drops the in-flight LEG (the
    // A* waypoint queue toward the current work position) so the next leg is planned
    // fresh from context.playerPos, exactly like prepareCurrentWorkPosition already does
    // for a brand new work position. The work position itself (route[routeIndex], its
    // covered targets) is untouched -- a position correction does not invalidate stand
    // selection, so this must not re-run candidate scoring (see
    // checkCurrentPlanWorkPosition's own doc).
    private fun resyncPlanLegAfterCorrection(context: MoverTickContext): Unit {
        if (moverState == MoverState.CRUISE) {
            prepareCurrentWorkPosition(context)
        } else {
            clearFlightPath()
            pathProbePending = false
            movementTarget = null
        }
    }

    private fun baseTarget(): Vec3? {
        return if (laneModeActive) {
            laneWaypoints.getOrNull(laneWaypointIndex)
        } else {
            route.getOrNull(routeIndex)?.target
        }
    }

    private fun stopForDrain(context: MoverTickContext): MoverCommand {
        markCompletePending(context, cause = "stop-for-drain")
        return STOP_COMMAND
    }

    private fun markCompletePending(context: MoverTickContext, cause: String, releaseBranch: String? = null): Unit {
        logMarkCompletePendingDiagnostics(context, cause, releaseBranch)
        completePending = true
        completePendingRevision = context.queueRevision
        moverState = MoverState.HOLD
        setPlanMovementTarget(context, "mark-complete-pending", null)
        route = emptyList()
        planRouteBanCells = emptyList()
        routeIndex = 0
        pathProbePending = false
        clearFlightPath()
        pathRecoveryAvailable = false
        pathSearchCount = 0
        holdMode = null
        resetCruiseWatchdog()
        laneModeActive = false
        laneWaypoints = emptyList()
        laneWaypointIndex = 0
    }

    private fun abort(reason: MoverAbortReason): Unit {
        moverState = MoverState.ABORTED
        moverAbortReason = reason
        clearCandidateRetryState()
    }

    // The sole COMPLETE transition. Callers reach it only from a fresh-tick empty-route
    // confirmation after context.canComplete has enforced the printer invariant.
    private fun complete(): MoverCommand {
        moverState = MoverState.COMPLETE
        moverAbortReason = null
        movementTarget = null
        flightLossCount = 0
        route = emptyList()
        planRouteBanCells = emptyList()
        routeIndex = 0
        routeStartRevision = null
        routeGateSignature = null
        frozenLayerMembers = emptySet()
        clearFlightPath()
        pathRecoveryAvailable = false
        pathSearchCount = 0
        holdMode = null
        resetCruiseWatchdog()
        clearCandidateRetryState()
        laneModeActive = false
        laneWaypoints = emptyList()
        laneWaypointIndex = 0
        lanePassStartRevision = null
        return STOP_COMMAND
    }

    private fun clearCandidateRetryState(): Unit {
        completePending = false
        completePendingRevision = null
        bannedTargets.clear()
        unproductiveTargetsByPosition.clear()
        planBannedCells.clear()
    }

    private fun resetRun(): Unit {
        activeSessionKey = null
        observedQueueRevision = null
        takeoffTicks = 0
        flightLossCount = 0
        route = emptyList()
        planRouteBanCells = emptyList()
        routeIndex = 0
        routeStartRevision = null
        routeGateSignature = null
        frozenLayerMembers = emptySet()
        remainingMissing = 0
        movementTarget = null
        pathProbePending = false
        clearFlightPath()
        pathRecoveryAvailable = false
        pathSearchCount = 0
        aStarInvocations = 0
        aStarSuccesses = 0
        aStarFailVolumeClamped = 0
        aStarFailBudgetExhausted = 0
        aStarFailNoPath = 0
        aStarFailStartOrGoalBlocked = 0
        aStarResolveStartRescues = 0
        aStarResolveGoalRescues = 0
        smoothingWaypointsIn = 0
        smoothingWaypointsOut = 0
        resetCruiseWatchdog()
        holdMode = null
        chunkWaitTicks = 0
        arrivalHoldTicks = 0
        fedEmptyHoldTicks = 0
        lastFinalSweepRevision = null
        clearCandidateRetryState()
        laneModeActive = false
        laneWaypoints = emptyList()
        laneWaypointIndex = 0
        lanePassStartRevision = null
        lanePasses = 0
        laneSegmentsTotal = 0
        laneTicksTotal = 0
        trappedPreviousPos = null
        trappedStationaryTicks = 0
        planFinalAtArm = false
        planRouteWaitingPositions = emptySet()
        planRouteColumnBlockedPositions = emptySet()
        lastEmptyPlanRoutePositions = null
        lastEmptyPlanRouteColumnBlockedPositions = emptySet()
        lastEmptyPlanRouteTick = 0L
        // previousPlanMode is deliberately NOT cleared here: the mode itself did not
        // change across this restart, so resetting it would make the next tick's
        // planModeChanged check see a spurious flip and fire an unnecessary
        // mode-switch reset.
        // An abort+restart must not carry over the previous run's correction history
        // either -- a fresh run starts with a clean rolling window.
        planCorrectionTicks.clear()
        planCorrectionWorkPositionCell = null
        planCorrectionCountForWorkPosition = 0
        planCorrectionWorkPositionTick = 0L
        planCorrectionRebuildPending = false
    }

    private fun MoverState.isActive(): Boolean {
        return this == MoverState.TAKEOFF ||
            this == MoverState.CRUISE ||
            this == MoverState.HOLD
    }

    private enum class HoldMode {
        ARRIVAL,
        CHUNK_WAIT,
    }

    private companion object {
        private const val ARRIVAL_DISTANCE_SQUARED: Double = 0.25
        private const val VERTICAL_DEAD_ZONE: Double = 0.3
        private const val TAKEOFF_TIMEOUT_TICKS: Int = 100
        private const val MAX_FLIGHT_LOSSES_PER_WORK_POSITION: Int = 3
        private const val UNPRODUCTIVE_TARGET_LIMIT: Int = 2
        private const val FED_EMPTY_ADVANCE_TICKS: Int = 2
        private const val HOLD_HARD_CAP_TICKS: Int = 200
        private const val CHUNK_WAIT_TIMEOUT_TICKS: Int = 200
        private const val CRUISE_STALL_TICKS: Int = 100
        private const val MIN_CRUISE_MOVEMENT_SQUARED: Double = 0.0001
        private const val MAX_PATH_SEARCHES_PER_LEG: Int = 2
        private const val LANE_SPEED_STOP: Double = 0.0
        private const val LANE_SPEED_SLOW: Double = 0.3
        private const val LANE_SPEED_FULL: Double = 1.0
        // Worst-case turnaround for one placement to reach a final verdict
        // (PrinterAttemptTracker.DEADLINE_TICKS) plus the shortest gap before the next
        // submission cycle can start (PrinterRateLimiter.DEFAULT_INTERVAL_TICKS): an
        // accepted placement inside this many ticks means the backlog at the current
        // lane position is still actively being drained.
        private const val RECENT_PROGRESS_WINDOW_TICKS: Long =
            PrinterAttemptTracker.DEADLINE_TICKS + PrinterRateLimiter.DEFAULT_INTERVAL_TICKS
        private const val TRAPPED_MOVEMENT_SQUARED: Double = 0.25
        private const val TRAPPED_STATIONARY_TICKS: Int = 100
        // Fixed clearance added above the working set/stand/player feet ceiling for the
        // over-the-top fallback leg (see beginOverTheTopLeg).
        private const val OVER_THE_TOP_CLEARANCE: Double = 2.0
        // Margin inflating a plan leg's own endpoint bounding box into its A* search
        // space (see localSearchAabb) -- generous enough for the detours a real leg
        // actually needs without approaching the search caps at typical leg lengths.
        private const val LOCAL_SEARCH_MARGIN: Int = 16
        private const val PLAN_BAN_MAX_AGE_TICKS: Long = 200L
        private const val PLAN_EMPTY_ROUTE_REBUILD_TICKS: Long = 20L
        private const val PLAN_EVACUATION_RING_MIN_RADIUS: Int = 2
        private const val PLAN_EVACUATION_RING_MAX_RADIUS: Int = 3
        // Extra clearance a STAND needs beyond the ordinary PLAYER_HEIGHT a passing-
        // through leg requires -- a 1.8-to-1.99-tall gap is flyable but too tight to
        // actually work from.
        private const val STAND_HEADROOM_CLEARANCE: Double = 0.2

        // Rolling window (see handlePlanCorrection): more than this many corrections
        // within this many ticks is a genuine desync, not integrated-server rounding
        // noise around a fitted hover height.
        private const val PLAN_CORRECTION_WINDOW_TICKS: Long = 100L
        private const val PLAN_CORRECTION_ABORT_THRESHOLD: Int = 3
        // Corrections landing against the SAME active work position, in a row, before
        // handlePlanCorrection gives up on resyncing and bans the stand instead (see its
        // own doc).
        private const val PLAN_LEG_CORRECTION_ESCAPE_THRESHOLD: Int = 2

        // Corner deceleration (planCornerSpeedMagnitude): final approach distance the
        // slowdown applies over, the speed factor applied within it, and the incoming/
        // outgoing direction cosine below which a turn counts as a "corner" rather than
        // noise -- 0.94 is about 20 degrees, comfortably below a grid path's typical
        // 90-degree turns while ignoring near-straight direction jitter.
        private const val CORNER_DECELERATION_DISTANCE: Double = 1.5
        private const val CORNER_DECELERATION_FACTOR: Double = 0.5
        private const val CORNER_TURN_COS_THRESHOLD: Double = 0.94
        private const val COLLISION_EPSILON_SQUARED: Double = 1.0E-9

        // Minimum mover-tick gap between two log lines of the same diagnostic category
        // (see the lastXDiagLogTick fields) -- keeps a churning branch from spamming
        // latest.log while still surfacing the condition regularly enough to observe live.
        private const val PLAN_DIAG_LOG_INTERVAL_TICKS: Long = 100L

        private val IDLE_COMMAND = MoverCommand(
            jump = false,
            enableFlight = false,
            horizontalX = 0.0,
            horizontalZ = 0.0,
            vertical = 0,
            stopMovement = false,
        )
        private val JUMP_COMMAND = IDLE_COMMAND.copy(jump = true)
        private val ENABLE_FLIGHT_COMMAND = IDLE_COMMAND.copy(enableFlight = true)
        private val STOP_COMMAND = IDLE_COMMAND.copy(stopMovement = true)
    }
}
