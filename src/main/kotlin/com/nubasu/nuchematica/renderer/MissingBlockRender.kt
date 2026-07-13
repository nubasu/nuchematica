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
    // Incremented on every initialize(); an in-flight build whose generation no longer
    // matches is discarded instead of uploading stale geometry.
    @Volatile
    private var buildGeneration = 0

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuchematica-missing-block-builder").apply { isDaemon = true }
    }
    private var missingBlockBuffer: VertexBuffer? = null

    public fun initialize() {
        buildGeneration++
        isBuilt = false
        Minecraft.getInstance().execute {
            missingBlockBuffer?.close()
            missingBlockBuffer = null
        }
    }

    public fun render(offset: Vec3, rotate: Float, rotateAxis: Vec3, event: RenderLevelStageEvent) {
        if (!isBuilt) buildMissingBlockVertexBufferAsync()
        if (!isBuilt) return
        val camPos = event.camera.position
        val poseStack = event.poseStack
        val projection = event.projectionMatrix

        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
        poseStack.translate(offset.x, offset.y, offset.z)
        poseStack.mulPose(
            YP.rotationDegrees(rotate),
        )
        poseStack.translate(rotateAxis.x, rotateAxis.y, rotateAxis.z)
        missingBlockBuffer?.let {
            NuchematicaRenderTypes.MISSING_OVERLAY.setupRenderState()
            it.bind()
            it.drawWithShader(poseStack.last().pose(), projection, RenderSystem.getShader())
            VertexBuffer.unbind()
            NuchematicaRenderTypes.MISSING_OVERLAY.clearRenderState()
        }
        poseStack.popPose()
    }

    private fun buildMissingBlockVertexBufferAsync() {
        if (isBuilt || isBuilding) return
        isBuilding = true

        // Snapshot on the main thread: the holder lists are mutated by the client tick
        // handler, so the worker must never touch the live collections.
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
                    // finally guarantees isBuilding is released even if the upload throws.
                    try {
                        if (generation != buildGeneration) {
                            // A newer initialize() superseded this build; drop it and let
                            // the next frame rebuild from current data.
                            return@execute
                        }
                        missingBlockBuffer?.close()

                        missingBlockBuffer = VertexBuffer().apply {
                            bind()
                            upload(missingBlockBuilder)
                            VertexBuffer.unbind()
                        }
                        isBuilt = true
                    } finally {
                        isBuilding = false
                    }
                }
            } catch (e: Exception) {
                LogUtils.getLogger().error("failed to build missing-block vertex buffer", e)
                // Mark as built so the render loop does not retry (and log-spam) every
                // frame; the next initialize() resets the state and tries again.
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
            // Only draw the faces that are not hidden by an adjacent schematic block.
            val visibleFaces = mutableSetOf<Direction>()
            for (dir in Direction.values()) {
                if (!schematicBlocks.containsKey(pos.relative(dir))) {
                    visibleFaces.add(dir)
                }
            }
            if (visibleFaces.isEmpty()) return@forEach

            poseStack.pushPose()
            poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            BaseRender.drawVisibleFacesCubeWithBuffer(
                builder,
                poseStack,
                Vec3(0.0, 0.0, 0.0),
                color,
                visibleFaces
            )
            poseStack.popPose()
        }
    }
}
