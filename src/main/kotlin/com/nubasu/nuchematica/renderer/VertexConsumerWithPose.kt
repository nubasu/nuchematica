package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.core.BlockPos

class VertexConsumerWithPose(
    val parent: VertexConsumer, val originPos: BlockPos, val pose: PoseStack,
    private val overrideColor: FloatArray? = null,
): VertexConsumer {

    override fun vertex(
        p_85945_: Double,
        p_85946_: Double,
        p_85947_: Double
    ): VertexConsumer {
        val dx = originPos.getX() and 15
        val dy = originPos.getY() and 15
        val dz = originPos.getZ() and 15
        parent.vertex(
            pose.last().pose(),
            p_85945_.toFloat() - dx,
            p_85946_.toFloat() - dy,
            p_85947_.toFloat() - dz
        )
        return this
    }

    override fun color(
        p_85973_: Int,
        p_85974_: Int,
        p_85975_: Int,
        p_85976_: Int
    ): VertexConsumer {
        val c = overrideColor
        if (c != null) {
            parent.color(c[0], c[1], c[2], c[3])
        } else {
            parent.color(p_85973_, p_85974_, p_85975_, p_85976_)
        }
        return this
    }

    override fun uv(p_85948_: Float, p_85949_: Float): VertexConsumer {
        parent.uv(p_85948_, p_85949_)
        return this
    }

    override fun overlayCoords(p_85971_: Int, p_85972_: Int): VertexConsumer {
        parent.overlayCoords(p_85971_, p_85972_)
        return this
    }

    override fun uv2(p_86010_: Int, p_86011_: Int): VertexConsumer {
        parent.uv2(p_86010_, p_86011_)
        return this
    }

    override fun normal(
        p_86005_: Float,
        p_86006_: Float,
        p_86007_: Float
    ): VertexConsumer {
        parent.normal(p_86005_, p_86006_, p_86007_)
        return this
    }

    override fun endVertex() {
        parent.endVertex()
    }

    override fun defaultColor(
        p_166901_: Int,
        p_166902_: Int,
        p_166903_: Int,
        p_166904_: Int
    ) {
        parent.defaultColor(p_166901_, p_166902_, p_166903_, p_166904_)
    }

    override fun unsetDefaultColor() {
        parent.unsetDefaultColor()
    }
}
