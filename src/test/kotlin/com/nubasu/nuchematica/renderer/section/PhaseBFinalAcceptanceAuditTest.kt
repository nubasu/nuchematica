package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Matrix4f
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase B final acceptance criterion 2: after repeated content replacement the
 * resident GPU handle count, byte accounting, and live job counters must return
 * to the post-initial-build baseline, and every superseded handle must be closed
 * exactly once. Written by the reviewer independently of the implementation work.
 */
public class PhaseBFinalAcceptanceAuditTest {

    @Test
    public fun twentyContentReplacesReturnVboAndJobCountsToBaseline(): Unit {
        val content = SchematicContentSnapshot.copyOf(
            mapOf(
                BlockPos.ZERO to Blocks.GLASS.defaultBlockState(),
                BlockPos(16, 0, 0) to Blocks.GLASS.defaultBlockState(),
            ),
            Blocks.AIR.defaultBlockState(),
        )
        val level: ClientLevel = mockk(relaxed = true)
        val service = QuadMeshingService(resourceEpoch = 1L)
        val backend = FakeGpuBackend()
        val counter = AtomicLong()
        val mesh = SectionedSchematicMesh(
            nanoTime = { counter.addAndGet(1_000L) },
            gpuBackend = backend,
        )
        val event = RenderLevelStageEvent(
            RenderLevelStageEvent.Stage.AFTER_PARTICLES,
            null,
            PoseStack(),
            identityMatrix(),
            0,
            0.0f,
            AuditCamera(),
            AuditFrustum(),
        )

        try {
            mesh.replaceContent(level, content, service, TRANSFORM)
            renderUntilIdle(mesh, event, backend, expectedTotalHandles = 2)
            val baselineResident = mesh.residentSnapshot()
            val baselineHandles = backend.handles.size
            assertEquals(2, baselineResident.handleCount)

            repeat(20) { cycle ->
                mesh.replaceContent(level, content, service, TRANSFORM)
                renderUntilIdle(mesh, event, backend, expectedTotalHandles = baselineHandles + (cycle + 1) * 2)
            }

            val resident = mesh.residentSnapshot()
            val runtime = mesh.runtimeSnapshot()
            assertEquals(baselineResident.handleCount, resident.handleCount)
            assertEquals(baselineResident.bytes, resident.bytes)
            assertEquals(0, runtime.geometryJobs)
            assertEquals(0, runtime.sortJobs)
            assertEquals(0, runtime.liveCpuBuffers)
            assertEquals(0, runtime.completionQueue)
            assertEquals(baselineHandles + 20 * 2, backend.handles.size)

            val superseded = backend.handles.dropLast(2)
            val resident2 = backend.handles.takeLast(2)
            assertTrue(superseded.all { it.closeCount == 1 }) {
                "every superseded handle must be closed exactly once"
            }
            assertTrue(resident2.all { it.closeCount == 0 }) {
                "resident handles must remain open"
            }
        } finally {
            mesh.close()
        }
    }

    private fun renderUntilIdle(
        mesh: SectionedSchematicMesh,
        event: RenderLevelStageEvent,
        backend: FakeGpuBackend,
        expectedTotalHandles: Int,
    ): Unit {
        repeat(MAX_IDLE_FRAMES) {
            mesh.render(TRANSFORM, event, opacity = 0.5f)
            val runtime = mesh.runtimeSnapshot()
            if (
                runtime.geometryJobs == 0 &&
                runtime.sortJobs == 0 &&
                runtime.completionQueue == 0 &&
                backend.handles.size == expectedTotalHandles
            ) {
                return
            }
        }
        error("mesh did not settle at $expectedTotalHandles handles within $MAX_IDLE_FRAMES frames")
    }

    private class QuadMeshingService(
        override val resourceEpoch: Long,
    ) : SectionMeshingService {
        override fun passesFor(blockState: BlockState): List<SectionLayerPass> {
            return listOf(SectionLayerPass(SectionSourceLayer.TRANSLUCENT, true, false))
        }

        override fun renderLayer(
            pass: SectionLayerPass,
            pos: BlockPos,
            blockState: BlockState,
            view: BlockAndTintGetter,
            target: VertexConsumer,
        ): SectionLayerRenderResult {
            emitQuad(target, pos)
            return SectionLayerRenderResult(blockRendered = true, fluidRendered = false)
        }
    }

    private class AuditGpuHandle : SectionGpuHandle {
        internal var closeCount: Int = 0
            private set

        override fun close(): Unit {
            closeCount++
        }
    }

    private class FakeGpuBackend : SectionGpuBackend {
        internal val handles: MutableList<AuditGpuHandle> = mutableListOf()

        override fun upload(builder: BufferBuilder): SectionGpuHandle {
            builder.popNextBuffer()
            return AuditGpuHandle().also(handles::add)
        }

        override fun uploadSort(handle: SectionGpuHandle, builder: BufferBuilder): Unit {
            builder.popNextBuffer()
        }

        override fun drawLayer(
            handles: List<SectionGpuHandle>,
            renderType: RenderType,
            poseStack: PoseStack,
            projection: Matrix4f,
            opacity: Float,
        ): Unit = Unit
    }

    private class AuditCamera : Camera() {
        init {
            setPosition(Vec3.ZERO)
        }
    }

    private class AuditFrustum : Frustum(identityMatrix(), identityMatrix()) {
        override fun isVisible(box: net.minecraft.world.phys.AABB): Boolean = true
    }

    public companion object {
        private val TRANSFORM = RenderTransform(Vec3.ZERO, 0.0f, Vec3.ZERO, 1L)
        private const val MAX_IDLE_FRAMES = 64

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private fun identityMatrix(): Matrix4f {
            return Matrix4f().apply(Matrix4f::setIdentity)
        }

        private fun emitQuad(target: VertexConsumer, pos: BlockPos): Unit {
            val x = pos.x.toDouble()
            val y = pos.y.toDouble()
            val z = pos.z.toDouble()
            emitVertex(target, x, y, z, 0.0f, 0.0f)
            emitVertex(target, x + 1.0, y, z, 1.0f, 0.0f)
            emitVertex(target, x + 1.0, y + 1.0, z, 1.0f, 1.0f)
            emitVertex(target, x, y + 1.0, z, 0.0f, 1.0f)
        }

        private fun emitVertex(
            target: VertexConsumer,
            x: Double,
            y: Double,
            z: Double,
            u: Float,
            v: Float,
        ): Unit {
            target.vertex(x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .uv2(15, 15)
                .normal(0.0f, 1.0f, 0.0f)
                .endVertex()
        }
    }
}
