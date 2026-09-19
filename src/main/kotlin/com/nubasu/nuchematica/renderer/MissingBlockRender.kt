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
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public class MissingBlockRender {
    @Volatile
    private var isBuilding = false

    private val index: MissingOverlaySectionIndex = MissingOverlaySectionIndex()
    private val sectionBuffers: HashMap<SectionKey, VertexBuffer> = HashMap()
    private var indexedContent: Any? = null

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
        }
        val wrongBlockPositions = MissingBlockHolder.blockPos.toList()
        val missingPositions = MissingBlockHolder.airPos.toList()
        val extraPositions = MissingBlockHolder.extraPos.toList()
        val vanishedSections = index.rebuildAll(missingPositions, wrongBlockPositions, extraPositions)
        for (key in vanishedSections) {
            sectionBuffers.remove(key)?.close()
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

        val camPos = event.camera.position
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
                        if (!event.frustum.isVisible(transform.sectionWorldAabb(key))) continue
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

        // Snapshot main-thread state before the worker reads it.
        val schematicBlocks = SchematicHolder.renderingBlocks.blocks
        val mc = Minecraft.getInstance()
        val red = Vector3f(1.0f, 0.0f, 0.0f)
        val green = Vector3f(0.0f, 1.0f, 0.0f)
        val purple = Vector3f(0.6f, 0.0f, 1.0f)

        buildExecutor.submit {
            try {
                val results = inputs.map { input -> buildSectionGeometry(input, schematicBlocks, red, green, purple) }
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
        schematicBlocks: Map<BlockPos, *>,
        red: Vector3f,
        green: Vector3f,
        purple: Vector3f,
    ): SectionBuildResult {
        if (input.wrongBlockPositions.isEmpty() && input.missingPositions.isEmpty() && input.extraPositions.isEmpty()) {
            return SectionBuildResult(input, null)
        }
        val builder = BufferBuilder(262144)
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
        if (!index.isCurrent(input.key, input.generation)) {
            // A newer change superseded this build; it will be rebuilt from the dirty queue.
            return
        }
        if (result.builder == null) {
            sectionBuffers.remove(input.key)?.close()
            return
        }
        var newBuffer: VertexBuffer? = null
        try {
            newBuffer = uploadVertexBuffer(result.builder)
            val oldBuffer = sectionBuffers.put(input.key, newBuffer)
            newBuffer = null
            oldBuffer?.close()
        } catch (e: Exception) {
            LogUtils.getLogger().error("failed to upload missing-block vertex buffer", e)
        } finally {
            newBuffer?.close()
        }
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
