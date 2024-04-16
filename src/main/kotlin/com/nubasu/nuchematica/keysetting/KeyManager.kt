package com.nubasu.nuchematica.keysetting

import com.mojang.blaze3d.platform.InputConstants
import com.nubasu.nuchematica.Nuchematica
import com.nubasu.nuchematica.gui.screen.SettingScreen
import com.nubasu.nuchematica.io.NbtReader
import com.nubasu.nuchematica.io.SpongeSchematicV3Reader
import com.nubasu.nuchematica.io.WorldEditSchematicReader
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.utils.ChatSender
import it.unimi.dsi.fastutil.io.FastBufferedInputStream
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

@Mod.EventBusSubscriber(modid = Nuchematica.MODID)
public class KeyManager {
    private val setting: KeyMapping = KeyMapping(
        "key.nuchematica.setting",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'K'.code,
        "key.nuchematica.category"
    )

    private val pos1Key: KeyMapping = KeyMapping(
        "key.nuchematica.minus",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        '-'.code,
        "key.nuchematica.category"
    )

    private val pos2Key: KeyMapping = KeyMapping(
        "key.nuchematica.plus",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        ';'.code,
        "key.nuchematica.category"
    )

    private val saveKey: KeyMapping = KeyMapping(
        "key.nuchematica.save",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        'N'.code,
        "key.nuchematica.category"
    )

    @SubscribeEvent
    public fun keyRegister(event: RegisterKeyMappingsEvent) {
        event.register(setting)
        event.register(pos1Key)
        event.register(pos2Key)
        event.register(saveKey)
    }

    @SubscribeEvent
    public fun keyPressed(event: InputEvent.Key) {
        if (setting.consumeClick()) {
            Minecraft.getInstance().setScreen(SettingScreen())
        }
        if (pos1Key.consumeClick()) {
            val pos = Minecraft.getInstance().player?.position()!!
            ChatSender.send("pos1: ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}")
            SelectedRegionManager.setFirstPosition(pos)
        }
        if (pos2Key.consumeClick()) {
            ChatSender.send(Minecraft.getInstance().gameDirectory.absolutePath)
            val pos = Minecraft.getInstance().player?.position()!!
            ChatSender.send("pos1: ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}")
            SelectedRegionManager.setSecondPosition(pos)
        }
        if (saveKey.consumeClick()) {
            val shemDir = Minecraft.getInstance().gameDirectory.absolutePath + "/schematics"
            val inputStream = DataInputStream(FastBufferedInputStream(GZIPInputStream(FileInputStream("$shemDir/test_V3.schem"))))
            val compoundTag = NbtReader(inputStream).readCompoundTag()
            val clipboard = SpongeSchematicV3Reader.read(compoundTag)
            SelectedRegionManager.place(clipboard)
        }
    }
}