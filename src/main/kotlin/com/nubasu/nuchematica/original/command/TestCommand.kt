package com.nubasu.nuchematica.original.command

import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.original.SelectedRegion
import com.nubasu.nuchematica.original.renderer.SelectedRegionRenderer
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

public class TestCommand {
    private val mc: Minecraft = Minecraft.getInstance()
    private val render = SelectedRegionRenderer()

    public var isRendering = false

    public fun renderLine(selectedRegion: SelectedRegion, poseStack: PoseStack, projectionMatrix: Matrix4f) {
        if (isRendering) {
            render.renderSelectedRegion(selectedRegion, poseStack, projectionMatrix, mc.gameRenderer.mainCamera)
        }
    }
}