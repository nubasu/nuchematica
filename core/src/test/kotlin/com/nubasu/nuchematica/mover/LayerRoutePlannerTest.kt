package com.nubasu.nuchematica.mover

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.math.floor

public class LayerRoutePlannerTest {
    @Test
    public fun sameInputProducesSameRoute(): Unit {
        val missing = listOf(
            BlockPos(20, 1, 20),
            BlockPos(4, 0, 0),
            BlockPos.ZERO,
        )

        val first = planRoute(missing, reach = 4.5)
        val second = planRoute(missing, reach = 4.5)

        assertEquals(first, second)
    }

    @Test
    public fun routeCoversEveryReachableMissingPosition(): Unit {
        val missing = listOf(
            BlockPos.ZERO,
            BlockPos(4, 0, 0),
            BlockPos(20, 0, 0),
            BlockPos(20, 3, 4),
        )

        val covered = planRoute(missing, reach = 4.5)
            .route
            .flatMap { workPosition -> workPosition.covered }
            .toSet()

        assertEquals(missing.toSet(), covered)
    }

    @Test
    public fun firstWorkPositionUsesLowestYThenXThenSnakeDirectedZAnchor(): Unit {
        val route = planRoute(
            placeableMissing = listOf(
                BlockPos(-100, 1, -100),
                BlockPos(5, 0, 5),
                BlockPos(-10, 0, 10),
                BlockPos(-10, 0, -5),
            ),
            reach = 4.5,
        )

        assertEquals(Vec3(-8.0, 1.4, -5.0), route.route.first().target)
    }

    @Test
    public fun evenXUsesAscendingZAndOddXUsesDescendingZForTheSnakeTieBreak(): Unit {
        val positions = listOf(
            BlockPos(2, 0, 4),
            BlockPos(1, 0, -3),
            BlockPos(0, 0, 4),
            BlockPos(2, 0, -3),
            BlockPos(1, 0, 4),
            BlockPos(0, 0, -3),
        )

        val plan = planRoute(positions, reach = 0.0)

        assertEquals(
            listOf(
                BlockPos(0, 0, -3),
                BlockPos(0, 0, 4),
                BlockPos(1, 0, 4),
                BlockPos(1, 0, -3),
                BlockPos(2, 0, -3),
                BlockPos(2, 0, 4),
            ),
            plan.uncoverable,
        )
    }

    @Test
    public fun equalCoverageUsesCandidateOffsetDefinitionOrder(): Unit {
        val route = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = 4.5,
        )

        assertEquals(Vec3(2.0, 1.4, 0.0), route.route.single().target)
    }

    @Test
    public fun workPositionAddsPointFourHoverClearanceToTheCandidateHeight(): Unit {
        val target = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = 4.5,
        ).route.single().target

        assertEquals(1.4, target.y, 0.0)
    }

    @Test
    public fun anchorWithNoCandidateCoverageIsReportedUncoverable(): Unit {
        val plan = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = 0.0,
        )

        assertTrue(plan.route.isEmpty())
        assertEquals(listOf(BlockPos.ZERO), plan.uncoverable)
    }

    @Test
    public fun isolatedAnchorIsCoveredAtEverySupportedMoverReach(): Unit {
        var reach = MOVER_MINIMUM_REACH
        while (reach <= 5.0) {
            val plan = planRoute(
                placeableMissing = listOf(BlockPos.ZERO),
                reach = reach,
            )

            assertTrue(plan.route.any { workPosition -> BlockPos.ZERO in workPosition.covered }) {
                "isolated anchor must be coverable at reach=$reach"
            }
            assertTrue(plan.uncoverable.isEmpty())
            reach += 0.5
        }
    }

    @Test
    public fun bannedBestTargetUsesTheNextCandidate(): Unit {
        val plan = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = 4.5,
            bannedTargets = setOf(Vec3(2.0, 1.4, 0.0)),
        )

        assertEquals(Vec3(-2.0, 1.4, 0.0), plan.route.single().target)
        assertTrue(plan.uncoverable.isEmpty())
    }

    @Test
    public fun bannedOriginalCandidatesUseFirstSameLevelLateralCandidate(): Unit {
        val plan = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = MOVER_MINIMUM_REACH,
            bannedTargets = setOf(
                Vec3(2.0, 1.4, 0.0),
                Vec3(-2.0, 1.4, 0.0),
                Vec3(0.0, 1.4, 2.0),
                Vec3(0.0, 1.4, -2.0),
                Vec3(0.0, 3.4, 0.0),
            ),
        )

        assertEquals(Vec3(3.0, HOVER_CLEARANCE, 0.0), plan.route.single().target)
        assertEquals(setOf(BlockPos.ZERO), plan.route.single().covered)
        assertTrue(plan.uncoverable.isEmpty())
    }

    @Test
    public fun allCandidateTargetsBannedReportsAnchorUncoverable(): Unit {
        val plan = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = 4.5,
            bannedTargets = setOf(
                Vec3(2.0, 1.4, 0.0),
                Vec3(-2.0, 1.4, 0.0),
                Vec3(0.0, 1.4, 2.0),
                Vec3(0.0, 1.4, -2.0),
                Vec3(0.0, 3.4, 0.0),
                Vec3(3.0, 0.4, 0.0),
                Vec3(-3.0, 0.4, 0.0),
                Vec3(0.0, 0.4, 3.0),
                Vec3(0.0, 0.4, -3.0),
            ),
        )

        assertTrue(plan.route.isEmpty())
        assertEquals(listOf(BlockPos.ZERO), plan.uncoverable)
    }

    @Test
    public fun preserveInputOrderConsumesAnchorsInTheGivenOrderInsteadOfTheSweepOrder(): Unit {
        val inputFirst = BlockPos(5, 0, 5)
        val positions = listOf(
            inputFirst,
            BlockPos(-10, 0, 10),
            BlockPos(-10, 0, -5),
        )

        val plan = planRoute(
            placeableMissing = positions,
            reach = 4.5,
            preserveInputOrder = true,
        )

        assertTrue(inputFirst in plan.route.first().covered)
    }

    @Test
    public fun defaultPreserveInputOrderStaysSweepOrderedByteIdentically(): Unit {
        val positions = listOf(
            BlockPos(-100, 1, -100),
            BlockPos(5, 0, 5),
            BlockPos(-10, 0, 10),
            BlockPos(-10, 0, -5),
        )

        val explicitFalse = planRoute(placeableMissing = positions, reach = 4.5, preserveInputOrder = false)
        val default = planRoute(placeableMissing = positions, reach = 4.5)

        assertEquals(default, explicitFalse)
        assertEquals(Vec3(-8.0, 1.4, -5.0), default.route.first().target)
    }

    @Test
    public fun denseSingleLayerRouteNeverParksOnAColumnWithStillPendingWork(): Unit {
        val size = 8
        val missing = (0 until size).flatMap { x -> (0 until size).map { z -> BlockPos(x, 0, z) } }

        val plan = planRoute(
            placeableMissing = missing,
            reach = 4.5,
            centerCandidateForScoring = true,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
        )

        assertTrue(plan.uncoverable.isEmpty())
        var consumed = emptySet<BlockPos>()
        for (entry in plan.route) {
            val standColumn = floor(entry.target.x).toInt() to floor(entry.target.z).toInt()
            val stillPending = missing.filter { pos -> pos !in consumed && (pos.x to pos.z) == standColumn }
            assertTrue(stillPending.isEmpty()) {
                "stand at ${entry.target} parks on column $standColumn with still-pending work $stillPending"
            }
            consumed = consumed + entry.covered
        }
        assertEquals(missing.toSet(), consumed)
    }

    @Test
    public fun columnRejectionIsTemporalAndClearsOnceThePriorOccupantIsConsumed(): Unit {
        val p1 = BlockPos(0, 0, 0)
        val p2 = BlockPos(2, 50, 0)
        val p3 = BlockPos(-2, 100, 0)

        val plan = planRoute(
            placeableMissing = listOf(p1, p2, p3),
            reach = 4.5,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
        )

        assertEquals(3, plan.route.size)
        assertTrue(plan.uncoverable.isEmpty())

        val p1Entry = plan.route.first { entry -> p1 in entry.covered }
        assertEquals(Vec3(0.0, 1.4, 2.0), p1Entry.target)

        val p3Entry = plan.route.first { entry -> p3 in entry.covered }
        assertEquals(Vec3(0.0, 101.4, 0.0), p3Entry.target)
    }

    @Test
    public fun candidateBanPredicateExcludesTheBannedCellFromTheFirstPlanRouteCall(): Unit {
        val missing = listOf(BlockPos.ZERO)
        var calls = 0
        val countingExpectedStateAt: (BlockPos) -> BlockState? = { _ -> calls++; null }
        val bannedRawTarget = Vec3(2.0, 1.4, 0.0)

        val plan = planRoute(
            placeableMissing = missing,
            reach = 4.5,
            expectedStateAt = countingExpectedStateAt,
            isCandidateBanned = { target -> target == bannedRawTarget },
        )

        assertEquals(Vec3(-2.0, 1.4, 0.0), plan.route.single().target)
        assertTrue(calls <= 2) { "expected at most 2 expectedStateAt calls for one planRoute call, got $calls" }
    }

    @Test
    public fun coveredMissingCachesEnvelopesAndSkipsFarTargetsBeforeComputingThem(): Unit {
        val near = BlockPos(0, 0, 0)
        val far = BlockPos(1000, 0, 0)
        val calls = HashMap<BlockPos, Int>()
        val countingExpectedStateAt: (BlockPos) -> BlockState? = { pos ->
            calls[pos] = (calls[pos] ?: 0) + 1
            null
        }

        val plan = planRoute(
            placeableMissing = listOf(near, far),
            reach = 4.5,
            expectedStateAt = countingExpectedStateAt,
        )

        assertEquals(2, plan.route.size)
        assertEquals(1, calls[near])
        assertEquals(1, calls[far])
    }

    @Test
    public fun snakeOrderCoverageProgressesMonotonicallyWithContiguousPrefixCoverage(): Unit {
        val size = 8
        val snakeOrder = (0 until size).flatMap { x ->
            val zs = if (x % 2 == 0) (0 until size) else (size - 1 downTo 0)
            zs.map { z -> BlockPos(x, 0, z) }
        }

        val plan = planRoute(
            placeableMissing = snakeOrder,
            reach = 4.5,
            centerCandidateForScoring = true,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
            coverContiguousPrefix = true,
        )

        val entryIndexOf = HashMap<BlockPos, Int>()
        plan.route.forEachIndexed { index, entry ->
            for (pos in entry.covered) entryIndexOf[pos] = index
        }
        val sequence = snakeOrder.mapNotNull { pos -> entryIndexOf[pos] }
        val violations = (1 until sequence.size).filter { i -> sequence[i] < sequence[i - 1] }
        assertTrue(violations.isEmpty()) {
            "entry index covering each plan-order target must be non-decreasing; " +
                "violations at $violations, sequence=$sequence"
        }
    }

    @Test
    public fun groundLevelFootingAnchorIsCoveredByItsOwnLowStandInsteadOfBundlingTheNextHigherLayerCell(): Unit {
        val anchor = BlockPos(0, -60, 0)
        val higherLayerCell = BlockPos(2, -58, 0)
        val strictlyAboveOnly: (Vec3, List<BlockPos>) -> Boolean = { target, covered ->
            val highest = covered.maxOfOrNull { position -> position.y }
            highest == null || floor(target.y).toInt() >= highest + 1
        }

        val plan = planRoute(
            placeableMissing = listOf(anchor, higherLayerCell),
            reach = 4.5,
            centerCandidateForScoring = true,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
            isStandUsable = strictlyAboveOnly,
            coverContiguousPrefix = true,
        )

        assertTrue(plan.uncoverable.isEmpty()) {
            "expected every position coverable; uncoverable=${plan.uncoverable}"
        }
        val anchorEntry = plan.route.first()
        assertTrue(anchor in anchorEntry.covered)
        assertTrue(higherLayerCell !in anchorEntry.covered)
    }

    @Test
    public fun planFallbackIncludesOneBlockLateralTwoBlockRaisedStandForAnEnclosedTarget(): Unit {
        val requiredStand = Vec3(1.0, 2.4, 0.0)

        val plan = planRoute(
            placeableMissing = listOf(BlockPos.ZERO),
            reach = 4.0,
            centerCandidateForScoring = true,
            excludeOwnColumnFromCoverage = true,
            preserveInputOrder = true,
            isStandUsable = { target, _ -> target == requiredStand },
            coverContiguousPrefix = true,
            includePlanFallbackCandidates = true,
        )

        assertEquals(requiredStand, plan.route.single().target)
        assertEquals(setOf(BlockPos.ZERO), plan.route.single().covered)
        assertTrue(plan.uncoverable.isEmpty())
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
