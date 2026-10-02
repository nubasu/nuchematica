package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.PrinterAttemptTracker
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.PrinterRateLimiter
import com.nubasu.nuchematica.printer.availableSupportHitPoints
import com.nubasu.nuchematica.printer.isInPlayerColumn
import com.nubasu.nuchematica.printer.isPlacementBlockedByPlayer
import com.nubasu.nuchematica.printer.placementCollisionReservationState
import com.nubasu.nuchematica.printer.potentialSupportHitPoints
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal fun planReachHitPoints(
    pos: BlockPos,
    expected: BlockState,
    stateAt: ((BlockPos) -> BlockState)?,
    breakTargets: Set<BlockPos>,
): List<Vec3> {
    if (pos in breakTargets) return listOf(Vec3.atCenterOf(pos))
    return if (stateAt != null) {
        availableSupportHitPoints(pos, expected, stateAt)
    } else {
        potentialSupportHitPoints(pos, expected)
    }
}

internal fun planStandMeetsLayerSafety(
    standFeetY: Int,
    covered: List<BlockPos>,
    breakTargets: Set<BlockPos>,
): Boolean {
    val highestPlacementY = covered.asSequence()
        .filterNot { position -> position in breakTargets }
        .maxOfOrNull { position -> position.y }
    return highestPlacementY == null || standFeetY >= highestPlacementY
}

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
    private var planSegmentStart: Vec3? = null
    private var planLegRevision: Long? = null
    private var planFlatWaypointSegment: Boolean = false
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
    private var aStarResolveStartRescues: Int = 0
    private var aStarResolveGoalRescues: Int = 0
    private var smoothingWaypointsIn: Int = 0
    private var smoothingWaypointsOut: Int = 0
    private var routeBuilds: Int = 0
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

    private val planBannedCells: MutableMap<BlockPos, Long> = LinkedHashMap()

    private var planRouteBanCells: List<BlockPos> = emptyList()

    private var laneModeActive: Boolean = false
    private var laneWaypoints: List<Vec3> = emptyList()
    private var laneWaypointIndex: Int = 0
    private var lanePassStartRevision: Long? = null
    private var lanePasses: Int = 0
    private var laneSegmentsTotal: Int = 0
    private var laneTicksTotal: Int = 0

    private var trappedPreviousPos: Vec3? = null
    private var trappedStationaryTicks: Int = 0

    private var previousPlanMode: Boolean = false
    private var moverTickCounter: Long = 0L
    private var planFinalAtArm: Boolean = false
    private var planRouteWaitingPositions: List<BlockPos> = emptyList()
    private var planRouteColumnBlockedPositions: Set<BlockPos> = emptySet()
    private var planRouteInFlightPositions: Set<BlockPos> = emptySet()
    private var lastEmptyPlanRoutePositions: List<BlockPos>? = null
    private var lastEmptyPlanRouteColumnBlockedPositions: Set<BlockPos> = emptySet()
    private var lastEmptyPlanRouteTick: Long = 0L
    private var lastObservedPlayerPos: Vec3? = null
    private val planCorrectionTicks: ArrayDeque<Long> = ArrayDeque()
    private var planCorrectionWorkPositionCell: BlockPos? = null
    private var planCorrectionCountForWorkPosition: Int = 0
    private var planCorrectionWorkPositionTick: Long = 0L
    private var planCorrectionRebuildPending: Boolean = false

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

        val planModeChanged = context.planMode != previousPlanMode
        previousPlanMode = context.planMode
        if (planModeChanged) {
            resetRouteStateForModeSwitch()
            if (moverState != MoverState.TAKEOFF) {
                if (!buildRoute(context, clearBannedTargets = true)) return STOP_COMMAND
            }
        }

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

        if (completePending) return confirmCompletion(context)

        if (
            !context.planMode &&
            routeStartRevision != null &&
            context.gateY != routeGateSignature &&
            moverState != MoverState.TAKEOFF
        ) {
            if (!buildRoute(context, clearBannedTargets = true)) return STOP_COMMAND
        }

        if (context.planMode && moverState == MoverState.CRUISE && routeStartRevision != null) {
            val currentPlanWaiting = context.planFrontier?.waitingForReach.orEmpty()
                .map { target -> target.pos.immutable() }
            val currentPlanColumnBlocked = context.planFrontier?.columnBlocked.orEmpty()
                .mapTo(LinkedHashSet<BlockPos>()) { pos -> pos.immutable() }
            val currentPlanInFlight = context.planFrontier?.inFlight.orEmpty()
                .mapTo(LinkedHashSet<BlockPos>()) { pos -> pos.immutable() }
            if (
                (
                    context.queueRevision != planLegRevision ||
                        currentPlanWaiting != planRouteWaitingPositions ||
                        currentPlanColumnBlocked != planRouteColumnBlockedPositions ||
                        currentPlanInFlight != planRouteInFlightPositions
                    ) &&
                !refreshPlanCruiseRoute(context)
            ) {
                return STOP_COMMAND
            }
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
            routeBuilds = routeBuilds,
        )
    }

    internal fun laneTelemetry(): MoverLaneTelemetry {
        return MoverLaneTelemetry(
            passes = lanePasses,
            segments = laneSegmentsTotal,
            laneTicks = laneTicksTotal,
        )
    }

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

    private fun planModeIntentionalHold(context: MoverTickContext): Boolean {
        if (!context.planMode) return false
        if (holdMode == HoldMode.ARRIVAL) return true
        if (!route.isEmpty()) return false
        val emptyFrontier = context.planFrontier?.waitingForReach.isNullOrEmpty() &&
            context.planFrontier?.columnBlocked.isNullOrEmpty()
        if (emptyFrontier) return true
        return lastEmptyPlanRoutePositions != null
    }

    private fun tickTakeoff(context: MoverTickContext): MoverCommand {
        if (context.flying) {
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

        var resetHorizontalVelocity = false
        while (true) {
            val target = movementTarget ?: return stopForDrain(context)
            val insideArrivalRadius = context.playerPos.distanceToSqr(target) <= ARRIVAL_DISTANCE_SQUARED
            if (pathProbePending && !insideArrivalRadius) {
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
            var tightenPlanWaypoint = false
            var precisePlanConnectorApproach = false
            val arrivedAtCurrentTarget =
                context.playerPos.distanceToSqr(currentTarget) <= ARRIVAL_DISTANCE_SQUARED
            val arrivalStillBlocksPlacement =
                arrivedAtCurrentTarget && planFinalArrivalStillBlocksCoveredPosition(context)
            if (arrivedAtCurrentTarget && !arrivalStillBlocksPlacement) {
                if (followingFlightPath) {
                    val nextWaypoint = pathWaypointQueue.peekFirst()
                    if (nextWaypoint != null) {
                        val nextProbe = if (context.planMode) context.pathProbe(context.playerPos, nextWaypoint) else null
                        val reservedSegmentClear = if (nextProbe == null) {
                            true
                        } else {
                            val stateAt = planCollisionStateAt(context)
                            stateAt == null || sweptVolumeCollisionFree(
                                context.playerPos,
                                nextWaypoint,
                                stateAt,
                            )
                        }
                        val serverHeadroomSegmentClear = if (nextProbe == null) {
                            true
                        } else {
                            val stateAt = planCollisionStateAt(context)
                            stateAt == null || sweptVolumeCollisionFree(
                                context.playerPos,
                                nextWaypoint,
                                stateAt,
                                requiredHeight = PLAYER_HEIGHT + PLAN_SERVER_HEADROOM_MARGIN,
                                halfWidth = PLAYER_HALF_WIDTH,
                            )
                        }
                        precisePlanConnectorApproach =
                            pathProbePending &&
                            nextProbe != null &&
                                nextProbe.chunkLoaded &&
                                nextProbe.clear &&
                                reservedSegmentClear &&
                                !serverHeadroomSegmentClear &&
                                abs(context.playerPos.y - currentTarget.y) >
                                PLAN_PRECISE_CONNECTOR_VERTICAL_DEAD_ZONE
                        if (
                            nextProbe != null &&
                            (
                                    !nextProbe.chunkLoaded ||
                                    !nextProbe.clear ||
                                    !reservedSegmentClear ||
                                    precisePlanConnectorApproach
                                )
                        ) {
                            tightenPlanWaypoint = true
                        } else {
                            val resetForSharpTurn = context.planMode &&
                                isSharpHorizontalWaypointTurn(currentTarget, nextWaypoint)
                            pathWaypointQueue.removeFirst()
                            setPlanMovementTarget(context, "waypoint-advance", nextWaypoint)
                            planFlatWaypointSegment = context.planMode &&
                                abs(nextWaypoint.y - currentTarget.y) <= PLAN_SEGMENT_HEIGHT_EPSILON
                            // Reuse this segment probe instead of repeating it in the same loop.
                            pathProbePending = nextProbe == null
                            resetCruiseWatchdog()
                            resetHorizontalVelocity = resetHorizontalVelocity || resetForSharpTurn
                            continue
                        }
                    } else {
                        followingFlightPath = false
                        if (laneModeActive) {
                            if (!advanceLaneWaypointOrFinish(context)) return STOP_COMMAND
                            continue
                        }
                        enterArrivalHold(context)
                        return STOP_COMMAND
                    }
                } else if (laneModeActive) {
                    if (!advanceLaneWaypointOrFinish(context)) return STOP_COMMAND
                    continue
                } else {
                    enterArrivalHold(context)
                    return STOP_COMMAND
                }
            }
            val speedMagnitude = when {
                laneModeActive -> laneSpeedMagnitude(context)
                context.planMode -> planCornerSpeedMagnitude(context, currentTarget)
                else -> 1.0
            }
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
            return movementCommand(
                from = context.playerPos,
                to = currentTarget,
                verticalTargetY = if (precisePlanConnectorApproach) {
                    currentTarget.y
                } else if (context.planMode) {
                    planSegmentHeightAt(context.playerPos, currentTarget)
                } else {
                    currentTarget.y
                },
                speedMagnitude = speedMagnitude,
                horizontalDeadZone = when {
                    precisePlanConnectorApproach &&
                        abs(currentTarget.y - context.playerPos.y) >
                        PLAN_PRECISE_CONNECTOR_VERTICAL_DEAD_ZONE ->
                        Double.POSITIVE_INFINITY
                    context.planMode -> PLAN_WAYPOINT_HORIZONTAL_DEAD_ZONE
                    else -> 0.0
                },
                ascentDeadZone = when {
                    precisePlanConnectorApproach -> PLAN_PRECISE_CONNECTOR_VERTICAL_DEAD_ZONE
                    context.planMode && planFlatWaypointSegment -> PLAN_FLAT_WAYPOINT_ASCENT_DEAD_ZONE
                    context.planMode -> PLAN_ASCENT_DEAD_ZONE
                    else -> VERTICAL_DEAD_ZONE
                },
                descentDeadZone = when {
                    precisePlanConnectorApproach -> PLAN_PRECISE_CONNECTOR_VERTICAL_DEAD_ZONE
                    tightenPlanWaypoint -> PLAN_TIGHT_WAYPOINT_DESCENT_DEAD_ZONE
                    else -> VERTICAL_DEAD_ZONE
                },
                resetHorizontalVelocity = resetHorizontalVelocity,
            )
        }
    }

    private fun planFinalArrivalStillBlocksCoveredPosition(context: MoverTickContext): Boolean {
        if (!context.planMode) return false
        if (followingFlightPath && pathWaypointQueue.isNotEmpty()) return false
        val covered = route.getOrNull(routeIndex)?.covered ?: return false
        return context.planFrontier?.columnBlocked.orEmpty().any { position ->
            position in covered && isPlanColumnBlockedAtLivePose(position, context)
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

    private fun tickPlanArrivalHold(context: MoverTickContext): MoverCommand {
        arrivalHoldTicks++
        if (arrivalHoldTicks >= HOLD_HARD_CAP_TICKS) {
            context.onHoldHardCap()
            releaseArrivalHold(context)
            return STOP_COMMAND
        }

        val currentWaiting = context.planFrontier?.waitingForReach.orEmpty()
            .map { target -> target.pos.immutable() }
        val currentColumnBlocked = context.planFrontier?.columnBlocked.orEmpty()
            .mapTo(LinkedHashSet<BlockPos>()) { pos -> pos.immutable() }

        if (currentColumnBlocked.any { position -> isPlanColumnBlockedAtLivePose(position, context) }) {
            releaseArrivalHold(
                context,
                frontierChanged = true,
                releaseBranch = "invalid-live-player-column",
            )
            return STOP_COMMAND
        }

        val current = route.getOrNull(routeIndex)
        val currentHead = context.planFrontier?.waitingForReach?.firstOrNull()
        if (
            current != null &&
            currentHead != null &&
            currentHead.pos in current.covered &&
            !isPlanHeadReachableFromLivePose(currentHead, context)
        ) {
            handleUnproductiveWorkPosition(context)
            return STOP_COMMAND
        }

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
                if (
                    currentHead?.pos == check.head &&
                    context.planSupportStateAt != null &&
                    isPlanHeadReachableFromLivePose(currentHead, context) &&
                    extendCurrentPlanCoverage(check.head)
                ) {
                    planRouteWaitingPositions = currentWaiting
                    planRouteColumnBlockedPositions = currentColumnBlocked
                    return STOP_COMMAND
                }
                releaseBranch = "head-left-coverage-no-forward-cover"
            }
            is PlanWorkPositionCheck.Invalid -> releaseBranch = check.branch
        }
        releaseArrivalHold(context, frontierChanged = true, releaseBranch = releaseBranch)
        return STOP_COMMAND
    }

    private fun extendCurrentPlanCoverage(head: BlockPos): Boolean {
        val current = route.getOrNull(routeIndex) ?: return false
        val updated = current.copy(covered = current.covered + head.immutable())
        route = route.toMutableList().also { entries -> entries[routeIndex] = updated }
        return true
    }

    private fun isPlanColumnBlockedAtLivePose(position: BlockPos, context: MoverTickContext): Boolean {
        val target = context.planFrontier?.waitingForReach?.firstOrNull { candidate ->
            candidate.pos == position
        }
        val stateAt = context.planSupportStateAt ?: context.planStateAt
        return if (target != null && stateAt != null) {
            isPlacementBlockedByPlayer(position, target.expected, context.playerPos, stateAt)
        } else {
            isInPlayerColumn(position, context.playerPos)
        }
    }

    private fun isPlanHeadReachableFromLivePose(
        head: MoverPlanFrontierTarget,
        context: MoverTickContext,
    ): Boolean {
        val plannedStand = route.getOrNull(routeIndex)?.target ?: context.playerPos
        if (
            head.pos !in context.planBreakTargets &&
            head.pos.y > floor(plannedStand.y).toInt()
        ) {
            return false
        }
        val stateAt = context.planSupportStateAt
        if (stateAt == null && head.pos !in context.planBreakTargets) return true
        val eyePosition = Vec3(
            context.playerPos.x,
            context.playerPos.y + PLAYER_EYE_HEIGHT,
            context.playerPos.z,
        )
        val reachSquared = context.reach * context.reach
        return planReachHitPoints(
            pos = head.pos,
            expected = head.expected,
            stateAt = stateAt,
            breakTargets = context.planBreakTargets,
        ).any { hitPoint ->
            eyePosition.distanceToSqr(hitPoint) <= reachSquared
        }
    }

    private sealed interface PlanWorkPositionCheck {
        object Valid : PlanWorkPositionCheck
        data class HeadLeftCoverage(val head: BlockPos) : PlanWorkPositionCheck
        data class Invalid(val branch: String) : PlanWorkPositionCheck
    }

    private fun checkCurrentPlanWorkPosition(
        currentWaiting: List<BlockPos>,
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

    private fun refreshPlanCruiseRoute(context: MoverTickContext): Boolean {
        val currentWaiting = context.planFrontier?.waitingForReach.orEmpty()
            .map { target -> target.pos.immutable() }
        val currentColumnBlocked = context.planFrontier?.columnBlocked.orEmpty()
            .mapTo(LinkedHashSet<BlockPos>()) { pos -> pos.immutable() }
        val currentInFlight = context.planFrontier?.inFlight.orEmpty()
            .mapTo(LinkedHashSet<BlockPos>()) { pos -> pos.immutable() }
        return when (val check = checkCurrentPlanWorkPosition(currentWaiting, currentColumnBlocked)) {
            PlanWorkPositionCheck.Valid -> {
                val worldRevisionChanged = context.queueRevision != planLegRevision
                val inFlightChanged = currentInFlight != planRouteInFlightPositions
                val collisionWorldChanged = worldRevisionChanged || inFlightChanged
                val current = route.getOrNull(routeIndex)
                val collisionStateAt = if (collisionWorldChanged) planCollisionStateAt(context) else null
                if (
                    collisionWorldChanged &&
                    (current == null || standFittedHeight(current.target, context, collisionStateAt) == null)
                ) {
                    buildRoute(context)
                } else if (!collisionWorldChanged) {
                    true
                } else if (
                    collisionStateAt != null &&
                    activePlanFlightPathClear(context, collisionStateAt)
                ) {
                    planRouteInFlightPositions = currentInFlight
                    planLegRevision = context.queueRevision
                    true
                } else {
                    planRouteInFlightPositions = currentInFlight
                    prepareCurrentWorkPosition(
                        context,
                        cause = if (worldRevisionChanged) "world-revision" else "in-flight-changed",
                    )
                }
            }
            is PlanWorkPositionCheck.HeadLeftCoverage -> {
                val advanceIndex = ((routeIndex + 1) until route.size).firstOrNull { index ->
                    check.head in route[index].covered
                }
                if (advanceIndex != null) {
                    advanceRouteToCoveringEntry(advanceIndex, context)
                    true
                } else {
                    buildRoute(context)
                }
            }
            is PlanWorkPositionCheck.Invalid -> buildRoute(context)
        }
    }

    private fun activePlanFlightPathClear(
        context: MoverTickContext,
        stateAt: (BlockPos) -> BlockState,
    ): Boolean {
        val currentTarget = movementTarget ?: return false
        val insideArrivalRadius = context.playerPos.distanceToSqr(currentTarget) <= ARRIVAL_DISTANCE_SQUARED
        var segmentStart = if (insideArrivalRadius) currentTarget else context.playerPos

        if (!insideArrivalRadius && !planPathSegmentClear(context, segmentStart, currentTarget, stateAt)) {
            return false
        }
        for (waypoint in pathWaypointQueue) {
            if (!planPathSegmentClear(context, segmentStart, waypoint, stateAt)) return false
            segmentStart = waypoint
        }
        return true
    }

    private fun planPathSegmentClear(
        context: MoverTickContext,
        from: Vec3,
        to: Vec3,
        stateAt: (BlockPos) -> BlockState,
    ): Boolean {
        val probe = context.pathProbe(from, to)
        return probe.chunkLoaded && probe.clear &&
            sweptVolumeCollisionFree(
                from,
                to,
                stateAt,
                requiredHeight = PLAYER_HEIGHT + SMOOTHING_CLEARANCE_MARGIN,
                halfWidth = PLAYER_HALF_WIDTH + SMOOTHING_CLEARANCE_MARGIN,
            )
    }

    private fun advanceRouteToCoveringEntry(targetIndex: Int, context: MoverTickContext): Unit {
        if (targetIndex != routeIndex) {
            flightLossCount = 0
        }
        routeIndex = targetIndex
        prepareCurrentWorkPosition(context, cause = "advance-in-place")
    }

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

    private fun buildRoute(
        context: MoverTickContext,
        clearBannedTargets: Boolean = false,
        confirmEmpty: Boolean = false,
        completeAfterEmpty: Boolean = false,
    ): Boolean {
        routeBuilds++
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
        val gateLayerMissing = frozenLayerMembers.filter { worldPos ->
            worldPos in placeableSet
        }
        routeStartRevision = context.queueRevision
        routeGateSignature = context.gateY

        if (gateLayerMissing.isNotEmpty()) {
            return buildLanePass(context, gateLayerMissing)
        }
        laneModeActive = false

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
        planRouteWaitingPositions = waitingForReach.map { target -> target.pos.immutable() }
        planRouteColumnBlockedPositions = columnBlocked.mapTo(LinkedHashSet()) { pos -> pos.immutable() }
        planRouteInFlightPositions = inFlight.mapTo(LinkedHashSet()) { pos -> pos.immutable() }
        prunePlanBannedCells()

        if (
            lastEmptyPlanRoutePositions != null &&
            (
                lastEmptyPlanRoutePositions != planRouteWaitingPositions ||
                    lastEmptyPlanRouteColumnBlockedPositions != planRouteColumnBlockedPositions
                )
        ) {
            lastEmptyPlanRoutePositions = null
        }

        if (columnBlocked.isNotEmpty()) {
            route = emptyList()
            planRouteBanCells = emptyList()
            routeIndex = 0
            return buildPlanEvacuationRoute(context, waitingForReach, columnBlocked, inFlight)
        }

        if (waitingForReach.isEmpty()) {
            route = emptyList()
            planRouteBanCells = emptyList()
            routeIndex = 0
            return finishPlanRoute(context, confirmEmpty)
        }

        val lastEmptyPositions = lastEmptyPlanRoutePositions
        if (
            lastEmptyPositions == planRouteWaitingPositions &&
            moverTickCounter - lastEmptyPlanRouteTick < PLAN_EMPTY_ROUTE_REBUILD_TICKS
        ) {
            return finishPlanRoute(context, confirmEmpty)
        }

        val expectedByPos = waitingForReach.associate { target -> target.pos to target.expected }
        val coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3> = { pos, expected ->
            planReachHitPoints(
                pos = pos,
                expected = expected,
                stateAt = context.planSupportStateAt,
                breakTargets = context.planBreakTargets,
            )
        }
        val externalBlockedColumns = planColumnsOf(emptyList(), columnBlocked, inFlight)
        val planIsCandidateBanned: (Vec3) -> Boolean = { target ->
            standBanCell(target) in planBannedCells ||
                target in bannedTargets ||
                planColumnOf(target) in externalBlockedColumns
        }
        val planIsStandUsable: (Vec3, List<BlockPos>) -> Boolean = { target, covered ->
            isStandAcceptable(target, covered, context)
        }
        val planScoringPosition: (Vec3) -> Vec3 = { target ->
            val cellX = floor(target.x)
            val cellZ = floor(target.z)
            Vec3(cellX + 0.5, standFittedHeight(target, context) ?: target.y, cellZ + 0.5)
        }
        val planned = planRoute(
            placeableMissing = waitingForReach.map { target -> target.pos },
            reach = context.reach,
            expectedStateAt = { pos -> expectedByPos[pos] },
            coverageHitPointsAt = coverageHitPointsAt,
            coverageMargin = PLAN_HIT_COVERAGE_MARGIN,
            centerCandidateForScoring = true,
            scoringPositionForCandidate = planScoringPosition,
            excludeOwnColumnFromCoverage = true,
            rejectPendingTargetColumns = context.planSupportStateAt == null,
            preserveInputOrder = true,
            includePlanFallbackCandidates = true,
            isCandidateBanned = planIsCandidateBanned,
            isStandUsable = planIsStandUsable,
            coverContiguousPrefix = true,
        )

        val previousRoute = route
        val previousIndex = routeIndex
        planRouteBanCells = planned.route.map { entry -> standBanCell(entry.target) }
        route = planned.route.map { entry -> entry.copy(target = standFittedTarget(entry, context)) }
        routeIndex = 0
        if (route != previousRoute || routeIndex != previousIndex) {
            flightLossCount = 0
        }
        val dispatchHead = waitingForReach.first().pos.immutable()
        val dispatchHeadUncoverable = dispatchHead in planned.uncoverable
        val dispatchHeadRejections = if (dispatchHeadUncoverable) {
            diagnosePlanRouteFirstAnchorRejections(
                placeableMissing = waitingForReach.map { target -> target.pos },
                reach = context.reach,
                expectedStateAt = { pos -> expectedByPos[pos] },
                coverageHitPointsAt = coverageHitPointsAt,
                coverageMargin = PLAN_HIT_COVERAGE_MARGIN,
                scoringPositionForCandidate = planScoringPosition,
                rejectPendingTargetColumns = false,
                includePlanFallbackCandidates = true,
                bannedTargets = bannedTargets,
                isCandidateBanned = planIsCandidateBanned,
                isStandUsable = planIsStandUsable,
            )
        } else {
            null
        }
        val headProofContainsMoverBans = dispatchHeadRejections?.let { breakdown ->
            breakdown.bannedTargetsCount > 0 || breakdown.candidateBannedCount > 0
        } == true
        if (
            dispatchHeadUncoverable &&
            !headProofContainsMoverBans &&
            inFlight.isEmpty() &&
            context.planSupportStateAt != null
        ) {
            context.onPlanPositionUnreachable(dispatchHead)
        }
        if (route.isEmpty()) {
            logEmptyPlanRouteDiagnostics(
                waitingForReach = waitingForReach,
                reach = context.reach,
                expectedByPos = expectedByPos,
                coverageHitPointsAt = coverageHitPointsAt,
                scoringPositionForCandidate = planScoringPosition,
                isCandidateBanned = planIsCandidateBanned,
                isStandUsable = planIsStandUsable,
            )
            lastEmptyPlanRoutePositions = planRouteWaitingPositions
            lastEmptyPlanRouteColumnBlockedPositions = planRouteColumnBlockedPositions
            lastEmptyPlanRouteTick = moverTickCounter
            return finishPlanRoute(context, confirmEmpty)
        }
        if (dispatchHeadUncoverable) {
            logHeadNotCoveredDiagnostics(
                head = dispatchHead,
                waitingForReach = waitingForReach,
                routeSize = route.size,
                uncoverableSize = planned.uncoverable.size,
                reach = context.reach,
                coverageHitPointsAt = coverageHitPointsAt,
                scoringPositionForCandidate = planScoringPosition,
                isCandidateBanned = planIsCandidateBanned,
                isStandUsable = planIsStandUsable,
            )
            route = emptyList()
            planRouteBanCells = emptyList()
            routeIndex = 0
            lastEmptyPlanRoutePositions = planRouteWaitingPositions
            lastEmptyPlanRouteColumnBlockedPositions = planRouteColumnBlockedPositions
            lastEmptyPlanRouteTick = moverTickCounter
            return finishPlanRoute(context, confirmEmpty)
        }
        lastEmptyPlanRoutePositions = null
        planFinalAtArm = false
        completePending = false
        completePendingRevision = null
        return prepareCurrentWorkPosition(context)
    }

    private fun logEmptyPlanRouteDiagnostics(
        waitingForReach: List<MoverPlanFrontierTarget>,
        reach: Double,
        expectedByPos: Map<BlockPos, BlockState>,
        coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3>,
        scoringPositionForCandidate: (Vec3) -> Vec3,
        isCandidateBanned: (Vec3) -> Boolean,
        isStandUsable: (Vec3, List<BlockPos>) -> Boolean,
    ): Unit {
        if (moverTickCounter - lastEmptyPlanRouteDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastEmptyPlanRouteDiagLogTick = moverTickCounter
        val breakdown = diagnosePlanRouteFirstAnchorRejections(
            placeableMissing = waitingForReach.map { target -> target.pos },
            reach = reach,
            expectedStateAt = { pos -> expectedByPos[pos] },
            coverageHitPointsAt = coverageHitPointsAt,
            coverageMargin = PLAN_HIT_COVERAGE_MARGIN,
            scoringPositionForCandidate = scoringPositionForCandidate,
            rejectPendingTargetColumns = false,
            includePlanFallbackCandidates = true,
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

    private fun logHeadNotCoveredDiagnostics(
        head: BlockPos,
        waitingForReach: List<MoverPlanFrontierTarget>,
        routeSize: Int,
        uncoverableSize: Int,
        reach: Double,
        coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3>,
        scoringPositionForCandidate: (Vec3) -> Vec3,
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
            coverageHitPointsAt = coverageHitPointsAt,
            coverageMargin = PLAN_HIT_COVERAGE_MARGIN,
            scoringPositionForCandidate = scoringPositionForCandidate,
            rejectPendingTargetColumns = false,
            includePlanFallbackCandidates = true,
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

    private fun logPlanBanAdd(site: String, cell: BlockPos): Unit {
        com.mojang.logging.LogUtils.getLogger().info(
            "C3DBG[ban] add t={} site={} cell={} banSize={}",
            moverTickCounter,
            site,
            cell.toShortString(),
            planBannedCells.size,
        )
    }

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

    private fun setPlanMovementTarget(context: MoverTickContext, cause: String, value: Vec3?): Unit {
        val previous = movementTarget
        movementTarget = value
        if (context.planMode) {
            planSegmentStart = if (value == null) null else context.playerPos
            planLegRevision = if (value == null) null else context.queueRevision
            planFlatWaypointSegment = false
        }
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

    private fun logFlightBeginFailDiagnostics(context: MoverTickContext, cause: String): Unit {
        if (!context.planMode) return
        if (moverTickCounter - lastFlightBeginFailDiagLogTick < PLAN_DIAG_LOG_INTERVAL_TICKS) return
        lastFlightBeginFailDiagLogTick = moverTickCounter
        com.mojang.logging.LogUtils.getLogger().info(
            "C3DBG[flight] beginPlanFlightPath early-reject t={} cause={} routeIndex={} routeSize={} laneModeActive={}",
            moverTickCounter, cause, routeIndex, route.size, laneModeActive,
        )
    }

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

    private fun buildPlanEvacuationRoute(
        context: MoverTickContext,
        waitingForReach: List<MoverPlanFrontierTarget>,
        columnBlocked: List<BlockPos>,
        inFlight: List<BlockPos>,
    ): Boolean {
        prunePlanBannedCells()
        val playerCell = floorCell(context.playerPos)
        val expectedByPosition = waitingForReach.associate { target -> target.pos.immutable() to target.expected }
        val currentCollisionStateAt = planCollisionStateAt(context)
        val blockingCollisionStates = columnBlocked.mapNotNull { pos ->
            expectedByPosition[pos]?.let { expected ->
                pos.immutable() to placementCollisionReservationState(expected)
            }
        }.toMap()
        val postPlacementStateAt = currentCollisionStateAt?.let { currentStateAt ->
            { pos: BlockPos -> blockingCollisionStates[pos] ?: currentStateAt(pos) }
        }
        val isEnterable = evacuationCandidatePredicate(context, currentCollisionStateAt)
        val stateAt = currentCollisionStateAt ?: context.planStateAt
        val isOutsideBlockedPlayerVolumes: (BlockPos) -> Boolean = { cell ->
            val candidateFeet = Vec3(
                cell.x + 0.5,
                evacuationFittedHeight(context, cell, currentCollisionStateAt),
                cell.z + 0.5,
            )
            val clearsTypedTargets = waitingForReach.none { target ->
                if (stateAt == null) {
                    isInPlayerColumn(target.pos, candidateFeet)
                } else {
                    isPlacementBlockedByPlayer(target.pos, target.expected, candidateFeet, stateAt)
                }
            }
            val clearsUntypedPositions = (columnBlocked.asSequence() + inFlight.asSequence()).none { position ->
                position !in expectedByPosition && isInPlayerColumn(position, candidateFeet)
            }
            val keepsEscape = postPlacementStateAt == null ||
                playerHasLocalEscapeRoute(candidateFeet, postPlacementStateAt)
            clearsTypedTargets && clearsUntypedPositions && keepsEscape
        }
        val selectCandidate: (List<BlockPos>) -> BlockPos? = { candidates ->
            candidates
                .filter { cell -> cell !in planBannedCells }
                .filter { cell -> isEnterable(cell) }
                .filter { cell -> isOutsideBlockedPlayerVolumes(cell) }
                .minByOrNull { cell -> blockPosDistanceSquared(cell, playerCell) }
        }
        val target = selectCandidate(evacuationRingCandidates(playerCell))
            ?: selectCandidate(verticalEvacuationCandidates(playerCell))
            ?: return finishPlanRoute(context, confirmEmpty = false)

        val evacuationTarget = Vec3(
            target.x + 0.5,
            evacuationFittedHeight(context, target, currentCollisionStateAt),
            target.z + 0.5,
        )
        val previousRoute = route
        val previousIndex = routeIndex
        route = listOf(
            PlannedWorkPosition(
                target = evacuationTarget,
                covered = columnBlocked.toSet(),
            ),
        )
        planRouteBanCells = listOf(standBanCell(evacuationTarget))
        routeIndex = 0
        if (route != previousRoute || routeIndex != previousIndex) {
            flightLossCount = 0
        }
        lastEmptyPlanRoutePositions = null
        planFinalAtArm = false
        completePending = false
        completePendingRevision = null
        return prepareCurrentWorkPosition(context, cause = "evacuation")
    }

    private fun evacuationCandidatePredicate(
        context: MoverTickContext,
        stateAt: ((BlockPos) -> BlockState)?,
    ): (BlockPos) -> Boolean {
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) {
            return { cell -> context.isPassableCell(cell) && context.isPassableCell(cell.above()) }
        }
        val profile = FlightPassabilityProfile.collisionAware(travelAabb(travelBounds), stateAt)
        return { cell -> profile.feetHeightAt(cell) != null }
    }

    private fun evacuationFittedHeight(
        context: MoverTickContext,
        cell: BlockPos,
        stateAt: ((BlockPos) -> BlockState)?,
    ): Double {
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) {
            return cell.y.toDouble() + HOVER_CLEARANCE
        }
        val profile = FlightPassabilityProfile.collisionAware(travelAabb(travelBounds), stateAt)
        return profile.feetHeightAt(cell) ?: (cell.y.toDouble() + HOVER_CLEARANCE)
    }

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

    private fun verticalEvacuationCandidates(center: BlockPos): List<BlockPos> {
        return (1..PLAN_EVACUATION_VERTICAL_MAX_RISE).map { rise -> center.above(rise) }
    }

    private fun blockPosDistanceSquared(first: BlockPos, second: BlockPos): Long {
        val deltaX = (first.x - second.x).toLong()
        val deltaY = (first.y - second.y).toLong()
        val deltaZ = (first.z - second.z).toLong()
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
    }

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

    private fun isStandAcceptable(target: Vec3, covered: List<BlockPos>, context: MoverTickContext): Boolean {
        val standFeetY = floor(target.y).toInt()
        if (!planStandMeetsLayerSafety(standFeetY, covered, context.planBreakTargets)) return false
        return standFittedHeight(target, context) != null
    }

    private fun standFittedHeight(target: Vec3, context: MoverTickContext): Double? {
        return standFittedHeight(target, context, context.planStateAt)
    }

    private fun standFittedHeight(
        target: Vec3,
        context: MoverTickContext,
        stateAt: ((BlockPos) -> BlockState)?,
    ): Double? {
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

    private fun standFittedTarget(entry: PlannedWorkPosition, context: MoverTickContext): Vec3 {
        val fittedHeight = standFittedHeight(entry.target, context) ?: entry.target.y
        val cellX = floor(entry.target.x)
        val cellZ = floor(entry.target.z)
        return Vec3(cellX + 0.5, fittedHeight, cellZ + 0.5)
    }

    private fun standBanCell(rawTarget: Vec3): BlockPos = floorCell(rawTarget)

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
        planRouteWaitingPositions = emptyList()
        planRouteColumnBlockedPositions = emptySet()
        planRouteInFlightPositions = emptySet()
        lastEmptyPlanRoutePositions = null
        lastEmptyPlanRouteColumnBlockedPositions = emptySet()
        lastEmptyPlanRouteTick = 0L
        planCorrectionTicks.clear()
        planCorrectionWorkPositionCell = null
        planCorrectionCountForWorkPosition = 0
        planCorrectionWorkPositionTick = 0L
        planCorrectionRebuildPending = false
    }

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

    private fun skipToNextLaneSegment(context: MoverTickContext): Boolean {
        val nextSegmentStartIndex = (laneWaypointIndex / 2 + 1) * 2
        laneWaypointIndex = nextSegmentStartIndex
        if (laneWaypointIndex < laneWaypoints.size) {
            beginLaneLeg()
            return true
        }
        return finishLanePass(context)
    }

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

    private fun isSharpHorizontalWaypointTurn(current: Vec3, next: Vec3): Boolean {
        val start = planSegmentStart ?: return false
        val incomingX = current.x - start.x
        val incomingZ = current.z - start.z
        val outgoingX = next.x - current.x
        val outgoingZ = next.z - current.z
        val incomingLengthSquared = incomingX * incomingX + incomingZ * incomingZ
        val outgoingLengthSquared = outgoingX * outgoingX + outgoingZ * outgoingZ
        if (
            incomingLengthSquared < COLLISION_EPSILON_SQUARED ||
            outgoingLengthSquared < COLLISION_EPSILON_SQUARED
        ) {
            return false
        }
        val cosine = (incomingX * outgoingX + incomingZ * outgoingZ) /
            sqrt(incomingLengthSquared * outgoingLengthSquared)
        return cosine < CORNER_TURN_COS_THRESHOLD
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
            if (beginPlanFlightPath(context, cause)) return true
            val failed = route.getOrNull(routeIndex) ?: return false
            context.onWorkPositionAbandoned(failed.covered)
            val banCell = planRouteBanCells.getOrNull(routeIndex) ?: standBanCell(failed.target)
            planBannedCells[banCell] = moverTickCounter
            logPlanBanAdd("path-no-route", banCell)
            return buildRoute(context)
        }
        movementTarget = current.target
        pathProbePending = true
        return true
    }

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

    private fun beginPlanFlightPath(context: MoverTickContext, cause: String = "prepare"): Boolean {
        val target = baseTarget() ?: run {
            logFlightBeginFailDiagnostics(context, cause)
            return false
        }
        pathSearchCount++
        val stateAt = planCollisionStateAt(context)
        val travelBounds = context.planTravelBounds
        if (stateAt == null || travelBounds == null) {
            return beginOverTheTopLeg(context, target, cause)
        }

        aStarInvocations++
        val searchBounds = localSearchAabb(travelBounds, floorCell(context.playerPos), floorCell(target))
        val profile = FlightPassabilityProfile.collisionAware(searchBounds, stateAt)
        val isEnterableCell = { position: BlockPos -> profile.feetHeightAt(position) != null }
        val lineClear: (Vec3, Vec3) -> Boolean = { from, to ->
            val probe = context.pathProbe(from, to)
            probe.chunkLoaded && probe.clear &&
                sweptVolumeCollisionFree(
                    from,
                    to,
                    stateAt,
                    requiredHeight = PLAYER_HEIGHT + SMOOTHING_CLEARANCE_MARGIN,
                    halfWidth = PLAYER_HALF_WIDTH + SMOOTHING_CLEARANCE_MARGIN,
                )
        }
        val isStartConnectorClear: (BlockPos) -> Boolean = { position ->
            val feetHeight = profile.feetHeightAt(position)
            val connectorTarget = if (feetHeight == null) {
                null
            } else {
                Vec3(position.x + 0.5, feetHeight, position.z + 0.5)
            }
            val probe = connectorTarget?.let { target -> context.pathProbe(context.playerPos, target) }
            connectorTarget != null && probe?.chunkLoaded == true && probe.clear &&
                sweptVolumeCollisionFree(
                    context.playerPos,
                    connectorTarget,
                    stateAt,
                    requiredHeight = PLAYER_HEIGHT,
                    halfWidth = PLAYER_HALF_WIDTH,
                )
        }
        val startCell = resolveEnterableCell(
            context.playerPos,
            PLAYER_HALF_WIDTH,
            isEnterableCell,
            isStartConnectorClear,
        )
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
            profile = profile,
        )
        val path = result.path
        val feetHeights = result.feetHeights
        if (path == null || feetHeights == null) {
            recordAStarFailure(result.failureReason)
            return false
        }
        aStarSuccesses++

        clearFlightPath()
        val pathWaypoints = if (path.isEmpty()) {
            listOf(target)
        } else {
            path.mapIndexed { index, cell ->
                if (index == path.lastIndex) {
                    Vec3(target.x, feetHeights[index], target.z)
                } else {
                    Vec3(cell.x + 0.5, feetHeights[index], cell.z + 0.5)
                }
            }
        }
        val startWaypoint = Vec3(
            startCell.x + 0.5,
            requireNotNull(profile.feetHeightAt(startCell)),
            startCell.z + 0.5,
        )
        val unsmoothed = buildList(pathWaypoints.size + 1) {
            if (startWaypoint != context.playerPos && startWaypoint != pathWaypoints.firstOrNull()) {
                add(startWaypoint)
            }
            addAll(pathWaypoints)
        }
        val smoothed = smoothFlightPath(
            start = context.playerPos,
            waypoints = unsmoothed,
            lineClear = lineClear,
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

    private fun planCollisionStateAt(context: MoverTickContext): ((BlockPos) -> BlockState)? {
        val liveStateAt = context.planStateAt ?: return null
        val frontier = context.planFrontier
        val inFlight = frontier?.inFlight.orEmpty()
            .mapTo(HashSet<BlockPos>()) { pos -> pos.immutable() }
        if (inFlight.isEmpty()) return liveStateAt
        val collisionStates = frontier?.inFlightCollisionStates.orEmpty()
            .associate { target -> target.pos.immutable() to target.expected }
        return { pos ->
            collisionStates[pos] ?: if (pos in inFlight) Blocks.STONE.defaultBlockState() else liveStateAt(pos)
        }
    }

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
        planSegmentStart = null
        planLegRevision = null
        planFlatWaypointSegment = false
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

    private fun movementCommand(
        from: Vec3,
        to: Vec3,
        verticalTargetY: Double = to.y,
        speedMagnitude: Double,
        horizontalDeadZone: Double,
        ascentDeadZone: Double,
        descentDeadZone: Double,
        resetHorizontalVelocity: Boolean = false,
    ): MoverCommand {
        val deltaX = to.x - from.x
        val deltaY = verticalTargetY - from.y
        val deltaZ = to.z - from.z
        val horizontalLength = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val unitX = if (horizontalLength > horizontalDeadZone) deltaX / horizontalLength else 0.0
        val unitZ = if (horizontalLength > horizontalDeadZone) deltaZ / horizontalLength else 0.0
        val vertical = when {
            deltaY > ascentDeadZone -> 1
            deltaY < -descentDeadZone -> -1
            else -> 0
        }
        return MoverCommand(
            jump = false,
            enableFlight = false,
            horizontalX = unitX * speedMagnitude,
            horizontalZ = unitZ * speedMagnitude,
            vertical = vertical,
            stopMovement = false,
            resetHorizontalVelocity = resetHorizontalVelocity,
        )
    }

    private fun planSegmentHeightAt(position: Vec3, target: Vec3): Double {
        val targetDeltaX = target.x - position.x
        val targetDeltaZ = target.z - position.z
        if (
            targetDeltaX * targetDeltaX + targetDeltaZ * targetDeltaZ <=
            PLAN_WAYPOINT_HORIZONTAL_DEAD_ZONE * PLAN_WAYPOINT_HORIZONTAL_DEAD_ZONE
        ) {
            return target.y
        }
        val start = planSegmentStart ?: return target.y
        val segmentX = target.x - start.x
        val segmentZ = target.z - start.z
        val horizontalLengthSquared = segmentX * segmentX + segmentZ * segmentZ
        if (horizontalLengthSquared <= PLAN_SEGMENT_HORIZONTAL_EPSILON) return target.y

        val progress = (
            ((position.x - start.x) * segmentX + (position.z - start.z) * segmentZ) /
                horizontalLengthSquared
            ).coerceIn(0.0, 1.0)
        val projectedHeight = start.y + (target.y - start.y) * progress
        return if (target.y > start.y && projectedHeight < target.y) {
            minOf(target.y, projectedHeight + PLAN_SEGMENT_ASCENT_TRIGGER)
        } else {
            projectedHeight
        }
    }

    private fun immediateAbortReason(context: MoverTickContext): MoverAbortReason? {
        return when {
            context.manualInput -> MoverAbortReason.MANUAL_INPUT
            context.guiOpen -> MoverAbortReason.GUI_OPEN
            context.hurt -> MoverAbortReason.DAMAGED
            context.correctionReceived && !context.planMode -> MoverAbortReason.SERVER_CORRECTION
            !context.isCreative -> MoverAbortReason.GAMEMODE_LOST
            activeSessionKey?.matches(context.sessionKey) == false ->
                MoverAbortReason.SESSION_CHANGED
            else -> null
        }
    }

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

    private fun logPlanCorrectionDiagnostics(context: MoverTickContext, previousPlayerPos: Vec3?): Unit {
        val delta = if (previousPlayerPos != null) {
            context.playerPos.subtract(previousPlayerPos)
        } else {
            Vec3.ZERO
        }
        com.mojang.logging.LogUtils.getLogger().info(
            "C3MOV plan-correction t={} delta=({}, {}, {}) segmentStart={} waypoint={}",
            moverTickCounter,
            String.format("%.3f", delta.x),
            String.format("%.3f", delta.y),
            String.format("%.3f", delta.z),
            planSegmentStart,
            movementTarget,
        )
    }

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
        routeBuilds = 0
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
        planRouteWaitingPositions = emptyList()
        planRouteColumnBlockedPositions = emptySet()
        planRouteInFlightPositions = emptySet()
        lastEmptyPlanRoutePositions = null
        lastEmptyPlanRouteColumnBlockedPositions = emptySet()
        lastEmptyPlanRouteTick = 0L
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
        private const val PLAN_ASCENT_DEAD_ZONE: Double = 0.0
        private const val PLAN_TIGHT_WAYPOINT_DESCENT_DEAD_ZONE: Double = 0.15
        private const val PLAN_SERVER_HEADROOM_MARGIN: Double = 0.05
        // Flat and connector waypoints tolerate one creative-flight input's residual.
        private const val PLAN_PRECISE_CONNECTOR_VERTICAL_DEAD_ZONE: Double = 0.05
        private const val PLAN_FLAT_WAYPOINT_ASCENT_DEAD_ZONE: Double = 0.05
        private const val PLAN_WAYPOINT_HORIZONTAL_DEAD_ZONE: Double = 0.15
        private const val PLAN_SEGMENT_HORIZONTAL_EPSILON: Double = 1.0E-9
        private const val PLAN_SEGMENT_HEIGHT_EPSILON: Double = 1.0E-9
        private const val PLAN_SEGMENT_ASCENT_TRIGGER: Double = 1.0E-6
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
        private const val RECENT_PROGRESS_WINDOW_TICKS: Long =
            PrinterAttemptTracker.DEADLINE_TICKS + PrinterRateLimiter.DEFAULT_INTERVAL_TICKS
        private const val TRAPPED_MOVEMENT_SQUARED: Double = 0.25
        private const val TRAPPED_STATIONARY_TICKS: Int = 100
        private const val OVER_THE_TOP_CLEARANCE: Double = 2.0
        private const val LOCAL_SEARCH_MARGIN: Int = 16
        private const val PLAN_BAN_MAX_AGE_TICKS: Long = 200L
        private const val PLAN_EMPTY_ROUTE_REBUILD_TICKS: Long = 20L
        private const val PLAN_EVACUATION_RING_MIN_RADIUS: Int = 2
        private const val PLAN_EVACUATION_RING_MAX_RADIUS: Int = 3
        private const val PLAN_EVACUATION_VERTICAL_MAX_RISE: Int = 3
        private const val STAND_HEADROOM_CLEARANCE: Double = 0.2

        private const val PLAN_CORRECTION_WINDOW_TICKS: Long = 100L
        private const val PLAN_CORRECTION_ABORT_THRESHOLD: Int = 3
        private const val PLAN_LEG_CORRECTION_ESCAPE_THRESHOLD: Int = 2

        private const val CORNER_DECELERATION_DISTANCE: Double = 1.5
        private const val CORNER_DECELERATION_FACTOR: Double = 0.5
        private const val CORNER_TURN_COS_THRESHOLD: Double = 0.94
        private const val COLLISION_EPSILON_SQUARED: Double = 1.0E-9

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
