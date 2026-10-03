package com.nubasu.nuchematica.printer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class PrinterStatusTrackerTest {
    @Test
    public fun sameSessionAccumulatesAcceptedDeltasAcrossStatusChanges(): Unit {
        val tracker = PrinterStatusTracker()
        val level = Any()
        val content = Any()

        assertEquals(2, tracker.update(level, content, 1L, 12, 2, emptyMap()).placed)
        val statusOnlyUpdate = tracker.update(
            level,
            content,
            1L,
            remaining = 9,
            acceptedDelta = 0,
            skips = mapOf(PrinterSkipReason.OUT_OF_REACH to 4),
        )

        assertEquals(2, statusOnlyUpdate.placed)
        assertEquals(9, statusOnlyUpdate.remaining)
        assertEquals(mapOf(PrinterSkipReason.OUT_OF_REACH to 4), statusOnlyUpdate.skips)
        assertEquals(3, tracker.update(level, content, 1L, 8, 1, emptyMap()).placed)
    }

    @Test
    public fun contentAndTransformChangesRestartAccumulation(): Unit {
        val tracker = PrinterStatusTracker()
        val level = Any()
        val firstContent = Any()
        tracker.update(level, firstContent, 1L, 10, 3, emptyMap())

        assertEquals(2, tracker.update(level, Any(), 1L, 8, 2, emptyMap()).placed)
        assertEquals(1, tracker.update(level, firstContent, 2L, 7, 1, emptyMap()).placed)
    }

    @Test
    public fun levelChangeAndResetRestartAccumulation(): Unit {
        val tracker = PrinterStatusTracker()
        val content = Any()
        tracker.update(Any(), content, 1L, 10, 3, emptyMap())

        assertEquals(2, tracker.update(Any(), content, 1L, 8, 2, emptyMap()).placed)
        tracker.reset()
        assertEquals(1, tracker.update(Any(), content, 1L, 7, 1, emptyMap()).placed)
    }
}
