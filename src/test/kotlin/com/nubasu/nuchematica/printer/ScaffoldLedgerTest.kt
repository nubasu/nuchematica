package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class ScaffoldLedgerTest {
    private val scaffoldPos = BlockPos(0, 1, 0)
    private val targetPos = BlockPos(1, 1, 0)

    @Test
    public fun recordPlacedMarksTheCellOutstandingAndAScaffoldCell(): Unit {
        val ledger = ScaffoldLedger()

        ledger.recordPlaced(scaffoldPos, targetPos)

        assertTrue(ledger.isOutstanding())
        assertTrue(ledger.isScaffoldCell(scaffoldPos))
        assertFalse(ledger.isScaffoldCell(targetPos))
        assertEquals(ScaffoldState.PLACED, ledger.snapshot().single().state)
    }

    @Test
    public fun markConsumedIsANoOpForAPositionThatIsNotATrackedTarget(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)

        ledger.markConsumed(BlockPos(9, 9, 9))

        assertEquals(ScaffoldState.PLACED, ledger.snapshot().single().state)
    }

    @Test
    public fun markConsumedTransitionsTheMatchingRecordFromPlacedToConsumed(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)

        ledger.markConsumed(targetPos)

        assertEquals(ScaffoldState.CONSUMED, ledger.snapshot().single().state)
        assertTrue(ledger.isOutstanding())
    }

    @Test
    public fun tickBreaksIgnoresRecordsStillInPlacedState(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        var destroyCalls = 0

        ledger.tickBreaks(
            tick = 0L,
            stateAt = { Blocks.SLIME_BLOCK.defaultBlockState() },
            destroy = { destroyCalls++; true },
        )

        assertEquals(0, destroyCalls)
    }

    @Test
    public fun tickBreaksDoesNotSubmitOrSpendRetriesUntilTheScaffoldIsInServerReach(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)
        var inReach = false
        var destroyCalls = 0

        for (tick in 0L..(PrinterAttemptTracker.DEADLINE_TICKS * 2L)) {
            ledger.tickBreaks(
                tick = tick,
                stateAt = { Blocks.SLIME_BLOCK.defaultBlockState() },
                canDestroy = { inReach },
                destroy = { destroyCalls++; true },
            )
        }

        assertEquals(0, destroyCalls)
        assertFalse(ledger.snapshot().single().exhausted)

        inReach = true
        ledger.tickBreaks(
            tick = PrinterAttemptTracker.DEADLINE_TICKS * 2L + 1L,
            stateAt = { Blocks.SLIME_BLOCK.defaultBlockState() },
            canDestroy = { inReach },
            destroy = { destroyCalls++; true },
        )

        assertEquals(1, destroyCalls)
        assertTrue(ledger.isBreakInFlight(scaffoldPos))
    }

    @Test
    public fun tickBreaksSubmitsOnceAndEvictsOnceTheCellSettlesToAir(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)
        var world = Blocks.SLIME_BLOCK.defaultBlockState()
        var destroyCalls = 0
        val stateAt: (BlockPos) -> BlockState = { world }
        val destroy: (BlockPos) -> Boolean = {
            destroyCalls++
            world = Blocks.AIR.defaultBlockState()
            true
        }
        var broken: BlockPos? = null

        ledger.tickBreaks(tick = 0L, stateAt = stateAt, destroy = destroy, onBroken = { broken = it })
        assertEquals(1, destroyCalls)
        assertTrue(ledger.isOutstanding()) { "must not evict before the ack settles" }

        for (tick in 1L until PrinterAttemptTracker.SETTLE_TICKS.toLong()) {
            ledger.tickBreaks(tick = tick, stateAt = stateAt, destroy = destroy, onBroken = { broken = it })
            assertTrue(ledger.isOutstanding())
        }
        ledger.tickBreaks(
            tick = PrinterAttemptTracker.SETTLE_TICKS.toLong(),
            stateAt = stateAt,
            destroy = destroy,
            onBroken = { broken = it },
        )

        assertEquals(scaffoldPos, broken)
        assertFalse(ledger.isOutstanding())
        assertFalse(ledger.isScaffoldCell(scaffoldPos))
        assertEquals(1, destroyCalls) { "a successful break must not resubmit" }
    }

    @Test
    public fun tickBreaksDoesNotAcceptOptimisticClientAirBeforeTheServerAcknowledgesTheBreak(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)
        var clientState = Blocks.SLIME_BLOCK.defaultBlockState()
        var serverAcknowledgedState = Blocks.SLIME_BLOCK.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { clientState }

        ledger.tickBreaks(
            tick = 0L,
            stateAt = stateAt,
            breakStateAt = { serverAcknowledgedState },
            destroy = {
                clientState = Blocks.AIR.defaultBlockState()
                true
            },
        )
        for (tick in 1L..PrinterAttemptTracker.SETTLE_TICKS.toLong()) {
            ledger.tickBreaks(
                tick = tick,
                stateAt = stateAt,
                breakStateAt = { serverAcknowledgedState },
                destroy = { error("an unacknowledged break must stay in flight") },
            )
        }

        assertTrue(ledger.isOutstanding()) {
            "client-side optimistic air must not be mistaken for a server-confirmed break"
        }

        serverAcknowledgedState = Blocks.AIR.defaultBlockState()
        for (tick in
            (PrinterAttemptTracker.SETTLE_TICKS + 1L)..
                (PrinterAttemptTracker.SETTLE_TICKS * 2L)
        ) {
            ledger.tickBreaks(
                tick = tick,
                stateAt = stateAt,
                breakStateAt = { serverAcknowledgedState },
                destroy = { error("the acknowledged first request must not be resubmitted") },
            )
        }

        assertFalse(ledger.isOutstanding())
    }

    @Test
    public fun tickBreaksRetriesTwiceThenStopsResubmittingButStaysOutstanding(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)
        val stillThere = Blocks.SLIME_BLOCK.defaultBlockState()
        var destroyCalls = 0
        val stateAt: (BlockPos) -> BlockState = { stillThere }
        val destroy: (BlockPos) -> Boolean = { destroyCalls++; false }
        var exhaustedAttempts: Int? = null

        var tick = 0L
        ledger.tickBreaks(tick, stateAt, destroy)
        val exhaustionTick = PrinterAttemptTracker.DEADLINE_TICKS *
            (ScaffoldLedger.MAX_BREAK_RETRIES + 1).toLong()
        while (tick < exhaustionTick) {
            tick++
            ledger.tickBreaks(
                tick = tick,
                stateAt = stateAt,
                destroy = destroy,
                onRetryExhausted = { _, attempts -> exhaustedAttempts = attempts },
            )
        }

        assertEquals(3, destroyCalls) { "retryCount 0, 1, 2 -- three attempts total" }
        assertEquals(3, exhaustedAttempts)
        assertTrue(ledger.isOutstanding()) { "a failed break must never be silently dropped" }
        assertEquals(ScaffoldState.CONSUMED, ledger.snapshot().single().state)

        for (extra in 1..PrinterAttemptTracker.SETTLE_TICKS) {
            tick++
            ledger.tickBreaks(tick, stateAt, destroy)
        }
        assertEquals(3, destroyCalls)
    }

    @Test
    public fun rearmExhaustedResetsAnExhaustedRecordSoTickBreaksResumesSubmitting(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)
        val stillThere = Blocks.SLIME_BLOCK.defaultBlockState()
        var destroyCalls = 0
        val stateAt: (BlockPos) -> BlockState = { stillThere }
        val destroy: (BlockPos) -> Boolean = { destroyCalls++; false }

        var tick = 0L
        ledger.tickBreaks(tick, stateAt, destroy)
        val exhaustionTick = PrinterAttemptTracker.DEADLINE_TICKS *
            (ScaffoldLedger.MAX_BREAK_RETRIES + 1).toLong()
        while (tick < exhaustionTick) {
            tick++
            ledger.tickBreaks(tick = tick, stateAt = stateAt, destroy = destroy)
        }
        assertEquals(3, destroyCalls)
        assertTrue(ledger.snapshot().single().exhausted)

        val rearmedCount = ledger.rearmExhausted()

        assertEquals(1, rearmedCount)
        assertFalse(ledger.snapshot().single().exhausted)
        assertEquals(0, ledger.snapshot().single().retryCount)

        tick++
        ledger.tickBreaks(tick = tick, stateAt = stateAt, destroy = destroy)
        assertEquals(4, destroyCalls)
    }

    @Test
    public fun rearmExhaustedIsANoOpWhenNoRecordIsExhausted(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)

        val rearmedCount = ledger.rearmExhausted()

        assertEquals(0, rearmedCount)
        assertEquals(ScaffoldState.CONSUMED, ledger.snapshot().single().state)
    }

    @Test
    public fun cleanupAllBreaksReachableScaffoldsAndReportsTheUnreachableLeftoverCount(): Unit {
        val ledger = ScaffoldLedger()
        val breakable = BlockPos(0, 1, 0)
        val stuck = BlockPos(5, 1, 0)
        ledger.recordPlaced(breakable, BlockPos(0, 1, 1))
        ledger.recordPlaced(stuck, BlockPos(5, 1, 1))
        val world = HashMap<BlockPos, BlockState>()
        world[breakable] = Blocks.SLIME_BLOCK.defaultBlockState()
        world[stuck] = Blocks.SLIME_BLOCK.defaultBlockState()
        val destroyCallsByPos = HashMap<BlockPos, Int>()
        val stateAt: (BlockPos) -> BlockState = { pos -> world[pos] ?: Blocks.AIR.defaultBlockState() }
        val destroy: (BlockPos) -> Boolean = { pos ->
            destroyCallsByPos[pos] = (destroyCallsByPos[pos] ?: 0) + 1
            if (pos == breakable) world[pos] = Blocks.AIR.defaultBlockState()
            true
        }

        val leftover = ledger.cleanupAll(stateAt, destroy)

        assertEquals(1, leftover)
        assertEquals(1, destroyCallsByPos[breakable])
        assertEquals(3, destroyCallsByPos[stuck]) { "bounded CLEANUP_MAX_ATTEMPTS retries" }
        assertFalse(ledger.isOutstanding()) { "cleanup evicts every record regardless of outcome" }
        assertFalse(ledger.isScaffoldCell(breakable))
        assertFalse(ledger.isScaffoldCell(stuck))
    }

    @Test
    public fun cleanupAllIsANoOpOnAnEmptyLedger(): Unit {
        val ledger = ScaffoldLedger()

        val leftover = ledger.cleanupAll(
            stateAt = { Blocks.AIR.defaultBlockState() },
            destroy = { error("must not be called") },
        )

        assertEquals(0, leftover)
    }

    @Test
    public fun clearAllForgetsEveryRecordWithoutAttemptingToBreakAnything(): Unit {
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)

        ledger.clearAll()

        assertFalse(ledger.isOutstanding())
        assertFalse(ledger.isScaffoldCell(scaffoldPos))
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test
    public fun aTargetlessOrphanRecordedWithItselfAsTargetGoesStraightToTheBreakQueue(): Unit {
        val ledger = ScaffoldLedger()
        val orphan = BlockPos(3, 1, 3)
        var world = Blocks.SLIME_BLOCK.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { world }
        val destroy: (BlockPos) -> Boolean = { world = Blocks.AIR.defaultBlockState(); true }
        var broken: BlockPos? = null

        ledger.recordPlaced(orphan, orphan)
        ledger.markConsumed(orphan)
        assertEquals(ScaffoldState.CONSUMED, ledger.snapshot().single().state)

        for (tick in 0L..PrinterAttemptTracker.SETTLE_TICKS.toLong()) {
            ledger.tickBreaks(tick = tick, stateAt = stateAt, destroy = destroy, onBroken = { broken = it })
        }

        assertEquals(orphan, broken)
        assertFalse(ledger.isOutstanding())
        assertFalse(ledger.isScaffoldCell(orphan))
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
