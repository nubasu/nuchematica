package com.nubasu.nuchematica.utils

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

public object ChatSender {
    public fun send(text: String) {
        Minecraft.getInstance().player?.sendSystemMessage(Component.literal(text))
    }
}