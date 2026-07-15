package com.nubasu.nuchematica.renderer.section

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class SectionMeshRuntimeSupportTest {

    @Test
    public fun worldIdentityUsesTheLevelPassedByTheLifecycleCallback(): Unit {
        val identity = SectionWorldIdentity<Any>()
        val eventLevel = Any()
        val minecraftSingletonLevel = Any()

        assertTrue(identity.load(eventLevel))
        assertTrue(identity.isCurrent(eventLevel))
        assertFalse(identity.isCurrent(minecraftSingletonLevel))
        assertFalse(identity.unload(minecraftSingletonLevel))
        assertTrue(identity.unload(eventLevel))
    }

    @Test
    public fun frameCapsAllowOnlyOneCaptureSubmissionAndUpload(): Unit {
        val limits = SectionFrameLimits(maxCaptures = 1, maxSubmissions = 1, maxUploads = 1)

        limits.beginFrame()
        assertTrue(limits.tryCapture())
        assertFalse(limits.tryCapture())
        assertTrue(limits.trySubmit())
        assertFalse(limits.trySubmit())
        assertTrue(limits.tryUpload())
        assertFalse(limits.tryUpload())
        assertEquals(1, limits.captures)
        assertEquals(1, limits.submissions)
        assertEquals(1, limits.uploads)

        limits.beginFrame()
        assertTrue(limits.tryCapture())
        assertTrue(limits.trySubmit())
        assertTrue(limits.tryUpload())
    }

    @Test
    public fun metricsKeepEveryAcceptanceCounterAndFirstVisibleLatency(): Unit {
        val metrics = SectionMeshMetrics(loggingEnabled = false)
        metrics.beginContent(100L)
        metrics.recordCapture(5L)
        metrics.recordGeometryAdvance(processedBlocks = 3, durationNanos = 7L)
        metrics.recordSort(11L)
        metrics.recordUpload(13L)
        metrics.recordFrame(
            inFlight = 1,
            submissions = 1,
            uploads = 1,
            liveCpuBuffers = 2,
            completionQueue = 1,
        )
        assertNull(metrics.snapshot().timeToFirstVisibleNanos)

        metrics.markFirstVisible(160L)

        assertEquals(
            SectionMeshMetricsSnapshot(
                captureAdmissions = 1L,
                captureNanos = 5L,
                captureP95Nanos = 5L,
                geometryCursorAdvances = 1L,
                geometryBlocks = 3L,
                geometryNanos = 7L,
                geometryP95Nanos = 7L,
                sortJobs = 1L,
                sortNanos = 11L,
                sortP95Nanos = 11L,
                uploads = 1L,
                uploadNanos = 13L,
                uploadP95Nanos = 13L,
                timeToFirstVisibleNanos = 60L,
                maxInFlight = 1,
                maxFrameSubmissions = 1,
                maxFrameUploads = 1,
                maxLiveCpuBuffers = 2,
                maxCompletionQueue = 1,
            ),
            metrics.snapshot(),
        )
    }

    @Test
    public fun timingCountersExposeAReproducibleRollingP95(): Unit {
        val metrics = SectionMeshMetrics(loggingEnabled = false)
        for (duration in 1L..100L) {
            metrics.recordCapture(duration)
            metrics.recordGeometryAdvance(processedBlocks = 1, durationNanos = duration * 2L)
            metrics.recordSort(duration * 3L)
            metrics.recordUpload(duration * 4L)
        }

        val snapshot = metrics.snapshot()
        assertEquals(95L, snapshot.captureP95Nanos)
        assertEquals(190L, snapshot.geometryP95Nanos)
        assertEquals(285L, snapshot.sortP95Nanos)
        assertEquals(380L, snapshot.uploadP95Nanos)
    }
}
