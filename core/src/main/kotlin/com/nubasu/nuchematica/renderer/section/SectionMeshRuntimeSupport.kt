package com.nubasu.nuchematica.renderer.section

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

internal class SectionWorldIdentity<T : Any> {
    private var current: T? = null

    internal fun load(level: T): Boolean {
        if (current === level) return false
        current = level
        return true
    }

    internal fun unload(level: T): Boolean {
        if (current !== level) return false
        current = null
        return true
    }

    internal fun isCurrent(level: T): Boolean {
        return current === level
    }

    internal fun clear(): Unit {
        current = null
    }
}

internal class SectionFrameLimits(
    private val maxCaptures: Int,
    private val maxSubmissions: Int,
    private val maxUploads: Int,
) {
    init {
        require(maxCaptures >= 0)
        require(maxSubmissions >= 0)
        require(maxUploads >= 0)
    }

    internal var captures: Int = 0
        private set
    internal var submissions: Int = 0
        private set
    internal var uploads: Int = 0
        private set

    internal fun beginFrame(): Unit {
        captures = 0
        submissions = 0
        uploads = 0
    }

    internal fun tryCapture(): Boolean {
        if (captures >= maxCaptures) return false
        captures++
        return true
    }

    internal fun trySubmit(): Boolean {
        if (submissions >= maxSubmissions) return false
        submissions++
        return true
    }

    internal fun tryUpload(): Boolean {
        if (uploads >= maxUploads) return false
        uploads++
        return true
    }

    internal fun hasUploadCapacity(): Boolean {
        return uploads < maxUploads
    }
}

internal data class SectionMeshMetricsSnapshot(
    internal val captureAdmissions: Long,
    internal val captureNanos: Long,
    internal val captureP95Nanos: Long?,
    internal val geometryCursorAdvances: Long,
    internal val geometryBlocks: Long,
    internal val geometryNanos: Long,
    internal val geometryP95Nanos: Long?,
    internal val sortJobs: Long,
    internal val sortNanos: Long,
    internal val sortP95Nanos: Long?,
    internal val uploads: Long,
    internal val uploadNanos: Long,
    internal val uploadP95Nanos: Long?,
    internal val timeToFirstVisibleNanos: Long?,
    internal val maxInFlight: Int,
    internal val maxFrameSubmissions: Int,
    internal val maxFrameUploads: Int,
    internal val maxLiveCpuBuffers: Int,
    internal val maxCompletionQueue: Int,
)

private class RollingTimingSamples(
    capacity: Int,
) {
    private val values: AtomicLongArray = AtomicLongArray(capacity)
    private val sequence: AtomicLong = AtomicLong()

    internal fun record(durationNanos: Long): Unit {
        val sample = durationNanos.coerceAtLeast(0L)
        val position = Math.floorMod(sequence.getAndIncrement(), values.length().toLong()).toInt()
        values.set(position, sample)
    }

    internal fun p95(): Long? {
        val count = minOf(sequence.get(), values.length().toLong()).toInt()
        if (count == 0) return null
        val snapshot = LongArray(count) { values.get(it) }
        snapshot.sort()
        val index = ((count * 95 + 99) / 100 - 1).coerceIn(0, count - 1)
        return snapshot[index]
    }
}

internal class SectionMeshMetrics(
    internal val loggingEnabled: Boolean = metricsEnvironmentEnabled(),
) {
    private val captureAdmissions: AtomicLong = AtomicLong()
    private val captureNanos: AtomicLong = AtomicLong()
    private val geometryCursorAdvances: AtomicLong = AtomicLong()
    private val geometryBlocks: AtomicLong = AtomicLong()
    private val geometryNanos: AtomicLong = AtomicLong()
    private val sortJobs: AtomicLong = AtomicLong()
    private val sortNanos: AtomicLong = AtomicLong()
    private val uploads: AtomicLong = AtomicLong()
    private val uploadNanos: AtomicLong = AtomicLong()
    private val maxInFlight: AtomicInteger = AtomicInteger()
    private val maxFrameSubmissions: AtomicInteger = AtomicInteger()
    private val maxFrameUploads: AtomicInteger = AtomicInteger()
    private val maxLiveCpuBuffers: AtomicInteger = AtomicInteger()
    private val maxCompletionQueue: AtomicInteger = AtomicInteger()
    private val contentStartNanos: AtomicLong = AtomicLong(UNSET_TIME)
    private val firstVisibleNanos: AtomicLong = AtomicLong(UNSET_TIME)
    private val captureSamples: RollingTimingSamples = RollingTimingSamples(TIMING_SAMPLE_CAPACITY)
    private val geometrySamples: RollingTimingSamples = RollingTimingSamples(TIMING_SAMPLE_CAPACITY)
    private val sortSamples: RollingTimingSamples = RollingTimingSamples(TIMING_SAMPLE_CAPACITY)
    private val uploadSamples: RollingTimingSamples = RollingTimingSamples(TIMING_SAMPLE_CAPACITY)

    internal fun beginContent(nowNanos: Long): Unit {
        contentStartNanos.set(nowNanos)
        firstVisibleNanos.set(UNSET_TIME)
    }

    internal fun recordCapture(durationNanos: Long): Unit {
        captureSamples.record(durationNanos)
        captureAdmissions.incrementAndGet()
        captureNanos.addAndGet(durationNanos.coerceAtLeast(0L))
    }

    internal fun recordGeometryAdvance(processedBlocks: Int, durationNanos: Long): Unit {
        geometrySamples.record(durationNanos)
        geometryCursorAdvances.incrementAndGet()
        geometryBlocks.addAndGet(processedBlocks.toLong())
        geometryNanos.addAndGet(durationNanos.coerceAtLeast(0L))
    }

    internal fun recordSort(durationNanos: Long): Unit {
        sortSamples.record(durationNanos)
        sortJobs.incrementAndGet()
        sortNanos.addAndGet(durationNanos.coerceAtLeast(0L))
    }

    internal fun recordUpload(durationNanos: Long): Unit {
        uploadSamples.record(durationNanos)
        uploads.incrementAndGet()
        uploadNanos.addAndGet(durationNanos.coerceAtLeast(0L))
    }

    internal fun recordFrame(
        inFlight: Int,
        submissions: Int,
        uploads: Int,
        liveCpuBuffers: Int,
        completionQueue: Int,
    ): Unit {
        maxInFlight.accumulateAndGet(inFlight, ::maxOf)
        maxFrameSubmissions.accumulateAndGet(submissions, ::maxOf)
        maxFrameUploads.accumulateAndGet(uploads, ::maxOf)
        maxLiveCpuBuffers.accumulateAndGet(liveCpuBuffers, ::maxOf)
        maxCompletionQueue.accumulateAndGet(completionQueue, ::maxOf)
    }

    internal fun markFirstVisible(nowNanos: Long): Unit {
        if (contentStartNanos.get() == UNSET_TIME) return
        firstVisibleNanos.compareAndSet(UNSET_TIME, nowNanos)
    }

    internal fun snapshot(): SectionMeshMetricsSnapshot {
        val start = contentStartNanos.get()
        val first = firstVisibleNanos.get()
        return SectionMeshMetricsSnapshot(
            captureAdmissions = captureAdmissions.get(),
            captureNanos = captureNanos.get(),
            captureP95Nanos = captureSamples.p95(),
            geometryCursorAdvances = geometryCursorAdvances.get(),
            geometryBlocks = geometryBlocks.get(),
            geometryNanos = geometryNanos.get(),
            geometryP95Nanos = geometrySamples.p95(),
            sortJobs = sortJobs.get(),
            sortNanos = sortNanos.get(),
            sortP95Nanos = sortSamples.p95(),
            uploads = uploads.get(),
            uploadNanos = uploadNanos.get(),
            uploadP95Nanos = uploadSamples.p95(),
            timeToFirstVisibleNanos = if (start == UNSET_TIME || first == UNSET_TIME) {
                null
            } else {
                (first - start).coerceAtLeast(0L)
            },
            maxInFlight = maxInFlight.get(),
            maxFrameSubmissions = maxFrameSubmissions.get(),
            maxFrameUploads = maxFrameUploads.get(),
            maxLiveCpuBuffers = maxLiveCpuBuffers.get(),
            maxCompletionQueue = maxCompletionQueue.get(),
        )
    }

    private companion object {
        private const val UNSET_TIME: Long = Long.MIN_VALUE
        private const val TIMING_SAMPLE_CAPACITY: Int = 2_048

        private fun metricsEnvironmentEnabled(): Boolean {
            val value = System.getenv("NUCHEMATICA_SECTION_METRICS") ?: return false
            return value == "1" || value.equals("true", ignoreCase = true)
        }
    }
}
