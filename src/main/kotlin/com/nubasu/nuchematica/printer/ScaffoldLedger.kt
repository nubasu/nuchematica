package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

internal enum class ScaffoldState { PLACED, CONSUMED }

internal class ScaffoldRecord internal constructor(
    internal val scaffoldPos: BlockPos,
    internal val targetPos: BlockPos,
    internal var state: ScaffoldState,
) {
    internal var retryCount: Int = 0
    internal var exhausted: Boolean = false
}

/**
 * Owns scaffold cells accepted from printer submissions.
 *
 * Break and cleanup operations only target positions previously added through [recordPlaced].
 */
internal class ScaffoldLedger {
    private val byScaffoldPos: LinkedHashMap<BlockPos, ScaffoldRecord> = LinkedHashMap()
    private val scaffoldByTarget: HashMap<BlockPos, BlockPos> = HashMap()
    private val breakTracker: PrinterAttemptTracker = PrinterAttemptTracker()

    internal fun isOutstanding(): Boolean = byScaffoldPos.isNotEmpty()

    internal fun isScaffoldCell(worldPos: BlockPos): Boolean = worldPos.immutable() in byScaffoldPos

    internal fun isBreakInFlight(worldPos: BlockPos): Boolean = breakTracker.isInFlight(worldPos)

    internal fun snapshot(): List<ScaffoldRecord> = byScaffoldPos.values.toList()

    /** Records a scaffold after its placement attempt has been accepted. */
    internal fun recordPlaced(scaffoldPos: BlockPos, targetPos: BlockPos): Unit {
        val immutableScaffold = scaffoldPos.immutable()
        val immutableTarget = targetPos.immutable()
        byScaffoldPos[immutableScaffold] = ScaffoldRecord(immutableScaffold, immutableTarget, ScaffoldState.PLACED)
        scaffoldByTarget[immutableTarget] = immutableScaffold
    }

    /** Marks the scaffold supporting [targetPos] eligible for removal. */
    internal fun markConsumed(targetPos: BlockPos): Unit {
        val scaffoldPos = scaffoldByTarget[targetPos.immutable()] ?: return
        val record = byScaffoldPos[scaffoldPos] ?: return
        if (record.state == ScaffoldState.PLACED) record.state = ScaffoldState.CONSUMED
    }

    /**
     * Advances acknowledged breaks for consumed scaffolds.
     *
     * Retry-exhausted cells remain tracked until rearmed or explicitly cleaned up.
     */
    internal fun tickBreaks(
        tick: Long,
        stateAt: (BlockPos) -> BlockState,
        destroy: (BlockPos) -> Boolean,
        canDestroy: (BlockPos) -> Boolean = { true },
        breakStateAt: (BlockPos) -> BlockState = stateAt,
        onAttemptSettled: (BlockPos) -> Unit = {},
        onBroken: (BlockPos) -> Unit = {},
        onRetryExhausted: (BlockPos, Int) -> Unit = { _, _ -> },
    ): Unit {
        for (result in breakTracker.observe(tick, breakStateAt)) {
            onAttemptSettled(result.attempt.worldPos)
            val record = byScaffoldPos[result.attempt.worldPos] ?: continue
            if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                byScaffoldPos.remove(record.scaffoldPos)
                scaffoldByTarget.remove(record.targetPos)
                onBroken(record.scaffoldPos)
            } else if (result.attempt.retryCount >= MAX_BREAK_RETRIES) {
                record.exhausted = true
                onRetryExhausted(record.scaffoldPos, result.attempt.retryCount + 1)
            } else {
                record.retryCount = result.attempt.retryCount + 1
            }
        }
        for (record in byScaffoldPos.values) {
            if (record.state != ScaffoldState.CONSUMED) continue
            if (record.exhausted) continue
            if (breakTracker.isInFlight(record.scaffoldPos)) continue
            if (!canDestroy(record.scaffoldPos)) continue
            val baseline = stateAt(record.scaffoldPos)
            destroy(record.scaffoldPos)
            breakTracker.attempt(
                worldPos = record.scaffoldPos,
                expectedState = AIR_STATE,
                baselineState = baseline,
                sentTick = tick,
                retryCount = record.retryCount,
            )
        }
    }

    /** Attempts bounded immediate cleanup and returns the number of cells left behind. */
    internal fun cleanupAll(
        stateAt: (BlockPos) -> BlockState,
        destroy: (BlockPos) -> Boolean,
    ): Int {
        var leftover = 0
        for (scaffoldPos in byScaffoldPos.keys.toList()) {
            var attempts = 0
            while (attempts < CLEANUP_MAX_ATTEMPTS && !isReplaceableTarget(stateAt(scaffoldPos))) {
                destroy(scaffoldPos)
                attempts++
            }
            val record = byScaffoldPos.remove(scaffoldPos) ?: continue
            scaffoldByTarget.remove(record.targetPos)
            if (!isReplaceableTarget(stateAt(scaffoldPos))) leftover++
        }
        breakTracker.cancelAll()
        return leftover
    }

    /** Clears bookkeeping only; callers must remove world scaffolds first. */
    internal fun clearAll(): Unit {
        byScaffoldPos.clear()
        scaffoldByTarget.clear()
        breakTracker.cancelAll()
    }

    internal fun rearmExhausted(): Int {
        var count = 0
        for (record in byScaffoldPos.values) {
            if (!record.exhausted) continue
            record.exhausted = false
            record.retryCount = 0
            count++
        }
        return count
    }

    internal companion object {
        internal const val MAX_BREAK_RETRIES: Int = 2
        private const val CLEANUP_MAX_ATTEMPTS: Int = 3
        private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()
    }
}
