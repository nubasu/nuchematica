package com.nubasu.nuchematica.gui

import com.mojang.blaze3d.systems.RenderSystem
import com.nubasu.nuchematica.printer.PrinterHudFormatter
import com.nubasu.nuchematica.printer.SchematicPrinter
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

public class PrinterHudOverlay : Gui(Minecraft.getInstance()) {
    private val mc: Minecraft = Minecraft.getInstance()

    @SubscribeEvent
    public fun onPostRenderGuiOverlayEvent(event: RenderGameOverlayEvent.Post): Unit {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return
        if (!SchematicPrinter.enabled) return
        val status = SchematicPrinter.latestStatus ?: return

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        val stack = event.matrixStack
        stack.pushPose()
        PrinterHudFormatter.lines(status).forEachIndexed { index, line ->
            mc.font.draw(
                stack,
                line,
                HUD_X.toFloat(),
                (HUD_Y + index * LINE_HEIGHT).toFloat(),
                if (index == 0) ACTIVE_COLOR else TEXT_COLOR,
            )
        }
        stack.popPose()
    }

    private companion object {
        private const val HUD_X: Int = 10
        private const val HUD_Y: Int = 30
        private const val LINE_HEIGHT: Int = 10
        private const val ACTIVE_COLOR: Int = 0xFFFF55
        private const val TEXT_COLOR: Int = 0xFFFFFF
    }
}
