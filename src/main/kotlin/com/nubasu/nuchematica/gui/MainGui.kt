package com.nubasu.nuchematica.gui

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.awt.Color


public class MainGui: Gui(Minecraft.getInstance()) {
    private val mc: Minecraft = Minecraft.getInstance()

    @SubscribeEvent
    public fun onPostRenderGuiOverlayEvent(event: RenderGameOverlayEvent.Post) {
        // Post fires once per element type; draw only once per frame.
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return
        if (mc.player == null) return
        RenderSystem.setShaderColor(
            1f,
            1f,
            1f,
            1f
        )
        val stack = event.matrixStack
        val vec3 = mc.player!!.position()
        val pos = String.format("X: %.4f / Y: %.4f / Z: %.4f", vec3.x, vec3.y, vec3.z) // 下4桁まで
        stack.pushPose()
        mc.font.draw(
            stack,
            pos,  // 表示する文字列
            0f,  // 座標 0, 0 に描画
            0f,
            Color.GREEN.rgb // 緑色
        )
        stack.popPose()
    }
}