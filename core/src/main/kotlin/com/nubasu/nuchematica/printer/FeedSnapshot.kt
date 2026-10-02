package com.nubasu.nuchematica.printer

public data class FeedSnapshot(
    public val tick: Long,
    public val queueRevision: Long,
    public val candidateCount: Int,
    public val submittedCount: Int,
    public val rateLimitedRemainder: Int,
    public val inFlightCount: Int,
    public val acceptedThisTick: Int,
    /** Elapsed session ticks since the last acceptance, or null before the first. */
    public val ticksSinceLastAccept: Long? = null,
)

internal class FeedSnapshotStore {
    private var sessionKey: PrinterSessionKey? = null
    private var snapshot: FeedSnapshot? = null

    internal fun update(sessionKey: PrinterSessionKey, snapshot: FeedSnapshot): Unit {
        this.sessionKey = sessionKey
        this.snapshot = snapshot
    }

    internal fun latest(sessionKey: PrinterSessionKey): FeedSnapshot? {
        return snapshot?.takeIf { this.sessionKey?.matches(sessionKey) == true }
    }

    internal fun fresh(sessionKey: PrinterSessionKey, queueRevision: Long): FeedSnapshot? {
        return latest(sessionKey)?.takeIf { snapshot ->
            snapshot.queueRevision == queueRevision
        }
    }

    internal fun clear(): Unit {
        sessionKey = null
        snapshot = null
    }
}
