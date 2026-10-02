package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Vector3f.YP
import com.nubasu.nuchematica.renderer.section.MainThreadSectionMeshingService
import com.nubasu.nuchematica.renderer.section.RenderTransform
import com.nubasu.nuchematica.renderer.section.SchematicContentSnapshot
import com.nubasu.nuchematica.renderer.section.SectionedSchematicMesh
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity
import net.minecraft.world.phys.AABB

internal data class SchematicRenderSnapshot(
    internal val level: ClientLevel,
    internal val content: SchematicContentSnapshot,
    internal val blockEntities: Map<BlockPos, BlockEntity>,
    internal val suppressedPositions: Set<BlockPos>,
    internal val transform: RenderTransform,
    internal val resourceEpoch: Long,
)

internal class SectionedSchematicRenderer(
    private val mesh: SectionedSchematicMesh = SectionedSchematicMesh(),
    private val minecraft: () -> Minecraft = Minecraft::getInstance,
) : AutoCloseable {
    private val blockEntityPass: SchematicBlockEntityPass = SchematicBlockEntityPass(minecraft)
    private var snapshot: SchematicRenderSnapshot? = null

    internal fun attachLevel(level: ClientLevel): Unit {
        mesh.worldLoaded(level)
    }

    internal fun detachLevel(level: ClientLevel): Unit {
        if (snapshot?.level === level) {
            snapshot = null
        }
        mesh.worldUnloaded(level)
    }

    internal fun replaceContent(snapshot: SchematicRenderSnapshot): Unit {
        this.snapshot = snapshot
        val service = MainThreadSectionMeshingService.create(
            blockRenderer = minecraft().blockRenderer,
            content = snapshot.content,
            resourceEpoch = snapshot.resourceEpoch,
        )
        mesh.replaceContent(
            level = snapshot.level,
            content = snapshot.content,
            meshingService = service,
            transform = snapshot.transform,
            suppressed = snapshot.suppressedPositions,
        )
    }

    internal fun updateTransform(snapshot: SchematicRenderSnapshot): Unit {
        check(this.snapshot?.content === snapshot.content) {
            "content changes must be applied through replaceContent"
        }
        this.snapshot = snapshot
        mesh.updateTransform(snapshot.level, snapshot.transform)
    }

    internal fun setBlockSuppressed(localPos: BlockPos, suppressed: Boolean): Boolean {
        return mesh.setBlockSuppressed(localPos, suppressed)
    }

    internal fun render(context: LevelRenderContext, opacity: Float): Unit {
        val current = snapshot ?: return
        mesh.render(current.transform, context, opacity)
        blockEntityPass.render(current, context, opacity)
    }

    internal fun clear(): Unit {
        snapshot = null
        mesh.clearContent()
    }

    override fun close(): Unit {
        snapshot = null
        mesh.close()
    }
}

internal class SchematicBlockEntityPass(
    private val minecraft: () -> Minecraft = Minecraft::getInstance,
) {
    private val opacityBufferSource: BlockEntityOpacityBufferSource = BlockEntityOpacityBufferSource()

    internal fun render(
        snapshot: SchematicRenderSnapshot,
        context: LevelRenderContext,
        opacity: Float,
    ): Unit {
        if (opacity <= 0.0f || snapshot.blockEntities.isEmpty()) return

        val mc = minecraft()
        val dispatcher = mc.blockEntityRenderDispatcher
        val cameraWorld = context.camera.position
        val cameraLocal = snapshot.transform.worldPointToLocal(cameraWorld)
        val poseStack = context.poseStack
        val usesOpacityBuffer = opacity < 1.0f
        val bufferSource: MultiBufferSource = if (usesOpacityBuffer) {
            opacityBufferSource.begin(opacity)
            opacityBufferSource
        } else {
            mc.renderBuffers().bufferSource()
        }

        poseStack.pushPose()
        try {
            poseStack.translate(-cameraWorld.x, -cameraWorld.y, -cameraWorld.z)
            poseStack.translate(
                snapshot.transform.renderBase.x,
                snapshot.transform.renderBase.y,
                snapshot.transform.renderBase.z,
            )
            poseStack.mulPose(YP.rotationDegrees(snapshot.transform.rotateDeg))
            poseStack.translate(
                snapshot.transform.rotateAxis.x,
                snapshot.transform.rotateAxis.y,
                snapshot.transform.rotateAxis.z,
            )

            try {
                NuchematicaRenderTypes.setupGhostLayering()
                try {
                    for ((pos, blockEntity) in snapshot.blockEntities) {
                        if (blockEntity is PistonMovingBlockEntity) continue
                        blockEntity.setLevel(snapshot.level)
                        val renderer = dispatcher.getRenderer(blockEntity) ?: continue
                        if (!renderer.shouldRender(blockEntity, cameraLocal)) continue
                        if (
                            !renderer.shouldRenderOffScreen(blockEntity) &&
                            !context.frustum.isVisible(AABB(snapshot.transform.localBlockToWorld(pos)))
                        ) {
                            continue
                        }

                        poseStack.pushPose()
                        try {
                            poseStack.translate(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                            renderer.render(
                                blockEntity,
                                0.0f,
                                poseStack,
                                bufferSource,
                                FULL_BRIGHT_LIGHT,
                                OverlayTexture.NO_OVERLAY,
                            )
                        } finally {
                            poseStack.popPose()
                        }
                    }
                } finally {
                    try {
                        if (usesOpacityBuffer) {
                            opacityBufferSource.endBatch()
                        } else {
                            (bufferSource as MultiBufferSource.BufferSource).endBatch()
                        }
                    } finally {
                        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
                    }
                }
            } finally {
                NuchematicaRenderTypes.clearGhostLayering()
            }
        } finally {
            try {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            } finally {
                poseStack.popPose()
            }
        }
    }

    private companion object {
        private const val FULL_BRIGHT_LIGHT: Int = 0x0f000f0
    }
}
