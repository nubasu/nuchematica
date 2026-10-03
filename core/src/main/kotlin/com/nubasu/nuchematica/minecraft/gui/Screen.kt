package com.nubasu.nuchematica.minecraft.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.Widget
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.TextComponent

public abstract class Screen(
    titleText: String
): Screen(TextComponent(titleText)){
    public val font: Font
        get() = super.font

    public fun <T> addWidget(widget: T)
            where T : GuiEventListener,
                  T : Widget,
                  T : NarratableEntry {
        super.addRenderableWidget<T>(widget)
    }
}