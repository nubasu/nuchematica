package com.nubasu.nuchematica.printer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class PrinterHudFormatterTest {
    @Test
    public fun formatsActiveStatusWithOnlyNonZeroSkipReasons(): Unit {
        val status = PrinterStatus(
            remaining = 12,
            placed = 3,
            skips = mapOf(
                PrinterSkipReason.OUT_OF_REACH to 2,
                PrinterSkipReason.RETRY_LIMIT to 1,
            ),
        )

        assertEquals(
            listOf(
                "Printer: ACTIVE",
                "Remaining: 12",
                "Placed: 3",
                "Skipped: 3 (reach: 2, retry: 1)",
            ),
            PrinterHudFormatter.lines(status),
        )
    }

    @Test
    public fun formatsAllZeroSkipReasonsWithoutDetails(): Unit {
        val skips = PrinterSkipReason.values().associateWith { 0 }

        assertEquals(
            listOf(
                "Printer: ACTIVE",
                "Remaining: 0",
                "Placed: 0",
                "Skipped: 0",
            ),
            PrinterHudFormatter.lines(PrinterStatus(0, 0, skips)),
        )
    }

    // v3's own PrinterStatus constructions never pass planPhase, so it defaults to null and the
    // line above must stay absent -- covered by formatsAllZeroSkipReasonsWithoutDetails already
    // omitting it. This case covers the v4 opposite: planPhase set must append a last "Plan: ..."
    // line without disturbing any of the existing lines.
    @Test
    public fun appendsPlanPhaseLineLastWhenSet(): Unit {
        val status = PrinterStatus(
            remaining = 5,
            placed = 1,
            skips = emptyMap(),
            planPhase = "executing",
        )

        assertEquals(
            listOf(
                "Printer: ACTIVE",
                "Remaining: 5",
                "Placed: 1",
                "Skipped: 0",
                "Plan: executing",
            ),
            PrinterHudFormatter.lines(status),
        )
    }
}
