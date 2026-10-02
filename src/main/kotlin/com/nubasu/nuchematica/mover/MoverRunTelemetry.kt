package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.FeedSnapshot

internal data class MoverFeedTelemetry(
    internal val activeTicks: Long,
    internal val feedTicks: Long,
    internal val submittedCount: Long,
    internal val acceptedCount: Long,
)

internal data class MoverRunTelemetrySnapshot(
    internal val totalActiveTicks: Long,
    internal val feedTicks: Long,
    internal val submittedCount: Long,
    internal val acceptedCount: Long,
    internal val cruise: MoverFeedTelemetry,
    internal val hold: MoverFeedTelemetry,
)

internal class MoverRunTelemetry {
    private var totalActiveTicks: Long = 0L
    private var feedTicks: Long = 0L
    private var submittedCount: Long = 0L
    private var acceptedCount: Long = 0L
    private val cruise: MutableMoverFeedTelemetry = MutableMoverFeedTelemetry()
    private val hold: MutableMoverFeedTelemetry = MutableMoverFeedTelemetry()

    internal fun record(state: MoverState, snapshot: FeedSnapshot?): Unit {
        totalActiveTicks++
        if (snapshot != null) {
            if (snapshot.candidateCount > 0) feedTicks++
            submittedCount += snapshot.submittedCount
            acceptedCount += snapshot.acceptedThisTick
        }
        when (state) {
            MoverState.CRUISE -> cruise.record(snapshot)
            MoverState.HOLD -> hold.record(snapshot)
            else -> Unit
        }
    }

    internal fun snapshot(): MoverRunTelemetrySnapshot {
        return MoverRunTelemetrySnapshot(
            totalActiveTicks = totalActiveTicks,
            feedTicks = feedTicks,
            submittedCount = submittedCount,
            acceptedCount = acceptedCount,
            cruise = cruise.snapshot(),
            hold = hold.snapshot(),
        )
    }

    internal fun reset(): Unit {
        totalActiveTicks = 0L
        feedTicks = 0L
        submittedCount = 0L
        acceptedCount = 0L
        cruise.reset()
        hold.reset()
    }
}

private class MutableMoverFeedTelemetry {
    private var activeTicks: Long = 0L
    private var feedTicks: Long = 0L
    private var submittedCount: Long = 0L
    private var acceptedCount: Long = 0L

    internal fun record(snapshot: FeedSnapshot?): Unit {
        activeTicks++
        if (snapshot == null) return
        if (snapshot.candidateCount > 0) feedTicks++
        submittedCount += snapshot.submittedCount
        acceptedCount += snapshot.acceptedThisTick
    }

    internal fun snapshot(): MoverFeedTelemetry {
        return MoverFeedTelemetry(
            activeTicks = activeTicks,
            feedTicks = feedTicks,
            submittedCount = submittedCount,
            acceptedCount = acceptedCount,
        )
    }

    internal fun reset(): Unit {
        activeTicks = 0L
        feedTicks = 0L
        submittedCount = 0L
        acceptedCount = 0L
    }
}
