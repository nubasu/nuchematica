package com.nubasu.nuchematica.printer

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import java.io.File

@Serializable
public data class PrinterSettings(
    public var attemptsPerTick: Int = 1,
    public var reach: Double = 4.5,
    public var placeWaterloggedDry: Boolean = false,
)

public object PrinterSettingsHolder {
    public var printerSettings: PrinterSettings = PrinterSettings()
}

public object PrinterSettingsCodec {
    public fun encode(settings: PrinterSettings): String {
        return Json.encodeToString(settings)
    }

    public fun decode(json: String?): PrinterSettings {
        val decoded = json?.let {
            runCatching { Json.decodeFromString<PrinterSettings>(it) }.getOrNull()
        } ?: PrinterSettings()
        return PrinterSettings(
            attemptsPerTick = decoded.attemptsPerTick.coerceIn(MIN_ATTEMPTS_PER_TICK, MAX_ATTEMPTS_PER_TICK),
            reach = decoded.reach.coerceIn(MIN_REACH, MAX_REACH),
            placeWaterloggedDry = decoded.placeWaterloggedDry,
        )
    }

    private const val MIN_ATTEMPTS_PER_TICK: Int = 1
    private const val MAX_ATTEMPTS_PER_TICK: Int = 8
    private const val MIN_REACH: Double = 1.0
    private const val MAX_REACH: Double = 5.0
}

public object PrinterSettingsIO {
    public fun save(settings: PrinterSettings): Unit {
        val file = settingsFile()
        file.parentFile.mkdirs()
        file.writeText(PrinterSettingsCodec.encode(settings))
    }

    public fun load(): PrinterSettings {
        val file = settingsFile()
        val json = runCatching {
            if (file.exists()) file.readText() else null
        }.getOrNull()
        return PrinterSettingsCodec.decode(json)
    }

    private fun settingsFile(): File {
        val settingsDir = File(Minecraft.getInstance().gameDirectory, "nuchematica_settings")
        return File(settingsDir, "printer.json")
    }
}
