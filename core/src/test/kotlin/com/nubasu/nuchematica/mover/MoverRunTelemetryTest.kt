package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.FeedSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class MoverRunTelemetryTest {
    @Test
    public fun aggregatesOverallAndCruiseHoldFeedUtilization(): Unit {
        val telemetry = MoverRunTelemetry()

        telemetry.record(
            MoverState.TAKEOFF,
            feed(candidateCount = 2, submittedCount = 1),
        )
        telemetry.record(
            MoverState.CRUISE,
            feed(candidateCount = 0, acceptedThisTick = 1),
        )
        telemetry.record(
            MoverState.HOLD,
            feed(candidateCount = 1, submittedCount = 1),
        )
        telemetry.record(MoverState.HOLD, snapshot = null)

        assertEquals(
            MoverRunTelemetrySnapshot(
                totalActiveTicks = 4,
                feedTicks = 2,
                submittedCount = 2,
                acceptedCount = 1,
                cruise = MoverFeedTelemetry(
                    activeTicks = 1,
                    feedTicks = 0,
                    submittedCount = 0,
                    acceptedCount = 1,
                ),
                hold = MoverFeedTelemetry(
                    activeTicks = 2,
                    feedTicks = 1,
                    submittedCount = 1,
                    acceptedCount = 0,
                ),
            ),
            telemetry.snapshot(),
        )

        telemetry.reset()
        assertEquals(0L, telemetry.snapshot().totalActiveTicks)
    }

    private fun feed(
        candidateCount: Int,
        submittedCount: Int = 0,
        acceptedThisTick: Int = 0,
    ): FeedSnapshot {
        return FeedSnapshot(
            tick = 1L,
            queueRevision = 1L,
            candidateCount = candidateCount,
            submittedCount = submittedCount,
            rateLimitedRemainder = 0,
            inFlightCount = submittedCount,
            acceptedThisTick = acceptedThisTick,
        )
    }
}
