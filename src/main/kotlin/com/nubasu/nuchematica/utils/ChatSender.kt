package com.nubasu.nuchematica.utils

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.TextComponent

public object ChatSender {
    public fun send(text: String) {
        Minecraft.getInstance().player?.sendMessage(TextComponent(text), Minecraft.getInstance().player!!.uuid)
    }
}