package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

// Lifecycle states for a tracked scaffold cell. BROKEN is transient: the
// instant a break is confirmed (world state settles back to air/replaceable) the record
// is evicted from the ledger rather than lingering in a BROKEN bucket, so "no longer
// present in the ledger" IS the BROKEN state externally -- isOutstanding()/
// isScaffoldCell() both read straight off ledger membership.
internal enum class ScaffoldState { PLACED, CONSUMED }

internal class ScaffoldRecord internal constructor(
    internal val scaffoldPos: BlockPos,
    internal val targetPos: BlockPos,
    internal var state: ScaffoldState,
) {
    internal var retryCount: Int = 0
    internal var exhausted: Boolean = false
}

// Printer-owned ledger for every scaffold cell the printer itself has
// placed. This is the sole authority the highest-stakes invariant here rests on
// -- "the no-breaking relaxation is EXACTLY ledger-tracked scaffold cells" -- because
// tickBreaks/cleanupAll only ever iterate byScaffoldPos.values: there is no code path in
// this class (or its caller, SchematicPrinter) that calls the injected destroy function
// on any BlockPos that did not first arrive here through recordPlaced.
internal class ScaffoldLedger {
    private val byScaffoldPos: LinkedHashMap<BlockPos, ScaffoldRecord> = LinkedHashMap()
    private val scaffoldByTarget: HashMap<BlockPos, BlockPos> = HashMap()
    private val breakTracker: PrinterAttemptTracker = PrinterAttemptTracker()

    internal fun isOutstanding(): Boolean = byScaffoldPos.isNotEmpty()

    internal fun isScaffoldCell(worldPos: BlockPos): Boolean = worldPos.immutable() in byScaffoldPos

    internal fun snapshot(): List<ScaffoldRecord> = byScaffoldPos.values.toList()

    // Called once the scaffold's OWN placement attempt settles ACCEPTED: the cell now
    // genuinely holds SCAFFOLD_BLOCK_STATE in the world.
    internal fun recordPlaced(scaffoldPos: BlockPos, targetPos: BlockPos): Unit {
        val immutableScaffold = scaffoldPos.immutable()
        val immutableTarget = targetPos.immutable()
        byScaffoldPos[immutableScaffold] = ScaffoldRecord(immutableScaffold, immutableTarget, ScaffoldState.PLACED)
        scaffoldByTarget[immutableTarget] = immutableScaffold
    }

    // Meant to be called for EVERY ordinary placement completion (ACCEPTED/WRONG_STATE):
    // a no-op unless targetPos happens to be a tracked scaffold's dependent real block,
    // so callers never need to pre-filter by "is this a scaffold target" themselves.
    internal fun markConsumed(targetPos: BlockPos): Unit {
        val scaffoldPos = scaffoldByTarget[targetPos.immutable()] ?: return
        val record = byScaffoldPos[scaffoldPos] ?: return
        if (record.state == ScaffoldState.PLACED) record.state = ScaffoldState.CONSUMED
    }

    // Drives the break ack cycle for every CONSUMED record: submits a creative-instant
    // destroy (MultiPlayerGameMode.destroyBlock(BlockPos): boolean, javap-verified
    // against forge-1.18.2-40.3.0_mapped_official_1.18.2.jar) through the same
    // submit-then-observe pattern PrinterAttemptTracker already gives ordinary placement
    // attempts ("existing gateway/ack machinery pattern"), then evicts
    // once the cell is confirmed air/replaceable. Bounded deadline, 2 retries mirrors
    // PrinterRuntime.MAX_RETRIES exactly (retryCount 0, 1, 2 submitted -- three attempts
    // total -- before giving up); once exhausted the record is left CONSUMED (still
    // outstanding, still blocking canAutoMoveComplete) rather than evicted, so a failed
    // break is reported, never silently treated as done.
    internal fun tickBreaks(
        tick: Long,
        stateAt: (BlockPos) -> BlockState,
        destroy: (BlockPos) -> Boolean,
        onBroken: (BlockPos) -> Unit = {},
        onRetryExhausted: (BlockPos, Int) -> Unit = { _, _ -> },
    ): Unit {
        for (result in breakTracker.observe(tick, stateAt)) {
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

    // Best-effort synchronous cleanup for every tracked record (abort/toggle-off/
    // session-change exit paths): destroyBlock is synchronous in
    // creative, so no multi-tick ack cycle is needed here -- retries the destroy call up
    // to CLEANUP_MAX_ATTEMPTS times per cell within this single invocation, evicts every
    // cell that ends up air/replaceable, and returns the count that did not. Callers
    // report that count via chat rather than silently dropping it (never leave the
    // scaffold material in the world unreported).
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

    // Session reset (no breaking attempted -- callers must run cleanupAll first if
    // breaking matters): forgets every record and any in-flight break attempt.
    internal fun clearAll(): Unit {
        byScaffoldPos.clear()
        scaffoldByTarget.clear()
        breakTracker.cancelAll()
    }

    // Gives every retry-exhausted CONSUMED record one more
    // bounded round of break attempts instead of leaving it stuck outstanding forever.
    // Resets exhausted/retryCount so the next tickBreaks call treats the record as
    // freshly CONSUMED again (submits at retryCount=0, up to MAX_BREAK_RETRIES more
    // times before exhausting again). Unconditional and idempotent -- bounding how
    // often this runs per stuck encounter is the caller's job (SchematicPrinter's
    // once-per-terminal-encounter guard), not this method's.
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
