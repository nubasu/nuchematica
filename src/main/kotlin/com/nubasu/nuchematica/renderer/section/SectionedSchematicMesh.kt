package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.logging.LogUtils
import com.mojang.math.Matrix4f
import com.mojang.math.Vector3f.YP
import com.nubasu.nuchematica.renderer.GhostRenderDistance
import com.nubasu.nuchematica.renderer.LevelRenderContext
import com.nubasu.nuchematica.renderer.NuchematicaRenderTypes
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

private data class ActiveSectionGeometry(
    internal val token: SectionJobToken,
    internal val builder: SectionMeshBuilder,
    internal val buffers: SectionGeometryBufferLease,
)

private data class PendingSectionGeometry(
    internal val token: SectionJobToken,
    internal val geometry: SectionGeometry?,
    internal val buffers: SectionGeometryBufferLease,
)

private data class SectionSortWorkerInput(
    internal val token: SectionJobToken,
    internal val sortState: BufferBuilder.SortState,
    internal val sortOrigin: Vec3,
)

private data class PendingSectionSort(
    internal val token: SectionJobToken,
    internal val completion: WorkerCompletion<BufferBuilder>,
)

internal data class SectionMeshRuntimeSnapshot(
    internal val geometryJobs: Int,
    internal val sortJobs: Int,
    internal val liveCpuBuffers: Int,
    internal val completionQueue: Int,
)

internal interface SectionGpuHandle {
    fun close(): Unit
}

internal interface SectionGpuBackend {
    fun upload(builder: BufferBuilder): SectionGpuHandle

    fun uploadSort(handle: SectionGpuHandle, builder: BufferBuilder): Unit

    fun drawLayer(
        handles: List<SectionGpuHandle>,
        renderType: RenderType,
        poseStack: PoseStack,
        projection: Matrix4f,
        opacity: Float,
    ): Unit
}

private class VertexBufferSectionHandle(
    internal val buffer: VertexBuffer,
) : SectionGpuHandle {
    override fun close(): Unit {
        buffer.close()
    }
}

private object VertexBufferSectionGpuBackend : SectionGpuBackend {
    override fun upload(builder: BufferBuilder): SectionGpuHandle {
        val buffer = VertexBuffer()
        var uploaded = false
        try {
            try {
                buffer.bind()
                buffer.upload(builder)
            } finally {
                VertexBuffer.unbind()
            }
            uploaded = true
            return VertexBufferSectionHandle(buffer)
        } finally {
            if (!uploaded) {
                buffer.close()
            }
        }
    }

    override fun uploadSort(handle: SectionGpuHandle, builder: BufferBuilder): Unit {
        val buffer = (handle as VertexBufferSectionHandle).buffer
        try {
            buffer.bind()
            buffer.upload(builder)
        } finally {
            VertexBuffer.unbind()
        }
    }

    override fun drawLayer(
        handles: List<SectionGpuHandle>,
        renderType: RenderType,
        poseStack: PoseStack,
        projection: Matrix4f,
        opacity: Float,
    ): Unit {
        if (handles.isEmpty()) return
        try {
            renderType.setupRenderState()
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, opacity)
            for (handle in handles) {
                val buffer = (handle as VertexBufferSectionHandle).buffer
                try {
                    buffer.bind()
                    buffer.drawWithShader(poseStack.last().pose(), projection, RenderSystem.getShader())
                } finally {
                    VertexBuffer.unbind()
                }
            }
        } finally {
            renderType.clearRenderState()
        }
    }
}

internal class SectionedSchematicMesh(
    private val nanoTime: () -> Long = System::nanoTime,
    private val metrics: SectionMeshMetrics = SectionMeshMetrics(),
    private val gpuBackend: SectionGpuBackend = VertexBufferSectionGpuBackend,
    private val bufferPool: SectionMeshBufferPool = SectionMeshBufferPool(),
    private val renderDistanceBlocks: () -> Int = { GhostRenderDistance.currentBlocks() },
) : AutoCloseable {
    private val logger = LogUtils.getLogger()
    private val threadGuard: MainThreadGuard = MainThreadGuard.captureCurrentThread()
    private val requestFactory: MainThreadSectionBuildRequestFactory =
        MainThreadSectionBuildRequestFactory(threadGuard)
    private val worldIdentity: SectionWorldIdentity<ClientLevel> = SectionWorldIdentity()
    private val state: SectionMeshState<SectionGpuHandle, BufferBuilder.SortState> = SectionMeshState(
        budget = GPU_BUDGET,
        closeHandle = SectionGpuHandle::close,
        warn = logger::warn,
        error = logger::error,
    )
    private val frameLimits: SectionFrameLimits = SectionFrameLimits(
        maxCaptures = MAX_CAPTURES_PER_FRAME,
        maxSubmissions = MAX_SUBMISSIONS_PER_FRAME,
        maxUploads = MAX_UPLOADS_PER_FRAME,
    )
    private val sortWorker: SingleSlotWorker<SectionSortWorkerInput, BufferBuilder> = SingleSlotWorker(
        threadName = "nuchematica-section-sorter",
        operation = ::runSortJob,
        discard = bufferPool::discardSort,
    )
    private val suppressedPositions: HashSet<BlockPos> = HashSet()

    private var level: ClientLevel? = null
    private var content: SchematicContentSnapshot? = null
    private var meshingService: SectionMeshingService? = null
    private var transform: RenderTransform? = null
    private var resourceEpoch: Long? = null
    private var activeGeometry: ActiveSectionGeometry? = null
    private var pendingGeometry: PendingSectionGeometry? = null
    private var activeSortToken: SectionJobToken? = null
    private var pendingSort: PendingSectionSort? = null
    private var lastSortCamera: Vec3? = null
    private var frame: Long = 0L

    internal fun worldLoaded(eventLevel: ClientLevel): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed || worldIdentity.isCurrent(eventLevel)) return
        try {
            releaseContent()
        } finally {
            worldIdentity.load(eventLevel)
            level = eventLevel
        }
    }

    internal fun worldUnloaded(eventLevel: ClientLevel): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed || !worldIdentity.unload(eventLevel)) return
        try {
            releaseContent()
        } finally {
            level = null
        }
    }

    internal fun replaceContent(
        level: ClientLevel,
        content: SchematicContentSnapshot,
        meshingService: SectionMeshingService,
        transform: RenderTransform,
        suppressed: Set<BlockPos> = emptySet(),
    ): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed) return

        val worldChanged = !worldIdentity.isCurrent(level)
        val resourceChanged = resourceEpoch != null && resourceEpoch != meshingService.resourceEpoch
        suppressedPositions.clear()
        cancelCpuWork()
        if (worldChanged || resourceChanged) {
            state.clear()
        }
        worldIdentity.load(level)
        this.level = level
        this.content = content
        this.meshingService = meshingService
        this.transform = transform
        resourceEpoch = meshingService.resourceEpoch
        if (!resourceChanged) {
            suppressedPositions.addAll(suppressed)
        }
        lastSortCamera = null
        state.replaceSections(worldAabbs(content, transform))
        metrics.beginContent(nanoTime())
    }

    internal fun updateTransform(
        level: ClientLevel,
        transform: RenderTransform,
    ): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed) return
        suppressedPositions.clear()
        val currentContent = content ?: return
        cancelCpuWork()

        if (!worldIdentity.isCurrent(level)) {
            state.clear()
            worldIdentity.load(level)
            state.replaceSections(worldAabbs(currentContent, transform))
        } else {
            state.updateTransform(worldAabbs(currentContent, transform))
        }
        this.level = level
        this.transform = transform
        lastSortCamera = null
        metrics.beginContent(nanoTime())
    }

    internal fun clearContent(): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed) return
        releaseContent()
    }

    internal fun setBlockSuppressed(localPos: BlockPos, suppressed: Boolean): Boolean {
        threadGuard.checkOwnerThread()
        if (state.isClosed) return false
        val changed = if (suppressed) {
            suppressedPositions.add(localPos)
        } else {
            suppressedPositions.remove(localPos)
        }
        if (!changed) return false
        state.markSectionDirty(SectionKey.of(localPos))
        return true
    }

    internal fun render(
        transform: RenderTransform,
        context: LevelRenderContext,
        opacity: Float,
    ): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed) return
        val currentTransform = this.transform ?: return
        check(transform == currentTransform) {
            "render transform must be applied through updateTransform before drawing"
        }
        if (content == null || level == null || meshingService == null) return

        val frameStart = nanoTime()
        frame++
        frameLimits.beginFrame()
        val cameraWorld = context.camera.position
        val cameraLocal = currentTransform.worldPointToLocal(cameraWorld)
        updateCameraRevision(cameraLocal)
        val radius = renderDistanceBlocks()
        val (activeKeys, visibleKeys) = activeAndVisibleSections(context, cameraWorld, radius)
        state.updateVisibility(visibleKeys, activeKeys, frame)

        collectSortCompletion()
        applyPendingCompletions()
        admitWork(cameraWorld, cameraLocal)
        advanceGeometry(frameStart)
        applyPendingCompletions()

        if (opacity > 0.0f) {
            drawVisibleSections(
                visibleKeys = visibleKeys,
                cameraWorld = cameraWorld,
                poseStack = context.poseStack,
                projection = context.projectionMatrix,
                transform = currentTransform,
                opacity = opacity,
            )
        }
        recordFrameMetrics()
        logMetricsIfEnabled()
    }

    internal fun metricsSnapshot(): SectionMeshMetricsSnapshot {
        return metrics.snapshot()
    }

    internal fun residentSnapshot(): SectionResidentSnapshot {
        return state.residentSnapshot()
    }

    internal fun runtimeSnapshot(): SectionMeshRuntimeSnapshot {
        threadGuard.checkOwnerThread()
        return currentRuntimeSnapshot()
    }

    override fun close(): Unit {
        threadGuard.checkOwnerThread()
        if (state.isClosed) return
        try {
            cancelCpuWork()
        } finally {
            try {
                sortWorker.close()
            } finally {
                try {
                    state.close()
                } finally {
                    worldIdentity.clear()
                    level = null
                    content = null
                    meshingService = null
                    transform = null
                    resourceEpoch = null
                    suppressedPositions.clear()
                    lastSortCamera = null
                }
            }
        }
    }

    private fun releaseContent(): Unit {
        try {
            cancelCpuWork()
        } finally {
            try {
                state.clear()
            } finally {
                content = null
                meshingService = null
                transform = null
                resourceEpoch = null
                suppressedPositions.clear()
                lastSortCamera = null
            }
        }
    }

    private fun cancelCpuWork(): Unit {
        val activeGeometryToCancel = activeGeometry
        activeGeometry = null
        val pendingGeometryToCancel = pendingGeometry
        pendingGeometry = null
        val pendingSortToCancel = pendingSort
        pendingSort = null
        val activeSortToCancel = activeSortToken
        activeSortToken = null
        try {
            try {
                if (activeGeometryToCancel != null) {
                    try {
                        activeGeometryToCancel.builder.discard()
                    } finally {
                        bufferPool.releaseGeometry(activeGeometryToCancel.buffers)
                    }
                }
            } finally {
                activeGeometryToCancel?.token?.let(state::cancelGeometry)
            }
        } finally {
            try {
                try {
                    if (pendingGeometryToCancel != null) {
                        try {
                            discardGeometry(pendingGeometryToCancel.geometry)
                        } finally {
                            bufferPool.releaseGeometry(pendingGeometryToCancel.buffers)
                        }
                    }
                } finally {
                    pendingGeometryToCancel?.token?.let(state::cancelGeometry)
                }
            } finally {
                try {
                    pendingSortToCancel?.let(::discardSortCompletion)
                } finally {
                    try {
                        activeSortToCancel?.let(state::cancelSort)
                    } finally {
                        sortWorker.cancelCurrent()
                    }
                }
            }
        }
    }

    private fun updateCameraRevision(cameraLocal: Vec3): Unit {
        val previous = lastSortCamera
        if (previous == null) {
            lastSortCamera = cameraLocal
        } else if (cameraLocal.distanceToSqr(previous) > RESORT_DISTANCE_SQ) {
            lastSortCamera = cameraLocal
            state.markCameraThresholdCrossed()
        }
    }

    /** The frustum is only consulted for sections already within [radius] of [cameraWorld]. */
    private fun activeAndVisibleSections(
        context: LevelRenderContext,
        cameraWorld: Vec3,
        radius: Int,
    ): Pair<Set<SectionKey>, Set<SectionKey>> {
        val active = LinkedHashSet<SectionKey>()
        val visible = LinkedHashSet<SectionKey>()
        for (key in state.sectionKeys) {
            val worldAabb = state.worldAabb(key) ?: continue
            if (!GhostRenderDistance.withinHorizontal(cameraWorld, worldAabb, radius)) continue
            active += key
            if (context.frustum.isVisible(worldAabb)) {
                visible += key
            }
        }
        return active to visible
    }

    private fun collectSortCompletion(): Unit {
        if (pendingSort != null) return
        val completion = sortWorker.pollCompletion() ?: return
        val token = activeSortToken
        activeSortToken = null
        if (token == null) {
            if (completion is WorkerCompletion.Success) {
                bufferPool.discardSort(completion.value)
            }
            return
        }
        pendingSort = PendingSectionSort(token, completion)
    }

    private fun applyPendingCompletions(): Unit {
        val geometry = pendingGeometry
        if (geometry != null) {
            if (applyGeometryCompletion(geometry)) {
                pendingGeometry = null
            }
        }
        val sort = pendingSort
        if (sort != null && pendingGeometry == null) {
            if (applySortCompletion(sort)) {
                pendingSort = null
            }
        }
    }

    private fun applyGeometryCompletion(pending: PendingSectionGeometry): Boolean {
        val geometry = pending.geometry
        val planned = plannedAllocation(geometry)
        if (planned.handleCount > 0 && !frameLimits.hasUploadCapacity()) return false

        var uploaded = false
        var result: SectionApplyResult? = null
        try {
            result = state.applyGeometry(
                token = pending.token,
                plannedBytes = planned.bytes,
                plannedHandleCount = planned.handleCount,
                sortPayload = geometry?.translucentSortState,
            ) {
                if (planned.handleCount == 0) {
                    SectionGpuAllocation.empty()
                } else {
                    check(frameLimits.tryUpload()) { "section upload frame cap exhausted" }
                    val started = nanoTime()
                    try {
                        uploadGeometry(checkNotNull(geometry), planned)
                    } finally {
                        metrics.recordUpload(nanoTime() - started)
                    }
                }.also { uploaded = true }
            }

            return when (result) {
                SectionApplyResult.DEFERRED -> false
                SectionApplyResult.APPLIED -> {
                    if (
                        state.snapshot(pending.token.key)
                            ?.let { it.visible && it.allocation.handleCount > 0 } == true
                    ) {
                        metrics.markFirstVisible(nanoTime())
                    }
                    true
                }
                SectionApplyResult.STALE,
                SectionApplyResult.FAILED,
                -> true
                null -> error("geometry apply did not produce a result")
            }
        } finally {
            if (result != SectionApplyResult.DEFERRED) {
                try {
                    if (!uploaded) {
                        discardGeometry(geometry)
                    }
                } finally {
                    bufferPool.releaseGeometry(pending.buffers)
                }
            }
        }
    }

    private fun applySortCompletion(pending: PendingSectionSort): Boolean {
        return when (val completion = pending.completion) {
            is WorkerCompletion.Failure -> {
                state.failSort(pending.token, completion.throwable)
                true
            }
            is WorkerCompletion.Success -> {
                if (!state.isSortCurrent(pending.token)) {
                    bufferPool.discardSort(completion.value)
                    return true
                }
                if (!frameLimits.hasUploadCapacity()) return false
                var uploaded = false
                try {
                    val result = state.applySort(pending.token) { translucent ->
                        check(frameLimits.tryUpload()) { "section upload frame cap exhausted" }
                        val started = nanoTime()
                        try {
                            gpuBackend.uploadSort(translucent, completion.value)
                            uploaded = true
                        } finally {
                            metrics.recordUpload(nanoTime() - started)
                        }
                    }
                    check(result != SectionApplyResult.DEFERRED) {
                        "in-place sort upload must not be GPU-budget deferred"
                    }
                    true
                } finally {
                    if (uploaded) {
                        bufferPool.releaseSort(completion.value)
                    } else {
                        bufferPool.discardSort(completion.value)
                    }
                }
            }
        }
    }

    private fun admitWork(cameraWorld: Vec3, cameraLocal: Vec3): Unit {
        if (activeGeometry != null || pendingGeometry != null || pendingSort != null) return
        val geometryKey = state.nextGeometryCandidate(cameraWorld)
        val sortKey = state.nextSortCandidate(cameraWorld)
        val chooseGeometry = when {
            geometryKey == null -> false
            sortKey == null -> true
            state.snapshot(geometryKey)?.visible == true -> true
            state.snapshot(sortKey)?.visible == true -> false
            else -> true
        }

        if (chooseGeometry) {
            if (frameLimits.tryCapture()) {
                startGeometry(checkNotNull(geometryKey), cameraLocal)
            }
        } else if (sortKey != null && sortWorker.inFlightCount == 0 && frameLimits.trySubmit()) {
            startSort(sortKey, cameraLocal)
        }
    }

    private fun startGeometry(key: SectionKey, cameraLocal: Vec3): Unit {
        val currentLevel = level ?: return
        val currentContent = content ?: return
        val currentTransform = transform ?: return
        val currentService = meshingService ?: return
        val started = nanoTime()
        val token = state.beginGeometry(key) ?: return
        var buffers: SectionGeometryBufferLease? = null
        try {
            check(currentService.resourceEpoch == resourceEpoch) {
                "section meshing service crossed a resource epoch"
            }
            val request = requestFactory.create(
                level = currentLevel,
                key = key,
                content = currentContent,
                suppressed = suppressedPositions
                    .filterTo(HashSet()) { SectionKey.of(it) == key },
                transform = currentTransform,
                sortOrigin = cameraLocal,
                meshEpoch = token.meshEpoch,
                sectionGeometryGeneration = token.geometryGeneration,
                cameraSortRevision = token.sortRevision,
            )
            buffers = bufferPool.acquireGeometry()
            activeGeometry = ActiveSectionGeometry(
                token = token,
                builder = SectionMeshBuilder(request, currentService, buffers),
                buffers = buffers,
            )
        } catch (throwable: Throwable) {
            try {
                buffers?.let(bufferPool::releaseGeometry)
            } catch (releaseFailure: Throwable) {
                throwable.addSuppressed(releaseFailure)
            }
            state.failGeometry(token, throwable)
        } finally {
            metrics.recordCapture(nanoTime() - started)
        }
    }

    private fun startSort(key: SectionKey, cameraLocal: Vec3): Unit {
        val job = state.beginSort(key) ?: return
        val submitted = try {
            sortWorker.submit(
                SectionSortWorkerInput(
                    token = job.token,
                    sortState = job.sortPayload,
                    sortOrigin = cameraLocal,
                ),
            )
        } catch (throwable: Throwable) {
            state.failSort(job.token, throwable)
            return
        }
        if (submitted) {
            activeSortToken = job.token
        } else {
            state.cancelSort(job.token)
        }
    }

    private fun advanceGeometry(frameStart: Long): Unit {
        val active = activeGeometry ?: return
        if (!state.isGeometryCurrent(active.token)) {
            try {
                active.builder.discard()
            } finally {
                bufferPool.releaseGeometry(active.buffers)
                activeGeometry = null
            }
            return
        }

        val deadline = saturatedAdd(frameStart, MAIN_THREAD_SECTION_BUDGET_NANOS)
        var cancelled = false
        val started = nanoTime()
        val result = try {
            active.builder.build(deadline) {
                if (Thread.currentThread().isInterrupted || !state.isGeometryCurrent(active.token)) {
                    cancelled = true
                    Long.MAX_VALUE
                } else {
                    nanoTime()
                }
            }
        } catch (throwable: Throwable) {
            metrics.recordGeometryAdvance(processedBlocks = 0, durationNanos = nanoTime() - started)
            try {
                active.builder.discard()
            } finally {
                try {
                    bufferPool.releaseGeometry(active.buffers)
                } finally {
                    state.failGeometry(active.token, throwable)
                    activeGeometry = null
                }
            }
            return
        }
        metrics.recordGeometryAdvance(
            processedBlocks = result.cursor.processedBlocks,
            durationNanos = nanoTime() - started,
        )
        if (cancelled || !state.isGeometryCurrent(active.token)) {
            try {
                active.builder.discard()
            } finally {
                try {
                    bufferPool.releaseGeometry(active.buffers)
                } finally {
                    state.cancelGeometry(active.token)
                    activeGeometry = null
                }
            }
        } else if (result.cursor.complete) {
            activeGeometry = null
            pendingGeometry = PendingSectionGeometry(active.token, result.geometry, active.buffers)
        }
    }

    private fun runSortJob(input: SectionSortWorkerInput): BufferBuilder {
        val started = nanoTime()
        var builder: BufferBuilder? = null
        return try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val acquiredBuilder = bufferPool.acquireSort()
            builder = acquiredBuilder
            SectionMeshBuilder.resort(input.sortState, input.sortOrigin, acquiredBuilder).also {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException()
                }
            }
        } catch (throwable: Throwable) {
            try {
                builder?.let(bufferPool::discardSort)
            } catch (releaseFailure: Throwable) {
                throwable.addSuppressed(releaseFailure)
            }
            throw throwable
        } finally {
            metrics.recordSort(nanoTime() - started)
        }
    }

    private fun uploadGeometry(
        geometry: SectionGeometry,
        planned: SectionGpuAllocation<Unit>,
    ): SectionGpuAllocation<SectionGpuHandle> {
        var solid: SectionGpuHandle? = null
        var translucent: SectionGpuHandle? = null
        try {
            solid = geometry.solid?.let(gpuBackend::upload)
            translucent = geometry.translucent?.let(gpuBackend::upload)
            return SectionGpuAllocation(
                solid = solid,
                solidBytes = if (solid == null) 0L else planned.solidBytes,
                translucent = translucent,
                translucentBytes = if (translucent == null) 0L else planned.translucentBytes,
            )
        } catch (throwable: Throwable) {
            try {
                solid?.close()
            } finally {
                try {
                    translucent?.close()
                } finally {
                    discardGeometry(geometry)
                }
            }
            throw throwable
        }
    }

    private fun plannedAllocation(geometry: SectionGeometry?): SectionGpuAllocation<Unit> {
        if (geometry == null) return SectionGpuAllocation.empty()
        val solidPresent = geometry.solid != null
        val translucentPresent = geometry.translucent != null
        return when {
            solidPresent && translucentPresent -> SectionGpuAllocation(
                solid = Unit,
                solidBytes = ESTIMATED_SECTION_GPU_BYTES / 2L,
                translucent = Unit,
                translucentBytes = ESTIMATED_SECTION_GPU_BYTES - ESTIMATED_SECTION_GPU_BYTES / 2L,
            )
            solidPresent -> SectionGpuAllocation(Unit, ESTIMATED_SECTION_GPU_BYTES, null, 0L)
            translucentPresent -> SectionGpuAllocation(null, 0L, Unit, ESTIMATED_SECTION_GPU_BYTES)
            else -> SectionGpuAllocation.empty()
        }
    }

    private fun drawVisibleSections(
        visibleKeys: Set<SectionKey>,
        cameraWorld: Vec3,
        poseStack: PoseStack,
        projection: Matrix4f,
        transform: RenderTransform,
        opacity: Float,
    ): Unit {
        val solid = visibleKeys
            .sortedWith(SECTION_KEY_COMPARATOR)
            .mapNotNull { state.allocation(it)?.solid }
        val translucent = visibleKeys
            .sortedWith(
                compareByDescending<SectionKey> { key ->
                    state.worldAabb(key)?.let { centerDistanceToSqr(cameraWorld, it) } ?: 0.0
                }.then(SECTION_KEY_COMPARATOR),
            )
            .mapNotNull { state.allocation(it)?.translucent }
        if (solid.isEmpty() && translucent.isEmpty()) return

        poseStack.pushPose()
        try {
            poseStack.translate(-cameraWorld.x, -cameraWorld.y, -cameraWorld.z)
            poseStack.translate(transform.renderBase.x, transform.renderBase.y, transform.renderBase.z)
            poseStack.mulPose(YP.rotationDegrees(transform.rotateDeg))
            poseStack.translate(transform.rotateAxis.x, transform.rotateAxis.y, transform.rotateAxis.z)
            gpuBackend.drawLayer(
                solid,
                NuchematicaRenderTypes.GHOST_BLOCKS,
                poseStack,
                projection,
                opacity,
            )
            gpuBackend.drawLayer(
                translucent,
                NuchematicaRenderTypes.GHOST_TRANSLUCENT,
                poseStack,
                projection,
                opacity,
            )
        } finally {
            try {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            } finally {
                poseStack.popPose()
            }
        }
    }

    private fun discardSortCompletion(pending: PendingSectionSort): Unit {
        try {
            if (pending.completion is WorkerCompletion.Success) {
                bufferPool.discardSort(pending.completion.value)
            }
        } finally {
            state.cancelSort(pending.token)
        }
    }

    private fun discardGeometry(geometry: SectionGeometry?): Unit {
        try {
            geometry?.solid?.discard()
        } finally {
            geometry?.translucent?.discard()
        }
    }

    private fun worldAabbs(
        content: SchematicContentSnapshot,
        transform: RenderTransform,
    ): Map<SectionKey, AABB> {
        return content.blocksBySection.keys.associateWith(transform::sectionWorldAabb)
    }

    private fun recordFrameMetrics(): Unit {
        val runtime = currentRuntimeSnapshot()
        metrics.recordFrame(
            inFlight = sortWorker.inFlightCount,
            submissions = frameLimits.submissions,
            uploads = frameLimits.uploads,
            liveCpuBuffers = runtime.liveCpuBuffers,
            completionQueue = runtime.completionQueue,
        )
    }

    private fun currentRuntimeSnapshot(): SectionMeshRuntimeSnapshot {
        val liveGeometryBuffers = when {
            pendingGeometry?.geometry == null && pendingGeometry != null -> 0
            pendingGeometry != null ->
                (if (pendingGeometry?.geometry?.solid == null) 0 else 1) +
                    (if (pendingGeometry?.geometry?.translucent == null) 0 else 1)
            activeGeometry != null -> 2
            else -> 0
        }
        val liveSortBuffers = when {
            pendingSort?.completion is WorkerCompletion.Success -> 1
            sortWorker.inFlightCount > 0 -> 1
            else -> 0
        }
        return SectionMeshRuntimeSnapshot(
            geometryJobs = (if (activeGeometry == null) 0 else 1) +
                (if (pendingGeometry == null) 0 else 1),
            sortJobs = sortWorker.inFlightCount +
                (if (pendingSort == null) 0 else 1),
            liveCpuBuffers = liveGeometryBuffers + liveSortBuffers,
            completionQueue = (if (pendingGeometry == null) 0 else 1) +
                (if (pendingSort == null) 0 else 1) + sortWorker.pendingCompletionCount,
        )
    }

    private fun logMetricsIfEnabled(): Unit {
        if (metrics.loggingEnabled && frame % METRICS_LOG_INTERVAL_FRAMES == 0L) {
            logger.info("section mesh metrics: {}", metrics.snapshot())
        }
    }

    private fun centerDistanceToSqr(point: Vec3, box: AABB): Double {
        val dx = point.x - (box.minX + box.maxX) * 0.5
        val dy = point.y - (box.minY + box.maxY) * 0.5
        val dz = point.z - (box.minZ + box.maxZ) * 0.5
        return dx * dx + dy * dy + dz * dz
    }

    private fun saturatedAdd(value: Long, increment: Long): Long {
        return if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }

    private companion object {
        private const val MAIN_THREAD_SECTION_BUDGET_NANOS: Long = 2_000_000L
        private const val MAX_CAPTURES_PER_FRAME: Int = 1
        private const val MAX_SUBMISSIONS_PER_FRAME: Int = 1
        private const val MAX_UPLOADS_PER_FRAME: Int = 1
        private const val GPU_SOFT_BYTES: Long = 64L * 1024L * 1024L
        private const val GPU_HARD_BYTES: Long = 96L * 1024L * 1024L
        private const val GPU_HARD_HANDLE_COUNT: Int = 512
        private const val ESTIMATED_SECTION_GPU_BYTES: Long = 792_448L
        private const val RESORT_DISTANCE_SQ: Double = 64.0
        private const val METRICS_LOG_INTERVAL_FRAMES: Long = 600L
        private val GPU_BUDGET = SectionGpuBudget(
            softBytes = GPU_SOFT_BYTES,
            hardBytes = GPU_HARD_BYTES,
            hardHandleCount = GPU_HARD_HANDLE_COUNT,
        )
        private val SECTION_KEY_COMPARATOR = compareBy<SectionKey>(
            { it.x },
            { it.y },
            { it.z },
        )
    }
}
