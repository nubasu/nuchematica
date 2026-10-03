package com.nubasu.nuchematica.automode

import com.nubasu.nuchematica.mover.MoverAbortReason
import com.nubasu.nuchematica.mover.MoverCommand
import com.nubasu.nuchematica.mover.MoverCore
import com.nubasu.nuchematica.mover.MoverPlanFrontier
import com.nubasu.nuchematica.mover.MoverPlanFrontierTarget
import com.nubasu.nuchematica.mover.MoverSessionKey
import com.nubasu.nuchematica.mover.MoverState
import com.nubasu.nuchematica.mover.MoverTickContext
import com.nubasu.nuchematica.mover.PathProbeResult
import com.nubasu.nuchematica.mover.controlledMoverVelocity
import com.nubasu.nuchematica.mover.isFootprintCollisionFree
import com.nubasu.nuchematica.mover.sweptVolumeCollisionFree
import com.nubasu.nuchematica.printer.ActionState
import com.nubasu.nuchematica.printer.ItemSupplier
import com.nubasu.nuchematica.printer.PlacementBehaviorSettings
import com.nubasu.nuchematica.printer.PlacementGateway
import com.nubasu.nuchematica.printer.PlacementRotation
import com.nubasu.nuchematica.printer.PlanAction
import com.nubasu.nuchematica.printer.PlanExecutionCursor
import com.nubasu.nuchematica.printer.PlanRuntimeAdapter
import com.nubasu.nuchematica.printer.PlanRuntimeTickContext
import com.nubasu.nuchematica.printer.PrintPlan
import com.nubasu.nuchematica.printer.PrintPlanParams
import com.nubasu.nuchematica.printer.PrintPlanner
import com.nubasu.nuchematica.printer.PrinterAttemptTracker
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.PrinterSettings
import com.nubasu.nuchematica.printer.SCAFFOLD_BLOCK_STATE
import com.nubasu.nuchematica.printer.eligiblePlacementBlockItem
import com.nubasu.nuchematica.printer.executablePlanUnits
import com.nubasu.nuchematica.printer.isPlacementBlockedByPlayer
import com.nubasu.nuchematica.printer.isSupportingState
import com.nubasu.nuchematica.printer.planOrientedPrediction
import com.nubasu.nuchematica.printer.shouldAutoRetryPlanSession
import com.nubasu.nuchematica.printer.submitOrientedPlacement
import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import io.mockk.every
import io.mockk.mockk
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal enum class SimulatedPacketOutcome {
    ACCEPT,
    REJECT,
    WRONG_STATE_THEN_CORRECT,
    TIMEOUT,
}

internal enum class SimulatedSubmissionKind { PLACE, REMOVE }

internal data class SimulatedNetworkFault(
    internal val submissionIndex: Int,
    internal val outcome: SimulatedPacketOutcome,
    internal val kind: SimulatedSubmissionKind = SimulatedSubmissionKind.PLACE,
)

internal data class AutomodeScenario(
    internal val name: String,
    internal val content: List<Pair<BlockPos, BlockState>>,
    internal val initialWorld: Map<BlockPos, BlockState> = emptyMap(),
    internal val startFeet: Vec3? = null,
    internal val reach: Double = 4.0,
    internal val seed: Long = 0L,
    internal val latencyTicks: List<Int> = listOf(2),
    internal val faults: List<SimulatedNetworkFault> = emptyList(),
    internal val correctionTicks: Set<Long> = emptySet(),
    internal val maxTicks: Long = 20_000L,
    internal val attemptsPerTick: Int = 1,
    internal val printerSettings: PrinterSettings = PrinterSettings(),
    internal val behavior: PlacementBehaviorSettings = PlacementBehaviorSettings(
        substituteLookalikes = true,
        placeWaterloggedDry = false,
    ),
) {
    init {
        require(content.isNotEmpty()) { "scenario content must not be empty" }
        require(latencyTicks.isNotEmpty()) { "at least one latency is required" }
        require(latencyTicks.all { latency -> latency >= 0 }) { "latencies must be non-negative" }
        require(maxTicks > 0L) { "maxTicks must be positive" }
        require(printerSettings.planFirstMode) {
            "the deterministic automode simulator executes the production plan-first path; " +
                "legacy v3 must be selected explicitly outside this simulator"
        }
    }
}

internal data class AutomodeSimulationMetrics(
    internal val ticks: Long,
    internal val totalDistance: Double,
    internal val horizontalDistance: Double,
    internal val verticalDistance: Double,
    internal val ascentDistance: Double,
    internal val descentDistance: Double,
    internal val movementLegs: Int,
    internal val blockedMovementSteps: Int,
    internal val pathProbeInvocations: Int,
    internal val pathProbeBlocked: Int,
    internal val bodyCollisions: Int,
    internal val flightLosses: Int,
    internal val outOfBoundsBody: Int,
    internal val invalidPlacements: Int,
    internal val reachOutsideClicks: Int,
    internal val supportMissingClicks: Int,
    internal val playerColumnClicks: Int,
    internal val unexpectedWorldWrites: Int,
    internal val hardCaps: Int,
    internal val stallEscapes: Int,
    internal val nonproductiveMovementLegs: Int,
    internal val wastedMovementDistance: Double,
    internal val targetChanges: Int,
    internal val holdTicks: Long,
    internal val abandonedWorkPositions: Int,
    internal val uncoverablePositions: Int,
    internal val submissions: Int,
    internal val placementSubmissions: Int,
    internal val removalSubmissions: Int,
    internal val acceptedOutcomes: Int,
    internal val rejectedOutcomes: Int,
    internal val wrongStateOutcomes: Int,
    internal val timeoutOutcomes: Int,
    internal val retries: Int,
    internal val serverCorrections: Int,
    internal val scaffoldsPlaced: Int,
    internal val scaffoldsBroken: Int,
    internal val aStarInvocations: Int,
    internal val aStarSuccesses: Int,
    internal val smoothingWaypointsIn: Int,
    internal val smoothingWaypointsOut: Int,
    internal val routeRebuilds: Int,
)

internal data class AutomodeActionIssue(
    internal val pass: Int,
    internal val actionId: Long,
    internal val unitIndex: Int,
    internal val kind: String,
    internal val pos: BlockPos,
    internal val outcome: String,
)

internal data class AutomodeSimulationResult(
    internal val scenario: String,
    internal val seed: Long,
    internal val moverState: MoverState,
    internal val abortReason: MoverAbortReason?,
    internal val cursorComplete: Boolean,
    internal val sourceBlockCount: Int,
    internal val plannedTargetCount: Int,
    internal val plannedTargetPositions: Set<BlockPos>,
    internal val matchedSourceBlockCount: Int,
    internal val attemptFailedActions: Int,
    internal val attemptSkippedActions: Int,
    internal val unresolvedFailedActions: Int,
    internal val unresolvedSkippedActions: Int,
    internal val actionIssues: List<AutomodeActionIssue>,
    internal val missingPlannedTargets: List<BlockPos>,
    internal val orphanScaffolds: List<BlockPos>,
    internal val metrics: AutomodeSimulationMetrics,
    internal val trace: List<String>,
) {
    internal val normalizedTraceHash: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trace.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    internal fun cleanViolations(): List<String> {
        val violations = mutableListOf<String>()
        if (moverState != MoverState.COMPLETE) violations += "mover=$moverState/$abortReason"
        if (!cursorComplete) violations += "cursor did not complete"
        if (unresolvedFailedActions != 0) violations += "unresolvedFailedActions=$unresolvedFailedActions"
        if (unresolvedSkippedActions != 0) violations += "unresolvedSkippedActions=$unresolvedSkippedActions"
        if (missingPlannedTargets.isNotEmpty()) violations += "missingTargets=${missingPlannedTargets.size}"
        if (orphanScaffolds.isNotEmpty()) violations += "orphanScaffolds=${orphanScaffolds.size}"
        if (metrics.bodyCollisions != 0) violations += "bodyCollisions=${metrics.bodyCollisions}"
        if (metrics.flightLosses != 0) violations += "flightLosses=${metrics.flightLosses}"
        if (metrics.outOfBoundsBody != 0) violations += "outOfBoundsBody=${metrics.outOfBoundsBody}"
        if (metrics.invalidPlacements != 0) violations += "invalidPlacements=${metrics.invalidPlacements}"
        if (metrics.reachOutsideClicks != 0) violations += "reachOutsideClicks=${metrics.reachOutsideClicks}"
        if (metrics.supportMissingClicks != 0) violations += "supportMissingClicks=${metrics.supportMissingClicks}"
        if (metrics.playerColumnClicks != 0) violations += "playerColumnClicks=${metrics.playerColumnClicks}"
        if (metrics.unexpectedWorldWrites != 0) {
            violations += "unexpectedWorldWrites=${metrics.unexpectedWorldWrites}"
        }
        if (metrics.hardCaps != 0) violations += "hardCaps=${metrics.hardCaps}"
        if (metrics.stallEscapes != 0) violations += "stallEscapes=${metrics.stallEscapes}"
        if (metrics.serverCorrections != 0) violations += "serverCorrections=${metrics.serverCorrections}"
        if (metrics.timeoutOutcomes != 0) violations += "timeouts=${metrics.timeoutOutcomes}"
        if (metrics.nonproductiveMovementLegs != 0) {
            violations += "nonproductiveMovementLegs=${metrics.nonproductiveMovementLegs}"
        }
        return violations
    }
}

internal class DeterministicAutomodeSimulator(
    private val scenario: AutomodeScenario,
) {
    private val air: BlockState = Blocks.AIR.defaultBlockState()
    private val ground: BlockState = Blocks.STONE.defaultBlockState()
    private val minContentY: Int = scenario.content.minOf { (pos, _) -> pos.y }
    private val contentExpected: Map<BlockPos, BlockState> = LinkedHashMap<BlockPos, BlockState>().also { map ->
        for ((pos, state) in scenario.content) map[pos.immutable()] = state
    }
    private val world = SimulatedWorld(minContentY, ground, air, scenario.initialWorld)
    private val contentIdentity = Any()
    private val levelIdentity = Any()
    private val sessionKey = MoverSessionKey(levelIdentity, contentIdentity, 1L)
    private val mover = MoverCore()
    private val network = SimulatedNetwork()
    private val placementBoundary = SimulatedPlacementBoundary()

    private var tick: Long = 0L
    private var playerFeet: Vec3 = scenario.startFeet ?: defaultStartFeet()
    private var onGround: Boolean = true
    private var flying: Boolean = false
    private var verticalVelocity: Double = 0.0
    private var queueRevision: Long = 0L
    private var correctionThisTick: Boolean = false
    private var heldState: BlockState? = null

    private lateinit var plan: PrintPlan
    private lateinit var cursor: PlanExecutionCursor
    private lateinit var adapter: PlanRuntimeAdapter
    private var retryUsed: Boolean = false
    private var sessionFinal: Boolean = false
    private var passIndex: Int = 0
    private var completedPassFailed: Int = 0
    private var completedPassSkipped: Int = 0
    private var completedPassStallEscapes: Int = 0
    private var finalStatusRecorded: Boolean = false
    private val actionIssues: MutableList<AutomodeActionIssue> = mutableListOf()

    private val plannedTargets: MutableSet<BlockPos> = LinkedHashSet()
    private val plannedScaffolds: MutableSet<BlockPos> = LinkedHashSet()
    private val currentAllowedPlacements: MutableMap<BlockPos, BlockState> = LinkedHashMap()
    private val trace: MutableList<String> = ArrayList()

    private var totalDistance: Double = 0.0
    private var horizontalDistance: Double = 0.0
    private var verticalDistance: Double = 0.0
    private var ascentDistance: Double = 0.0
    private var descentDistance: Double = 0.0
    private var movementLegs: Int = 0
    private var blockedMovementSteps: Int = 0
    private var pathProbeInvocations: Int = 0
    private var pathProbeBlocked: Int = 0
    private var bodyCollisions: Int = 0
    private var bodyCollisionActive: Boolean = false
    private var flightLosses: Int = 0
    private var outOfBoundsBody: Int = 0
    private var invalidPlacements: Int = 0
    private var reachOutsideClicks: Int = 0
    private var supportMissingClicks: Int = 0
    private var playerColumnClicks: Int = 0
    private var unexpectedWorldWrites: Int = 0
    private var hardCaps: Int = 0
    private var nonproductiveMovementLegs: Int = 0
    private var wastedMovementDistance: Double = 0.0
    private var targetChanges: Int = 0
    private var holdTicks: Long = 0L
    private var abandonedWorkPositions: Int = 0
    private var uncoverablePositions: Int = 0
    private var serverCorrections: Int = 0
    private var scaffoldsPlaced: Int = 0
    private var scaffoldsBroken: Int = 0
    private var predictionMismatchDiagnostics: Int = 0
    private var previousMoverState: MoverState = MoverState.IDLE
    private var previousMovementTarget: Vec3? = null
    private var movementLegStartRevision: Long = 0L
    private var movementLegStartSubmissions: Int = 0
    private var movementLegStartDistance: Double = 0.0
    private var arrivalLegStartRevision: Long? = null
    private var arrivalLegStartSubmissions: Int? = null
    private var arrivalLegStartDistance: Double? = null

    init {
        buildPass(resyncFrozen = false)
    }

    internal fun run(): AutomodeSimulationResult {
        bodyCollisionActive = !isFootprintCollisionFree(playerFeet.x, playerFeet.y, playerFeet.z, world::liveStateAt)
        if (bodyCollisionActive) {
            bodyCollisions++
        }
        if (!isBodyWithinTravelBounds(playerFeet)) outOfBoundsBody++
        mover.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)

        while (tick < scenario.maxTicks) {
            correctionThisTick = false
            network.deliverDue()
            observeBodyCollision("world-mutation-body-collision", playerFeet, Vec3.ZERO)
            applyScriptedCorrection()
            advancePlanSessionIfNeeded()

            adapter.tick(printerContext())
            val frontier = adapter.frontierSnapshot().let { snapshot ->
                MoverPlanFrontier(
                    waitingForReach = snapshot.waitingForReach.map { target ->
                        MoverPlanFrontierTarget(target.pos, target.expected)
                    },
                    columnBlocked = snapshot.columnBlocked,
                    inFlight = snapshot.inFlight,
                    inFlightCollisionStates = snapshot.inFlightCollisionStates.map { target ->
                        MoverPlanFrontierTarget(target.pos, target.expected)
                    },
                    totalWaitingForReach = snapshot.totalWaitingForReach,
                    totalColumnBlocked = snapshot.totalColumnBlocked,
                    totalRemainingActions = snapshot.totalRemainingActions,
                )
            }
            val command = mover.tick(moverContext(frontier))
            applyMovement(command)
            observeBodyCollision("body-collision", playerFeet, Vec3.ZERO)
            observeMovementLeg()
            appendTrace(frontier, command)

            val moverStatus = mover.status()
            if (moverStatus.state == MoverState.ABORTED) break
            if (sessionFinal && cursor.status().isComplete && moverStatus.state == MoverState.COMPLETE) break
            tick++
        }

        if (mover.status().state == MoverState.ABORTED || tick >= scenario.maxTicks) {
            appendTerminalDiagnostics()
        }
        recordFinalCursorStatus()
        val status = mover.status()
        val cursorStatus = cursor.status()
        val path = mover.pathTelemetry()
        val missingTargets = plannedTargets.filter { pos ->
            val expected = contentExpected[pos] ?: return@filter false
            !BlockStateEquivalence.matches(expected, world.liveStateAt(pos), scenario.behavior)
        }
        appendMissingTargetDiagnostics(missingTargets)
        val orphanScaffolds = world.liveEntries().filter { (pos, state) ->
            pos !in contentExpected && BlockStateEquivalence.matches(state, SCAFFOLD_BLOCK_STATE, scenario.behavior)
        }.keys.toList()
        val missingTargetSet = missingTargets.toSet()
        val orphanScaffoldSet = orphanScaffolds.toSet()
        val unresolvedIssues = actionIssues.filter { issue ->
            when (issue.kind) {
                PlanAction.PlaceTarget::class.simpleName -> issue.pos in missingTargetSet
                PlanAction.PlaceScaffold::class.simpleName,
                PlanAction.RemoveScaffold::class.simpleName,
                -> issue.pos in orphanScaffoldSet
                else -> true
            }
        }
        return AutomodeSimulationResult(
            scenario = scenario.name,
            seed = scenario.seed,
            moverState = status.state,
            abortReason = status.abortReason,
            cursorComplete = cursorStatus.isComplete,
            sourceBlockCount = contentExpected.size,
            plannedTargetCount = plannedTargets.size,
            plannedTargetPositions = plannedTargets.toSet(),
            matchedSourceBlockCount = contentExpected.count { (pos, expected) ->
                BlockStateEquivalence.matches(expected, world.liveStateAt(pos), scenario.behavior)
            },
            attemptFailedActions = completedPassFailed,
            attemptSkippedActions = completedPassSkipped,
            unresolvedFailedActions = unresolvedIssues.count { issue -> issue.outcome != "SKIPPED" },
            unresolvedSkippedActions = unresolvedIssues.count { issue -> issue.outcome == "SKIPPED" },
            actionIssues = actionIssues.toList(),
            missingPlannedTargets = missingTargets,
            orphanScaffolds = orphanScaffolds,
            metrics = AutomodeSimulationMetrics(
                ticks = tick + 1L,
                totalDistance = totalDistance,
                horizontalDistance = horizontalDistance,
                verticalDistance = verticalDistance,
                ascentDistance = ascentDistance,
                descentDistance = descentDistance,
                movementLegs = movementLegs,
                blockedMovementSteps = blockedMovementSteps,
                pathProbeInvocations = pathProbeInvocations,
                pathProbeBlocked = pathProbeBlocked,
                bodyCollisions = bodyCollisions,
                flightLosses = flightLosses,
                outOfBoundsBody = outOfBoundsBody,
                invalidPlacements = invalidPlacements,
                reachOutsideClicks = reachOutsideClicks,
                supportMissingClicks = supportMissingClicks,
                playerColumnClicks = playerColumnClicks,
                unexpectedWorldWrites = unexpectedWorldWrites,
                hardCaps = hardCaps,
                stallEscapes = completedPassStallEscapes,
                nonproductiveMovementLegs = nonproductiveMovementLegs,
                wastedMovementDistance = wastedMovementDistance,
                targetChanges = targetChanges,
                holdTicks = holdTicks,
                abandonedWorkPositions = abandonedWorkPositions,
                uncoverablePositions = uncoverablePositions,
                submissions = network.submissionCount,
                placementSubmissions = network.placementSubmissionCount,
                removalSubmissions = network.removalSubmissionCount,
                acceptedOutcomes = network.acceptedOutcomeCount,
                rejectedOutcomes = network.rejectedOutcomeCount,
                wrongStateOutcomes = network.wrongStateOutcomeCount,
                timeoutOutcomes = network.timeoutOutcomeCount,
                retries = if (retryUsed) 1 else 0,
                serverCorrections = serverCorrections,
                scaffoldsPlaced = scaffoldsPlaced,
                scaffoldsBroken = scaffoldsBroken,
                aStarInvocations = path.aStarInvocations,
                aStarSuccesses = path.aStarSuccesses,
                smoothingWaypointsIn = path.smoothingWaypointsIn,
                smoothingWaypointsOut = path.smoothingWaypointsOut,
                routeRebuilds = path.routeBuilds,
            ),
            trace = trace.toList(),
        )
    }

    private fun buildPass(resyncFrozen: Boolean): Unit {
        if (resyncFrozen) world.resyncFrozenFromLive()
        plan = PrintPlanner.plan(
            content = scenario.content,
            worldState = world::frozenStateAt,
            params = PrintPlanParams(behavior = scenario.behavior),
        )
        cursor = PlanExecutionCursor(plan)
        adapter = PlanRuntimeAdapter(plan, cursor, bounds = ::withinPlanningBounds)
        currentAllowedPlacements.clear()
        for (unit in plan.units) {
            for (action in unit.actions) registerPlanAction(action)
        }
        for (actions in plan.reservations.values) {
            for (action in actions) registerPlanAction(action)
        }
    }

    private fun registerPlanAction(action: PlanAction): Unit {
        when (action) {
            is PlanAction.PlaceTarget -> {
                val pos = action.pos.immutable()
                plannedTargets += pos
                currentAllowedPlacements[pos] = action.expected
            }
            is PlanAction.PlaceScaffold -> {
                val pos = action.pos.immutable()
                plannedScaffolds += pos
                currentAllowedPlacements[pos] = SCAFFOLD_BLOCK_STATE
            }
            is PlanAction.RemoveScaffold -> Unit
        }
    }

    private fun advancePlanSessionIfNeeded(): Unit {
        if (!cursor.status().isComplete || sessionFinal) return
        recordFinalCursorStatus()
        val retry = shouldAutoRetryPlanSession(
            status = cursor.status(),
            hasReservations = plan.report.reservedCount > 0,
            retryUsed = retryUsed,
        )
        if (retry) {
            retryUsed = true
            passIndex++
            finalStatusRecorded = false
            buildPass(resyncFrozen = true)
        } else {
            sessionFinal = true
        }
    }

    private fun recordFinalCursorStatus(): Unit {
        if (finalStatusRecorded) return
        val status = cursor.status()
        completedPassFailed += status.failedCount
        completedPassSkipped += status.skippedCount
        completedPassStallEscapes += adapter.stallEscapeCount()
        var actionId = 0L
        for ((unitIndex, unit) in executablePlanUnits(plan).withIndex()) {
            for (action in unit.actions) {
                val state = cursor.stateOf(actionId)
                val outcome = when (state) {
                    is ActionState.Failed -> state.reason.name
                    is ActionState.Skipped -> "SKIPPED"
                    else -> null
                }
                if (outcome != null) {
                    val pos = when (action) {
                        is PlanAction.PlaceTarget -> action.pos
                        is PlanAction.PlaceScaffold -> action.pos
                        is PlanAction.RemoveScaffold -> action.pos
                    }
                    actionIssues += AutomodeActionIssue(
                        pass = passIndex,
                        actionId = actionId,
                        unitIndex = unitIndex,
                        kind = action::class.simpleName ?: "PlanAction",
                        pos = pos.immutable(),
                        outcome = outcome,
                    )
                }
                actionId++
            }
        }
        finalStatusRecorded = true
    }

    private fun printerContext(): PlanRuntimeTickContext {
        return PlanRuntimeTickContext(
            tick = tick,
            stateAt = world::frozenStateAt,
            liveStateAt = network::observedLiveStateAt,
            recordWrite = { pos, state ->
                if (!BlockStateEquivalence.matches(state, world.liveStateAt(pos), scenario.behavior)) {
                    unexpectedWorldWrites++
                }
                world.recordWrite(pos, state)
                queueRevision++
            },
            eyePosition = playerFeet.add(0.0, EYE_HEIGHT, 0.0),
            reach = scenario.reach,
            playerFeetPos = playerFeet,
            settings = scenario.behavior,
            placementContext = placementBoundary::forHit,
            predictPlacement = { item, context ->
                val predicted = item.getPlacementState(context)
                recordPredictionMismatch(context, predicted)
                predicted
            },
            orientedPrediction = planOrientedPrediction(scenario.behavior),
            itemSupplier = ItemSupplier { state -> heldState = state; true },
            placementGateway = PlacementGateway { hit, requiredRotation ->
                val expected = heldState ?: return@PlacementGateway false
                val placedState = placementBoundary.placementState(expected, hit, requiredRotation)
                    ?: return@PlacementGateway false
                network.submitPlacement(hit, placedState)
            },
            destroy = network::submitRemoval,
            placementIntervalTicks = 1,
            attemptsPerTick = scenario.attemptsPerTick,
        )
    }

    private fun moverContext(frontier: MoverPlanFrontier): MoverTickContext {
        return MoverTickContext(
            sessionKey = sessionKey,
            playerPos = playerFeet,
            onGround = onGround,
            flying = flying,
            mayfly = true,
            isCreative = true,
            guiOpen = false,
            hurt = false,
            manualInput = false,
            correctionReceived = correctionThisTick,
            queueRevision = queueRevision,
            feedSnapshot = null,
            gateY = null,
            gatePhase = PrinterLayerGatePhase.ASCENT,
            missingWorld = emptyList(),
            isPlaceable = { false },
            expectedStateAt = { null },
            isPassableCell = { pos ->
                world.liveStateAt(pos).getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty
            },
            reach = scenario.reach,
            pathProbe = { from, to ->
                pathProbeInvocations++
                val clear = sweptVolumeCollisionFree(from, to, world::liveStateAt)
                if (!clear) {
                    pathProbeBlocked++
                }
                PathProbeResult(clear = clear, chunkLoaded = true)
            },
            onWorkPositionAbandoned = { abandonedWorkPositions++ },
            onWorkPositionUnproductive = { _, _ -> Unit },
            onPositionsUncoverable = { positions, _ -> uncoverablePositions += positions.size },
            onPlanPositionUnreachable = { pos ->
                adapter.requestFrontierUnreachable(pos)
            },
            canComplete = { true },
            onHoldHardCap = {
                hardCaps++
                appendHardCapDiagnostic(frontier)
            },
            planMode = true,
            planFrontier = frontier,
            planSessionFinal = sessionFinal,
            planStateAt = world::liveStateAt,
            planSupportStateAt = world::liveStateAt,
            planTravelBounds = travelBounds(),
        )
    }

    private fun applyMovement(command: MoverCommand): Unit {
        if (command.jump && onGround) {
            val jumped = playerFeet.add(0.0, JUMP_DELTA, 0.0)
            if (sweptVolumeCollisionFree(playerFeet, jumped, world::liveStateAt)) moveTo(jumped) else blockedMovementSteps++
            onGround = false
        }
        if (command.enableFlight) {
            flying = true
            onGround = false
        }
        verticalVelocity = controlledMoverVelocity(
            currentVelocity = Vec3(0.0, verticalVelocity, 0.0),
            command = command,
            flying = flying,
        ).y
        if (command.stopMovement) return
        if (flying) {
            verticalVelocity += command.vertical.toDouble() * CREATIVE_VERTICAL_INPUT
        }

        val requested = Vec3(
            command.horizontalX * HORIZONTAL_BLOCKS_PER_TICK,
            verticalVelocity,
            command.horizontalZ * HORIZONTAL_BLOCKS_PER_TICK,
        )
        if (requested.lengthSqr() == 0.0) {
            retainCreativeVerticalVelocity()
            return
        }
        val destination = playerFeet.add(requested)
        if (sweptVolumeCollisionFree(playerFeet, destination, world::liveStateAt)) {
            moveTo(destination, requested)
            retainCreativeVerticalVelocity()
            return
        }

        blockedMovementSteps++
        var position = playerFeet
        var downwardCollision = false
        for (axisDelta in listOf(
            Vec3(requested.x, 0.0, 0.0),
            Vec3(0.0, requested.y, 0.0),
            Vec3(0.0, 0.0, requested.z),
        )) {
            if (axisDelta.lengthSqr() == 0.0) continue
            val candidate = position.add(axisDelta)
            if (sweptVolumeCollisionFree(position, candidate, world::liveStateAt)) {
                position = candidate
            } else if (axisDelta.y < 0.0 && flying) {
                downwardCollision = true
            }
        }
        moveTo(position, requested)
        if (downwardCollision) {
            appendPhysicsEvent("flight-loss", destination = position, requested = requested)
            flightLosses++
            flying = false
            onGround = true
            verticalVelocity = 0.0
        } else {
            retainCreativeVerticalVelocity()
        }
    }

    private fun retainCreativeVerticalVelocity(): Unit {
        if (flying) verticalVelocity *= CREATIVE_VERTICAL_RETENTION
    }

    private fun moveTo(destination: Vec3, requested: Vec3 = destination.subtract(playerFeet)): Unit {
        val delta = destination.subtract(playerFeet)
        totalDistance += delta.length()
        horizontalDistance += sqrt(delta.x * delta.x + delta.z * delta.z)
        verticalDistance += abs(delta.y)
        if (delta.y > 0.0) ascentDistance += delta.y
        if (delta.y < 0.0) descentDistance -= delta.y
        playerFeet = destination
        observeBodyCollision("body-collision", destination, requested)
        if (!isBodyWithinTravelBounds(playerFeet)) outOfBoundsBody++
    }

    private fun observeBodyCollision(event: String, destination: Vec3, requested: Vec3): Unit {
        val colliding = !isFootprintCollisionFree(playerFeet.x, playerFeet.y, playerFeet.z, world::liveStateAt)
        if (colliding && !bodyCollisionActive) {
            bodyCollisions++
            if (bodyCollisions <= PHYSICS_EVENT_TRACE_LIMIT) {
                appendPhysicsEvent(event, destination = destination, requested = requested)
            }
        }
        bodyCollisionActive = colliding
    }

    private fun appendPhysicsEvent(event: String, destination: Vec3, requested: Vec3): Unit {
        val status = mover.status()
        trace += buildString(240) {
            append("{\"event\":\"").append(event).append('"')
            append(",\"tick\":").append(tick)
            append(",\"player\":[").append(decimal(playerFeet.x)).append(',')
                .append(decimal(playerFeet.y)).append(',').append(decimal(playerFeet.z)).append(']')
            append(",\"destination\":[").append(decimal(destination.x)).append(',')
                .append(decimal(destination.y)).append(',').append(decimal(destination.z)).append(']')
            append(",\"requested\":[").append(decimal(requested.x)).append(',')
                .append(decimal(requested.y)).append(',').append(decimal(requested.z)).append(']')
            append(",\"mover\":\"").append(status.state.name).append('"')
            append(",\"target\":")
            val target = status.target
            if (target == null) {
                append("null")
            } else {
                append('[').append(decimal(target.x)).append(',')
                    .append(decimal(target.y)).append(',').append(decimal(target.z)).append(']')
            }
            append(",\"nearbySolid\":[")
            append(nearbySolidCells(destination).joinToString(",") { pos ->
                "[${pos.x},${pos.y},${pos.z}]"
            })
            append(']')
            append('}')
        }
    }

    private fun appendPlacementViolation(pos: BlockPos, actual: BlockState, hit: BlockHitResult): Unit {
        trace += buildString(384) {
            append("{\"event\":\"player-placement-collision\"")
            append(",\"tick\":").append(tick)
            append(",\"player\":[").append(decimal(playerFeet.x)).append(',')
                .append(decimal(playerFeet.y)).append(',').append(decimal(playerFeet.z)).append(']')
            append(",\"pos\":[").append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(']')
            append(",\"support\":[").append(hit.blockPos.x).append(',').append(hit.blockPos.y)
                .append(',').append(hit.blockPos.z).append(']')
            append(",\"state\":\"").append(traceJsonEscape(actual.toString())).append('\"')
            append('}')
        }
    }

    private fun recordPredictionMismatch(context: BlockPlaceContext, predicted: BlockState?): Unit {
        val pos = context.clickedPos.immutable()
        val expected = currentAllowedPlacements[pos] ?: return
        if (predicted != null && BlockStateEquivalence.matches(expected, predicted, scenario.behavior)) return
        if (predictionMismatchDiagnostics >= PREDICTION_DIAGNOSTIC_LIMIT) return
        predictionMismatchDiagnostics++
        trace += buildString(512) {
            append("{\"event\":\"placement-prediction-mismatch\"")
            append(",\"tick\":").append(tick)
            append(",\"pass\":").append(passIndex)
            append(",\"pos\":[").append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(']')
            append(",\"face\":\"").append(context.clickedFace.name).append('\"')
            val click = context.clickLocation
            append(",\"click\":[").append(decimal(click.x)).append(',')
                .append(decimal(click.y)).append(',').append(decimal(click.z)).append(']')
            append(",\"expected\":\"").append(traceJsonEscape(expected.toString())).append('\"')
            append(",\"predicted\":")
            if (predicted == null) {
                append("null")
            } else {
                append('\"').append(traceJsonEscape(predicted.toString())).append('\"')
            }
            append('}')
        }
    }

    private fun appendHardCapDiagnostic(frontier: MoverPlanFrontier): Unit {
        val status = mover.status()
        trace += buildString(768) {
            append("{\"event\":\"hold-hard-cap\"")
            append(",\"tick\":").append(tick)
            append(",\"pass\":").append(passIndex)
            append(",\"player\":[").append(decimal(playerFeet.x)).append(',')
                .append(decimal(playerFeet.y)).append(',').append(decimal(playerFeet.z)).append(']')
            append(",\"mover\":\"").append(status.state.name).append('\"')
            append(",\"target\":")
            val target = status.target
            if (target == null) {
                append("null")
            } else {
                append('[').append(decimal(target.x)).append(',')
                    .append(decimal(target.y)).append(',').append(decimal(target.z)).append(']')
            }
            append(",\"reach\":").append(frontier.waitingForReach.size)
            append(",\"column\":").append(frontier.columnBlocked.size)
            append(",\"flight\":").append(frontier.inFlight.size)
            append(",\"reachHead\":")
            val reachHead = frontier.waitingForReach.firstOrNull()?.pos
            if (reachHead == null) {
                append("null")
            } else {
                append('[').append(reachHead.x).append(',').append(reachHead.y).append(',').append(reachHead.z).append(']')
            }
            append('}')
        }
    }

    private fun appendMissingTargetDiagnostics(missingTargets: List<BlockPos>): Unit {
        for (pos in missingTargets) {
            val expected = contentExpected[pos] ?: continue
            val actual = world.liveStateAt(pos)
            trace += buildString(1_024) {
                append("{\"event\":\"missing-target\"")
                append(",\"tick\":").append(tick)
                append(",\"pos\":[").append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(']')
                append(",\"expected\":\"").append(traceJsonEscape(expected.toString())).append('\"')
                append(",\"actual\":\"").append(traceJsonEscape(actual.toString())).append('\"')
                append(",\"neighbors\":[")
                Direction.values().forEachIndexed { index, direction ->
                    if (index > 0) append(',')
                    val neighborPos = pos.relative(direction)
                    val neighbor = world.liveStateAt(neighborPos)
                    append("{\"direction\":\"").append(direction.name).append("\"")
                    append(",\"pos\":[").append(neighborPos.x).append(',').append(neighborPos.y)
                        .append(',').append(neighborPos.z).append(']')
                    append(",\"state\":\"").append(traceJsonEscape(neighbor.toString())).append("\"}")
                }
                append("]}")
            }
        }
    }

    private fun nearbySolidCells(position: Vec3): List<BlockPos> {
        val cells = mutableListOf<BlockPos>()
        for (x in floor(position.x - PLAYER_HALF_WIDTH).toInt()..floor(position.x + PLAYER_HALF_WIDTH).toInt()) {
            for (y in floor(position.y).toInt() - 1..floor(position.y + PLAYER_HEIGHT).toInt()) {
                for (z in floor(position.z - PLAYER_HALF_WIDTH).toInt()..floor(position.z + PLAYER_HALF_WIDTH).toInt()) {
                    val pos = BlockPos(x, y, z)
                    if (!world.liveStateAt(pos).isAir) cells += pos
                }
            }
        }
        return cells
    }

    private fun observeMovementLeg(): Unit {
        val status = mover.status()
        val current = status.state
        val target = status.target
        if (target != previousMovementTarget && (target != null || previousMovementTarget != null)) {
            targetChanges++
        }
        previousMovementTarget = target
        if (current == MoverState.HOLD) holdTicks++
        if (previousMoverState != MoverState.CRUISE && current == MoverState.CRUISE) {
            movementLegs++
            movementLegStartRevision = queueRevision
            movementLegStartSubmissions = network.submissionCount
            movementLegStartDistance = totalDistance
        }
        if (previousMoverState != MoverState.HOLD && current == MoverState.HOLD) {
            arrivalLegStartRevision = movementLegStartRevision
            arrivalLegStartSubmissions = movementLegStartSubmissions
            arrivalLegStartDistance = movementLegStartDistance
        } else if (previousMoverState == MoverState.HOLD && current != MoverState.HOLD) {
            val startRevision = arrivalLegStartRevision
            val startSubmissions = arrivalLegStartSubmissions
            val startDistance = arrivalLegStartDistance
            if (
                startRevision != null &&
                startSubmissions != null &&
                startDistance != null &&
                startRevision == queueRevision &&
                startSubmissions == network.submissionCount &&
                current != MoverState.ABORTED
            ) {
                nonproductiveMovementLegs++
                wastedMovementDistance += totalDistance - startDistance
            }
            arrivalLegStartRevision = null
            arrivalLegStartSubmissions = null
            arrivalLegStartDistance = null
            if (current == MoverState.CRUISE) {
                movementLegStartRevision = queueRevision
                movementLegStartSubmissions = network.submissionCount
                movementLegStartDistance = totalDistance
            }
        }
        previousMoverState = current
    }

    private fun applyScriptedCorrection(): Unit {
        if (tick !in scenario.correctionTicks) return
        serverCorrections++
        val nudged = playerFeet.add(CORRECTION_NUDGE, 0.0, 0.0)
        if (isFootprintCollisionFree(nudged.x, nudged.y, nudged.z, world::liveStateAt)) {
            playerFeet = nudged
            if (!isBodyWithinTravelBounds(playerFeet)) outOfBoundsBody++
        }
        correctionThisTick = true
    }

    private fun appendTrace(frontier: MoverPlanFrontier, command: MoverCommand): Unit {
        val status = mover.status()
        val target = status.target
        val inFlight = frontier.inFlight.mapTo(HashSet<BlockPos>()) { pos -> pos.immutable() }
        val inFlightCollisionStates = frontier.inFlightCollisionStates
            .associate { collision -> collision.pos.immutable() to collision.expected }
        val reservedStateAt: (BlockPos) -> BlockState = { pos ->
            inFlightCollisionStates[pos] ?:
                if (pos in inFlight) Blocks.STONE.defaultBlockState() else world.liveStateAt(pos)
        }
        val unsafeReservedSegment = target != null &&
            inFlight.isNotEmpty() &&
            !sweptVolumeCollisionFree(playerFeet, target, reservedStateAt)
        trace += buildString(320) {
            append("{\"tick\":").append(tick)
            append(",\"pass\":").append(passIndex)
            append(",\"player\":[").append(decimal(playerFeet.x)).append(',')
                .append(decimal(playerFeet.y)).append(',').append(decimal(playerFeet.z)).append(']')
            append(",\"mover\":\"").append(status.state.name).append('\"')
            append(",\"target\":")
            if (target == null) {
                append("null")
            } else {
                append('[').append(decimal(target.x)).append(',')
                    .append(decimal(target.y)).append(',').append(decimal(target.z)).append(']')
            }
            append(",\"flying\":").append(flying)
            append(",\"onGround\":").append(onGround)
            append(",\"revision\":").append(queueRevision)
            append(",\"frontier\":{")
            append("\"reach\":").append(frontier.waitingForReach.size)
            append(",\"column\":").append(frontier.columnBlocked.size)
            append(",\"flight\":").append(frontier.inFlight.size)
            append(",\"flightPositions\":[")
            frontier.inFlight.sortedWith(compareBy<BlockPos>({ pos -> pos.y }, { pos -> pos.x }, { pos -> pos.z }))
                .forEachIndexed { index, pos ->
                    if (index > 0) append(',')
                    append('[').append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(']')
                }
            append(']')
            if (frontier.inFlightCollisionStates.isNotEmpty()) {
                append(",\"flightStates\":[")
                frontier.inFlightCollisionStates
                    .sortedWith(compareBy<MoverPlanFrontierTarget>({ target -> target.pos.y }, { target -> target.pos.x }, { target -> target.pos.z }))
                    .forEachIndexed { index, collision ->
                        if (index > 0) append(',')
                        val pos = collision.pos
                        append("{\"pos\":[").append(pos.x).append(',').append(pos.y).append(',').append(pos.z)
                            .append("]")
                        append(",\"state\":\"").append(traceJsonEscape(collision.expected.toString())).append("\"}")
                    }
                append(']')
            }
            if (unsafeReservedSegment) {
                append(",\"unsafeReservedSegment\":true")
                append(",\"reachPositions\":[")
                frontier.waitingForReach.forEachIndexed { index, pending ->
                    if (index > 0) append(',')
                    val pos = pending.pos
                    append('[').append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(']')
                }
                append(']')
            }
            append('}')
            append(",\"command\":[").append(decimal(command.horizontalX)).append(',')
                .append(command.vertical).append(',').append(decimal(command.horizontalZ)).append(']')
            append(",\"network\":{\"submissions\":").append(network.submissionCount)
            append(",\"accepted\":").append(network.acceptedOutcomeCount)
            append(",\"rejected\":").append(network.rejectedOutcomeCount)
            append(",\"wrongState\":").append(network.wrongStateOutcomeCount)
            append(",\"timeout\":").append(network.timeoutOutcomeCount).append('}')
            append(",\"oracle\":{\"pathProbes\":").append(pathProbeInvocations)
            append(",\"blockedProbes\":").append(pathProbeBlocked)
            append(",\"blockedMovement\":").append(blockedMovementSteps)
            append(",\"body\":").append(bodyCollisions).append('}')
            append('}')
        }
    }

    private fun appendTerminalDiagnostics(): Unit {
        val center = BlockPos(
            floor(playerFeet.x).toInt(),
            floor(playerFeet.y).toInt(),
            floor(playerFeet.z).toInt(),
        )
        trace += buildString(4_096) {
            append("{\"event\":\"terminal-diagnostic\"")
            append(",\"tick\":").append(tick)
            append(",\"player\":[").append(decimal(playerFeet.x)).append(',')
                .append(decimal(playerFeet.y)).append(',').append(decimal(playerFeet.z)).append(']')
            append(",\"blocks\":[")
            var firstBlock = true
            for (x in center.x - 2..center.x + 2) {
                for (y in center.y - 2..center.y + 3) {
                    for (z in center.z - 2..center.z + 2) {
                        val pos = BlockPos(x, y, z)
                        val state = world.liveStateAt(pos)
                        if (state.isAir) continue
                        if (!firstBlock) append(',')
                        firstBlock = false
                        append("{\"pos\":[").append(x).append(',').append(y).append(',').append(z).append(']')
                        append(",\"state\":\"").append(traceJsonEscape(state.toString())).append('"')
                        append(",\"shape\":[")
                        state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).toAabbs()
                            .forEachIndexed { index, box ->
                                if (index > 0) append(',')
                                append('[').append(decimal(box.minX)).append(',').append(decimal(box.minY))
                                    .append(',').append(decimal(box.minZ)).append(',').append(decimal(box.maxX))
                                    .append(',').append(decimal(box.maxY)).append(',').append(decimal(box.maxZ))
                                    .append(']')
                            }
                        append("]}")
                    }
                }
            }
            append(']')
            append(",\"sameHeightExits\":[")
            var firstExit = true
            for (x in center.x - 2..center.x + 2) {
                for (z in center.z - 2..center.z + 2) {
                    if (x == center.x && z == center.z) continue
                    val destination = Vec3(x + 0.5, playerFeet.y, z + 0.5)
                    val endpointClear = isFootprintCollisionFree(
                        destination.x,
                        destination.y,
                        destination.z,
                        world::liveStateAt,
                    )
                    val sweepClear = sweptVolumeCollisionFree(playerFeet, destination, world::liveStateAt)
                    if (!firstExit) append(',')
                    firstExit = false
                    append("{\"cell\":[").append(x).append(',').append(center.y).append(',').append(z).append(']')
                    append(",\"endpointClear\":").append(endpointClear)
                    append(",\"sweepClear\":").append(sweepClear).append('}')
                }
            }
            append("]}")
        }
    }

    private fun traceJsonEscape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    private fun defaultStartFeet(): Vec3 {
        val minX = scenario.content.minOf { (pos, _) -> pos.x }
        val minZ = scenario.content.minOf { (pos, _) -> pos.z }
        return Vec3(minX - 5.5, minContentY + 0.01, minZ - 5.5)
    }

    private fun travelBounds(): Pair<BlockPos, BlockPos> {
        val positions = scenario.content.map { (pos, _) -> pos } + scenario.initialWorld.keys
        val min = BlockPos(
            positions.minOf(BlockPos::getX) - TRAVEL_MARGIN,
            positions.minOf(BlockPos::getY) - TRAVEL_MARGIN,
            positions.minOf(BlockPos::getZ) - TRAVEL_MARGIN,
        )
        val max = BlockPos(
            positions.maxOf(BlockPos::getX) + TRAVEL_MARGIN,
            positions.maxOf(BlockPos::getY) + TRAVEL_MARGIN,
            positions.maxOf(BlockPos::getZ) + TRAVEL_MARGIN,
        )
        return min to max
    }

    private fun withinPlanningBounds(pos: BlockPos): Boolean {
        val (min, max) = travelBounds()
        return pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
    }

    private fun isBodyWithinTravelBounds(position: Vec3): Boolean {
        val (min, max) = travelBounds()
        return position.x - PLAYER_HALF_WIDTH >= min.x &&
            position.x + PLAYER_HALF_WIDTH <= max.x + 1.0 &&
            position.y >= min.y &&
            position.y + PLAYER_HEIGHT <= max.y + 1.0 &&
            position.z - PLAYER_HALF_WIDTH >= min.z &&
            position.z + PLAYER_HALF_WIDTH <= max.z + 1.0
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    private inner class SimulatedPlacementBoundary {
        private val level: Level = mockk(relaxed = true)
        private val player: Player = mockk(relaxed = true)
        private var yaw: Float = 0f
        private var pitch: Float = 0f

        init {
            every { level.getBlockState(any()) } answers {
                world.liveStateAt(firstArg<BlockPos>())
            }
            every { level.getFluidState(any()) } answers {
                world.liveStateAt(firstArg<BlockPos>()).fluidState
            }
            every { level.hasNeighborSignal(any()) } returns false
            every {
                level.isUnobstructed(any(), any(), any<CollisionContext>())
            } returns true

            every { player.yRot } answers { yaw }
            every { player.xRot } answers { pitch }
            every { player.getViewYRot(any()) } answers { yaw }
            every { player.getViewXRot(any()) } answers { pitch }
            every { player.direction } answers { horizontalDirection(yaw) }
            every { player.setYRot(any()) } answers { yaw = firstArg() }
            every { player.setXRot(any()) } answers { pitch = firstArg() }
        }

        internal fun forHit(expectedState: BlockState, hit: BlockHitResult): BlockPlaceContext {
            return BlockPlaceContext(
                level,
                player,
                InteractionHand.MAIN_HAND,
                ItemStack(expectedState.block.asItem()),
                hit,
            )
        }

        internal fun placementState(
            expectedState: BlockState,
            hit: BlockHitResult,
            requiredRotation: PlacementRotation?,
        ): BlockState? {
            val item = eligiblePlacementBlockItem(expectedState) ?: return null
            val predict = { item.getPlacementState(forHit(expectedState, hit)) }
            if (requiredRotation == null) return predict()

            var placedState: BlockState? = null
            val submitted = submitOrientedPlacement(
                requiredRotation = requiredRotation,
                originalRotation = PlacementRotation(yaw, pitch),
                setLocalRotation = { rotation ->
                    yaw = rotation.yaw
                    pitch = rotation.pitch
                },
                sendServerRotation = {},
                useItemOn = {
                    placedState = predict()
                    placedState != null
                },
            )
            return if (submitted) placedState else null
        }

        private fun horizontalDirection(rotationYaw: Float): Direction {
            val normalized = ((rotationYaw % 360f) + 360f) % 360f
            return when {
                normalized < 45f || normalized >= 315f -> Direction.SOUTH
                normalized < 135f -> Direction.WEST
                normalized < 225f -> Direction.NORTH
                else -> Direction.EAST
            }
        }
    }

    private inner class SimulatedNetwork {
        private val scheduled: MutableList<ScheduledMutation> = mutableListOf()
        private val unstableObservations: MutableMap<BlockPos, Long> = LinkedHashMap()
        private var sequence: Long = 0L
        private var placeSubmissions: Int = 0
        private var removeSubmissions: Int = 0
        private var acceptedOutcomes: Int = 0
        private var rejectedOutcomes: Int = 0
        private var wrongStateOutcomes: Int = 0
        private var timeoutOutcomes: Int = 0

        internal val submissionCount: Int
            get() = placeSubmissions + removeSubmissions
        internal val placementSubmissionCount: Int get() = placeSubmissions
        internal val removalSubmissionCount: Int get() = removeSubmissions
        internal val acceptedOutcomeCount: Int get() = acceptedOutcomes
        internal val rejectedOutcomeCount: Int get() = rejectedOutcomes
        internal val wrongStateOutcomeCount: Int get() = wrongStateOutcomes
        internal val timeoutOutcomeCount: Int get() = timeoutOutcomes

        internal fun submitPlacement(hit: BlockHitResult, placedState: BlockState): Boolean {
            val target = hit.blockPos.relative(hit.direction).immutable()
            placeSubmissions++
            validatePlacement(hit, target, placedState)
            val outcome = faultFor(placeSubmissions, SimulatedSubmissionKind.PLACE)
                ?: SimulatedPacketOutcome.ACCEPT
            recordOutcome(outcome)
            val due = tick + effectiveLatency(placeSubmissions)
            when (outcome) {
                SimulatedPacketOutcome.ACCEPT -> schedule(due, target, placedState)
                SimulatedPacketOutcome.REJECT -> Unit
                SimulatedPacketOutcome.WRONG_STATE_THEN_CORRECT -> {
                    val baseline = world.liveStateAt(target)
                    schedule(due, target, Blocks.DIRT.defaultBlockState())
                    schedule(due + PrinterAttemptTracker.SETTLE_TICKS, target, baseline)
                }
                SimulatedPacketOutcome.TIMEOUT -> {
                    unstableObservations[target] = tick + PrinterAttemptTracker.DEADLINE_TICKS + 1L
                }
            }
            return true
        }

        internal fun submitRemoval(pos: BlockPos): Boolean {
            removeSubmissions++
            validateRemoval(pos)
            val outcome = faultFor(removeSubmissions, SimulatedSubmissionKind.REMOVE)
                ?: SimulatedPacketOutcome.ACCEPT
            recordOutcome(outcome)
            val due = tick + effectiveLatency(removeSubmissions)
            when (outcome) {
                SimulatedPacketOutcome.ACCEPT -> schedule(due, pos.immutable(), air)
                SimulatedPacketOutcome.REJECT -> Unit
                SimulatedPacketOutcome.WRONG_STATE_THEN_CORRECT -> {
                    val baseline = world.liveStateAt(pos)
                    schedule(due, pos.immutable(), Blocks.DIRT.defaultBlockState())
                    schedule(due + PrinterAttemptTracker.SETTLE_TICKS, pos.immutable(), baseline)
                }
                SimulatedPacketOutcome.TIMEOUT -> {
                    unstableObservations[pos.immutable()] = tick + PrinterAttemptTracker.DEADLINE_TICKS + 1L
                }
            }
            return true
        }

        internal fun observedLiveStateAt(pos: BlockPos): BlockState {
            val until = unstableObservations[pos]
            if (until != null) {
                if (tick <= until) {
                    return if (((tick + scenario.seed) and 1L) == 0L) {
                        Blocks.CAVE_AIR.defaultBlockState()
                    } else {
                        Blocks.VOID_AIR.defaultBlockState()
                    }
                }
                unstableObservations.remove(pos)
            }
            return world.liveStateAt(pos)
        }

        internal fun deliverDue(): Unit {
            if (scheduled.isEmpty()) return
            scheduled.sortWith(compareBy<ScheduledMutation> { mutation -> mutation.dueTick }.thenBy { it.sequence })
            val iterator = scheduled.iterator()
            while (iterator.hasNext()) {
                val mutation = iterator.next()
                if (mutation.dueTick > tick) break
                recordScaffoldMutation(mutation)
                world.writeLive(mutation.pos, mutation.state)
                iterator.remove()
            }
        }

        private fun validatePlacement(hit: BlockHitResult, pos: BlockPos, actual: BlockState): Unit {
            val expected = currentAllowedPlacements[pos]
            if (expected == null || !BlockStateEquivalence.matches(expected, actual, scenario.behavior)) {
                invalidPlacements++
                unexpectedWorldWrites++
            }
            if (playerFeet.add(0.0, EYE_HEIGHT, 0.0).distanceToSqr(hit.location) > scenario.reach * scenario.reach) {
                reachOutsideClicks++
            }
            if (!isSupportingState(world.liveStateAt(hit.blockPos))) supportMissingClicks++
            if (isPlacementBlockedByPlayer(pos, actual, playerFeet, world::liveStateAt)) {
                playerColumnClicks++
                appendPlacementViolation(pos, actual, hit)
            }
        }

        private fun validateRemoval(pos: BlockPos): Unit {
            if (pos !in plannedScaffolds || world.liveStateAt(pos).isAir) unexpectedWorldWrites++
        }

        private fun recordOutcome(outcome: SimulatedPacketOutcome): Unit {
            when (outcome) {
                SimulatedPacketOutcome.ACCEPT -> acceptedOutcomes++
                SimulatedPacketOutcome.REJECT -> rejectedOutcomes++
                SimulatedPacketOutcome.WRONG_STATE_THEN_CORRECT -> wrongStateOutcomes++
                SimulatedPacketOutcome.TIMEOUT -> timeoutOutcomes++
            }
        }

        private fun recordScaffoldMutation(mutation: ScheduledMutation): Unit {
            if (mutation.pos !in plannedScaffolds || mutation.pos in contentExpected) return
            val before = world.liveStateAt(mutation.pos)
            if (
                !BlockStateEquivalence.matches(before, SCAFFOLD_BLOCK_STATE, scenario.behavior) &&
                BlockStateEquivalence.matches(mutation.state, SCAFFOLD_BLOCK_STATE, scenario.behavior)
            ) {
                scaffoldsPlaced++
            }
            if (
                BlockStateEquivalence.matches(before, SCAFFOLD_BLOCK_STATE, scenario.behavior) &&
                mutation.state.isAir
            ) {
                scaffoldsBroken++
            }
        }

        private fun faultFor(index: Int, kind: SimulatedSubmissionKind): SimulatedPacketOutcome? {
            return scenario.faults.firstOrNull { fault ->
                fault.submissionIndex == index && fault.kind == kind
            }?.outcome
        }

        private fun effectiveLatency(index: Int): Long {
            val mixed = mix64(scenario.seed xor index.toLong())
            val selected = ((mixed ushr 1) % scenario.latencyTicks.size.toLong()).toInt()
            return scenario.latencyTicks[selected].coerceAtLeast(1).toLong()
        }

        private fun schedule(dueTick: Long, pos: BlockPos, state: BlockState): Unit {
            scheduled += ScheduledMutation(dueTick, sequence++, pos.immutable(), state)
        }
    }

    private data class ScheduledMutation(
        val dueTick: Long,
        val sequence: Long,
        val pos: BlockPos,
        val state: BlockState,
    )

    private class SimulatedWorld(
        private val minY: Int,
        private val ground: BlockState,
        private val air: BlockState,
        initial: Map<BlockPos, BlockState>,
    ) {
        private val live: MutableMap<BlockPos, BlockState> = LinkedHashMap()
        private val frozen: MutableMap<BlockPos, BlockState> = LinkedHashMap()

        init {
            for ((pos, state) in initial.entries.sortedWith(compareBy({ it.key.y }, { it.key.x }, { it.key.z }))) {
                live[pos.immutable()] = state
                frozen[pos.immutable()] = state
            }
        }

        internal fun liveStateAt(pos: BlockPos): BlockState = live[pos] ?: if (pos.y < minY) ground else air

        internal fun frozenStateAt(pos: BlockPos): BlockState = frozen[pos] ?: if (pos.y < minY) ground else air

        internal fun writeLive(pos: BlockPos, state: BlockState): Unit {
            if (state.isAir) live.remove(pos) else live[pos.immutable()] = state
        }

        internal fun recordWrite(pos: BlockPos, state: BlockState): Unit {
            if (state.isAir) frozen.remove(pos) else frozen[pos.immutable()] = state
        }

        internal fun liveEntries(): Map<BlockPos, BlockState> = live.toMap()

        internal fun resyncFrozenFromLive(): Unit {
            frozen.clear()
            frozen.putAll(live)
        }
    }

    private companion object {
        private const val EYE_HEIGHT: Double = 1.62
        private const val HORIZONTAL_BLOCKS_PER_TICK: Double = 0.30
        private const val CREATIVE_VERTICAL_INPUT: Double = 0.15
        private const val CREATIVE_VERTICAL_RETENTION: Double = 0.60
        private const val PHYSICS_EVENT_TRACE_LIMIT: Int = 20
        private const val PREDICTION_DIAGNOSTIC_LIMIT: Int = 2_000
        private const val JUMP_DELTA: Double = 0.42
        private const val CORRECTION_NUDGE: Double = 0.05
        private const val TRAVEL_MARGIN: Int = 10
        private const val PLAYER_HALF_WIDTH: Double = 0.3
        private const val PLAYER_HEIGHT: Double = 1.8

        private fun mix64(input: Long): Long {
            var value = input
            value = (value xor (value ushr 30)) * -4658895280553007687L
            value = (value xor (value ushr 27)) * -7723592293110705685L
            return value xor (value ushr 31)
        }
    }
}

internal fun lineBuilding(length: Int, start: BlockPos = BlockPos.ZERO): List<Pair<BlockPos, BlockState>> {
    require(length > 0)
    val stone = Blocks.STONE.defaultBlockState()
    return (0 until length).map { offset -> start.offset(offset, 0, 0) to stone }
}

internal fun euclideanDistance(from: Vec3, to: Vec3): Double {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val dz = to.z - from.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

internal fun feetCell(position: Vec3): BlockPos {
    return BlockPos(floor(position.x), floor(position.y), floor(position.z))
}
