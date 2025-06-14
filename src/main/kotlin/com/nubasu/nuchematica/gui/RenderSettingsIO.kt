package com.nubasu.nuchematica.gui

import com.mojang.logging.LogUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import net.minecraft.client.Minecraft

object RenderSettingsIO {
    private val settingsDir = File(Minecraft.getInstance().gameDirectory, "nuchematica_settings")

    init {
        if (!settingsDir.exists()) settingsDir.mkdirs()
    }

    fun save(settings: RenderSettings, name: String) {
        val file = File(settingsDir, "$name.json")
        LogUtils.getLogger().info("$settings")
        val json = Json.encodeToString(settings)
        LogUtils.getLogger().info("json $json")

        file.writeText(json)
        LogUtils.getLogger().info("save to $name.json")
    }

    fun load(name: String): RenderSettings? {
        val file = File(settingsDir, "$name.json")
        if (!file.exists()) return null
        LogUtils.getLogger().info("load from $name.json")
        val json = file.readText()
        LogUtils.getLogger().info("json $json")
        return Json.decodeFromString<RenderSettings>(json)
    }

    fun listPresets(): List<String> {
        return settingsDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
    }
}