package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterAttemptTrackerTest {
    private val worldPos: BlockPos = BlockPos(1, 2, 3)
    private val expectedState: BlockState = Blocks.STONE.defaultBlockState()
    private val baselineState: BlockState = Blocks.AIR.defaultBlockState()

    @Test
    public fun stableExpectedStateIsAccepted(): Unit {
        val tracker = trackedAttempt()

        val result = observeStable(tracker, expectedState)

        assertEquals(PrinterAttemptOutcome.ACCEPTED, result.outcome)
        assertEquals(5L, result.completedTick)
        assertFalse(tracker.isInFlight(worldPos))
    }

    @Test
    public fun stableBaselineStateIsRejected(): Unit {
        val tracker = trackedAttempt()

        for (tick in 1L until PrinterAttemptTracker.DEADLINE_TICKS) {
            assertTrue(tracker.observe(tick, { baselineState }).isEmpty())
        }
        val result = tracker.observe(PrinterAttemptTracker.DEADLINE_TICKS, { baselineState }).single()

        assertEquals(PrinterAttemptOutcome.REJECTED, result.outcome)
        assertEquals(PrinterAttemptTracker.DEADLINE_TICKS, result.completedTick)
    }

    @Test
    public fun stableBaselineBeforeDeadlineDoesNotPreemptDelayedAcceptance(): Unit {
        val tracker = trackedAttempt()

        for (tick in 1L until 10L) {
            assertTrue(tracker.observe(tick, { baselineState }).isEmpty())
        }
        var result: PrinterAttemptResult? = null
        for (tick in 10L until 10L + PrinterAttemptTracker.SETTLE_TICKS) {
            val completed = tracker.observe(tick, { expectedState })
            if (completed.isNotEmpty()) result = completed.single()
        }

        assertEquals(PrinterAttemptOutcome.ACCEPTED, result?.outcome)
        assertEquals(14L, result?.completedTick)
    }

    @Test
    public fun stableOtherStateIsWrongState(): Unit {
        val tracker = trackedAttempt()
        val wrongState = Blocks.DIRT.defaultBlockState()

        val result = observeStable(tracker, wrongState)

        assertEquals(PrinterAttemptOutcome.WRONG_STATE, result.outcome)
        assertEquals(wrongState, result.observedState)
    }

    @Test
    public fun stableEquivalentPlacedLeavesAreAccepted(): Unit {
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
            .setValue(BlockStateProperties.DISTANCE, 1)
        val tracker = trackedAttempt(naturalLeaves)

        val result = observeStable(tracker, placedLeaves)

        assertEquals(PrinterAttemptOutcome.ACCEPTED, result.outcome)
        assertEquals(5L, result.completedTick)
    }

    @Test
    public fun stableDifferentBlockFromExpectedLeavesIsWrongState(): Unit {
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val tracker = trackedAttempt(naturalLeaves)

        val result = observeStable(tracker, Blocks.DIRT.defaultBlockState())

        assertEquals(PrinterAttemptOutcome.WRONG_STATE, result.outcome)
    }

    @Test
    public fun unstableObservationTimesOutAtDeadline(): Unit {
        val tracker = trackedAttempt()
        var completed = emptyList<PrinterAttemptResult>()

        for (tick in 1L..PrinterAttemptTracker.DEADLINE_TICKS) {
            completed = tracker.observe(
                tick,
                {
                    if (tick % 2L == 0L) {
                        Blocks.DIRT.defaultBlockState()
                    } else {
                        Blocks.COBBLESTONE.defaultBlockState()
                    }
                },
            )
        }

        assertEquals(1, completed.size)
        assertEquals(PrinterAttemptOutcome.TIMEOUT, completed.single().outcome)
        assertEquals(PrinterAttemptTracker.DEADLINE_TICKS, completed.single().completedTick)
    }

    @Test
    public fun customMatcherOverridesDefaultEquivalence(): Unit {
        val tracker = trackedAttempt()
        val differentBlock = Blocks.DIRT.defaultBlockState()
        val alwaysMatches: (BlockState, BlockState) -> Boolean = { _, _ -> true }

        var result: PrinterAttemptResult? = null
        for (tick in 1L..PrinterAttemptTracker.SETTLE_TICKS.toLong()) {
            val completed = tracker.observe(tick, { differentBlock }, alwaysMatches)
            if (completed.isNotEmpty()) result = completed.single()
        }

        assertEquals(PrinterAttemptOutcome.ACCEPTED, result?.outcome)
    }

    @Test
    public fun duplicatePositionCannotBeInFlightTwice(): Unit {
        val tracker = PrinterAttemptTracker()

        assertTrue(
            tracker.attempt(worldPos, expectedState, baselineState, sentTick = 0L, retryCount = 0),
        )
        assertFalse(
            tracker.attempt(worldPos, expectedState, baselineState, sentTick = 1L, retryCount = 1),
        )
        assertEquals(1, tracker.activeAttempts().size)
    }

    @Test
    public fun cancelAllDiscardsLateObservations(): Unit {
        val tracker = trackedAttempt()

        tracker.cancelAll()

        assertTrue(tracker.observe(5L, { expectedState }).isEmpty())
        assertTrue(tracker.activeAttempts().isEmpty())
    }

    private fun trackedAttempt(expected: BlockState = expectedState): PrinterAttemptTracker {
        return PrinterAttemptTracker().also { tracker ->
            assertTrue(
                tracker.attempt(worldPos, expected, baselineState, sentTick = 0L, retryCount = 0),
            )
        }
    }

    private fun observeStable(
        tracker: PrinterAttemptTracker,
        state: BlockState,
    ): PrinterAttemptResult {
        for (tick in 1L until PrinterAttemptTracker.SETTLE_TICKS.toLong()) {
            assertTrue(tracker.observe(tick, { state }).isEmpty())
        }
        return tracker.observe(PrinterAttemptTracker.SETTLE_TICKS.toLong(), { state }).single()
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
