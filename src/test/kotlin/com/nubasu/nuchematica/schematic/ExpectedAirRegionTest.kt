package com.nubasu.nuchematica.schematic

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class ExpectedAirRegionTest {

    @Test
    public fun ofEmptyPositionsIsNull(): Unit {
        assertNull(ExpectedAirRegion.of(emptyList()))
    }

    @Test
    public fun ofBuildsTheInclusiveBoundingBoxIncludingNegativeCoordinates(): Unit {
        val positions = listOf(BlockPos(-2, -1, 3), BlockPos(4, 5, -6), BlockPos(0, 2, 0))

        val region = requireNotNull(ExpectedAirRegion.of(positions))

        assertEquals(-2, region.minX)
        assertEquals(-1, region.minY)
        assertEquals(-6, region.minZ)
        assertEquals(4, region.maxX)
        assertEquals(5, region.maxY)
        assertEquals(3, region.maxZ)
    }

    @Test
    public fun yClampNarrowsTheUpperBoundLikeUpToHeight(): Unit {
        val positions = listOf(BlockPos(0, 0, 0), BlockPos(0, 10, 0))

        val region = requireNotNull(ExpectedAirRegion.of(positions, yMin = Int.MIN_VALUE, yMax = 4))

        assertEquals(0, region.minY)
        assertEquals(4, region.maxY)
    }

    @Test
    public fun yClampPinsToASingleLayerLikeOnlyHeight(): Unit {
        val positions = listOf(BlockPos(0, 0, 0), BlockPos(0, 10, 0))

        val region = requireNotNull(ExpectedAirRegion.of(positions, yMin = 3, yMax = 3))

        assertEquals(3, region.minY)
        assertEquals(3, region.maxY)
        assertEquals(1L, (region.maxX - region.minX + 1).toLong() * (region.maxZ - region.minZ + 1).toLong())
    }

    @Test
    public fun yClampProducingAnEmptyRangeIsNull(): Unit {
        val positions = listOf(BlockPos(0, 0, 0), BlockPos(0, 10, 0))

        assertNull(ExpectedAirRegion.of(positions, yMin = 20, yMax = 30))
        assertNull(ExpectedAirRegion.of(positions, yMin = 20, yMax = 20))
    }

    @Test
    public fun containsHonoursEveryAxisBoundary(): Unit {
        val region = ExpectedAirRegion(minX = -1, minY = 0, minZ = 2, maxX = 1, maxY = 2, maxZ = 4)

        assertTrue(region.contains(BlockPos(-1, 0, 2)))
        assertTrue(region.contains(BlockPos(1, 2, 4)))
        assertTrue(region.contains(BlockPos(0, 1, 3)))
        assertFalse(region.contains(BlockPos(-2, 0, 2)))
        assertFalse(region.contains(BlockPos(-1, -1, 2)))
        assertFalse(region.contains(BlockPos(-1, 0, 5)))
        assertFalse(region.contains(BlockPos(2, 0, 2)))
    }

    @Test
    public fun positionAtEnumeratesExactlyVolumeDistinctPositionsAllInsideTheBox(): Unit {
        val region = ExpectedAirRegion(minX = -1, minY = 0, minZ = 2, maxX = 1, maxY = 1, maxZ = 3)

        val enumerated = (0 until region.volume).map { ordinal -> region.positionAt(ordinal) }

        assertEquals(region.volume, enumerated.toSet().size.toLong())
        assertTrue(enumerated.all { pos -> region.contains(pos) })
    }
}
