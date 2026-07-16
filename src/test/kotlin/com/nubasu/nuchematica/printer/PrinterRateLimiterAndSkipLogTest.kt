package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class PrinterRateLimiterAndSkipLogTest {
    @Test
    public fun oneAttemptPerTickResetsOnlyWhenTickChanges(): Unit {
        val limiter = PrinterRateLimiter()

        limiter.beginTick(10L)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        limiter.beginTick(10L)
        assertFalse(limiter.tryAcquire())
        limiter.beginTick(11L)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    public fun eightAttemptsPerTickHonorsMaximumBoundary(): Unit {
        val limiter = PrinterRateLimiter(PrinterRateLimiter.MAX_ATTEMPTS_PER_TICK)

        limiter.beginTick(1L)

        repeat(PrinterRateLimiter.MAX_ATTEMPTS_PER_TICK) {
            assertTrue(limiter.tryAcquire())
        }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    public fun updatedAttemptsPerTickClampsAndAppliesImmediately(): Unit {
        val limiter = PrinterRateLimiter()
        limiter.updateAttemptsPerTick(0)
        assertEquals(1, limiter.attemptsPerTick)
        limiter.updateAttemptsPerTick(99)
        assertEquals(PrinterRateLimiter.MAX_ATTEMPTS_PER_TICK, limiter.attemptsPerTick)

        limiter.updateAttemptsPerTick(2)
        limiter.beginTick(1L)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    public fun skipLogAggregatesEachReasonAndReturnsSnapshot(): Unit {
        val log = PrinterSkipLog()
        log.record(PrinterSkipReason.NO_SUPPORT_FACE, BlockPos(0, 0, 0))
        log.record(PrinterSkipReason.NO_SUPPORT_FACE, BlockPos(1, 0, 0))
        log.record(PrinterSkipReason.RETRY_LIMIT, BlockPos(2, 0, 0))

        val snapshot = log.snapshot()
        log.clear()

        assertEquals(2, snapshot[PrinterSkipReason.NO_SUPPORT_FACE])
        assertEquals(1, snapshot[PrinterSkipReason.RETRY_LIMIT])
        assertEquals(0, snapshot[PrinterSkipReason.OUT_OF_REACH])
        assertEquals(0, log.count(PrinterSkipReason.NO_SUPPORT_FACE))
    }

    @Test
    public fun recordingSamePositionTwiceStaysIdempotentPerReason(): Unit {
        val log = PrinterSkipLog()
        val pos = BlockPos(3, 4, 5)

        log.record(PrinterSkipReason.NO_SUPPORT_FACE, pos)
        log.record(PrinterSkipReason.NO_SUPPORT_FACE, pos)
        assertEquals(1, log.count(PrinterSkipReason.NO_SUPPORT_FACE))

        log.record(PrinterSkipReason.NO_SUPPORT_FACE, BlockPos(6, 7, 8))
        assertEquals(2, log.count(PrinterSkipReason.NO_SUPPORT_FACE))
    }
}
