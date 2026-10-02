package com.nubasu.nuchematica.gui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.mover.MoverHudFormatter
import com.nubasu.nuchematica.mover.MoverState
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.printer.PrinterHudFormatter
import com.nubasu.nuchematica.printer.SchematicPrinter
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui

public class PrinterHudOverlay : Gui(Minecraft.getInstance()) {
    private val mc: Minecraft = Minecraft.getInstance()

    public fun renderOverlay(poseStack: PoseStack): Unit {
        val printerLines = if (SchematicPrinter.enabled) {
            SchematicPrinter.latestStatus?.let(PrinterHudFormatter::lines).orEmpty()
        } else {
            emptyList()
        }
        val moverLines = SchematicMover.latestStatus
            ?.takeIf { status -> status.state != MoverState.IDLE }
            ?.let(MoverHudFormatter::lines)
            .orEmpty()
        if (printerLines.isEmpty() && moverLines.isEmpty()) return

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        poseStack.pushPose()
        printerLines.forEachIndexed { index, line ->
            mc.font.draw(
                poseStack,
                line,
                HUD_X.toFloat(),
                (HUD_Y + index * LINE_HEIGHT).toFloat(),
                if (index == 0) ACTIVE_COLOR else TEXT_COLOR,
            )
        }
        val moverStartY = HUD_Y + printerLines.size * LINE_HEIGHT
        moverLines.forEachIndexed { index, line ->
            mc.font.draw(
                poseStack,
                line,
                HUD_X.toFloat(),
                (moverStartY + index * LINE_HEIGHT).toFloat(),
                if (index == 0) ACTIVE_COLOR else TEXT_COLOR,
            )
        }
        poseStack.popPose()
    }

    private companion object {
        private const val HUD_X: Int = 10
        private const val HUD_Y: Int = 30
        private const val LINE_HEIGHT: Int = 10
        private const val ACTIVE_COLOR: Int = 0xFFFF55
        private const val TEXT_COLOR: Int = 0xFFFFFF
    }
}
