package com.nubasu.nuchematica.original.gui.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

public class SubScreen(public val parent: Screen): Screen(Component.translatable("screen.nuchematica.sub")) {
    override fun init() {
        val button: Button = Button.builder(
            Component.translatable("button.nuchematica.close")
        )  // Button! と表示
        { b -> Minecraft.getInstance().setScreen(null)} // ボタンを押したらSubScreenに移動
            // スクリーンの幅の半分から左に75、高さの半分から10上から描画する
            .pos(width / 2 - 75, height / 2 - 10)
            .size(150, 20) // 横150、高さ20のボタンを描画
            .build() // インスタンス化
        addRenderableWidget(button)
    }
}