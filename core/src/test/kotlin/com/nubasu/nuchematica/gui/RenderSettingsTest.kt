package com.nubasu.nuchematica.gui

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class RenderSettingsTest {
    @Test
    public fun missingAutomodeJsonFieldUsesFalseDefault(): Unit {
        val decoded = Json.decodeFromString<RenderSettings>("{}")

        assertFalse(decoded.automode)
    }

    @Test
    public fun automodeSurvivesEncodeDecodeRoundTripAndApplyFrom(): Unit {
        val encoded = Json.encodeToString(RenderSettings(automode = true))
        val decoded = Json.decodeFromString<RenderSettings>(encoded)
        val applied = RenderSettings()
        applied.applyFrom(decoded)

        assertTrue(decoded.automode)
        assertTrue(applied.automode)
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
