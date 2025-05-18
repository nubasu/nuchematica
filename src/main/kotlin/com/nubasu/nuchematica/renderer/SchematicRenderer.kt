package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.blaze3d.vertex.VertexBuffer.unbind
import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.io.SchematicFileLoader
import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.ModelData
import net.minecraftforge.client.model.data.ModelDataManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object ModRenderTypes {

    val TRANSLUCENT_NO_DEPTH_WRITE: RenderType = RenderType.create(
        "schematic_translucent_no_depth_write",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        262144,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { GameRenderer.getRendertypeTranslucentShader() })
            .setTextureState(RenderStateShard.TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
            .setTransparencyState(RenderStateShard.TransparencyStateShard("translucent", {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
            }, {
                RenderSystem.disableBlend()
            }))
            .setOutputState(RenderStateShard.OutputStateShard("translucent_target", {
                RenderSystem.setShader(GameRenderer::getRendertypeTranslucentShader)
            }, {}))
            .setWriteMaskState(RenderStateShard.WriteMaskStateShard(true, true))  // Color only
            .setDepthTestState(RenderStateShard.DepthTestStateShard("lequal", 515)) // GL_LEQUAL
            .createCompositeState(false)
    )
}

class SchematicRenderer {

    private var solidBuffer: VertexBuffer? = null
    private var translucentBuffer: VertexBuffer? = null

    private var isBuilt = false
    private var isBuilding = false

    private var renderPos: Vec3 = Vec3(0.0, 0.0, 0.0)
    private var cachedBlocks: SchematicCache = SchematicCache(emptyMap(), emptyMap())

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun setRenderPosition(pos: Vec3) {
        renderPos = pos
        isBuilt = false
    }

    private fun buildVertexBufferAsync() {
        if (isBuilt || isBuilding) return
        isBuilding = true

        buildExecutor.submit {
            val mc = Minecraft.getInstance()
            val cameraPos = mc.gameRenderer.mainCamera.position
            val blockColors = mc.blockColors
            val poseStack = PoseStack()
            val level = mc.level
            val modelManager = ModelDataManager(level)
            val blockRenderer = mc.blockRenderer
            val randomSource = RandomSource.create(0)
            val overlay = OverlayTexture.NO_OVERLAY
            val packedLight = 0x00F000F0 // ← sky=15, block=15, 両方強制

            val sortedBlocks = cachedBlocks.blocks.entries.sortedByDescending {
                val pos = it.key
                val dx = pos.x - cameraPos.x
                val dy = pos.y - cameraPos.y
                val dz = pos.z - cameraPos.z
                dx * dx + dy * dy + dz * dz
            }

            val solidBuilder = BufferBuilder(262144)
            val translucentBuilder = BufferBuilder(262144)

            solidBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
            translucentBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)

            for (block in sortedBlocks) {
                val pos = block.key
                val blockState = block.value

                val fluidState = blockState.fluidState
                if (!fluidState.isEmpty) {
                    poseStack.pushPose()
                    poseStack.translate(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
                    val translated = VertexConsumerWithPose(translucentBuilder, pos, poseStack)

                    mc.blockRenderer.renderLiquid(
                        pos,
                        DummyWorld,
                        translated,
                        blockState,
                        fluidState
                    )
                    poseStack.popPose()
                    continue
                }

                val model = blockRenderer.blockModelShaper.getBlockModel(blockState)
                val modelData =
                    model.getModelData(DummyWorld, pos, blockState, modelManager.getAt(pos) ?: ModelData.EMPTY)
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
                            cachedBlocks.blocks[neighborPos]?.isSolidRender(DummyWorld, neighborPos) ?: false
                        if (cachedBlocks.blocks.containsKey(neighborPos) && neighborIsSolid) continue

                        val quads = model.getQuads(blockState, direction, randomSource, modelData, renderType)
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
                    val nonSolidQuads = model.getQuads(blockState, null, randomSource, modelData, renderType)
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

        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShader { GameRenderer.getRendertypeTranslucentShader() }
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS)
        mc.gameRenderer.lightTexture().turnOnLightLayer()

        solidBuffer?.let {
            it.bind()
            it.drawWithShader(poseStack.last().pose(), projection, GameRenderer.getRendertypeTranslucentShader())
            VertexBuffer.unbind()
        }

        translucentBuffer?.let {
            it.bind()
            it.drawWithShader(poseStack.last().pose(), projection, GameRenderer.getRendertypeTranslucentShader())
            VertexBuffer.unbind()
        }

        val dispatcher = Minecraft.getInstance().blockEntityRenderDispatcher
        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()

        for ((pos, blockEntity) in cachedBlocks.blockEntities) {
            if (blockEntity == null) continue
            blockEntity.setLevel(mc.level)
            poseStack.pushPose()
            poseStack.translate((pos.x).toDouble() - camPos.x, (pos.y).toDouble() - camPos.y, (pos.z).toDouble() - camPos.z)
            dispatcher.render(blockEntity, 0.0f, poseStack, bufferSource)
            poseStack.popPose()
        }

        bufferSource.endBatch()
        RenderSystem.disableBlend()
        mc.gameRenderer.lightTexture().turnOffLightLayer()
        poseStack.popPose()
    }

    fun loadSchematic() {
        SchematicFileLoader.loadRenderBlocks("nature_town.schematic")
        cachedBlocks = SchematicHolder.schematicCache
        setRenderPosition(
            Minecraft.getInstance().gameRenderer.mainCamera.position
        )
    }
}
