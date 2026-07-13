package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.math.Matrix4f
import com.nubasu.nuchematica.common.SelectedRegion
import com.nubasu.nuchematica.common.Vector3
import net.minecraft.client.Minecraft

public class SelectedRegionRenderer {
    // The outline mesh is rebuilt only when the region changes; the vertex buffer is
    // reused across frames instead of being created and destroyed every draw call.
    private var vertexBuffer: VertexBuffer? = null
    private var cachedPos1: Vector3? = null
    private var cachedPos2: Vector3? = null

    // call only in RenderLevelStageEvent
    public fun renderSelectedRegion(selectRegion: SelectedRegion, poseStack: PoseStack, projectionMatrix: Matrix4f) {
        val view = Minecraft.getInstance().gameRenderer.mainCamera.position

        val buffer = if (selectRegion.pos1 == cachedPos1 && selectRegion.pos2 == cachedPos2 && vertexBuffer != null) {
            vertexBuffer!!
        } else {
            uploadRegion(selectRegion)
        }

        poseStack.pushPose()
        try {
            poseStack.translate(-view.x, -view.y, -view.z)
            try {
                NuchematicaRenderTypes.REGION_LINES.setupRenderState()
                try {
                    buffer.bind()
                    buffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader())
                } finally {
                    VertexBuffer.unbind()
                }
            } finally {
                NuchematicaRenderTypes.REGION_LINES.clearRenderState()
            }
        } finally {
            poseStack.popPose()
        }
    }

    private fun uploadRegion(region: SelectedRegion): VertexBuffer {
        val pos1 = region.pos1
        val pos2 = region.pos2
        val builder = BufferBuilder(512)
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)

        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        builder.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        builder.end()

        val buffer = uploadVertexBuffer(builder)
        val oldBuffer = vertexBuffer
        vertexBuffer = buffer
        cachedPos1 = pos1.copy()
        cachedPos2 = pos2.copy()
        oldBuffer?.close()
        return buffer
    }

    private fun uploadVertexBuffer(builder: BufferBuilder): VertexBuffer {
        val buffer = VertexBuffer()
        var uploaded = false
        try {
            try {
                buffer.bind()
                buffer.upload(builder)
            } finally {
                VertexBuffer.unbind()
            }
            uploaded = true
            return buffer
        } finally {
            if (!uploaded) {
                buffer.close()
            }
        }
    }
}
