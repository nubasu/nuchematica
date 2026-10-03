package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.nubasu.nuchematica.platform.Platform
import com.nubasu.nuchematica.renderer.VertexConsumerWithPose
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.BlockRenderDispatcher
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.Random

internal enum class GhostGeometryLayer {
    SOLID,
    TRANSLUCENT,
}

internal enum class SectionSourceLayer(
    internal val destination: GhostGeometryLayer,
) {
    SOLID(GhostGeometryLayer.SOLID),
    CUTOUT_MIPPED(GhostGeometryLayer.SOLID),
    CUTOUT(GhostGeometryLayer.SOLID),
    TRANSLUCENT(GhostGeometryLayer.TRANSLUCENT),
    TRIPWIRE(GhostGeometryLayer.SOLID),
}

internal data class SectionLayerPass(
    internal val sourceLayer: SectionSourceLayer,
    internal val renderBlock: Boolean,
    internal val renderFluid: Boolean,
)

internal data class SectionLayerRenderResult(
    internal val blockRendered: Boolean,
    internal val fluidRendered: Boolean,
) {
    internal val renderedAny: Boolean
        get() = blockRendered || fluidRendered
}

internal interface SectionMeshingService {
    val resourceEpoch: Long

    fun passesFor(blockState: BlockState): List<SectionLayerPass>

    fun renderLayer(
        pass: SectionLayerPass,
        pos: BlockPos,
        blockState: BlockState,
        view: BlockAndTintGetter,
        target: VertexConsumer,
    ): SectionLayerRenderResult
}

internal class MainThreadSectionMeshingService private constructor(
    private val blockRenderer: BlockRenderDispatcher,
    private val layerTypes: Map<SectionSourceLayer, RenderType>,
    private val passesByBlockState: Map<BlockState, List<SectionLayerPass>>,
    override val resourceEpoch: Long,
) : SectionMeshingService {
    private val poseStack: PoseStack = PoseStack()
    private val randomSource: Random = Random(0L)

    override fun passesFor(blockState: BlockState): List<SectionLayerPass> {
        return checkNotNull(passesByBlockState[blockState]) {
            "block state was not captured for resource epoch $resourceEpoch"
        }
    }

    override fun renderLayer(
        pass: SectionLayerPass,
        pos: BlockPos,
        blockState: BlockState,
        view: BlockAndTintGetter,
        target: VertexConsumer,
    ): SectionLayerRenderResult {
        check(pass in passesFor(blockState)) {
            "layer pass was not captured for resource epoch $resourceEpoch"
        }
        val renderType = checkNotNull(layerTypes[pass.sourceLayer]) {
            "missing render type for ${pass.sourceLayer}"
        }

        return Platform.hooks.withRenderLayer(renderType) {
            poseStack.pushPose()
            try {
                poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                val fluidRendered = if (pass.renderFluid) {
                    val fluidConsumer = VertexConsumerWithPose(
                        target,
                        pos,
                        poseStack,
                        fluidColor(blockState),
                    )
                    blockRenderer.renderLiquid(
                        pos,
                        view,
                        fluidConsumer,
                        blockState,
                        blockState.fluidState,
                    )
                } else {
                    false
                }
                val blockRendered = if (pass.renderBlock) {
                    Platform.hooks.renderBatched(
                        blockRenderer,
                        blockState,
                        pos,
                        view,
                        poseStack,
                        target,
                        true,
                        randomSource,
                    )
                } else {
                    false
                }
                SectionLayerRenderResult(blockRendered, fluidRendered)
            } finally {
                poseStack.popPose()
            }
        }
    }

    private fun fluidColor(blockState: BlockState): FloatArray {
        return if (blockState.fluidState.`is`(FluidTags.LAVA)) {
            LAVA_FLUID_COLOR
        } else {
            WATER_FLUID_COLOR
        }
    }

    internal companion object {
        private val LAVA_FLUID_COLOR = floatArrayOf(1.0f, 0.35f, 0.1f, 1.0f)
        private val WATER_FLUID_COLOR = floatArrayOf(0.15f, 0.35f, 1.0f, 1.0f)

        internal fun create(
            blockRenderer: BlockRenderDispatcher,
            content: SchematicContentSnapshot,
            resourceEpoch: Long,
        ): MainThreadSectionMeshingService {
            val layerTypes = captureLayerTypes()
            val passesByBlockState = LinkedHashMap<BlockState, List<SectionLayerPass>>()
            for (blockState in content.allBlocks.values.distinct()) {
                val fluidState = blockState.fluidState
                val passes = ArrayList<SectionLayerPass>()
                for ((sourceLayer, renderType) in layerTypes) {
                    val renderBlock = blockState.renderShape != RenderShape.INVISIBLE &&
                        Platform.hooks.canRenderInLayer(blockState, renderType)
                    val renderFluid = !fluidState.isEmpty &&
                        Platform.hooks.canRenderInLayer(fluidState, renderType)
                    if (renderBlock || renderFluid) {
                        passes += SectionLayerPass(sourceLayer, renderBlock, renderFluid)
                    }
                }
                passesByBlockState[blockState] = Collections.unmodifiableList(passes)
            }
            return MainThreadSectionMeshingService(
                blockRenderer = blockRenderer,
                layerTypes = Collections.unmodifiableMap(layerTypes),
                passesByBlockState = Collections.unmodifiableMap(passesByBlockState),
                resourceEpoch = resourceEpoch,
            )
        }

        private fun captureLayerTypes(): LinkedHashMap<SectionSourceLayer, RenderType> {
            val result = LinkedHashMap<SectionSourceLayer, RenderType>()
            for (renderType in RenderType.chunkBufferLayers()) {
                val sourceLayer = sourceLayerOf(renderType)
                check(result.put(sourceLayer, renderType) == null) {
                    "duplicate chunk render layer $sourceLayer"
                }
            }
            check(result.keys == SectionSourceLayer.values().toSet()) {
                "chunk render layer set does not match the frozen routing table"
            }
            return result
        }

        private fun sourceLayerOf(renderType: RenderType): SectionSourceLayer {
            return when (renderType) {
                RenderType.solid() -> SectionSourceLayer.SOLID
                RenderType.cutoutMipped() -> SectionSourceLayer.CUTOUT_MIPPED
                RenderType.cutout() -> SectionSourceLayer.CUTOUT
                RenderType.translucent() -> SectionSourceLayer.TRANSLUCENT
                RenderType.tripwire() -> SectionSourceLayer.TRIPWIRE
                else -> error("unsupported chunk render layer $renderType")
            }
        }
    }
}

internal data class SectionMeshBuildAdvanceResult(
    internal val cursor: CursorAdvanceResult,
    internal val geometry: SectionGeometry?,
)

internal class SectionGeometryBufferLease(
    internal val solid: BufferBuilder,
    internal val translucent: BufferBuilder,
)

internal class SectionMeshBufferPool(
    createBuffer: () -> BufferBuilder = { BufferBuilder(INITIAL_BUFFER_BYTES) },
) {
    private val geometryLease: SectionGeometryBufferLease = SectionGeometryBufferLease(
        solid = createBuffer(),
        translucent = createBuffer(),
    )
    private val sortBuilder: BufferBuilder = createBuffer()
    private val lock: Any = Any()
    private var geometryLeased: Boolean = false
    private var sortLeased: Boolean = false

    internal fun acquireGeometry(): SectionGeometryBufferLease {
        return synchronized(lock) {
            check(!geometryLeased) { "section geometry buffers are already leased" }
            prepareForReuse(geometryLease.solid)
            prepareForReuse(geometryLease.translucent)
            geometryLeased = true
            geometryLease
        }
    }

    internal fun releaseGeometry(lease: SectionGeometryBufferLease): Unit {
        synchronized(lock) {
            check(lease === geometryLease) { "geometry buffers do not belong to this pool" }
            check(geometryLeased) { "section geometry buffers are not leased" }
            prepareForReuse(geometryLease.solid)
            prepareForReuse(geometryLease.translucent)
            geometryLeased = false
        }
    }

    internal fun acquireSort(): BufferBuilder {
        return synchronized(lock) {
            check(!sortLeased) { "section sort buffer is already leased" }
            prepareForReuse(sortBuilder)
            sortLeased = true
            sortBuilder
        }
    }

    internal fun releaseSort(builder: BufferBuilder): Unit {
        synchronized(lock) {
            check(builder === sortBuilder) { "sort buffer does not belong to this pool" }
            check(sortLeased) { "section sort buffer is not leased" }
            prepareForReuse(sortBuilder)
            sortLeased = false
        }
    }

    internal fun discardSort(builder: BufferBuilder): Unit {
        synchronized(lock) {
            check(builder === sortBuilder) { "sort buffer does not belong to this pool" }
            check(sortLeased) { "section sort buffer is not leased" }
            discardReusableBuffer(sortBuilder)
            prepareForReuse(sortBuilder)
            sortLeased = false
        }
    }

    private fun prepareForReuse(builder: BufferBuilder): Unit {
        check(!builder.building()) { "cannot reuse a BufferBuilder while it is building" }
        builder.clear()
    }

    private companion object {
        private const val INITIAL_BUFFER_BYTES: Int = 262_144
    }
}

internal class SectionMeshBuilder(
    private val request: MainThreadSectionBuildRequest,
    private val meshingService: SectionMeshingService,
    buffers: SectionGeometryBufferLease,
) {
    private val solidBuilder: BufferBuilder = buffers.solid
    private val translucentBuilder: BufferBuilder = buffers.translucent
    private val solidConsumer: VertexConsumer = OpaqueVertexConsumer(solidBuilder)
    private val translucentConsumer: VertexConsumer = OpaqueVertexConsumer(translucentBuilder)
    private var solidUsed: Boolean = false
    private var translucentUsed: Boolean = false
    private var finished: Boolean = false

    init {
        try {
            solidBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
            translucentBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        } catch (throwable: Throwable) {
            try {
                discardReusableBuffer(solidBuilder)
            } finally {
                discardReusableBuffer(translucentBuilder)
            }
            throw throwable
        }
    }

    internal fun build(
        deadlineNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): SectionMeshBuildAdvanceResult {
        check(!finished) { "section mesh builder is already finished" }
        try {
            val cursorResult = request.cursor.advanceWithinBudget(deadlineNanos, nanoTime) { pos, blockState ->
                renderBlock(pos, blockState)
            }
            val geometry = if (cursorResult.complete) {
                finished = true
                finishGeometry()
            } else {
                null
            }
            return SectionMeshBuildAdvanceResult(cursorResult, geometry)
        } catch (throwable: Throwable) {
            finished = true
            discardBuffers()
            throw throwable
        }
    }

    internal fun discard(): Unit {
        if (!finished) {
            finished = true
        }
        discardBuffers()
    }

    private fun renderBlock(pos: BlockPos, blockState: BlockState): Unit {
        for (pass in meshingService.passesFor(blockState)) {
            val target = when (pass.sourceLayer.destination) {
                GhostGeometryLayer.SOLID -> solidConsumer
                GhostGeometryLayer.TRANSLUCENT -> translucentConsumer
            }
            val result = meshingService.renderLayer(pass, pos, blockState, request.view, target)
            if (result.renderedAny) {
                when (pass.sourceLayer.destination) {
                    GhostGeometryLayer.SOLID -> solidUsed = true
                    GhostGeometryLayer.TRANSLUCENT -> translucentUsed = true
                }
            }
        }
    }

    private fun finishGeometry(): SectionGeometry? {
        if (!solidUsed && !translucentUsed) {
            discardBuffers()
            return null
        }

        val solid = if (solidUsed) {
            solidBuilder.end()
            solidBuilder
        } else {
            discardReusableBuffer(solidBuilder)
            null
        }
        var translucentSortState: BufferBuilder.SortState? = null
        val translucent = if (translucentUsed) {
            translucentBuilder.setQuadSortOrigin(
                request.sortOrigin.x.toFloat(),
                request.sortOrigin.y.toFloat(),
                request.sortOrigin.z.toFloat(),
            )
            translucentSortState = translucentBuilder.sortState
            translucentBuilder.end()
            translucentBuilder
        } else {
            discardReusableBuffer(translucentBuilder)
            null
        }
        return SectionGeometry(solid, translucent, translucentSortState)
    }

    private fun discardBuffers(): Unit {
        try {
            discardReusableBuffer(solidBuilder)
        } finally {
            discardReusableBuffer(translucentBuilder)
        }
    }

    internal companion object {
        internal fun resort(
            sortState: BufferBuilder.SortState,
            sortOrigin: Vec3,
            builder: BufferBuilder,
        ): BufferBuilder {
            try {
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
                builder.restoreSortState(sortState)
                builder.setQuadSortOrigin(
                    sortOrigin.x.toFloat(),
                    sortOrigin.y.toFloat(),
                    sortOrigin.z.toFloat(),
                )
                builder.end()
                return builder
            } catch (throwable: Throwable) {
                discardReusableBuffer(builder)
                throw throwable
            }
        }
    }
}

private fun discardReusableBuffer(builder: BufferBuilder): Unit {
    if (builder.building()) {
        builder.end()
    }
    builder.discard()
}

private class OpaqueVertexConsumer(
    private val parent: VertexConsumer,
) : VertexConsumer {
    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
        parent.vertex(x, y, z)
        return this
    }

    override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        parent.color(red, green, blue, 255)
        return this
    }

    override fun uv(u: Float, v: Float): VertexConsumer {
        parent.uv(u, v)
        return this
    }

    override fun overlayCoords(u: Int, v: Int): VertexConsumer {
        parent.overlayCoords(u, v)
        return this
    }

    override fun uv2(u: Int, v: Int): VertexConsumer {
        parent.uv2(u, v)
        return this
    }

    override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
        parent.normal(x, y, z)
        return this
    }

    override fun endVertex(): Unit {
        parent.endVertex()
    }

    override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int): Unit {
        parent.defaultColor(red, green, blue, 255)
    }

    override fun unsetDefaultColor(): Unit {
        parent.unsetDefaultColor()
    }
}
