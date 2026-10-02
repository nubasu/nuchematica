package com.nubasu.nuchematica.automode

import com.nubasu.nuchematica.mover.FlightPassabilityProfile
import com.nubasu.nuchematica.mover.MoverCore
import com.nubasu.nuchematica.mover.MoverPlanFrontier
import com.nubasu.nuchematica.mover.MoverPlanFrontierTarget
import com.nubasu.nuchematica.mover.MoverSessionKey
import com.nubasu.nuchematica.mover.MoverState
import com.nubasu.nuchematica.mover.MoverTickContext
import com.nubasu.nuchematica.mover.PathProbeResult
import com.nubasu.nuchematica.mover.findFlightPath
import com.nubasu.nuchematica.mover.planRoute
import com.nubasu.nuchematica.mover.sweptVolumeCollisionFree
import com.nubasu.nuchematica.printer.PlacementBehaviorSettings
import com.nubasu.nuchematica.printer.PlacementResolution
import com.nubasu.nuchematica.printer.PlacementRotation
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.availableSupportHitPoints
import com.nubasu.nuchematica.printer.resolvePlacement
import com.nubasu.nuchematica.printer.supportHitPoint
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.math.floor

internal class AutomodeKnownFailureTest {
    private val behavior = PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)
    private val placementContext: BlockPlaceContext = mockk(relaxed = true)

    @Test
    internal fun everyPlannedCoverageHasARealSupportFaceWithinReach(): Unit {
        val target = BlockPos.ZERO
        val expected = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val mismatches = mutableListOf<String>()

        for (supportDirection in Direction.values()) {
            val support = target.relative(supportDirection)
            val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == support) expected else air }
            for (step in 30..100) {
                val reach = step / 20.0
                val planned = planRoute(
                    placeableMissing = listOf(target),
                    reach = reach,
                    expectedStateAt = { expected },
                    coverageHitPointsAt = { pos, state -> availableSupportHitPoints(pos, state, stateAt) },
                    coverageMargin = 0.500001,
                    centerCandidateForScoring = true,
                    scoringPositionForCandidate = { raw -> fittedFeet(raw, stateAt) ?: raw },
                    excludeOwnColumnFromCoverage = true,
                    preserveInputOrder = true,
                    coverContiguousPrefix = true,
                )
                val entry = planned.route.firstOrNull() ?: continue
                val feet = fittedFeet(entry.target, stateAt) ?: continue
                val resolution = resolveAt(target, expected, stateAt, feet, reach)
                if (resolution !is PlacementResolution.Resolved) {
                    mismatches += "support=$supportDirection reach=$reach feet=$feet resolution=$resolution"
                    break
                }
            }
        }

        assertTrue(mismatches.isEmpty()) {
            "planner credited support faces that do not exist in the world: ${mismatches.joinToString()}"
        }
    }

    @Test
    internal fun anyPositionInsideArrivalRadiusStillKeepsCoveredTargetInReach(): Unit {
        val target = BlockPos.ZERO
        val expected = Blocks.STONE.defaultBlockState()
        val supportDirection = Direction.DOWN
        val support = target.relative(supportDirection)
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == support) expected else air }
        val reach = 4.0
        val planned = planRoute(
            placeableMissing = listOf(target),
            reach = reach,
            expectedStateAt = { expected },
            coverageHitPointsAt = { pos, state -> availableSupportHitPoints(pos, state, stateAt) },
            coverageMargin = 0.500001,
            centerCandidateForScoring = true,
            scoringPositionForCandidate = { raw -> fittedFeet(raw, stateAt) ?: raw },
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
            coverContiguousPrefix = true,
        )
        val entry = planned.route.single()
        val feet = requireNotNull(fittedFeet(entry.target, stateAt))
        val eye = feet.add(0.0, EYE_HEIGHT, 0.0)
        val hit = requireNotNull(supportHitPoint(target, expected, supportDirection))
        val away = eye.subtract(hit).normalize().scale(ARRIVAL_RADIUS)
        val legalArrival = feet.add(away)

        val resolution = resolveAt(target, expected, stateAt, legalArrival, reach)

        assertTrue(resolution is PlacementResolution.Resolved) {
            "coverage was only reachable at the exact stand center; arrival=$legalArrival result=$resolution"
        }
    }

    @Test
    internal fun collisionAwareAStarNeverReturnsASegmentThroughASlabEdge(): Unit {
        val slabPos = BlockPos(1, 0, 0)
        val slab = Blocks.STONE_SLAB.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == slabPos) slab else air }
        val bounds = AABB(-3.0, -3.0, -3.0, 5.0, 5.0, 3.0)
        val profile = FlightPassabilityProfile.collisionAware(bounds, stateAt)
        val start = BlockPos.ZERO
        val goal = slabPos

        val result = findFlightPath(start, goal, isPassableCell = { true }, profile = profile)
        val path = requireNotNull(result.path)
        val heights = requireNotNull(result.feetHeights)
        var previous = Vec3(start.x + 0.5, requireNotNull(profile.feetHeightAt(start)), start.z + 0.5)
        val unsafe = mutableListOf<Pair<Vec3, Vec3>>()
        for ((index, cell) in path.withIndex()) {
            val next = Vec3(cell.x + 0.5, heights[index], cell.z + 0.5)
            if (!sweptVolumeCollisionFree(previous, next, stateAt)) unsafe += previous to next
            previous = next
        }

        assertTrue(unsafe.isEmpty()) { "A* returned unsafe transitions: $unsafe path=$path heights=$heights" }
    }

    @Test
    internal fun planFallbackCanUseARaisedOuterStandWhenLegacyCandidatesAreBlocked(): Unit {
        val target = BlockPos.ZERO
        val expected = Blocks.STONE.defaultBlockState()
        val acceptedCell = BlockPos(3, 1, 0)

        val planned = planRoute(
            placeableMissing = listOf(target),
            reach = 4.5,
            expectedStateAt = { expected },
            coverageMargin = 0.500001,
            centerCandidateForScoring = true,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
            includePlanFallbackCandidates = true,
            isStandUsable = { raw, _ ->
                BlockPos(floor(raw.x), floor(raw.y), floor(raw.z)) == acceptedCell
            },
            coverContiguousPrefix = true,
        )

        val selected = planned.route.single().target
        assertEquals(acceptedCell, BlockPos(floor(selected.x), floor(selected.y), floor(selected.z)))
    }

    @Test
    internal fun uncoverableStrictHeadDoesNotSendMoverToLaterPlanActions(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val head = BlockPos.ZERO
        val later = BlockPos(30, 0, 0)
        val frontier = MoverPlanFrontier(
            waitingForReach = listOf(
                MoverPlanFrontierTarget(head, stone),
                MoverPlanFrontierTarget(later, stone),
            ),
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
        val standRejectingStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.y in -5..5 && pos.x in -5..5 && pos.z in -5..5) stone else air
        }
        val core = MoverCore()
        val start = Vec3(-10.0, 1.0, 0.0)
        val bounds = BlockPos(-50, -20, -20) to BlockPos(50, 20, 20)
        core.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
        core.tick(moverContext(core, start, onGround = true, flying = false, frontier, standRejectingStateAt, bounds))
        core.tick(moverContext(core, start, onGround = false, flying = false, frontier, standRejectingStateAt, bounds))
        core.tick(moverContext(core, start, onGround = false, flying = true, frontier, standRejectingStateAt, bounds))

        assertEquals(MoverState.HOLD, core.status().state)
        assertEquals(null, core.status().target, "later work must not bypass an uncoverable strict head")
    }

    @Test
    internal fun nextLayerTargetColumnIsReservedBeforeMoverParksThere(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val futureTarget = BlockPos(2, 1, 0)
        val result = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "next-layer-column",
                content = listOf(BlockPos.ZERO to stone, futureTarget to stone),
                initialWorld = mapOf(futureTarget.below() to stone),
                seed = 31L,
                latencyTicks = listOf(2),
                maxTicks = 5_000L,
            ),
        ).run()

        val columnBlockedTicks = result.trace.count { line -> "\"column\":1" in line }
        assertEquals(0, columnBlockedTicks) {
            "mover parked in a known next-layer placement column for $columnBlockedTicks ticks"
        }
        assertTrue(result.cleanViolations().isEmpty()) { "strict simulation violations: ${result.cleanViolations()}" }
    }

    @Test
    internal fun scaffoldChainClimbsWithoutRepeatedDeepReturns(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val result = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "scaffold-chain-ascent",
                content = listOf(
                    BlockPos(8, 0, 0) to stone,
                    BlockPos(0, 4, 0) to stone,
                ),
                seed = 37L,
                latencyTicks = listOf(2),
                maxTicks = 5_000L,
            ),
        ).run()

        println("[automode-sim] scaffold-chain ${result.metrics}")
        assertTrue(result.cleanViolations().isEmpty()) { "strict simulation violations: ${result.cleanViolations()}" }
        assertTrue(result.metrics.scaffoldsPlaced >= 4) {
            "fixture did not exercise a deep scaffold chain: ${result.metrics.scaffoldsPlaced}"
        }
        assertEquals(result.metrics.scaffoldsPlaced, result.metrics.scaffoldsBroken)
        assertTrue(result.metrics.verticalDistance <= MAX_SCAFFOLD_CHAIN_VERTICAL_DISTANCE) {
            "scaffold actions caused repeated deep returns: vertical=${result.metrics.verticalDistance}"
        }
    }

    private fun resolveAt(
        target: BlockPos,
        expected: BlockState,
        stateAt: (BlockPos) -> BlockState,
        feet: Vec3,
        reach: Double,
    ): PlacementResolution {
        return resolvePlacement(
            worldPos = target,
            expectedState = expected,
            stateAt = stateAt,
            eyePosition = feet.add(0.0, EYE_HEIGHT, 0.0),
            reach = reach,
            settings = behavior,
            placementContext = { _, _ -> placementContext },
            predictPlacement = { _, _ -> null },
            orientedPrediction = { _, _, _ -> PlacementRotation(0f, 0f) },
        )
    }

    private fun fittedFeet(raw: Vec3, stateAt: (BlockPos) -> BlockState): Vec3? {
        val bounds = AABB(-10.0, -10.0, -10.0, 10.0, 10.0, 10.0)
        val profile = FlightPassabilityProfile.collisionAware(bounds, stateAt)
        val cell = BlockPos(floor(raw.x), floor(raw.y), floor(raw.z))
        val fittedY = profile.feetHeightAt(cell) ?: return null
        return Vec3(cell.x + 0.5, fittedY, cell.z + 0.5)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun moverContext(
        core: MoverCore,
        player: Vec3,
        onGround: Boolean,
        flying: Boolean,
        frontier: MoverPlanFrontier,
        stateAt: (BlockPos) -> BlockState,
        bounds: Pair<BlockPos, BlockPos>,
    ): MoverTickContext {
        return MoverTickContext(
            sessionKey = TEST_SESSION,
            playerPos = player,
            onGround = onGround,
            flying = flying,
            mayfly = true,
            isCreative = true,
            guiOpen = false,
            hurt = false,
            manualInput = false,
            correctionReceived = false,
            queueRevision = 0L,
            feedSnapshot = null,
            gateY = null,
            gatePhase = PrinterLayerGatePhase.ASCENT,
            missingWorld = emptyList(),
            isPlaceable = { false },
            expectedStateAt = { null },
            isPassableCell = { true },
            reach = 4.0,
            pathProbe = { _, _ -> PathProbeResult(clear = true, chunkLoaded = true) },
            onWorkPositionAbandoned = {},
            onWorkPositionUnproductive = { _, _ -> Unit },
            onPositionsUncoverable = { _, _ -> Unit },
            canComplete = { false },
            planMode = true,
            planFrontier = frontier,
            planSessionFinal = false,
            planStateAt = stateAt,
            planTravelBounds = bounds,
        )
    }

    internal companion object {
        private const val EYE_HEIGHT: Double = 1.62
        private const val ARRIVAL_RADIUS: Double = 0.5
        private const val MAX_SCAFFOLD_CHAIN_VERTICAL_DISTANCE: Double = 7.855
        private val TEST_SESSION = MoverSessionKey(Any(), Any(), 1L)

        @JvmStatic
        @BeforeAll
        internal fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
