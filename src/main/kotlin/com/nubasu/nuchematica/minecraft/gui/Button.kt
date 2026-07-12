package com.nubasu.nuchematica.minecraft.gui

import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.TextComponent

public class Button(
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
    public var text: String,
    private val onClicked: () -> Unit,
): Button(
    x,
    y,
    width,
    height,
    TextComponent(text),
    { onClicked() }
)