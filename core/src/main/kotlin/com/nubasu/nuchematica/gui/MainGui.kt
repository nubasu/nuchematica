package com.nubasu.nuchematica.gui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import java.awt.Color

public class MainGui: Gui(Minecraft.getInstance()) {
    private val mc: Minecraft = Minecraft.getInstance()

    public fun renderOverlay(poseStack: PoseStack): Unit {
        if (mc.player == null) return
        RenderSystem.setShaderColor(
            1f,
            1f,
            1f,
            1f
        )
        val vec3 = mc.player!!.position()
        val pos = String.format("X: %.4f / Y: %.4f / Z: %.4f", vec3.x, vec3.y, vec3.z)
        poseStack.pushPose()
        mc.font.draw(
            poseStack,
            pos,
            0f,
            0f,
            Color.GREEN.rgb
        )
        poseStack.popPose()
    }
}