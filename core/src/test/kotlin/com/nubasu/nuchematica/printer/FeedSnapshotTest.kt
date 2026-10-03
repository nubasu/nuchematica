package com.nubasu.nuchematica.printer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

public class FeedSnapshotTest {
    @Test
    public fun storeRejectsSessionAndQueueRevisionMismatches(): Unit {
        val level = Any()
        val content = Any()
        val transformRevision = 3L
        val sessionKey = PrinterSessionKey(level, content, transformRevision)
        val snapshot = FeedSnapshot(
            tick = 10L,
            queueRevision = 20L,
            candidateCount = 2,
            submittedCount = 1,
            rateLimitedRemainder = 1,
            inFlightCount = 1,
            acceptedThisTick = 0,
        )
        val store = FeedSnapshotStore()
        store.update(sessionKey, snapshot)

        assertEquals(snapshot, store.fresh(sessionKey, queueRevision = 20L))
        assertNull(store.fresh(sessionKey, queueRevision = 21L))
        assertNull(
            store.fresh(
                PrinterSessionKey(Any(), content, transformRevision),
                queueRevision = 20L,
            ),
        )
        assertNull(
            store.fresh(
                PrinterSessionKey(level, Any(), transformRevision),
                queueRevision = 20L,
            ),
        )
        assertNull(
            store.fresh(
                PrinterSessionKey(level, content, transformRevision + 1L),
                queueRevision = 20L,
            ),
        )
    }
}
