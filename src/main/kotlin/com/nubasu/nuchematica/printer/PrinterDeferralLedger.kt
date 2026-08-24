package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState

/** Independent causes that can temporarily exclude a position from printer work. */
internal enum class PrinterDeferralReason {
    PREDICTION_MISMATCH,
    RETRY_LIMIT,
    MOVER_UNREACHABLE,
    NO_PROGRESS,
}

/**
 * Tracks independent deferral causes per position and revalidates them lazily.
 *
 * Placement failures remain deferred until a neighboring state changes. Movement failures
 * use independent revision-based exponential backoff. Session identity changes clear both.
 */
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

    /** Records one cause without replacing other causes for the same position. */
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

    /**
     * Clears active movement backoffs for one final sweep without resetting failure history.
     */
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

    internal fun isDeferred(
        worldPos: BlockPos,
        stateAt: (BlockPos) -> BlockState,
        queueRevision: Long,
    ): Boolean = activeReasons(worldPos, stateAt, queueRevision).isNotEmpty()

    /** Returns the causes still valid after neighbor or revision-based revalidation. */
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
