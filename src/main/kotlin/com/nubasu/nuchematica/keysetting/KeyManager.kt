package com.nubasu.nuchematica.keysetting

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.gui.screen.SchematicListScreen
import com.nubasu.nuchematica.gui.screen.SchematicSettingsScreen
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.printer.PrinterActivationEvent
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.schematic.reader.SpongeSchematicV3Reader
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.client.ClientRegistry
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import java.io.File

public class KeyManager {
    private val settingKey: KeyMapping = KeyMapping(
        "key.nuchematica.setting",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'K'.code,
        "key.nuchematica.category"
    )

    private val pos1Key: KeyMapping = KeyMapping(
        "key.nuchematica.pos1",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        ','.code,
        "key.nuchematica.category"
    )

    private val pos2Key: KeyMapping = KeyMapping(
        "key.nuchematica.pos2",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        '.'.code,
        "key.nuchematica.category"
    )

    private val saveKey: KeyMapping = KeyMapping(
        "key.nuchematica.save",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'N'.code,
        "key.nuchematica.category"
    )

    private val shemaKey: KeyMapping = KeyMapping(
        "key.nuchematica.shema",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        ';'.code,
        "key.nuchematica.category"
    )

    private val toggleDisplayKey: KeyMapping = KeyMapping(
        "key.nuchematica.display",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'I'.code,
        "key.nuchematica.category"
    )

    private val printerKey: KeyMapping = KeyMapping(
        "key.nuchematica.printer",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'P'.code,
        "key.nuchematica.category"
    )

    private val moverKey: KeyMapping = KeyMapping(
        "key.nuchematica.mover",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'O'.code,
        "key.nuchematica.category"
    )

    public fun keyRegister(event: FMLClientSetupEvent) {
        ClientRegistry.registerKeyBinding(settingKey)
        ClientRegistry.registerKeyBinding(pos1Key)
        ClientRegistry.registerKeyBinding(pos2Key)
        ClientRegistry.registerKeyBinding(saveKey)
        ClientRegistry.registerKeyBinding(shemaKey)
        ClientRegistry.registerKeyBinding(toggleDisplayKey)
        ClientRegistry.registerKeyBinding(printerKey)
        ClientRegistry.registerKeyBinding(moverKey)
    }

    @SubscribeEvent
    public fun keyPressed(event: InputEvent.KeyInputEvent) {
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
