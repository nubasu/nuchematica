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

public class PrinterDeferralLedgerTest {
    @Test
    public fun retryLimitUsesTheSameNeighborRevalidationAsPredictionMismatch(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos(0, 1, 0)
        var westState = Blocks.STONE.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.west()) westState else Blocks.AIR.defaultBlockState()
        }

        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.RETRY_LIMIT, stateAt)
        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 0L))

        westState = Blocks.DIRT.defaultBlockState()
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 0L))
    }

    @Test
    public fun moverUnreachableStaysDeferredAtTheSameQueueRevisionAndEvictsWhenItChanges(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos(0, 1, 0)
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 5L)

        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 5L))
        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 5L))

        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 6L))
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 5L))
    }

    @Test
    public fun progressEpochBackoffRetriesAfterOneTwoFourAndCapsAtSixtyFourRevisions(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        val thresholds = listOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 64L)
        var revision = 0L
        ledger.synchronize(1L, Any(), Any())

        for (threshold in thresholds) {
            ledger.defer(
                target,
                PrinterDeferralReason.MOVER_UNREACHABLE,
                stateAt,
                queueRevision = revision,
            )

            assertTrue(ledger.isDeferred(target, stateAt, revision + threshold - 1L)) {
                "deferral must remain active until the $threshold-revision threshold"
            }
            assertFalse(ledger.isDeferred(target, stateAt, revision + threshold)) {
                "retry must be eligible at the $threshold-revision threshold"
            }
            revision += threshold
        }
    }

    @Test
    public fun progressEpochFailureCountsAreIndependentPerCause(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        ledger.synchronize(1L, Any(), Any())

        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 0L)
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 1L))
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 1L)
        ledger.defer(target, PrinterDeferralReason.NO_PROGRESS, stateAt, queueRevision = 1L)

        assertEquals(
            setOf(PrinterDeferralReason.MOVER_UNREACHABLE),
            ledger.activeReasons(target, stateAt, queueRevision = 2L),
            "NO_PROGRESS must still receive its own first-failure one-revision retry",
        )
        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 2L))
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 3L))
    }

    @Test
    public fun finalSweepBypassIsImmediateOnceAndPreservesFailureHistory(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.RETRY_LIMIT, stateAt)
        ledger.defer(target, PrinterDeferralReason.NO_PROGRESS, stateAt, queueRevision = 10L)

        ledger.grantFinalSweepBackoffBypass()

        assertEquals(
            setOf(PrinterDeferralReason.RETRY_LIMIT),
            ledger.activeReasons(target, stateAt, queueRevision = 10L),
            "the bypass must affect only progress-epoch causes",
        )
        ledger.defer(target, PrinterDeferralReason.NO_PROGRESS, stateAt, queueRevision = 10L)
        assertEquals(
            setOf(PrinterDeferralReason.RETRY_LIMIT, PrinterDeferralReason.NO_PROGRESS),
            ledger.activeReasons(target, stateAt, queueRevision = 11L),
            "the grant is consumed once and the re-defer must use failure count one",
        )
        assertEquals(
            setOf(PrinterDeferralReason.RETRY_LIMIT),
            ledger.activeReasons(target, stateAt, queueRevision = 12L),
        )
    }

    @Test
    public fun moverUnreachableIgnoresNeighborChangesUnlikeTheOtherTwoReasons(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos(0, 1, 0)
        var westState = Blocks.STONE.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.west()) westState else Blocks.AIR.defaultBlockState()
        }

        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 2L)
        westState = Blocks.DIRT.defaultBlockState()

        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 2L))
    }

    @Test
    public fun synchronizeClearsEveryReasonRegardlessOfRevalidationStrategy(): Unit {
        val ledger = PrinterDeferralLedger()
        val predictionTarget = BlockPos(0, 1, 0)
        val unreachableTarget = BlockPos(5, 1, 0)
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        val content = Any()
        val level = Any()

        ledger.synchronize(1L, content, level)
        ledger.defer(predictionTarget, PrinterDeferralReason.PREDICTION_MISMATCH, stateAt)
        ledger.defer(unreachableTarget, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 3L)

        ledger.synchronize(2L, content, level)

        assertFalse(ledger.isDeferred(predictionTarget, stateAt, queueRevision = 1L))
        assertFalse(ledger.isDeferred(unreachableTarget, stateAt, queueRevision = 3L))
    }

    @Test
    public fun independentCausesRevalidateWithoutEvictingEachOther(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos(0, 1, 0)
        var westState = Blocks.STONE.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.west()) westState else Blocks.AIR.defaultBlockState()
        }
        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.PREDICTION_MISMATCH, stateAt)
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 7L)

        assertEquals(
            setOf(
                PrinterDeferralReason.PREDICTION_MISMATCH,
                PrinterDeferralReason.MOVER_UNREACHABLE,
            ),
            ledger.activeReasons(target, stateAt, queueRevision = 7L),
        )

        assertEquals(
            setOf(PrinterDeferralReason.PREDICTION_MISMATCH),
            ledger.activeReasons(target, stateAt, queueRevision = 8L),
        )
        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 8L))

        westState = Blocks.DIRT.defaultBlockState()
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 8L))
    }

    @Test
    public fun noProgressUsesProgressEpochAndRefreshesOnlyItsOwnRecord(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.RETRY_LIMIT, stateAt)
        ledger.defer(target, PrinterDeferralReason.NO_PROGRESS, stateAt, queueRevision = 2L)
        ledger.defer(target, PrinterDeferralReason.NO_PROGRESS, stateAt, queueRevision = 4L)

        assertEquals(
            setOf(PrinterDeferralReason.RETRY_LIMIT, PrinterDeferralReason.NO_PROGRESS),
            ledger.activeReasons(target, stateAt, queueRevision = 4L),
        )
        assertEquals(
            setOf(PrinterDeferralReason.RETRY_LIMIT, PrinterDeferralReason.NO_PROGRESS),
            ledger.activeReasons(target, stateAt, queueRevision = 5L),
        )
        assertEquals(
            setOf(PrinterDeferralReason.RETRY_LIMIT),
            ledger.activeReasons(target, stateAt, queueRevision = 6L),
        )
    }

    @Test
    public fun clearMoverCausesAlsoResetsProgressFailureHistory(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 0L)
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 1L))
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 1L)

        ledger.clearMoverCauses()
        ledger.defer(target, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 1L)

        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 2L))
    }

    @Test
    public fun clearMoverCausesPreservesPrinterCausesAndClearAllRemovesEverything(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val other = BlockPos(5, 0, 0)
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        ledger.synchronize(1L, Any(), Any())
        ledger.defer(target, PrinterDeferralReason.RETRY_LIMIT, stateAt)
        ledger.defer(target, PrinterDeferralReason.NO_PROGRESS, stateAt, queueRevision = 3L)
        ledger.defer(other, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision = 3L)

        ledger.clearMoverCauses()

        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 3L))
        assertFalse(ledger.isDeferred(other, stateAt, queueRevision = 3L))
        ledger.clearAll()
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 3L))
    }

    @Test
    public fun synchronizeClearsOnClientLevelIdentityChange(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos.ZERO
        val content = Any()
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        ledger.synchronize(1L, content, Any())
        ledger.defer(target, PrinterDeferralReason.RETRY_LIMIT, stateAt)

        ledger.synchronize(1L, content, Any())

        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 0L))
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
