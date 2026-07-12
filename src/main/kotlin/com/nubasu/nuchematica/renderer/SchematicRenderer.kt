package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.logging.LogUtils
import com.mojang.math.Vector3f.YP
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.EmptyModelData
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SchematicRenderer {

    private var solidBuffer: VertexBuffer? = null
    private var translucentBuffer: VertexBuffer? = null

    @Volatile
    private var isBuilt = false
    @Volatile
    private var isBuilding = false
    // Incremented on every initialize(); an in-flight build whose generation no longer
    // matches is discarded instead of uploading stale geometry.
    @Volatile
    private var buildGeneration = 0

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuchematica-schematic-builder").apply { isDaemon = true }
    }

    private fun buildVertexBufferAsync() {
        if (isBuilt || isBuilding) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        isBuilding = true

        // Snapshot everything mutable on the main thread; the maps inside SchematicCache
        // are replaced wholesale (never mutated in place), so holding the reference is safe.
        val generation = buildGeneration
        val cachedBlocks = SchematicHolder.renderingBlocks
        val alpha = RenderSettingHolder.renderSettings.opacity
        val renderBase = SchematicRenderManager.getRenderBase()

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
                        poseStack.pushPose()
                        poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                        val translated = VertexConsumerWithPose(translucentBuilder, pos, poseStack)

                        blockRenderer.renderLiquid(
                            pos,
                            level,
                            translated,
                            blockState,
                            fluidState
                        )
                        poseStack.popPose()
                        continue
                    }

                    val model = blockRenderer.blockModelShaper.getBlockModel(blockState)
                    poseStack.pushPose()
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

                            buffer.putBulkData(pose, quad, r, g, b, alpha, packedLight, overlay, true)
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

                        buffer.putBulkData(pose, quad, r, g, b, alpha, packedLight, overlay, true)
                    }

                    poseStack.popPose()
                }

                solidBuilder.end()
                translucentBuilder.end()

                mc.execute {
                    // finally guarantees isBuilding is released even if the upload throws.
                    try {
                        if (generation != buildGeneration) {
                            // A newer initialize() superseded this build; drop it and let
                            // the next frame rebuild from current data.
                            return@execute
                        }
                        solidBuffer?.close()
                        translucentBuffer?.close()

                        solidBuffer = VertexBuffer().apply {
                            bind()
                            upload(solidBuilder)
                            VertexBuffer.unbind()
                        }
                        translucentBuffer = VertexBuffer().apply {
                            bind()
                            upload(translucentBuilder)
                            VertexBuffer.unbind()
                        }

                        isBuilt = true
                    } finally {
                        isBuilding = false
                    }
                }
            } catch (e: Exception) {
                LogUtils.getLogger().error("failed to build schematic vertex buffers", e)
                // Mark as built so the render loop does not retry (and log-spam) every
                // frame; the next initialize() resets the state and tries again.
                isBuilt = true
                isBuilding = false
            }
        }
    }

    fun render(offset: Vec3, rotate: Float, rotateAxis: Vec3, event: RenderLevelStageEvent) {
        if (!isBuilt) buildVertexBufferAsync()
        if (!isBuilt) return

        val mc = Minecraft.getInstance()
        val camPos = event.camera.position
        val poseStack = event.poseStack
        val projection = event.projectionMatrix
        val cachedBlocks = SchematicHolder.renderingBlocks

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enablePolygonOffset()
        RenderSystem.polygonOffset(0.5f, 5f)
        RenderSystem.setShader { GameRenderer.getRendertypeTranslucentShader() }
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS)
        mc.gameRenderer.lightTexture().turnOnLightLayer()

        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
        poseStack.translate(offset.x, offset.y, offset.z)

        poseStack.mulPose(
            YP.rotationDegrees(rotate),
        )
        poseStack.translate(rotateAxis.x, rotateAxis.y, rotateAxis.z)

        solidBuffer?.let {
            it.bind()
            it.drawWithShader(poseStack.last().pose(), projection, GameRenderer.getRendertypeTranslucentNoCrumblingShader())
            VertexBuffer.unbind()
        }

        translucentBuffer?.let {
            it.bind()
            it.drawWithShader(poseStack.last().pose(), projection, GameRenderer.getRendertypeTranslucentNoCrumblingShader())
            VertexBuffer.unbind()
        }

        val dispatcher = mc.blockEntityRenderDispatcher
        val bufferSource = mc.renderBuffers().bufferSource()

        for ((pos, blockEntity) in cachedBlocks.blockEntities) {
            val render = dispatcher.getRenderer(blockEntity) ?: continue
            if (blockEntity is PistonMovingBlockEntity) {
                continue
            }

            blockEntity.setLevel(mc.level)
            poseStack.pushPose()
            poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            render.render(blockEntity, 0.0f, poseStack, bufferSource, 0x0f000f0, OverlayTexture.NO_OVERLAY)
            poseStack.popPose()
        }

        bufferSource.endBatch()
        RenderSystem.disableBlend()
        RenderSystem.disablePolygonOffset()
        mc.gameRenderer.lightTexture().turnOffLightLayer()
        poseStack.popPose()
    }

    fun initialize() {
        buildGeneration++
        isBuilt = false
        Minecraft.getInstance().execute {
            solidBuffer?.close()
            solidBuffer = null
            translucentBuffer?.close()
            translucentBuffer = null
        }
    }
}
