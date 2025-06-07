package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.blaze3d.vertex.VertexBuffer.unbind
import com.mojang.logging.LogUtils
import com.mojang.math.Vector3f
import com.mojang.math.Vector3f.YP
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.BaseRender
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public class MissingBlockRender {
    private var isBuilt = false
    private var isBuilding = false

    private val buildExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var missingBlockBuffer: VertexBuffer? = null

    public fun initialize() {
        isBuilt = false
        Minecraft.getInstance().execute {
            missingBlockBuffer?.close()
        }
    }

    public fun render(offset: Vec3, rotate: Float, rotateAxis: Vec3, event: RenderLevelStageEvent) {
        if (!isBuilt) buildMissingBlockVertexBufferAsync(offset)
        if (!isBuilt) return
        val camPos = event.camera.position
        val poseStack = event.poseStack
        val projection = event.projectionMatrix

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.disableCull() // ← カリングを無効化
        RenderSystem.enablePolygonOffset()
        RenderSystem.polygonOffset(-1f, -10f) // 手前にずらす
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }

        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
        poseStack.translate(offset.x, offset.y, offset.z)
        poseStack.mulPose(
            YP.rotationDegrees(rotate),
        )
        poseStack.translate(rotateAxis.x, rotateAxis.y, rotateAxis.z)
        missingBlockBuffer?.let {
            it.bind()
            it.drawWithShader(poseStack.last().pose(), projection, GameRenderer.getPositionColorShader())
            VertexBuffer.unbind()
        }
        poseStack.popPose()

        RenderSystem.disablePolygonOffset()
        RenderSystem.enableCull() // ← カリングを戻す
        RenderSystem.enableDepthTest()
        RenderSystem.disableBlend()
    }

    private fun buildMissingBlockVertexBufferAsync(offset: Vec3) {
        if (isBuilt || isBuilding) return
        isBuilding = true
        val dummy = SchematicHolder.schematicCache

        buildExecutor.submit {
            LogUtils.getLogger().info("buildMissingBlockVertexBufferAsync")
            val missingBlockBuilder = BufferBuilder(262144)
            missingBlockBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
            val poseStack = PoseStack()

            LogUtils.getLogger().info("${MissingBlockHolder.blockPos.size}, ${MissingBlockHolder.airPos.size}")
            MissingBlockHolder.blockPos.forEach { pos ->
                val cubeColor = Vector3f(1.0f, 0.0f, 0.0f) // red

                // 各面に隣があるかを調べる
                val visibleFaces = mutableSetOf<Direction>()
                for (dir in Direction.values()) {
                    val neighborPos = pos.relative(dir)
                    if (!dummy.blocks.containsKey(neighborPos)) {
                        visibleFaces.add(dir)
                    }
                }
                poseStack.pushPose()
                poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                if (visibleFaces.isNotEmpty()) {
                    BaseRender.drawVisibleFacesCubeWithBuffer(
                        missingBlockBuilder,
                        poseStack,
                        Vec3(0.0, 0.0, 0.0),
                        cubeColor,
                        visibleFaces
                    )
                }
                poseStack.popPose()
            }

            MissingBlockHolder.airPos.forEach { pos ->
                val cubeColor = Vector3f(0.0f, 1.0f, 0.0f) // red

                // 各面に隣があるかを調べる
                val visibleFaces = mutableSetOf<Direction>()
                for (dir in Direction.values()) {
                    val neighborPos = pos.relative(dir)
                    if (!dummy.blocks.containsKey(neighborPos)) {
                        visibleFaces.add(dir)
                    }
                }
                poseStack.pushPose()
                poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                if (visibleFaces.isNotEmpty()) {
                    BaseRender.drawVisibleFacesCubeWithBuffer(
                        missingBlockBuilder,
                        poseStack,
                        Vec3(0.0, 0.0, 0.0),
                        cubeColor,
                        visibleFaces
                    )
                }
                poseStack.popPose()
            }

            missingBlockBuilder.end()

            Minecraft.getInstance().execute {
                missingBlockBuffer?.close()

                missingBlockBuffer = VertexBuffer().apply {
                    bind()
                    upload(missingBlockBuilder)
                    unbind()
                }
                isBuilt = true
                isBuilding = false
            }
        }
    }
}
