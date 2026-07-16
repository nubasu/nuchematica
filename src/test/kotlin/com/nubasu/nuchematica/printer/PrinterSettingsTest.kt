package com.nubasu.nuchematica.printer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

public class PrinterSettingsTest {
    @Test
    public fun encodeDecodeRoundTripPreservesValidSettings(): Unit {
        val settings = PrinterSettings(attemptsPerTick = 6, reach = 3.5)

        assertEquals(settings, PrinterSettingsCodec.decode(PrinterSettingsCodec.encode(settings)))
    }

    @Test
    public fun encodeDecodeRoundTripPreservesWaterloggedDrySetting(): Unit {
        val settings = PrinterSettings(
            attemptsPerTick = 6,
            reach = 3.5,
            placeWaterloggedDry = true,
        )

        assertEquals(settings, PrinterSettingsCodec.decode(PrinterSettingsCodec.encode(settings)))
    }

    @Test
    public fun legacyJsonDefaultsWaterloggedDrySettingToFalse(): Unit {
        val decoded = PrinterSettingsCodec.decode("""{"attemptsPerTick":2,"reach":3.0}""")

        assertFalse(decoded.placeWaterloggedDry)
    }

    @Test
    public fun decodeClampsValuesToSupportedRanges(): Unit {
        assertEquals(
            PrinterSettings(attemptsPerTick = 1, reach = 1.0),
            PrinterSettingsCodec.decode("""{"attemptsPerTick":0,"reach":0.1}"""),
        )
        assertEquals(
            PrinterSettings(attemptsPerTick = 8, reach = 5.0),
            PrinterSettingsCodec.decode("""{"attemptsPerTick":99,"reach":9.0}"""),
        )
    }

    @Test
    public fun nullAndMalformedJsonReturnDefaults(): Unit {
        assertEquals(PrinterSettings(), PrinterSettingsCodec.decode(null))
        assertEquals(PrinterSettings(), PrinterSettingsCodec.decode("not json"))
    }
}
