package com.nubasu.nuchematica.minecraft.gui

import com.mojang.blaze3d.vertex.PoseStack

public class PlainText(
    private val x: Float,
    private val y: Float,
    private val screen: Screen
) {
    public fun drawText(poseStack: PoseStack, text: String) {
        screen.font.draw(poseStack, text, x, y, 0xFFFFFF)
    }
}