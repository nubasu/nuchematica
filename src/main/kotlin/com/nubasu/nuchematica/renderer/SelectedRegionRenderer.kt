package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.vertex.*
import com.nubasu.nuchematica.common.SelectedRegion
import com.nubasu.nuchematica.common.Vector3
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.BlockRenderDispatcher
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.client.model.data.ModelData
import org.joml.Matrix4f

public class SelectedRegionRenderer {



    public fun render(block: BlockState) {
        val rendermanger: GlStateManager = GlStateManager()
        val dispatcher: BlockRenderDispatcher = Minecraft.getInstance().blockRenderer
//        GlStateManager.
//
//        val model = dispatcher.blockModelShaper.getBlockModel(block)
//        dispatcher.modelRenderer.
//
//        val renderer = Minecraft.getInstance().blockRenderer;
//        val tesselator = Tesselator.getInstance()
//        val buffer = tesselator.builder.putBulkData(PoseStack.Pose(block.), model.getQuads(block, Direction.EAST, RandomSource.create()).first(), 0.0f, 0.0f ,0.0f, 0,0)
//
//        dispatcher.renderSingleBlock(block, PoseStack(), MultiBufferSource.immediate(), 50, 50, model.getModelData(), RenderType.solid())
    }

    // call only in RenderLevelStageEvent
    public fun renderSelectedRegion(selectRegion: SelectedRegion, poseStack: PoseStack, projectionMatrix: Matrix4f, camera: Camera) {
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

    public fun testRender(blockState: BlockState, poseStack: PoseStack, camera: Camera, v: Vector3) {
        val cameraPosition = camera.position
        val player = Minecraft.getInstance().player

        val renderer = Minecraft.getInstance().blockRenderer

        val vertexConsumer = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderType.translucent())
        vertexConsumer.color(1f, 1f, 1f, 0.9f)

        val proxyBuffer = object: VertexConsumer {
            override fun putBulkData(pPoseEntry: PoseStack.Pose, pQuad: BakedQuad, pColorMuls: FloatArray, pRed: Float, pGreen: Float, pBlue: Float, pCombinedLights: IntArray, pCombinedOverlay: Int, pMulColor: Boolean) {
                putBulkData(pPoseEntry, pQuad, pColorMuls, pRed, pGreen, pBlue, 0.5f, pCombinedLights, pCombinedOverlay, pMulColor)
            }

            override fun vertex(pX: Double, pY: Double, pZ: Double): VertexConsumer {
                return vertexConsumer.vertex(pX, pY, pZ)
            }

            override fun color(pRed: Int, pGreen: Int, pBlue: Int, pAlpha: Int): VertexConsumer {
                return vertexConsumer.color(pRed, pGreen, pBlue, pAlpha)
            }

            override fun uv(pU: Float, pV: Float): VertexConsumer {
                return vertexConsumer.uv(pU, pV)
            }

            override fun overlayCoords(pU: Int, pV: Int): VertexConsumer {
                return vertexConsumer.overlayCoords(pU, pV)
            }

            override fun uv2(pU: Int, pV: Int): VertexConsumer {
                return vertexConsumer.uv2(pU, pV)
            }

            override fun normal(pX: Float, pY: Float, pZ: Float): VertexConsumer {
                return vertexConsumer.normal(pX, pY, pZ)
            }

            override fun endVertex() {
                vertexConsumer.endVertex()
            }

            override fun defaultColor(pDefaultR: Int, pDefaultG: Int, pDefaultB: Int, pDefaultA: Int) {
                vertexConsumer.defaultColor(pDefaultR, pDefaultG, pDefaultB, pDefaultA)
            }

            override fun unsetDefaultColor() {
                vertexConsumer.unsetDefaultColor()
            }
        }

//        renderer.renderSingleBlock(
//            blockState,
//            poseStack,
//            Minecraft.getInstance().renderBuffers().bufferSource(),
//            255,
//            -100,
//            ModelData.EMPTY,
//            RenderType.translucent()
//        )

        poseStack.pushPose()
        poseStack.translate(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
        poseStack.pushPose()
        poseStack.translate(-cameraPosition.x(), -cameraPosition.y(), -cameraPosition.z())

        renderer.renderBatched(
            blockState,
            BlockPos(v.x, v.y, v.z),
            player!!.getLevel(),
            poseStack,
            proxyBuffer,
            true,
            player.getLevel().getRandom(),
            ModelData.EMPTY,
            RenderType.translucent(),
            true)
        poseStack.popPose()
        poseStack.popPose()
    }
}