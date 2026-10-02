package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class B0MinecraftHeadlessProbeTest {

    @Test
    public fun bootstrapCreatesRealBlockStates(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val water = Blocks.WATER.defaultBlockState()

        assertFalse(stone.isAir)
        assertTrue(water.fluidState.isSource)
        println("B0_BOOTSTRAP PASS stone=${stone.block} water=${water.fluidState.type}")
    }

    @Test
    public fun syntheticQuadsRestoreAsIndexOnlyBuffer(): Unit {
        val initial = BufferBuilder(512)
        initial.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        addQuad(initial, 0f)
        addQuad(initial, 4f)
        initial.setQuadSortOrigin(0f, 0f, 8f)
        val sortState = initial.sortState
        initial.end()

        val initialBuffer = initial.popNextBuffer()
        val initialDrawState = initialBuffer.first
        assertFalse(initialDrawState.indexOnly())
        assertEquals(8, initialDrawState.vertexCount())
        assertEquals(12, initialDrawState.indexCount())
        assertTrue(initialBuffer.second.remaining() - initialDrawState.bufferSize() in 0..3)

        val resort = BufferBuilder(512)
        resort.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        resort.restoreSortState(sortState)
        resort.setQuadSortOrigin(0f, 0f, -8f)
        resort.sortState
        resort.end()

        val resortBuffer = resort.popNextBuffer()
        val resortDrawState = resortBuffer.first
        assertTrue(resortDrawState.indexOnly())
        assertEquals(8, resortDrawState.vertexCount())
        assertEquals(12, resortDrawState.indexCount())
        assertTrue(resortBuffer.second.remaining() - resortDrawState.bufferSize() in 0..3)
        val resortIndexBytes = resortDrawState.indexCount() * resortDrawState.indexType().bytes
        println(
            "B0_SORT PASS initialBytes=${initialDrawState.bufferSize()} " +
                "resortBytes=${resortDrawState.bufferSize()} " +
                "initialPayload=${initialBuffer.second.remaining()} " +
                "resortPayload=${resortBuffer.second.remaining()} " +
                "resortIndexBytes=$resortIndexBytes " +
                "logicalVertexBytes=${resortDrawState.vertexBufferSize()} " +
                "indexOnly=${resortDrawState.indexOnly()}"
        )
    }

    private fun addQuad(builder: BufferBuilder, z: Float) {
        builder.vertex(0.0, 0.0, z.toDouble()).color(255, 255, 255, 255).endVertex()
        builder.vertex(1.0, 0.0, z.toDouble()).color(255, 255, 255, 255).endVertex()
        builder.vertex(1.0, 1.0, z.toDouble()).color(255, 255, 255, 255).endVertex()
        builder.vertex(0.0, 1.0, z.toDouble()).color(255, 255, 255, 255).endVertex()
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
