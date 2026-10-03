package com.nubasu.nuchematica.mover

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class LanePlannerTest {
    @Test
    public fun sameInputProducesSameLanePlan(): Unit {
        val missing = listOf(
            BlockPos(20, 5, 4),
            BlockPos(0, 5, 0),
            BlockPos(4, 5, -8),
        )

        val first = planLanes(missing, gateY = 5)
        val second = planLanes(missing, gateY = 5)

        assertEquals(first, second)
    }

    @Test
    public fun inputOrderDoesNotAffectThePlan(): Unit {
        val missing = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 6), BlockPos(4, 5, 3))

        val fromOriginalOrder = planLanes(missing, gateY = 5)
        val fromReversedOrder = planLanes(missing.reversed(), gateY = 5)

        assertEquals(fromOriginalOrder, fromReversedOrder)
    }

    @Test
    public fun singlePositionProducesOneSegmentCollapsedToAPoint(): Unit {
        val plan = planLanes(listOf(BlockPos(0, 5, 0)), gateY = 5)

        assertEquals(1, plan.segments.size)
        assertEquals(plan.segments.single().start, plan.segments.single().end)
    }

    @Test
    public fun workPositionFeetAltitudeIsGatePlusTwoAsAnInteger(): Unit {
        val plan = planLanes(listOf(BlockPos(0, 5, 0)), gateY = 5)

        assertEquals(7.0, plan.segments.single().start.y, 0.0)
    }

    @Test
    public fun laneAtEachPitchStepCoversItsOwnColumn(): Unit {
        val missing = listOf(BlockPos(0, 5, 0), BlockPos(8, 5, 0))

        val plan = planLanes(missing, gateY = 5)

        val laneXs = plan.segments.map { segment -> segment.start.x }.sorted()
        assertEquals(listOf(0.5, 8.5), laneXs)
    }

    @Test
    public fun evenLaneIndexSweepsAscendingAndOddLaneIndexSweepsDescending(): Unit {
        val missing = listOf(
            BlockPos(0, 5, -2),
            BlockPos(0, 5, 2),
            BlockPos(4, 5, -2),
            BlockPos(4, 5, 2),
        )

        val plan = planLanes(missing, gateY = 5)
        val byLane = plan.segments.sortedBy { segment -> segment.start.x }

        assertEquals(2, byLane.size)
        assertEquals(Vec3(0.5, 7.0, -1.5), byLane[0].start)
        assertEquals(Vec3(0.5, 7.0, 2.5), byLane[0].end)
        assertEquals(Vec3(4.5, 7.0, 2.5), byLane[1].start)
        assertEquals(Vec3(4.5, 7.0, -1.5), byLane[1].end)
    }

    @Test
    public fun emptyStretchSplitsALaneIntoTwoSegmentsWithAJunctionBetweenThem(): Unit {
        val missing = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 20))

        val plan = planLanes(missing, gateY = 5)

        assertEquals(2, plan.segments.size)
        assertEquals(Vec3(0.5, 7.0, 0.5), plan.segments[0].start)
        assertEquals(Vec3(0.5, 7.0, 0.5), plan.segments[0].end)
        assertEquals(Vec3(0.5, 7.0, 20.5), plan.segments[1].start)
        assertEquals(Vec3(0.5, 7.0, 20.5), plan.segments[1].end)
    }

    @Test
    public fun withinBridgingDistanceTwoClustersMergeIntoOneSegment(): Unit {
        val missing = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 6))

        val plan = planLanes(missing, gateY = 5)

        assertEquals(1, plan.segments.size)
        assertEquals(Vec3(0.5, 7.0, 0.5), plan.segments.single().start)
        assertEquals(Vec3(0.5, 7.0, 6.5), plan.segments.single().end)
    }

    @Test
    public fun laneWithNoPositionsWithinRadiusProducesNoSegment(): Unit {
        val missing = listOf(BlockPos(0, 5, 0), BlockPos(10, 5, 0))

        val plan = planLanes(missing, gateY = 5)

        assertEquals(2, plan.segments.size)
        assertTrue(plan.segments.none { segment -> segment.start.x == 4.5 })
    }

    @Test
    public fun emptyInputProducesNoSegments(): Unit {
        val plan = planLanes(emptyList(), gateY = 5)

        assertTrue(plan.segments.isEmpty())
    }
}
