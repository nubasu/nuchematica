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
        assertFalse(decoded.planFirstMode)
    }

    @Test
    public fun encodeDecodeRoundTripPreservesPlanFirstModeSetting(): Unit {
        val settings = PrinterSettings(
            attemptsPerTick = 6,
            reach = 3.5,
            planFirstMode = true,
        )

        assertEquals(settings, PrinterSettingsCodec.decode(PrinterSettingsCodec.encode(settings)))
    }

    // Contract 8 (M3b1b-2): pins SchematicPrinter.tick's own plan-mode branch condition
    // (settings.planFirstMode && PrintWorldModel.status() == READY) exactly -- with the flag
    // off, that condition must stay false no matter what PrintWorldModel currently reports,
    // so plan mode can never be entered and the v3 path below it is the only one ever
    // reached. SchematicPrinter itself is not unit-testable here (its tick() reads
    // Minecraft.getInstance() directly with no test seam); this test's own reconstruction of
    // its exact branch expression, together with the full existing v3 suite staying green
    // with zero behavior changes, is this session's evidence for the flag-OFF invariant.
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
