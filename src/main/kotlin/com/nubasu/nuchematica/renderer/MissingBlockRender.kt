package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.logging.LogUtils
import com.mojang.math.Vector3f
import com.mojang.math.Vector3f.YP
import com.nubasu.nuchematica.renderer.section.RenderTransform
import com.nubasu.nuchematica.renderer.section.SectionKey
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.BaseRender
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public class MissingBlockRender(
    private val renderDistanceBlocks: () -> Int = { GhostRenderDistance.currentBlocks() },
) {
    @Volatile
    private var isBuilding = false

    private val index: MissingOverlaySectionIndex = MissingOverlaySectionIndex()
    private val sectionBuffers: HashMap<SectionKey, VertexBuffer> = HashMap()
    private val sectionAabbs: HashMap<SectionKey, AABB> = HashMap()
    private var sectionAabbRevision: Long = Long.MIN_VALUE
    private var indexedContent: Any? = null

    /** Builders are reused, one slot per section built in a batch, growing up to [MAX_SECTIONS_PER_BUILD]. */
    private val builderPool: ArrayList<BufferBuilder> = ArrayList()

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuchematica-missing-block-builder").apply { isDaemon = true }
    }

    /**
     * Re-indexes every section from the current missing sets.
     *
     * Surviving sections keep drawing their old geometry until rebuilt; geometry built for a
     * different schematic content is dropped at once because its local positions no longer
     * describe anything.
     */
    public fun initialize(): Unit {
        val content = SchematicHolder.renderingBlocks
        if (indexedContent !== content) {
            indexedContent = content
            for (buffer in sectionBuffers.values) buffer.close()
            sectionBuffers.clear()
            sectionAabbs.clear()
        }
        val wrongBlockPositions = MissingBlockHolder.blockPos.toList()
        val missingPositions = MissingBlockHolder.airPos.toList()
        val extraPositions = MissingBlockHolder.extraPos.toList()
        val vanishedSections = index.rebuildAll(missingPositions, wrongBlockPositions, extraPositions)
        for (key in vanishedSections) {
            sectionBuffers.remove(key)?.close()
            sectionAabbs.remove(key)
        }
    }

    /** Records one position's current status so only its own section is rebuilt. */
    internal fun markChanged(localPos: BlockPos): Unit {
        val missing = MissingBlockHolder.airPos.contains(localPos)
        val wrongBlock = MissingBlockHolder.blockPos.contains(localPos)
        val extra = MissingBlockHolder.extraPos.contains(localPos)
        index.apply(localPos, missing = missing, wrongBlock = wrongBlock, extra = extra)
    }

    internal fun render(transform: RenderTransform, event: RenderLevelStageEvent): Unit {
        if (!isBuilding && index.dirtyCount > 0) {
            submitSectionBuild()
        }
        if (sectionBuffers.isEmpty()) return

        if (transform.revision != sectionAabbRevision) {
            sectionAabbs.clear()
            sectionAabbRevision = transform.revision
        }

        val camPos = event.camera.position
        val radius = renderDistanceBlocks()
        val poseStack = event.poseStack
        val projection = event.projectionMatrix

        poseStack.pushPose()
        try {
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
            poseStack.translate(transform.renderBase.x, transform.renderBase.y, transform.renderBase.z)
            poseStack.mulPose(
                YP.rotationDegrees(transform.rotateDeg),
            )
            poseStack.translate(transform.rotateAxis.x, transform.rotateAxis.y, transform.rotateAxis.z)
            val pose = poseStack.last().pose()

            try {
                NuchematicaRenderTypes.MISSING_OVERLAY.setupRenderState()
                try {
                    for ((key, buffer) in sectionBuffers) {
                        val aabb = sectionAabbs.getOrPut(key) { transform.sectionWorldAabb(key) }
                        if (!GhostRenderDistance.withinHorizontal(camPos, aabb, radius)) continue
                        if (!event.frustum.isVisible(aabb)) continue
                        buffer.bind()
                        buffer.drawWithShader(pose, projection, RenderSystem.getShader())
                    }
                } finally {
                    VertexBuffer.unbind()
                }
            } finally {
                NuchematicaRenderTypes.MISSING_OVERLAY.clearRenderState()
            }
        } finally {
            poseStack.popPose()
        }
    }

    private fun submitSectionBuild() {
        val inputs = index.takeDirty(MAX_SECTIONS_PER_BUILD)
        if (inputs.isEmpty()) return
        isBuilding = true

        // A builder is only handed to a non-empty input; an empty section produces no geometry at all.
        val builders = arrayOfNulls<BufferBuilder>(inputs.size)
        var nextPoolIndex = 0
        for (i in inputs.indices) {
            val input = inputs[i]
            if (input.wrongBlockPositions.isEmpty() && input.missingPositions.isEmpty() && input.extraPositions.isEmpty()) {
                continue
            }
            if (nextPoolIndex == builderPool.size) {
                builderPool.add(BufferBuilder(INITIAL_BUFFER_BYTES))
            }
            builders[i] = builderPool[nextPoolIndex]
            nextPoolIndex++
        }

        // Snapshot main-thread state before the worker reads it.
        val schematicBlocks = SchematicHolder.renderingBlocks.blocks
        val mc = Minecraft.getInstance()
        val red = Vector3f(1.0f, 0.0f, 0.0f)
        val green = Vector3f(0.0f, 1.0f, 0.0f)
        val purple = Vector3f(0.6f, 0.0f, 1.0f)

        buildExecutor.submit {
            try {
                val results = inputs.indices.map { i ->
                    val builder = builders[i]
                    if (builder == null) {
                        SectionBuildResult(inputs[i], null)
                    } else {
                        buildSectionGeometry(inputs[i], builder, schematicBlocks, red, green, purple)
                    }
                }
                mc.execute {
                    try {
                        for (result in results) {
                            applySectionBuildResult(result)
                        }
                    } finally {
                        isBuilding = false
                    }
                }
            } catch (e: Exception) {
                LogUtils.getLogger().error("failed to build missing-block vertex buffer", e)
                isBuilding = false
            }
        }
    }

    private fun buildSectionGeometry(
        input: MissingOverlaySectionBuildInput,
        builder: BufferBuilder,
        schematicBlocks: Map<BlockPos, *>,
        red: Vector3f,
        green: Vector3f,
        purple: Vector3f,
    ): SectionBuildResult {
        // A builder left mid-build by a failed earlier batch would reject begin(); drain it first.
        if (builder.building()) resetForReuse(builder)
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val poseStack = PoseStack()

        buildCubes(builder, poseStack, input.wrongBlockPositions, schematicBlocks, red)
        buildCubes(builder, poseStack, input.missingPositions, schematicBlocks, green)
        val extrasInThisSection = input.extraPositions.associateWith { Unit }
        buildCubes(builder, poseStack, input.extraPositions, extrasInThisSection, purple)

        builder.end()
        return SectionBuildResult(input, builder)
    }

    private fun applySectionBuildResult(result: SectionBuildResult) {
        val input = result.input
        val builder = result.builder
        if (!index.isCurrent(input.key, input.generation)) {
            // A newer change superseded this build; it will be rebuilt from the dirty queue.
            builder?.let(::resetForReuse)
            return
        }
        if (builder == null) {
            sectionBuffers.remove(input.key)?.close()
            sectionAabbs.remove(input.key)
            return
        }
        var newBuffer: VertexBuffer? = null
        var uploaded = false
        try {
            newBuffer = uploadVertexBuffer(builder)
            uploaded = true
            val oldBuffer = sectionBuffers.put(input.key, newBuffer)
            newBuffer = null
            oldBuffer?.close()
        } catch (e: Exception) {
            LogUtils.getLogger().error("failed to upload missing-block vertex buffer", e)
        } finally {
            newBuffer?.close()
            // The next-frame handshake (isBuilding) guarantees this builder is idle before reuse:
            // it is only reused once every result in this batch has been applied here on the main thread.
            if (uploaded) {
                builder.clear()
            } else {
                resetForReuse(builder)
            }
        }
    }

    /** Drains a built-but-never-uploaded buffer before it can be handed to the next section. */
    private fun resetForReuse(builder: BufferBuilder): Unit {
        if (builder.building()) {
            builder.end()
        }
        builder.discard()
        builder.clear()
    }

    private fun buildCubes(
        builder: BufferBuilder,
        poseStack: PoseStack,
        positions: List<BlockPos>,
        schematicBlocks: Map<BlockPos, *>,
        color: Vector3f,
    ) {
        positions.forEach { pos ->
            val visibleFaces = selectMissingOverlayFaces(pos, schematicBlocks)
            if (visibleFaces.isEmpty()) return@forEach

            poseStack.pushPose()
            try {
                poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                BaseRender.drawVisibleFacesCubeWithBuffer(
                    builder,
                    poseStack,
                    Vec3(0.0, 0.0, 0.0),
                    color,
                    visibleFaces
                )
            } finally {
                poseStack.popPose()
            }
        }
    }

    private fun uploadVertexBuffer(builder: BufferBuilder): VertexBuffer {
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
            return buffer
        } finally {
            if (!uploaded) {
                buffer.close()
            }
        }
    }

    private class SectionBuildResult(
        val input: MissingOverlaySectionBuildInput,
        val builder: BufferBuilder?,
    )

    private companion object {
        private const val MAX_SECTIONS_PER_BUILD: Int = 32
        private const val INITIAL_BUFFER_BYTES: Int = 262_144
    }
}

internal fun selectMissingOverlayFaces(
    pos: BlockPos,
    schematicBlocks: Map<BlockPos, *>,
): Set<Direction> {
    val visibleFaces = mutableSetOf<Direction>()
    for (direction in Direction.values()) {
        if (!schematicBlocks.containsKey(pos.relative(direction))) {
            visibleFaces.add(direction)
        }
    }
    return visibleFaces
}
