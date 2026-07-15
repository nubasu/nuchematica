package com.nubasu.nuchematica.renderer.section

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

internal enum class SectionGeometryState {
    DIRTY,
    BUILDING,
    READY,
    FAILED,
}

internal enum class SectionSortState {
    CLEAN,
    DIRTY,
    SORTING,
}

internal data class SectionJobToken(
    internal val key: SectionKey,
    internal val meshEpoch: Long,
    internal val geometryGeneration: Long,
    internal val sortRevision: Long,
)

internal data class SectionSortJob<S>(
    internal val token: SectionJobToken,
    internal val sortPayload: S,
)

internal data class SectionGpuAllocation<H>(
    internal val solid: H?,
    internal val solidBytes: Long,
    internal val translucent: H?,
    internal val translucentBytes: Long,
) {
    init {
        require(solidBytes >= 0L) { "solidBytes must not be negative" }
        require(translucentBytes >= 0L) { "translucentBytes must not be negative" }
        require(solid != null || solidBytes == 0L) { "solidBytes requires a solid handle" }
        require(translucent != null || translucentBytes == 0L) {
            "translucentBytes requires a translucent handle"
        }
    }

    internal val bytes: Long
        get() = solidBytes + translucentBytes

    internal val handleCount: Int
        get() = (if (solid == null) 0 else 1) + (if (translucent == null) 0 else 1)

    internal companion object {
        internal fun <H> empty(): SectionGpuAllocation<H> {
            return SectionGpuAllocation(null, 0L, null, 0L)
        }
    }
}

internal data class SectionGpuBudget(
    internal val softBytes: Long,
    internal val hardBytes: Long,
    internal val hardHandleCount: Int,
) {
    init {
        require(softBytes >= 0L) { "softBytes must not be negative" }
        require(hardBytes >= softBytes) { "hardBytes must be at least softBytes" }
        require(hardHandleCount >= 0) { "hardHandleCount must not be negative" }
    }
}

internal enum class SectionApplyResult {
    APPLIED,
    STALE,
    DEFERRED,
    FAILED,
}

internal data class SectionStateSnapshot<H>(
    internal val geometryGeneration: Long,
    internal val geometryState: SectionGeometryState,
    internal val sortState: SectionSortState,
    internal val geometryFailures: Int,
    internal val allocation: SectionGpuAllocation<H>,
    internal val visible: Boolean,
    internal val lastVisibleFrame: Long,
)

internal data class SectionResidentSnapshot(
    internal val bytes: Long,
    internal val handleCount: Int,
    internal val maxProjectedBytes: Long,
    internal val maxProjectedHandleCount: Int,
)

private class MutableSectionState<H, S>(
    internal val key: SectionKey,
    internal var worldAabb: AABB,
) {
    internal var geometryGeneration: Long = 1L
    internal var geometryState: SectionGeometryState = SectionGeometryState.DIRTY
    internal var sortState: SectionSortState = SectionSortState.CLEAN
    internal var geometryFailures: Int = 0
    internal var sortFailures: Int = 0
    internal var allocation: SectionGpuAllocation<H> = SectionGpuAllocation.empty()
    internal var sortPayload: S? = null
    internal var visible: Boolean = false
    internal var lastVisibleFrame: Long = Long.MIN_VALUE
}

internal class SectionMeshState<H, S>(
    private val budget: SectionGpuBudget,
    private val closeHandle: (H) -> Unit,
    private val warn: (String) -> Unit = {},
    private val error: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val sections: LinkedHashMap<SectionKey, MutableSectionState<H, S>> = LinkedHashMap()
    private var warnedVisibleHardLimit: Boolean = false
    private var closed: Boolean = false
    private var maxProjectedBytes: Long = 0L
    private var maxProjectedHandleCount: Int = 0

    internal var meshEpoch: Long = 0L
        private set

    internal var sortRevision: Long = 0L
        private set

    internal val isClosed: Boolean
        get() = closed

    internal val sectionKeys: Set<SectionKey>
        get() = sections.keys.toSet()

    internal fun replaceSections(worldAabbs: Map<SectionKey, AABB>): Unit {
        if (closed) return
        meshEpoch++
        warnedVisibleHardLimit = false

        val removed = sections.keys.filter { it !in worldAabbs }
        for (key in removed) {
            val section = sections.remove(key) ?: continue
            closeAllocation(section.allocation)
        }

        for ((key, worldAabb) in worldAabbs) {
            val existing = sections[key]
            if (existing == null) {
                sections[key] = MutableSectionState(key, worldAabb)
            } else {
                existing.worldAabb = worldAabb
                invalidateGeometry(existing)
            }
        }
    }

    internal fun updateTransform(worldAabbs: Map<SectionKey, AABB>): Unit {
        if (closed) return
        check(worldAabbs.keys == sections.keys) {
            "transform update must preserve the current section set"
        }
        meshEpoch++
        warnedVisibleHardLimit = false
        for ((key, section) in sections) {
            section.worldAabb = checkNotNull(worldAabbs[key])
            invalidateGeometry(section)
        }
    }

    internal fun clear(): Unit {
        if (closed) return
        meshEpoch++
        sortRevision++
        try {
            closeAllSections()
        } finally {
            sections.clear()
            warnedVisibleHardLimit = false
        }
    }

    internal fun close(): Unit {
        if (closed) return
        closed = true
        meshEpoch++
        sortRevision++
        try {
            closeAllSections()
        } finally {
            sections.clear()
        }
    }

    internal fun updateVisibility(visibleKeys: Set<SectionKey>, frame: Long): Unit {
        if (closed) return
        for ((key, section) in sections) {
            section.visible = key in visibleKeys
            if (section.visible) {
                section.lastVisibleFrame = frame
            }
        }
    }

    internal fun markCameraThresholdCrossed(): Unit {
        if (closed) return
        sortRevision++
        for (section in sections.values) {
            if (
                section.geometryState == SectionGeometryState.READY &&
                section.allocation.translucent != null &&
                section.sortPayload != null
            ) {
                section.sortState = SectionSortState.DIRTY
                section.sortFailures = 0
            }
        }
    }

    internal fun nextGeometryCandidate(camera: Vec3): SectionKey? {
        if (closed) return null
        return sections.values
            .asSequence()
            .filter { it.geometryState == SectionGeometryState.DIRTY }
            .minWithOrNull(sectionPriority(camera))
            ?.key
    }

    internal fun nextSortCandidate(camera: Vec3): SectionKey? {
        if (closed) return null
        return sections.values
            .asSequence()
            .filter {
                it.geometryState == SectionGeometryState.READY &&
                    it.sortState == SectionSortState.DIRTY &&
                    it.allocation.translucent != null &&
                    it.sortPayload != null
            }
            .minWithOrNull(sectionPriority(camera))
            ?.key
    }

    internal fun beginGeometry(key: SectionKey): SectionJobToken? {
        if (closed) return null
        val section = sections[key] ?: return null
        if (section.geometryState != SectionGeometryState.DIRTY) return null
        section.geometryState = SectionGeometryState.BUILDING
        return token(section)
    }

    internal fun beginSort(key: SectionKey): SectionSortJob<S>? {
        if (closed) return null
        val section = sections[key] ?: return null
        val payload = section.sortPayload ?: return null
        if (
            section.geometryState != SectionGeometryState.READY ||
            section.sortState != SectionSortState.DIRTY ||
            section.allocation.translucent == null
        ) {
            return null
        }
        section.sortState = SectionSortState.SORTING
        return SectionSortJob(token(section), payload)
    }

    internal fun isGeometryCurrent(token: SectionJobToken): Boolean {
        if (closed || token.meshEpoch != meshEpoch) return false
        val section = sections[token.key] ?: return false
        return section.geometryGeneration == token.geometryGeneration &&
            section.geometryState == SectionGeometryState.BUILDING
    }

    internal fun isSortCurrent(token: SectionJobToken): Boolean {
        if (!isGenerationCurrent(token)) return false
        val section = sections[token.key] ?: return false
        return token.sortRevision == sortRevision && section.sortState == SectionSortState.SORTING
    }

    internal fun cancelGeometry(token: SectionJobToken): Unit {
        if (!isGeometryCurrent(token)) return
        sections[token.key]?.geometryState = SectionGeometryState.DIRTY
    }

    internal fun cancelSort(token: SectionJobToken): Unit {
        if (!isSortCurrent(token)) return
        sections[token.key]?.sortState = SectionSortState.DIRTY
    }

    internal fun applyGeometry(
        token: SectionJobToken,
        plannedBytes: Long,
        plannedHandleCount: Int,
        sortPayload: S?,
        upload: () -> SectionGpuAllocation<H>,
    ): SectionApplyResult {
        require(plannedBytes >= 0L) { "plannedBytes must not be negative" }
        require(plannedHandleCount >= 0) { "plannedHandleCount must not be negative" }
        if (!isGeometryCurrent(token)) return SectionApplyResult.STALE

        if (!makeUploadRoom(token.key, plannedBytes, plannedHandleCount)) {
            warnForUnshownVisibleSection()
            return SectionApplyResult.DEFERRED
        }

        val allocation = try {
            upload()
        } catch (throwable: Throwable) {
            failGeometry(token, throwable)
            return SectionApplyResult.FAILED
        }
        check(allocation.bytes <= plannedBytes) {
            "uploaded bytes ${allocation.bytes} exceeded planned bytes $plannedBytes"
        }
        check(allocation.handleCount <= plannedHandleCount) {
            "uploaded handles ${allocation.handleCount} exceeded planned count $plannedHandleCount"
        }

        if (!isGeometryCurrent(token)) {
            closeAllocation(allocation)
            return SectionApplyResult.STALE
        }

        val section = checkNotNull(sections[token.key])
        val oldAllocation = section.allocation
        section.allocation = allocation
        section.sortPayload = if (allocation.translucent == null) null else sortPayload
        section.geometryState = SectionGeometryState.READY
        section.geometryFailures = 0
        section.sortFailures = 0
        section.sortState = if (
            section.sortPayload != null && token.sortRevision != sortRevision
        ) {
            SectionSortState.DIRTY
        } else {
            SectionSortState.CLEAN
        }
        closeAllocation(oldAllocation)
        return SectionApplyResult.APPLIED
    }

    internal fun failGeometry(token: SectionJobToken, throwable: Throwable): Unit {
        if (!isGeometryCurrent(token)) return
        val section = checkNotNull(sections[token.key])
        section.geometryFailures++
        section.geometryState = if (section.geometryFailures <= MAX_AUTOMATIC_RETRIES) {
            SectionGeometryState.DIRTY
        } else {
            error("section geometry failed twice: ${section.key}", throwable)
            SectionGeometryState.FAILED
        }
    }

    internal fun applySort(
        token: SectionJobToken,
        upload: (H) -> Unit,
    ): SectionApplyResult {
        if (!isSortCurrent(token)) return SectionApplyResult.STALE
        val section = checkNotNull(sections[token.key])
        val translucent = section.allocation.translucent ?: return SectionApplyResult.STALE
        return try {
            upload(translucent)
            if (!isSortCurrent(token)) {
                SectionApplyResult.STALE
            } else {
                section.sortState = SectionSortState.CLEAN
                section.sortFailures = 0
                SectionApplyResult.APPLIED
            }
        } catch (throwable: Throwable) {
            handleSortUploadFailure(section, throwable)
            SectionApplyResult.FAILED
        }
    }

    internal fun failSort(token: SectionJobToken, throwable: Throwable): Unit {
        if (!isSortCurrent(token)) return
        val section = checkNotNull(sections[token.key])
        section.sortFailures++
        if (section.sortFailures <= MAX_AUTOMATIC_RETRIES) {
            section.sortState = SectionSortState.DIRTY
        } else {
            section.sortState = SectionSortState.CLEAN
            error("section translucent sort failed twice: ${section.key}", throwable)
        }
    }

    internal fun worldAabb(key: SectionKey): AABB? {
        return sections[key]?.worldAabb
    }

    internal fun allocation(key: SectionKey): SectionGpuAllocation<H>? {
        return sections[key]?.allocation
    }

    internal fun snapshot(key: SectionKey): SectionStateSnapshot<H>? {
        val section = sections[key] ?: return null
        return SectionStateSnapshot(
            geometryGeneration = section.geometryGeneration,
            geometryState = section.geometryState,
            sortState = section.sortState,
            geometryFailures = section.geometryFailures,
            allocation = section.allocation,
            visible = section.visible,
            lastVisibleFrame = section.lastVisibleFrame,
        )
    }

    internal fun residentSnapshot(): SectionResidentSnapshot {
        return SectionResidentSnapshot(
            bytes = residentBytes(),
            handleCount = residentHandleCount(),
            maxProjectedBytes = maxProjectedBytes,
            maxProjectedHandleCount = maxProjectedHandleCount,
        )
    }

    private fun invalidateGeometry(section: MutableSectionState<H, S>): Unit {
        section.geometryGeneration++
        section.geometryState = SectionGeometryState.DIRTY
        section.sortState = SectionSortState.CLEAN
        section.geometryFailures = 0
        section.sortFailures = 0
    }

    private fun token(section: MutableSectionState<H, S>): SectionJobToken {
        return SectionJobToken(
            key = section.key,
            meshEpoch = meshEpoch,
            geometryGeneration = section.geometryGeneration,
            sortRevision = sortRevision,
        )
    }

    private fun isGenerationCurrent(token: SectionJobToken): Boolean {
        if (closed || token.meshEpoch != meshEpoch) return false
        return sections[token.key]?.geometryGeneration == token.geometryGeneration
    }

    private fun makeUploadRoom(
        target: SectionKey,
        incomingBytes: Long,
        incomingHandleCount: Int,
    ): Boolean {
        while (
            residentBytes() + incomingBytes > budget.softBytes ||
            residentHandleCount() + incomingHandleCount > budget.hardHandleCount
        ) {
            if (!evictLeastRecentlyVisible(target)) break
        }

        val projectedBytes = residentBytes() + incomingBytes
        val projectedHandleCount = residentHandleCount() + incomingHandleCount
        val permitted = projectedBytes <= budget.hardBytes &&
            projectedHandleCount <= budget.hardHandleCount
        if (permitted) {
            maxProjectedBytes = maxOf(maxProjectedBytes, projectedBytes)
            maxProjectedHandleCount = maxOf(maxProjectedHandleCount, projectedHandleCount)
        }
        return permitted
    }

    private fun evictLeastRecentlyVisible(excludedKey: SectionKey): Boolean {
        val candidate = sections.values
            .asSequence()
            .filter {
                it.key != excludedKey &&
                    !it.visible &&
                    it.allocation.handleCount > 0 &&
                    it.geometryState != SectionGeometryState.BUILDING
            }
            .minWithOrNull(
                compareBy<MutableSectionState<H, S>>(
                    { it.lastVisibleFrame },
                    { it.key.x },
                    { it.key.y },
                    { it.key.z },
                ),
            ) ?: return false

        val allocation = candidate.allocation
        candidate.allocation = SectionGpuAllocation.empty()
        candidate.sortPayload = null
        invalidateGeometry(candidate)
        closeAllocation(allocation)
        return true
    }

    private fun warnForUnshownVisibleSection(): Unit {
        if (warnedVisibleHardLimit) return
        if (sections.values.none { it.visible && it.allocation.handleCount == 0 }) return
        warnedVisibleHardLimit = true
        warn(
            "section upload deferred at GPU hard limit: " +
                "bytes=${residentBytes()}/${budget.hardBytes}, " +
                "handles=${residentHandleCount()}/${budget.hardHandleCount}",
        )
    }

    private fun handleSortUploadFailure(
        section: MutableSectionState<H, S>,
        throwable: Throwable,
    ): Unit {
        val translucent = section.allocation.translucent
        section.allocation = SectionGpuAllocation(
            solid = section.allocation.solid,
            solidBytes = section.allocation.solidBytes,
            translucent = null,
            translucentBytes = 0L,
        )
        section.sortPayload = null
        invalidateGeometry(section)
        if (translucent != null) {
            closeHandle(translucent)
        }
        error("section translucent upload failed: ${section.key}", throwable)
    }

    private fun residentBytes(): Long {
        return sections.values.sumOf { it.allocation.bytes }
    }

    private fun residentHandleCount(): Int {
        return sections.values.sumOf { it.allocation.handleCount }
    }

    private fun closeAllSections(): Unit {
        var failure: Throwable? = null
        for (section in sections.values) {
            val allocation = section.allocation
            section.allocation = SectionGpuAllocation.empty()
            section.sortPayload = null
            try {
                closeAllocation(allocation)
            } catch (throwable: Throwable) {
                if (failure == null) {
                    failure = throwable
                } else {
                    failure.addSuppressed(throwable)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun closeAllocation(allocation: SectionGpuAllocation<H>): Unit {
        try {
            allocation.solid?.let(closeHandle)
        } finally {
            allocation.translucent?.let(closeHandle)
        }
    }

    private fun sectionPriority(camera: Vec3): Comparator<MutableSectionState<H, S>> {
        return compareBy<MutableSectionState<H, S>>(
            { !it.visible },
            { distanceToSqr(camera, it.worldAabb) },
            { it.key.x },
            { it.key.y },
            { it.key.z },
        )
    }

    private fun distanceToSqr(point: Vec3, box: AABB): Double {
        val dx = axisDistance(point.x, box.minX, box.maxX)
        val dy = axisDistance(point.y, box.minY, box.maxY)
        val dz = axisDistance(point.z, box.minZ, box.maxZ)
        return dx * dx + dy * dy + dz * dz
    }

    private fun axisDistance(value: Double, minimum: Double, maximum: Double): Double {
        return when {
            value < minimum -> minimum - value
            value > maximum -> value - maximum
            else -> 0.0
        }
    }

    private companion object {
        private const val MAX_AUTOMATIC_RETRIES: Int = 1
    }
}
