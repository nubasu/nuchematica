package com.nubasu.nuchematica.original.renderer

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.vertex.*
import com.nubasu.nuchematica.original.SelectedRegion
import com.nubasu.nuchematica.original.Vector3
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.block.BlockRenderDispatcher
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4f

public class SelectedRegionRenderer(private val selectRegion: SelectedRegion) {
    public fun render(block: Block) {
        val rendermanger: GlStateManager = GlStateManager()
        val dispatcher: BlockRenderDispatcher = Minecraft.getInstance().blockRenderer
        val blockState: BlockState = block.defaultBlockState()
    }

    public var pos1: Vector3 = Vector3.ZERO
    public var pos2: Vector3 = Vector3.ONE

    // call only in RenderLevelStageEvent
    public fun renderLine(poseStack: PoseStack, projectionMatrix: Matrix4f, camera: Camera) {
        val view = camera.position

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

        vertexBuffer.bind()
        vertexBuffer.upload(buffer.end())

        poseStack.pushPose()
        poseStack.translate(-view.x, -view.y, -view.z)
        val shader = GameRenderer.getPositionColorShader()
        vertexBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader)

        poseStack.popPose()

        vertexBuffer.close()
        VertexBuffer.unbind()
    }
}