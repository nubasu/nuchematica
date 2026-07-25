package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState

// Independent causes that can temporarily exclude a position from printer/mover work:
// PREDICTION_MISMATCH -- placement prediction did not match the schematic state.
// RETRY_LIMIT -- the runtime exhausted its bounded placement retries.
// MOVER_UNREACHABLE -- every usable mover target for the position was abandoned.
// NO_PROGRESS -- reached mover targets repeatedly produced no queue progress.
internal enum class PrinterDeferralReason {
    PREDICTION_MISMATCH,
    RETRY_LIMIT,
    MOVER_UNREACHABLE,
    NO_PROGRESS,
}

// A position can retain several causes at once. Refreshing one cause leaves the other
// records untouched, and lazy revalidation removes only the causes whose own condition
// changed. isDeferred therefore remains true until every independent cause is invalid.
//
// Revalidation strategies are selected per cause:
// - PREDICTION_MISMATCH / RETRY_LIMIT snapshot the position's six neighbors. A
//   reference change to any interned BlockState removes that cause.
// - MOVER_UNREACHABLE / NO_PROGRESS snapshot the queue revision and use independent
//   exponential backoff histories. Progress grants a retry only after that cause's
//   current threshold has elapsed.
// Transform, schematic-content, and client-level identity changes clear every cause.
// Auto-move re-toggle clears only mover causes so printer placement causes stay sticky.
internal class PrinterDeferralLedger {
    private var transformRevision: Long = 0L
    private var contentIdentity: Any? = null
    private var levelIdentity: Any? = null
    private val deferredWorld: HashMap<BlockPos, MutableMap<PrinterDeferralReason, DeferralRecord>> = HashMap()
    private val progressFailureCounts:
        HashMap<BlockPos, MutableMap<PrinterDeferralReason, Int>> = HashMap()

    internal fun synchronize(
        transformRevision: Long,
        contentIdentity: Any,
        levelIdentity: Any,
    ): Unit {
        if (
            this.transformRevision != transformRevision ||
            this.contentIdentity !== contentIdentity ||
            this.levelIdentity !== levelIdentity
        ) {
            clearAll()
        }
        this.transformRevision = transformRevision
        this.contentIdentity = contentIdentity
        this.levelIdentity = levelIdentity
    }

    // queueRevision is used only by progress-epoch causes. Neighbor-snapshot causes
    // retain the default and ignore it.
    internal fun defer(
        worldPos: BlockPos,
        reason: PrinterDeferralReason,
        stateAt: (BlockPos) -> BlockState,
        queueRevision: Long = 0L,
    ): Unit {
        val immutable = worldPos.immutable()
        val records = deferredWorld.getOrPut(immutable) { HashMap() }
        records[reason] = if (reason.usesProgressEpoch()) {
            val failureCounts = progressFailureCounts.getOrPut(immutable) { HashMap() }
            val failureCount = failureCounts[reason]?.let { previous ->
                if (previous == Int.MAX_VALUE) previous else previous + 1
            } ?: 0
            failureCounts[reason] = failureCount
            DeferralRecord(
                neighbors = null,
                queueRevisionAtDefer = queueRevision,
                failureCount = failureCount,
            )
        } else {
            DeferralRecord(
                neighbors = neighborSnapshot(immutable, stateAt),
                queueRevisionAtDefer = 0L,
                failureCount = 0,
            )
        }
    }

    internal fun clearMoverCauses(): Unit {
        val positions = deferredWorld.entries.iterator()
        while (positions.hasNext()) {
            val records = positions.next().value
            records.remove(PrinterDeferralReason.MOVER_UNREACHABLE)
            records.remove(PrinterDeferralReason.NO_PROGRESS)
            if (records.isEmpty()) positions.remove()
        }
        progressFailureCounts.clear()
    }

    // The mover guards this grant by queue revision. Removing only the active progress
    // records makes the terminal sweep eligible immediately, while the separate failure
    // history ensures a position re-deferred by that sweep receives its next threshold.
    internal fun grantFinalSweepBackoffBypass(): Unit {
        val positions = deferredWorld.entries.iterator()
        while (positions.hasNext()) {
            val records = positions.next().value
            val causes = records.keys.iterator()
            while (causes.hasNext()) {
                if (causes.next().usesProgressEpoch()) causes.remove()
            }
            if (records.isEmpty()) positions.remove()
        }
    }

    internal fun clearAll(): Unit {
        deferredWorld.clear()
        progressFailureCounts.clear()
    }

    // TEMP C3DBG (remove after the layer-pin investigation): raw recorded causes,
    // deliberately without the lazy revalidation/eviction activeReasons performs.
    internal fun deferredSnapshot(): Map<BlockPos, Set<PrinterDeferralReason>> =
        deferredWorld.mapValues { (_, records) -> records.keys.toSet() }

    internal fun isDeferred(
        worldPos: BlockPos,
        stateAt: (BlockPos) -> BlockState,
        queueRevision: Long,
    ): Boolean = activeReasons(worldPos, stateAt, queueRevision).isNotEmpty()

    // Revalidates each cause independently on every query. Callers only reach this for
    // in-reach candidates, gate scanning, and terminal classification, keeping the
    // neighbor-read cost bounded while ensuring diagnostics cannot authorize a stale
    // COMPLETE decision.
    internal fun activeReasons(
        worldPos: BlockPos,
        stateAt: (BlockPos) -> BlockState,
        queueRevision: Long,
    ): Set<PrinterDeferralReason> {
        val immutable = worldPos.immutable()
        val records = deferredWorld[immutable] ?: return emptySet()
        val causes = records.entries.iterator()
        while (causes.hasNext()) {
            val (reason, record) = causes.next()
            if (!record.isValid(reason, immutable, stateAt, queueRevision)) causes.remove()
        }
        if (records.isEmpty()) {
            deferredWorld.remove(immutable)
            return emptySet()
        }
        return records.keys.toSet()
    }

    private fun DeferralRecord.isValid(
        reason: PrinterDeferralReason,
        worldPos: BlockPos,
        stateAt: (BlockPos) -> BlockState,
        queueRevision: Long,
    ): Boolean {
        if (reason.usesProgressEpoch()) {
            val revisionsElapsed = queueRevision - queueRevisionAtDefer
            return revisionsElapsed < backoffThreshold(failureCount)
        }

        val snapshot = requireNotNull(neighbors)
        val current = neighborSnapshot(worldPos, stateAt)
        for (index in snapshot.indices) {
            // BlockState instances are interned per block+properties combination.
            if (snapshot[index] !== current[index]) return false
        }
        return true
    }

    private fun neighborSnapshot(
        worldPos: BlockPos,
        stateAt: (BlockPos) -> BlockState,
    ): Array<BlockState> {
        val directions = Direction.values()
        return Array(directions.size) { index -> stateAt(worldPos.relative(directions[index])) }
    }

    private fun PrinterDeferralReason.usesProgressEpoch(): Boolean {
        return this == PrinterDeferralReason.MOVER_UNREACHABLE ||
            this == PrinterDeferralReason.NO_PROGRESS
    }

    private fun backoffThreshold(failureCount: Int): Long {
        return if (failureCount >= MAX_EXPONENT) MAX_BACKOFF_REVISIONS else 1L shl failureCount
    }

    private data class DeferralRecord(
        val neighbors: Array<BlockState>?,
        val queueRevisionAtDefer: Long,
        val failureCount: Int,
    )

    private companion object {
        private const val MAX_EXPONENT: Int = 6
        private const val MAX_BACKOFF_REVISIONS: Long = 64L
    }
}
