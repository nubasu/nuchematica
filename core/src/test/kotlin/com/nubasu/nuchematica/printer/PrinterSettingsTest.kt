package com.nubasu.nuchematica.printer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class PrinterSettingsTest {
    @Test
    public fun encodeDecodeRoundTripPreservesValidSettings(): Unit {
        val settings = PrinterSettings(
            attemptsPerTick = 6,
            placementIntervalTicks = 12,
            reach = 3.5,
            facePlacement = false,
        )

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
    public fun encodeDecodeRoundTripPreservesLookalikeSubstitutionSetting(): Unit {
        val settings = PrinterSettings(
            attemptsPerTick = 6,
            reach = 3.5,
            substituteLookalikes = false,
        )

        assertEquals(settings, PrinterSettingsCodec.decode(PrinterSettingsCodec.encode(settings)))
    }

    @Test
    public fun legacyJsonDefaultsWaterloggedDrySettingToFalse(): Unit {
        val decoded = PrinterSettingsCodec.decode("""{"attemptsPerTick":2,"reach":3.0}""")

        assertEquals(1, decoded.placementIntervalTicks)
        assertTrue(decoded.facePlacement)
        assertFalse(decoded.placeWaterloggedDry)
        assertTrue(decoded.substituteLookalikes)
        assertTrue(decoded.planFirstMode)
    }

    @Test
    public fun encodeDecodeRoundTripPreservesPlanFirstModeSetting(): Unit {
        val settings = PrinterSettings(
            attemptsPerTick = 6,
            reach = 3.5,
            planFirstMode = false,
        )

        assertEquals(settings, PrinterSettingsCodec.decode(PrinterSettingsCodec.encode(settings)))
    }

    @Test
    public fun savedSettingsFromTheObservedFantasyRunMigrateToPlanFirstMode(): Unit {
        val decoded = PrinterSettingsCodec.decode(
            """{"placementIntervalTicks":3,"placeWaterloggedDry":true}""",
        )

        assertTrue(decoded.planFirstMode)
        assertEquals(3, decoded.placementIntervalTicks)
        assertTrue(decoded.placeWaterloggedDry)
    }

    @Test
    public fun planFirstModeOffKeepsTheSessionBranchConditionFalseRegardlessOfWorldModelStatus(): Unit {
        val settings = PrinterSettings(planFirstMode = false)
        for (status in PrintWorldModel.Status.values()) {
            assertFalse(settings.planFirstMode && status == PrintWorldModel.Status.READY)
        }
    }

    @Test
    public fun decodeClampsValuesToSupportedRanges(): Unit {
        assertEquals(
            PrinterSettings(attemptsPerTick = 1, placementIntervalTicks = 1, reach = 1.0),
            PrinterSettingsCodec.decode(
                """{"attemptsPerTick":0,"placementIntervalTicks":0,"reach":0.1}""",
            ),
        )
        assertEquals(
            PrinterSettings(attemptsPerTick = 8, placementIntervalTicks = 40, reach = 4.0),
            PrinterSettingsCodec.decode(
                """{"attemptsPerTick":99,"placementIntervalTicks":99,"reach":9.0}""",
            ),
        )
        assertEquals(
            PrinterSettings(reach = 4.0),
            PrinterSettingsCodec.decode("""{"reach":4.5}"""),
        )
    }

    @Test
    public fun nullAndMalformedJsonReturnDefaults(): Unit {
        assertEquals(PrinterSettings(), PrinterSettingsCodec.decode(null))
        assertEquals(PrinterSettings(), PrinterSettingsCodec.decode("not json"))
    }
}
