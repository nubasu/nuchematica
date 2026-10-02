package com.nubasu.nuchematica.renderer

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class GhostRenderDistanceTest {

    @Test
    public fun blocksCapsAtMaxChunksAndFloorsAtOneChunk(): Unit {
        assertEquals(16, GhostRenderDistance.blocks(1))
        assertEquals(GhostRenderDistance.MAX_CHUNKS * 16, GhostRenderDistance.blocks(GhostRenderDistance.MAX_CHUNKS))
        assertEquals(
            GhostRenderDistance.MAX_CHUNKS * 16,
            GhostRenderDistance.blocks(GhostRenderDistance.MAX_CHUNKS + 5),
        )
        assertEquals(16, GhostRenderDistance.blocks(0))
        assertEquals(16, GhostRenderDistance.blocks(-3))
    }

    @Test
    public fun withinHorizontalIsTrueInsideAndAtTheBoundary(): Unit {
        val box = AABB(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)

        assertTrue(GhostRenderDistance.withinHorizontal(Vec3(8.0, 8.0, 8.0), box, radiusBlocks = 10))
        assertTrue(GhostRenderDistance.withinHorizontal(Vec3(-10.0, 8.0, 0.0), box, radiusBlocks = 10))
    }

    @Test
    public fun withinHorizontalIsFalseJustOutsideOnXAndOnZ(): Unit {
        val box = AABB(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)

        assertFalse(GhostRenderDistance.withinHorizontal(Vec3(-10.0001, 8.0, 0.0), box, radiusBlocks = 10))
        assertFalse(GhostRenderDistance.withinHorizontal(Vec3(0.0, 8.0, -10.0001), box, radiusBlocks = 10))
    }

    @Test
    public fun withinHorizontalIgnoresY(): Unit {
        val highBox = AABB(0.0, 5_000.0, 0.0, 16.0, 5_016.0, 16.0)

        assertTrue(GhostRenderDistance.withinHorizontal(Vec3(8.0, 0.0, 8.0), highBox, radiusBlocks = 10))
    }
}
