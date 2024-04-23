package com.nubasu.nuchematica.command

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.common.SelectedRegion
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.renderer.SelectedRegionRenderer
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4f

public class TestCommand {
    private val mc: Minecraft = Minecraft.getInstance()
    private val render = SelectedRegionRenderer()

    public var isRendering: Boolean = false

    public fun renderLine(selectedRegion: SelectedRegion, poseStack: PoseStack, projectionMatrix: Matrix4f) {
        if (isRendering) {
            render.renderSelectedRegion(selectedRegion, poseStack, projectionMatrix, mc.gameRenderer.mainCamera)
        }
    }

    public fun renderBlock(blockState: BlockState, poseStack: PoseStack) {
        if (isRendering) {
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(0, 0, 0))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(0, 0, 1))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(0, 1, 0))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(0, 1, 1))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(1, 0, 0))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(1, 0, 1))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(1, 1, 0))
            render.testRender(blockState, poseStack, mc.gameRenderer.mainCamera, Vector3(1, 1, 1))
        }
    }
}