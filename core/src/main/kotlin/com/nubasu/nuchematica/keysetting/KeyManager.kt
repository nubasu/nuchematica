package com.nubasu.nuchematica.keysetting

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.gui.screen.SchematicListScreen
import com.nubasu.nuchematica.gui.screen.SchematicSettingsScreen
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.platform.Platform
import com.nubasu.nuchematica.printer.PrinterActivationEvent
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.schematic.reader.SpongeSchematicV3Reader
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import java.io.File

public class KeyManager {
    private val settingKey: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.setting",
        'K'.code,
        "key.nuchematica.category"
    )

    private val pos1Key: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.pos1",
        ','.code,
        "key.nuchematica.category"
    )

    private val pos2Key: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.pos2",
        '.'.code,
        "key.nuchematica.category"
    )

    private val saveKey: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.save",
        'N'.code,
        "key.nuchematica.category"
    )

    private val shemaKey: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.shema",
        ';'.code,
        "key.nuchematica.category"
    )

    private val toggleDisplayKey: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.display",
        'I'.code,
        "key.nuchematica.category"
    )

    private val printerKey: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.printer",
        'P'.code,
        "key.nuchematica.category"
    )

    private val moverKey: KeyMapping = Platform.hooks.keyMapping(
        "key.nuchematica.mover",
        'O'.code,
        "key.nuchematica.category"
    )

    /** Every key this mod owns, in the order the loader registers them. */
    public val keyMappings: List<KeyMapping> = listOf(
        settingKey,
        pos1Key,
        pos2Key,
        saveKey,
        shemaKey,
        toggleDisplayKey,
        printerKey,
        moverKey,
    )

    public fun handleKeyInputs(): Unit {
        if (settingKey.consumeClick()) {
            Minecraft.getInstance().setScreen(SchematicSettingsScreen(RenderSettingHolder.renderSettings))
        }
        if (pos1Key.consumeClick()) {
            Minecraft.getInstance().player?.position()?.let { pos ->
                ChatSender.send("pos1: ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}")
                SelectedRegionManager.setFirstPosition(pos)
            }
        }
        if (pos2Key.consumeClick()) {
            Minecraft.getInstance().player?.position()?.let { pos ->
                ChatSender.send("pos2: ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}")
                SelectedRegionManager.setSecondPosition(pos)
            }
        }
        if (saveKey.consumeClick()) {
            placeTestSchematic()
        }
        if (shemaKey.consumeClick()) {
            Minecraft.getInstance().setScreen(SchematicListScreen())
        }
        if (toggleDisplayKey.consumeClick()) {
            SchematicRenderManager.isRendering = !SchematicRenderManager.isRendering
        }
        if (printerKey.consumeClick()) {
            val isCreative = Minecraft.getInstance().gameMode?.playerMode?.isCreative == true
            when (SchematicPrinter.toggleRequested(isCreative)) {
                PrinterActivationEvent.ENABLED -> ChatSender.send("[nuchematica] printer: ON")
                PrinterActivationEvent.DISABLED -> ChatSender.send("[nuchematica] printer: OFF")
                PrinterActivationEvent.REQUIRES_CREATIVE -> {
                    ChatSender.send("[nuchematica] printer requires creative mode")
                }
                PrinterActivationEvent.AUTO_DISABLED -> Unit
            }
        }
        if (moverKey.consumeClick()) {
            SchematicMover.toggleRequested()
        }
    }

    private fun placeTestSchematic() {
        try {
            val file = File(Minecraft.getInstance().gameDirectory, "schematics/v3_sign.schem")
            val clipboard = SpongeSchematicV3Reader.read(SchematicFormatDetector.readRootTag(file))
            SelectedRegionManager.place(clipboard)
        } catch (e: Exception) {
            LogUtils.getLogger().error("failed to place test schematic", e)
            ChatSender.send("[nuchematica] failed to place test schematic: ${e.message}")
        }
    }
}
