package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.blaze3d.vertex.VertexBuffer.unbind
import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.ModelData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SchematicRenderer {

    private var solidBuffer: VertexBuffer? = null
    private var translucentBuffer: VertexBuffer? = null

    private var isBuilt = false
    private var isBuilding = false

    private var renderPos: Vec3 = Vec3(0.0, 0.0, 0.0)
    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val mc = Minecraft.getInstance()

    fun setRenderPosition(pos: Vec3) {
        renderPos = pos
        isBuilt = false
    }

    private fun buildVertexBufferAsync() {
        if (isBuilt || isBuilding) return
        isBuilding = true
        val level = mc.level!!

        buildExecutor.submit {
            LogUtils.getLogger().info("buildVertexBufferAsync")

            val cachedBlocks = SchematicHolder.schematicCache
            val cameraPos = mc.gameRenderer.mainCamera.position
            val blockColors = mc.blockColors
            val poseStack = PoseStack()

            val blockRenderer = mc.blockRenderer
            val randomSource = RandomSource.create(0)
            val overlay = OverlayTexture.NO_OVERLAY

            val sortedBlocks = cachedBlocks.blocks.entries.sortedByDescending {
                val pos = it.key
                val dx = pos.x - cameraPos.x
                val dy = pos.y - cameraPos.y
                val dz = pos.z - cameraPos.z
                dx * dx + dy * dy + dz * dz
            }
            LogUtils.getLogger().info("${sortedBlocks.size}")

            val solidBuilder = BufferBuilder(262144)
            val translucentBuilder = BufferBuilder(262144)

            solidBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
            translucentBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)

            for (block in sortedBlocks) {
                val pos = block.key
                val blockState = block.value

                val skyLight = level.getBrightness(LightLayer.SKY, pos)
                val blockLight = level.getBrightness(LightLayer.BLOCK, pos)
                val packedLight = LightTexture.pack(skyLight, blockLight) // ← 修正ここ

                val fluidState = blockState.fluidState
                if (!fluidState.isEmpty) {
                    poseStack.pushPose()
                    poseStack.translate(renderPos.x, renderPos.y, renderPos.z)
                    poseStack.translate(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
                    val translated = VertexConsumerWithPose(translucentBuilder, pos, poseStack)

                    mc.blockRenderer.renderLiquid(
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
                poseStack.translate(renderPos.x, renderPos.y, renderPos.z)
                poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                val pose = poseStack.last()

//                val renderType = RenderTypeHelper.getFallbackItemRenderType(blockState.block.asItem().defaultInstance, model, false)
                val renderTypes = model.getRenderTypes(blockState, randomSource, ModelData.EMPTY)
                for (renderType in renderTypes) {
                    val buffer = when (renderType) {
                        RenderType.translucent(), RenderType.cutout() -> translucentBuilder // , RenderType.cutoutMipped()
                        else -> solidBuilder
                    }

                    for (direction in Direction.values()) {
                        val neighborPos = pos.relative(direction)
                        val neighborIsSolid =
                            cachedBlocks.blocks[neighborPos]?.isSolidRender(level, neighborPos) ?: false
                        if (cachedBlocks.blocks.containsKey(neighborPos) && neighborIsSolid) continue

                        val quads = model.getQuads(blockState, direction, randomSource, ModelData.EMPTY, renderType)
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

                            buffer.putBulkData(pose, quad, r, g, b, 0.7f, packedLight, overlay, true)
                        }
                    }
                }
                for (renderType in renderTypes) {
                    val buffer = when (renderType) {
                        RenderType.translucent(), RenderType.cutout() -> translucentBuilder // , RenderType.cutoutMipped()
                        else -> solidBuilder
                    }
                    val nonSolidQuads = model.getQuads(blockState, null, randomSource, ModelData.EMPTY, renderType)
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

                        buffer.putBulkData(pose, quad, r, g, b, 0.7f, packedLight, overlay, true)
                    }
                }

                poseStack.popPose()
            }

            val solidBuilt = solidBuilder.end()
            val translucentBuilt = translucentBuilder.end()

            Minecraft.getInstance().execute {
                solidBuffer?.close()
                translucentBuffer?.close()

                solidBuffer = VertexBuffer().apply {
                    bind()
                    upload(solidBuilt)
                    unbind()
                }
                translucentBuffer = VertexBuffer().apply {
                    bind()
                    upload(translucentBuilt)
                    unbind()
                }

                isBuilt = true
                isBuilding = false
            }
        }
    }

    fun render(event: RenderLevelStageEvent) {
        if (!isBuilt) buildVertexBufferAsync()
        if (!isBuilt) return

        val mc = Minecraft.getInstance()
        val camPos = event.camera.position
        val poseStack = event.poseStack
        val projection = event.projectionMatrix
        val cachedBlocks = SchematicHolder.schematicCache

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShader { GameRenderer.getRendertypeTranslucentShader() }
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS)
        mc.gameRenderer.lightTexture().turnOnLightLayer()

        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)

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

        val dispatcher = Minecraft.getInstance().blockEntityRenderDispatcher
        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()

        for ((pos, blockEntity) in cachedBlocks.blockEntities) {
            if (blockEntity == null) continue
            val render = dispatcher.getRenderer(blockEntity)
            if (render == null) continue
            if (blockEntity is PistonMovingBlockEntity) {
                continue
            }

            blockEntity.setLevel(mc.level)
            poseStack.pushPose()
            poseStack.translate(renderPos.x, renderPos.y, renderPos.z)
            poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            render.render(blockEntity, 0.0f, poseStack, bufferSource, 0x0f000f0, OverlayTexture.NO_OVERLAY)
            poseStack.popPose()
        }

        bufferSource.endBatch()
        RenderSystem.disableBlend()
        mc.gameRenderer.lightTexture().turnOffLightLayer()
        poseStack.popPose()
    }

    fun initialize() {
        setRenderPosition(
            Minecraft.getInstance().gameRenderer.mainCamera.position
        )
    }
}