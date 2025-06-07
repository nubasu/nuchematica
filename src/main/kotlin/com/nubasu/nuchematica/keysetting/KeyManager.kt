package com.nubasu.nuchematica.keysetting

import com.mojang.blaze3d.platform.InputConstants
import com.nubasu.nuchematica.Nuchematica
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.gui.screen.SchematicListScreen
import com.nubasu.nuchematica.gui.screen.SchematicSettingsScreen
import com.nubasu.nuchematica.io.NbtReader
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.schematic.reader.SpongeSchematicV3Reader
import com.nubasu.nuchematica.utils.ChatSender
import it.unimi.dsi.fastutil.io.FastBufferedInputStream
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.client.ClientRegistry
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

@Mod.EventBusSubscriber(modid = Nuchematica.MODID)
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

    @SubscribeEvent
    public fun keyRegister(event: FMLClientSetupEvent) {
        ClientRegistry.registerKeyBinding(settingKey)
        ClientRegistry.registerKeyBinding(pos1Key)
        ClientRegistry.registerKeyBinding(pos2Key)
        ClientRegistry.registerKeyBinding(saveKey)
        ClientRegistry.registerKeyBinding(shemaKey)
        ClientRegistry.registerKeyBinding(toggleDisplayKey)
    }

    @SubscribeEvent
    public fun keyPressed(event: InputEvent.KeyInputEvent) {
        if (settingKey.consumeClick()) {
            Minecraft.getInstance().setScreen(SchematicSettingsScreen(RenderSettings()))
        }
        if (pos1Key.consumeClick()) {
            val pos = Minecraft.getInstance().player?.position()!!
            ChatSender.send("pos1: ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}")
            SelectedRegionManager.setFirstPosition(pos)
        }
        if (pos2Key.consumeClick()) {
            ChatSender.send(Minecraft.getInstance().gameDirectory.absolutePath)
            val pos = Minecraft.getInstance().player?.position()!!
            ChatSender.send("pos2: ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}")
            SelectedRegionManager.setSecondPosition(pos)
        }
        if (saveKey.consumeClick()) {
            val shemDir = Minecraft.getInstance().gameDirectory.absolutePath + "/schematics"
            val inputStream = DataInputStream(FastBufferedInputStream(GZIPInputStream(FileInputStream("$shemDir/v3_sign.schem"))))
            val compoundTag = NbtReader(inputStream).readCompoundTag()
            val clipboard = SpongeSchematicV3Reader.read(compoundTag)
            SelectedRegionManager.place(clipboard)
        }
        if (shemaKey.consumeClick()) {
            Minecraft.getInstance().setScreen(SchematicListScreen())
        }
        if (toggleDisplayKey.consumeClick()) {
            SchematicRenderManager.isRendering = !SchematicRenderManager.isRendering
        }
    }
}