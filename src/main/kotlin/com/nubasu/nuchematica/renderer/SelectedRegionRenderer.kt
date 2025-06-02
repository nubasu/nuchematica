package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.*
import com.mojang.math.Matrix4f
import com.nubasu.nuchematica.common.SelectedRegion
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer

public class SelectedRegionRenderer {
    // call only in RenderLevelStageEvent
    public fun renderSelectedRegion(selectRegion: SelectedRegion, poseStack: PoseStack, projectionMatrix: Matrix4f) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val view = camera.position
        val pos1 = selectRegion.pos1
        val pos2 = selectRegion.pos2

        val tesselator = Tesselator.getInstance()
        val buffer = tesselator.builder

        val vertexBuffer = VertexBuffer()
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)

        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos1.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos1.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos1.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()
        buffer.vertex(pos2.x.toDouble(), pos2.y.toDouble(), pos2.z.toDouble()).color(1f, 1f, 1f, 1f).endVertex()

        buffer.end()
        vertexBuffer.bind()
        vertexBuffer.upload(buffer)

        poseStack.pushPose()
        poseStack.translate(-view.x, -view.y, -view.z)
        val shader = GameRenderer.getPositionColorShader()
        vertexBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader)

        poseStack.popPose()

        vertexBuffer.close()
        VertexBuffer.unbind()
    }
}