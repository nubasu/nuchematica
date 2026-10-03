package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.VertexConsumer
import kotlin.math.roundToInt

internal class OpacityVertexConsumer(
    private val parent: VertexConsumer,
    private val opacity: Float,
) : VertexConsumer {

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
        parent.vertex(x, y, z)
        return this
    }

    override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        parent.color(red, green, blue, scaleAlpha(alpha, opacity))
        return this
    }

    override fun uv(u: Float, v: Float): VertexConsumer {
        parent.uv(u, v)
        return this
    }

    override fun overlayCoords(u: Int, v: Int): VertexConsumer {
        parent.overlayCoords(u, v)
        return this
    }

    override fun uv2(u: Int, v: Int): VertexConsumer {
        parent.uv2(u, v)
        return this
    }

    override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
        parent.normal(x, y, z)
        return this
    }

    override fun endVertex() {
        parent.endVertex()
    }

    override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) {
        parent.defaultColor(red, green, blue, scaleAlpha(alpha, opacity))
    }

    override fun unsetDefaultColor() {
        parent.unsetDefaultColor()
    }

    internal companion object {
        internal fun scaleAlpha(alpha: Int, opacity: Float): Int {
            return (alpha * opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        }
    }
}
