package com.nubasu.nuchematica.gui

import com.google.gson.GsonBuilder
import java.io.File
import net.minecraft.client.Minecraft

object RenderSettingsIO {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val settingsDir = File(Minecraft.getInstance().gameDirectory, "schematic_settings")

    init {
        if (!settingsDir.exists()) settingsDir.mkdirs()
    }

    fun save(settings: RenderSettings, name: String) {
        val file = File(settingsDir, "$name.json")
        file.writeText(gson.toJson(settings))
    }

    fun load(name: String): RenderSettings? {
        val file = File(settingsDir, "$name.json")
        if (!file.exists()) return null
        return gson.fromJson(file.readText(), RenderSettings::class.java)
    }

    fun listPresets(): List<String> {
        return settingsDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
    }
}