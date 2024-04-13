package com.nubasu.nuchematica.gui.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component


public class SettingScreen: Screen(Component.translatable("screen.nuchematica.setting")) {

    override fun init() {
        val button: Button = Button.builder(
            Component.translatable("button.nuchematica.next")
        )  // Button! と表示
        { b -> Minecraft.getInstance().setScreen(SubScreen(this)) } // ボタンを押したらSubScreenに移動
            // スクリーンの幅の半分から左に75、高さの半分から10上から描画する
            .pos(width / 2 - 75, height / 2 - 10)
            .size(150, 20) // 横150、高さ20のボタンを描画
            .build() // インスタンス化
        addRenderableWidget(button)
    }
}