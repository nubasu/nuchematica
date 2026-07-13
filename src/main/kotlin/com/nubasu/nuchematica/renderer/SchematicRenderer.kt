package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.logging.LogUtils
import com.mojang.math.Matrix4f
import com.mojang.math.Vector3f.YP
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.EmptyModelData
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

class SchematicRenderer {

    private var solidBuffer: VertexBuffer? = null
    private var translucentBuffer: VertexBuffer? = null
    private val blockEntityOpacityBufferSource = BlockEntityOpacityBufferSource()

    @Volatile
    private var isBuilt = false
    @Volatile
    private var isBuilding = false
    @Volatile
    private var buildFailed = false
    // Incremented on every initialize(); an in-flight build whose generation no longer
    // matches is discarded instead of uploading stale geometry.
    @Volatile
    private var buildGeneration = 0
    private var lastBuildCameraPos: Vec3 = Vec3.ZERO

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuchematica-schematic-builder").apply { isDaemon = true }
    }

    private fun buildVertexBufferAsync(renderBase: Vec3, rotateDeg: Float, rotateAxis: Vec3, camPos: Vec3) {
        if (isBuilt || isBuilding || buildFailed) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        isBuilding = true

        // Snapshot everything mutable on the main thread; the maps inside SchematicCache
        // are replaced wholesale (never mutated in place), so holding the reference is safe.
        val generation = buildGeneration
        val cachedBlocks = SchematicHolder.renderingBlocks
        lastBuildCameraPos = camPos

        // Camera position in schematic-local space, for translucency sorting.
        // Draw transform is world = renderBase + R_y(deg) * (local + rotateAxis),
        // so local = R_y(-deg) * (cam - renderBase) - rotateAxis.
        val rad = Math.toRadians(-rotateDeg.toDouble())
        val relX = camPos.x - renderBase.x
        val relZ = camPos.z - renderBase.z
        val sortX = relX * cos(rad) + relZ * sin(rad) - rotateAxis.x
        val sortY = camPos.y - renderBase.y - rotateAxis.y
        val sortZ = -relX * sin(rad) + relZ * cos(rad) - rotateAxis.z

        buildExecutor.submit {
            try {
                val blockColors = mc.blockColors
                val poseStack = PoseStack()
                val blockRenderer = mc.blockRenderer
                val randomSource = Random(0)
                val overlay = OverlayTexture.NO_OVERLAY

                val solidBuilder = BufferBuilder(262144)
                val translucentBuilder = BufferBuilder(262144)

                solidBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
                translucentBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)

                for ((pos, blockState) in cachedBlocks.blocks) {
                    // Sample light at the world position where the block will actually appear.
                    // If rotation changes later the baked light can lag one rebuild behind,
                    // which is acceptable for a preview.
                    val rotated = SchematicRenderManager.rotate(pos)
                    val worldPos = BlockPos(
                        rotated.x + renderBase.x.toInt(),
                        rotated.y + renderBase.y.toInt(),
                        rotated.z + renderBase.z.toInt()
                    )
                    val skyLight = level.getBrightness(LightLayer.SKY, worldPos)
                    val blockLight = level.getBrightness(LightLayer.BLOCK, worldPos)
                    val packedLight = LightTexture.pack(skyLight, blockLight)

                    val fluidState = blockState.fluidState
                    if (!fluidState.isEmpty) {
                        // A fluid rendered at its real (subtle) color is nearly invisible as a
                        // translucent ghost, so force a recognizable per-fluid tint instead.
                        val fluidColor = if (fluidState.`is`(FluidTags.LAVA)) {
                            floatArrayOf(1.0f, 0.35f, 0.1f, 0.85f)
                        } else {
                            floatArrayOf(0.15f, 0.35f, 1.0f, 0.85f)
                        }
                        poseStack.pushPose()
                        try {
                            poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                            val translated = VertexConsumerWithPose(translucentBuilder, pos, poseStack, fluidColor)

                            blockRenderer.renderLiquid(
                                pos,
                                level,
                                translated,
                                blockState,
                                fluidState
                            )
                        } finally {
                            poseStack.popPose()
                        }
                        continue
                    }

                    val model = blockRenderer.blockModelShaper.getBlockModel(blockState)
                    poseStack.pushPose()
                    try {
                        poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                        val pose = poseStack.last()

                        val renderType = ItemBlockRenderTypes.getRenderType(blockState, false)
                        val buffer = when (renderType) {
                            RenderType.translucent(), RenderType.cutout() -> translucentBuilder
                            else -> solidBuilder
                        }

                        for (direction in Direction.values()) {
                            val neighborPos = pos.relative(direction)
                            val neighborIsSolid =
                                cachedBlocks.blocks[neighborPos]?.isSolidRender(level, neighborPos) ?: false
                            if (cachedBlocks.blocks.containsKey(neighborPos) && neighborIsSolid) continue

                            val quads = model.getQuads(blockState, direction, randomSource, EmptyModelData.INSTANCE)
                            for (quad in quads) {
                                val tintIndex = quad.tintIndex
                                val color = if (quad.isTinted && tintIndex >= 0) {
                                    blockColors.getColor(blockState, level, pos, tintIndex)
                                } else -1

                                val (r, g, b) = if (color != -1) {
                                    Triple(
                                        (color shr 16 and 0xFF) / 255.0f,
                                        (color shr 8 and 0xFF) / 255.0f,
                                        (color and 0xFF) / 255.0f
                                    )
                                } else {
                                    Triple(1f, 1f, 1f)
                                }

                                buffer.putBulkData(pose, quad, r, g, b, 1.0f, packedLight, overlay, true)
                            }
                        }
                        val nonSolidQuads = model.getQuads(blockState, null, randomSource, EmptyModelData.INSTANCE)
                        for (quad in nonSolidQuads) {
                            val tintIndex = quad.tintIndex
                            val color = if (quad.isTinted && tintIndex >= 0) {
                                blockColors.getColor(blockState, level, pos, tintIndex)
                            } else {
                                -1
                            }

                            val (r, g, b) = if (color != -1) {
                                Triple(
                                    (color shr 16 and 0xFF) / 255.0f,
                                    (color shr 8 and 0xFF) / 255.0f,
                                    (color and 0xFF) / 255.0f
                                )
                            } else {
                                Triple(1f, 1f, 1f)
                            }

                            buffer.putBulkData(pose, quad, r, g, b, 1.0f, packedLight, overlay, true)
                        }
                    } finally {
                        poseStack.popPose()
                    }
                }

                solidBuilder.end()
                // BufferBuilder snapshots quad centers when this is called, so all translucent
                // vertices must already exist before setting the sort origin.
                translucentBuilder.setQuadSortOrigin(sortX.toFloat(), sortY.toFloat(), sortZ.toFloat())
                translucentBuilder.end()

                mc.execute {
                    var newSolidBuffer: VertexBuffer? = null
                    var newTranslucentBuffer: VertexBuffer? = null
                    var swapped = false
                    try {
                        if (generation != buildGeneration) {
                            // A newer initialize() superseded this build; drop it and let
                            // the next frame rebuild from current data.
                            return@execute
                        }

                        newSolidBuffer = uploadVertexBuffer(solidBuilder)
                        newTranslucentBuffer = uploadVertexBuffer(translucentBuilder)

                        val oldSolidBuffer = solidBuffer
                        val oldTranslucentBuffer = translucentBuffer
                        solidBuffer = newSolidBuffer
                        translucentBuffer = newTranslucentBuffer
                        newSolidBuffer = null
                        newTranslucentBuffer = null

                        isBuilt = true
                        swapped = true
                        closeVertexBuffers(oldSolidBuffer, oldTranslucentBuffer)
                    } catch (e: Exception) {
                        LogUtils.getLogger().error("failed to upload schematic vertex buffers", e)
                        if (!swapped && generation == buildGeneration) {
                            buildFailed = true
                        }
                    } finally {
                        try {
                            closeVertexBuffers(newSolidBuffer, newTranslucentBuffer)
                        } finally {
                            isBuilding = false
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtils.getLogger().error("failed to build schematic vertex buffers", e)
                // A deterministic model failure must not retry on every camera-distance rebuild.
                // Keep this separate from isBuilt so block entities can still render without VBOs.
                if (generation == buildGeneration) {
                    buildFailed = true
                }
                isBuilding = false
            }
        }
    }

    fun render(offset: Vec3, rotate: Float, rotateAxis: Vec3, event: RenderLevelStageEvent) {
        val opacity = RenderSettingHolder.renderSettings.opacity
        // Opacity zero hides all schematic content without leaving invisible depth writes.
        // Selection and missing/wrong overlays are separate renderers and remain visible.
        if (opacity <= 0f) return

        val camPos = event.camera.position

        // Translucency sorting is baked at build time; rebuild when the camera has moved
        // far enough for the sort order to go stale. Old buffers keep drawing meanwhile.
        if (isBuilt && !buildFailed && camPos.distanceToSqr(lastBuildCameraPos) > RESORT_DISTANCE_SQ) {
            isBuilt = false
        }
        if (!isBuilt && !buildFailed) buildVertexBufferAsync(offset, rotate, rotateAxis, camPos)

        val mc = Minecraft.getInstance()
        val poseStack = event.poseStack
        val projection = event.projectionMatrix
        val cachedBlocks = SchematicHolder.renderingBlocks

        poseStack.pushPose()
        try {
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
            poseStack.translate(offset.x, offset.y, offset.z)

            poseStack.mulPose(
                YP.rotationDegrees(rotate),
            )
            poseStack.translate(rotateAxis.x, rotateAxis.y, rotateAxis.z)

            solidBuffer?.let {
                drawBuffer(it, NuchematicaRenderTypes.GHOST_BLOCKS, poseStack, projection, opacity)
            }

            translucentBuffer?.let {
                drawBuffer(it, NuchematicaRenderTypes.GHOST_TRANSLUCENT, poseStack, projection, opacity)
            }

            // Reset before block entities render with their own render types.
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

            val dispatcher = mc.blockEntityRenderDispatcher
            val bufferSource: MultiBufferSource
            val usesOpacityBuffer = opacity < 1f
            if (usesOpacityBuffer) {
                blockEntityOpacityBufferSource.begin(opacity)
                bufferSource = blockEntityOpacityBufferSource
            } else {
                bufferSource = mc.renderBuffers().bufferSource()
            }

            try {
                NuchematicaRenderTypes.setupGhostLayering()
                try {
                    for ((pos, blockEntity) in cachedBlocks.blockEntities) {
                        val render = dispatcher.getRenderer(blockEntity) ?: continue
                        if (blockEntity is PistonMovingBlockEntity) {
                            continue
                        }

                        blockEntity.setLevel(mc.level)
                        poseStack.pushPose()
                        try {
                            poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                            render.render(
                                blockEntity,
                                0.0f,
                                poseStack,
                                bufferSource,
                                0x0f000f0,
                                OverlayTexture.NO_OVERLAY,
                            )
                        } finally {
                            poseStack.popPose()
                        }
                    }
                } finally {
                    try {
                        if (usesOpacityBuffer) {
                            blockEntityOpacityBufferSource.endBatch()
                        } else {
                            (bufferSource as MultiBufferSource.BufferSource).endBatch()
                        }
                    } finally {
                        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                    }
                }
            } finally {
                NuchematicaRenderTypes.clearGhostLayering()
            }
        } finally {
            try {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            } finally {
                poseStack.popPose()
            }
        }
    }

    private fun drawBuffer(
        buffer: VertexBuffer,
        renderType: RenderType,
        poseStack: PoseStack,
        projection: Matrix4f,
        opacity: Float,
    ) {
        try {
            renderType.setupRenderState()
            // RenderType setup/clear can reset the shader color, so apply opacity after setup.
            RenderSystem.setShaderColor(1f, 1f, 1f, opacity)
            try {
                buffer.bind()
                buffer.drawWithShader(poseStack.last().pose(), projection, RenderSystem.getShader())
            } finally {
                VertexBuffer.unbind()
            }
        } finally {
            renderType.clearRenderState()
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

    private fun closeVertexBuffers(first: VertexBuffer?, second: VertexBuffer?) {
        try {
            first?.close()
        } finally {
            second?.close()
        }
    }

    fun initialize() {
        buildGeneration++
        isBuilt = false
        buildFailed = false
    }

    private companion object {
        // Rebuild (and re-sort translucency) when the camera moves more than 16 blocks.
        private const val RESORT_DISTANCE_SQ = 256.0
    }
}
