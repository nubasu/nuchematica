package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.logging.LogUtils
import com.mojang.math.Vector3f
import com.mojang.math.Vector3f.YP
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
    private var isBuilt = false
    @Volatile
    private var isBuilding = false
    @Volatile
    private var buildGeneration = 0

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuchematica-missing-block-builder").apply { isDaemon = true }
    }
    private var missingBlockBuffer: VertexBuffer? = null

    public fun initialize() {
        buildGeneration++
        isBuilt = false
    }

    public fun render(offset: Vec3, rotate: Float, rotateAxis: Vec3, event: RenderLevelStageEvent) {
        if (!isBuilt) buildMissingBlockVertexBufferAsync()
        if (!isBuilt) return
        val camPos = event.camera.position
        val poseStack = event.poseStack
        val projection = event.projectionMatrix

        poseStack.pushPose()
        try {
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
            poseStack.translate(offset.x, offset.y, offset.z)
            poseStack.mulPose(
                YP.rotationDegrees(rotate),
            )
            poseStack.translate(rotateAxis.x, rotateAxis.y, rotateAxis.z)
            missingBlockBuffer?.let {
                try {
                    NuchematicaRenderTypes.MISSING_OVERLAY.setupRenderState()
                    try {
                        it.bind()
                        it.drawWithShader(poseStack.last().pose(), projection, RenderSystem.getShader())
                    } finally {
                        VertexBuffer.unbind()
                    }
                } finally {
                    NuchematicaRenderTypes.MISSING_OVERLAY.clearRenderState()
                }
            }
        } finally {
            poseStack.popPose()
        }
    }

    private fun buildMissingBlockVertexBufferAsync() {
        if (isBuilt || isBuilding) return
        isBuilding = true

        // Snapshot main-thread collections before the worker reads them.
        val generation = buildGeneration
        val wrongBlockPositions = MissingBlockHolder.blockPos.toList()
        val missingPositions = MissingBlockHolder.airPos.toList()
        val schematicBlocks = SchematicHolder.renderingBlocks.blocks
        val mc = Minecraft.getInstance()

        buildExecutor.submit {
            try {
                val missingBlockBuilder = BufferBuilder(262144)
                missingBlockBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
                val poseStack = PoseStack()

                val red = Vector3f(1.0f, 0.0f, 0.0f)
                val green = Vector3f(0.0f, 1.0f, 0.0f)
                buildCubes(missingBlockBuilder, poseStack, wrongBlockPositions, schematicBlocks, red)
                buildCubes(missingBlockBuilder, poseStack, missingPositions, schematicBlocks, green)

                missingBlockBuilder.end()

                mc.execute {
                    var newBuffer: VertexBuffer? = null
                    var swapped = false
                    try {
                        // Drop geometry superseded by initialize().
                        if (generation != buildGeneration) {
                            return@execute
                        }

                        newBuffer = uploadVertexBuffer(missingBlockBuilder)
                        val oldBuffer = missingBlockBuffer
                        missingBlockBuffer = newBuffer
                        newBuffer = null
                        isBuilt = true
                        swapped = true
                        oldBuffer?.close()
                    } catch (e: Exception) {
                        LogUtils.getLogger().error("failed to upload missing-block vertex buffer", e)
                        if (!swapped && generation == buildGeneration) {
                            isBuilt = true
                        }
                    } finally {
                        try {
                            newBuffer?.close()
                        } finally {
                            isBuilding = false
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtils.getLogger().error("failed to build missing-block vertex buffer", e)
                isBuilt = true
                isBuilding = false
            }
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
