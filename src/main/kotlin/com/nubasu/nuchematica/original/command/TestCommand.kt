package com.nubasu.nuchematica.original.command

import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.original.SelectedRegion
import com.nubasu.nuchematica.original.Vector3
import com.nubasu.nuchematica.original.renderer.SelectedRegionRenderer
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

public class TestCommand {
    private val mc: Minecraft = Minecraft.getInstance()
    private val render = SelectedRegionRenderer(SelectedRegion())
    public var projectionMatrix = Matrix4f().identity()
    public var poseStack = PoseStack()

    public var isRendering = false

    init {
        poseStack.setIdentity()
    }

    public fun renderLine() {
        if (isRendering) {
            render.renderSelectedRegion(poseStack, projectionMatrix, mc.gameRenderer.mainCamera)
        }
    }

    public fun registerPos1(pos1: Vector3) {
        render.pos1 = pos1
    }

    public fun registerPos2(pos2: Vector3) {
        render.pos2 = pos2
    }
}