package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.VertexConsumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

public class OpacityVertexConsumerTest {

    @Test
    public fun explicitAndDefaultAlphaAreScaled(): Unit {
        val parent = RecordingVertexConsumer()
        val consumer = OpacityVertexConsumer(parent, 0.5f)

        assertSame(consumer, consumer.color(10, 20, 30, 201))
        consumer.defaultColor(40, 50, 60, 255)

        assertEquals(101, parent.colorAlpha)
        assertEquals(128, parent.defaultAlpha)
    }

    @Test
    public fun alphaScalingIsClamped(): Unit {
        assertEquals(0, OpacityVertexConsumer.scaleAlpha(255, -1f))
        assertEquals(128, OpacityVertexConsumer.scaleAlpha(255, 0.5f))
        assertEquals(255, OpacityVertexConsumer.scaleAlpha(255, 2f))
    }

    private class RecordingVertexConsumer : VertexConsumer {
        var colorAlpha: Int = -1
        var defaultAlpha: Int = -1

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = this

        override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
            colorAlpha = alpha
            return this
        }

        override fun uv(u: Float, v: Float): VertexConsumer = this

        override fun overlayCoords(u: Int, v: Int): VertexConsumer = this

        override fun uv2(u: Int, v: Int): VertexConsumer = this

        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this

        override fun endVertex(): Unit = Unit

        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) {
            defaultAlpha = alpha
        }

        override fun unsetDefaultColor(): Unit = Unit
    }
}
