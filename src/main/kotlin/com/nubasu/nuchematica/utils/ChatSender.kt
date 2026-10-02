package com.nubasu.nuchematica.utils

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.TextComponent

public object ChatSender {
    public fun send(text: String) {
        val player = Minecraft.getInstance().player ?: return
        player.sendMessage(TextComponent(text), player.uuid)
    }
}