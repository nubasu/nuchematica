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
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

public class SectionedSchematicMeshTest {

    @Test
    public fun opacityOnlyFramesDrawWithoutGeometryOrSortJobs(): Unit {
        val fixture = Fixture(singleSectionContent())
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)
            val afterBuild = fixture.mesh.metricsSnapshot()
            assertEquals(1L, afterBuild.captureAdmissions)
            assertEquals(0L, afterBuild.sortJobs)
            assertEquals(1L, afterBuild.uploads)

            repeat(20) { index ->
                fixture.mesh.render(
                    TRANSFORM,
                    fixture.event,
                    opacity = (index + 1) / 21.0f,
                )
            }

            val afterOpacity = fixture.mesh.metricsSnapshot()
            val runtime = fixture.mesh.runtimeSnapshot()
            assertEquals(afterBuild.captureAdmissions, afterOpacity.captureAdmissions)
            assertEquals(afterBuild.sortJobs, afterOpacity.sortJobs)
            assertEquals(afterBuild.uploads, afterOpacity.uploads)
            assertEquals(0, runtime.geometryJobs)
            assertEquals(0, runtime.sortJobs)
            assertEquals(20.0f / 21.0f, fixture.backend.draws.last().opacity)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun cameraThresholdRunsIndexOnlySortAndKeepsGeometryHandle(): Unit {
        val fixture = Fixture(singleSectionContent())
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)
            val handle = fixture.backend.handles.single()
            val captures = fixture.mesh.metricsSnapshot().captureAdmissions

            fixture.camera.moveTo(Vec3(9.0, 0.0, 0.0))
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)
            awaitCondition {
                fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)
                fixture.backend.sortUploads == 1
            }

            assertEquals(captures, fixture.mesh.metricsSnapshot().captureAdmissions)
            assertEquals(1L, fixture.mesh.metricsSnapshot().sortJobs)
            assertSame(handle, fixture.backend.handles.single())
            assertEquals(0, handle.closeCount)
            assertTrue(fixture.backend.lastSortWasIndexOnly)
            assertEquals(1, fixture.mesh.metricsSnapshot().maxInFlight)
            assertEquals(0, fixture.mesh.runtimeSnapshot().geometryJobs)
            assertEquals(0, fixture.mesh.runtimeSnapshot().sortJobs)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun rebuildsAndResortsReuseTheMeshOwnedBufferIdentities(): Unit {
        val fixture = Fixture(singleSectionContent())
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            val firstGeometryBuilder = fixture.backend.geometryBuilders.single()

            fixture.mesh.replaceContent(fixture.level, fixture.content, fixture.service, TRANSFORM)
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)

            assertEquals(2, fixture.backend.geometryBuilders.size)
            assertSame(firstGeometryBuilder, fixture.backend.geometryBuilders.last())

            fixture.camera.moveTo(Vec3(9.0, 0.0, 0.0))
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            awaitCondition {
                fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
                fixture.backend.sortUploads == 1
            }
            val firstSortBuilder = fixture.backend.sortBuilders.single()

            fixture.camera.moveTo(Vec3(18.0, 0.0, 0.0))
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            awaitCondition {
                fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
                fixture.backend.sortUploads == 2
            }

            assertEquals(2, fixture.backend.sortBuilders.size)
            assertSame(firstSortBuilder, fixture.backend.sortBuilders.last())
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun eventFrustumControlsDrawVisibilityUsingCachedWorldAabb(): Unit {
        val fixture = Fixture(singleSectionContent())
        try {
            fixture.frustum.visible = false
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)

            assertEquals(listOf(TRANSFORM.sectionWorldAabb(SectionKey(0, 0, 0))), fixture.frustum.observed)
            assertTrue(fixture.backend.draws.isEmpty())

            fixture.frustum.visible = true
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)

            assertTrue(fixture.backend.draws.any { it.handles.isNotEmpty() })
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun multipleSectionsKeepCaptureAndUploadAtOnePerFrame(): Unit {
        val content = snapshot(
            mapOf(
                BlockPos.ZERO to Blocks.GLASS.defaultBlockState(),
                BlockPos(16, 0, 0) to Blocks.GLASS.defaultBlockState(),
            ),
        )
        val fixture = Fixture(content)
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)
            assertEquals(1L, fixture.mesh.metricsSnapshot().captureAdmissions)
            assertEquals(1L, fixture.mesh.metricsSnapshot().uploads)

            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.5f)

            val metrics = fixture.mesh.metricsSnapshot()
            assertEquals(2L, metrics.captureAdmissions)
            assertEquals(2L, metrics.uploads)
            assertEquals(1, metrics.maxFrameUploads)
            assertEquals(2, fixture.mesh.residentSnapshot().handleCount)
            assertEquals(2, fixture.backend.draws.size, "one translucent layer callback per frame")
            assertEquals(
                listOf(fixture.backend.handles[1], fixture.backend.handles[0]),
                fixture.backend.draws.last().handles,
                "translucent sections must draw far-to-near",
            )
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun suppressingOneBlockRebuildsOnlyItsSectionAndRepeatedValueIsNoop(): Unit {
        val localPos = BlockPos.ZERO
        val content = snapshot(
            mapOf(
                localPos to Blocks.GLASS.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.GLASS.defaultBlockState(),
                BlockPos(16, 0, 0) to Blocks.GLASS.defaultBlockState(),
            ),
        )
        val fixture = Fixture(content)
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            val ownSectionHandle = fixture.backend.handles[0]
            val adjacentSectionHandle = fixture.backend.handles[1]
            val capturesBefore = fixture.mesh.metricsSnapshot().captureAdmissions

            assertTrue(fixture.mesh.setBlockSuppressed(localPos, true))
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)

            assertEquals(capturesBefore + 1L, fixture.mesh.metricsSnapshot().captureAdmissions)
            assertEquals(listOf(8, 4, 4), fixture.backend.geometryVertexCounts)
            assertEquals(1, ownSectionHandle.closeCount)
            assertEquals(0, adjacentSectionHandle.closeCount)
            val uploadsAfterChange = fixture.backend.handles.size
            val capturesAfterChange = fixture.mesh.metricsSnapshot().captureAdmissions

            assertFalse(fixture.mesh.setBlockSuppressed(localPos, true))
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)

            assertEquals(uploadsAfterChange, fixture.backend.handles.size)
            assertEquals(capturesAfterChange, fixture.mesh.metricsSnapshot().captureAdmissions)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun initialSuppressionFiltersFirstBuildWithoutASecondRebuild(): Unit {
        val fixture = Fixture(
            content = twoBlockContent(),
            initialSuppressed = setOf(BlockPos.ZERO),
        )
        try {
            repeat(3) {
                fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            }

            assertEquals(listOf(4), fixture.backend.geometryVertexCounts)
            assertEquals(1L, fixture.mesh.metricsSnapshot().captureAdmissions)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun transformAndClearLifecycleDropSuppressedPositions(): Unit {
        val movedTransform = RenderTransform(Vec3(8.0, 0.0, 0.0), 0.0f, Vec3.ZERO, 2L)
        val fixture = Fixture(
            content = twoBlockContent(),
            initialSuppressed = setOf(BlockPos.ZERO),
        )
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            fixture.mesh.updateTransform(fixture.level, movedTransform)
            fixture.mesh.render(movedTransform, fixture.event, opacity = 0.0f)

            assertEquals(listOf(4, 8), fixture.backend.geometryVertexCounts)

            assertTrue(fixture.mesh.setBlockSuppressed(BlockPos.ZERO, true))
            fixture.mesh.render(movedTransform, fixture.event, opacity = 0.0f)
            assertEquals(listOf(4, 8, 4), fixture.backend.geometryVertexCounts)

            fixture.mesh.clearContent()
            assertFalse(fixture.mesh.setBlockSuppressed(BlockPos.ZERO, false))
            fixture.mesh.replaceContent(
                fixture.level,
                fixture.content,
                fixture.service,
                movedTransform,
            )
            fixture.mesh.render(movedTransform, fixture.event, opacity = 0.0f)

            assertEquals(listOf(4, 8, 4, 8), fixture.backend.geometryVertexCounts)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun resourceEpochReplacementClearsSuppressionBeforeRebuild(): Unit {
        val fixture = Fixture(
            content = twoBlockContent(),
            initialSuppressed = setOf(BlockPos.ZERO),
        )
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            fixture.mesh.replaceContent(
                level = fixture.level,
                content = fixture.content,
                meshingService = QuadMeshingService(resourceEpoch = 2L),
                transform = TRANSFORM,
                suppressed = setOf(BlockPos.ZERO),
            )
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)

            assertEquals(listOf(4, 8), fixture.backend.geometryVertexCounts)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun suppressedNeighborRemainsSolidForCulling(): Unit {
        val service = NeighborCullingMeshingService()
        val fixture = Fixture(twoBlockContent(), service = service)
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            assertTrue(fixture.mesh.setBlockSuppressed(BlockPos(1, 0, 0), true))
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)

            assertEquals(listOf(true, true), service.eastNeighborWasSolid)
            assertEquals(
                listOf(BlockPos.ZERO, BlockPos(1, 0, 0), BlockPos.ZERO),
                service.renderedPositions,
            )
            assertTrue(fixture.backend.handles.isEmpty())
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun clearClosesGpuStateButMeshCanBeReusedAndCloseIsTerminal(): Unit {
        val fixture = Fixture(singleSectionContent())
        fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
        val first = fixture.backend.handles.single()

        fixture.mesh.clearContent()

        assertEquals(1, first.closeCount)
        assertEquals(0, fixture.mesh.residentSnapshot().handleCount)
        assertEquals(0, fixture.mesh.runtimeSnapshot().geometryJobs)
        assertEquals(0, fixture.mesh.runtimeSnapshot().sortJobs)
        assertEquals(0, fixture.mesh.runtimeSnapshot().liveCpuBuffers)
        fixture.mesh.replaceContent(fixture.level, fixture.content, fixture.service, TRANSFORM)
        fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
        val second = fixture.backend.handles.last()
        assertFalse(first === second)

        fixture.mesh.close()
        fixture.mesh.replaceContent(fixture.level, fixture.content, fixture.service, TRANSFORM)
        fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)

        assertEquals(1, second.closeCount)
        assertEquals(2, fixture.backend.handles.size)
        assertEquals(0, fixture.mesh.residentSnapshot().handleCount)
    }

    @Test
    public fun resourceEpochChangeClosesOldGpuStateBeforeLazyRebuild(): Unit {
        val fixture = Fixture(singleSectionContent())
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            val old = fixture.backend.handles.single()

            fixture.mesh.replaceContent(
                fixture.level,
                fixture.content,
                QuadMeshingService(resourceEpoch = 2L),
                TRANSFORM,
            )

            assertEquals(1, old.closeCount)
            assertEquals(0, fixture.mesh.residentSnapshot().handleCount)
            assertEquals(0, fixture.mesh.runtimeSnapshot().geometryJobs)
            assertEquals(0, fixture.mesh.runtimeSnapshot().sortJobs)
            assertEquals(0, fixture.mesh.runtimeSnapshot().liveCpuBuffers)
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            assertEquals(2, fixture.backend.handles.size)
        } finally {
            fixture.mesh.close()
        }
    }

    @Test
    public fun worldUnloadUsesTheEventLevelIdentityAndMeshRemainsReusable(): Unit {
        val fixture = Fixture(singleSectionContent())
        try {
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            val old = fixture.backend.handles.single()
            val unrelatedLevel: ClientLevel = mockk(relaxed = true)

            fixture.mesh.worldUnloaded(unrelatedLevel)
            assertEquals(0, old.closeCount)

            fixture.mesh.worldUnloaded(fixture.level)
            assertEquals(1, old.closeCount)
            assertEquals(0, fixture.mesh.residentSnapshot().handleCount)
            assertEquals(0, fixture.mesh.runtimeSnapshot().geometryJobs)
            assertEquals(0, fixture.mesh.runtimeSnapshot().sortJobs)
            assertEquals(0, fixture.mesh.runtimeSnapshot().liveCpuBuffers)

            fixture.mesh.worldLoaded(fixture.level)
            fixture.mesh.replaceContent(fixture.level, fixture.content, fixture.service, TRANSFORM)
            fixture.mesh.render(TRANSFORM, fixture.event, opacity = 0.0f)
            assertEquals(2, fixture.backend.handles.size)
        } finally {
            fixture.mesh.close()
        }
    }

    private class Fixture(
        internal val content: SchematicContentSnapshot,
        internal val service: SectionMeshingService = QuadMeshingService(resourceEpoch = 1L),
        initialSuppressed: Set<BlockPos> = emptySet(),
    ) {
        internal val level: ClientLevel = mockk(relaxed = true)
        internal val backend: FakeGpuBackend = FakeGpuBackend()
        internal val camera: TestCamera = TestCamera(Vec3.ZERO)
        internal val frustum: TestFrustum = TestFrustum(visible = true)
        internal val event: RenderLevelStageEvent = RenderLevelStageEvent(
            RenderLevelStageEvent.Stage.AFTER_PARTICLES,
            null,
            PoseStack(),
            identityMatrix(),
            0,
            0.0f,
            camera,
            frustum,
        )
        private val counter: AtomicLong = AtomicLong()
        internal val mesh: SectionedSchematicMesh = SectionedSchematicMesh(
            nanoTime = { counter.addAndGet(1_000L) },
            gpuBackend = backend,
        )

        init {
            mesh.replaceContent(level, content, service, TRANSFORM, initialSuppressed)
        }
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

    private class NeighborCullingMeshingService : SectionMeshingService {
        override val resourceEpoch: Long = 1L
        internal val eastNeighborWasSolid: MutableList<Boolean> = mutableListOf()
        internal val renderedPositions: MutableList<BlockPos> = mutableListOf()

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
            renderedPositions += pos
            val rendered = if (pos == BlockPos.ZERO) {
                val neighborSolid = !view.getBlockState(pos.east()).isAir
                eastNeighborWasSolid += neighborSolid
                !neighborSolid
            } else {
                false
            }
            if (rendered) emitQuad(target, pos)
            return SectionLayerRenderResult(blockRendered = rendered, fluidRendered = false)
        }
    }

    private class FakeGpuHandle : SectionGpuHandle {
        internal var closeCount: Int = 0
            private set

        override fun close(): Unit {
            closeCount++
        }
    }

    private data class FakeDraw(
        internal val handles: List<SectionGpuHandle>,
        internal val renderType: RenderType,
        internal val opacity: Float,
    )

    private class FakeGpuBackend : SectionGpuBackend {
        internal val handles: MutableList<FakeGpuHandle> = mutableListOf()
        internal val draws: MutableList<FakeDraw> = mutableListOf()
        internal val geometryBuilders: MutableList<BufferBuilder> = mutableListOf()
        internal val sortBuilders: MutableList<BufferBuilder> = mutableListOf()
        internal val geometryVertexCounts: MutableList<Int> = mutableListOf()
        internal var sortUploads: Int = 0
            private set
        internal var lastSortWasIndexOnly: Boolean = false
            private set

        override fun upload(builder: BufferBuilder): SectionGpuHandle {
            geometryBuilders += builder
            val built = builder.popNextBuffer()
            assertFalse(built.first.indexOnly())
            geometryVertexCounts += built.first.vertexCount()
            return FakeGpuHandle().also(handles::add)
        }

        override fun uploadSort(handle: SectionGpuHandle, builder: BufferBuilder): Unit {
            assertTrue(handle in handles)
            sortBuilders += builder
            val built = builder.popNextBuffer()
            lastSortWasIndexOnly = built.first.indexOnly()
            sortUploads++
        }

        override fun drawLayer(
            handles: List<SectionGpuHandle>,
            renderType: RenderType,
            poseStack: PoseStack,
            projection: Matrix4f,
            opacity: Float,
        ): Unit {
            if (handles.isNotEmpty()) {
                draws += FakeDraw(handles.toList(), renderType, opacity)
            }
        }
    }

    private class TestCamera(position: Vec3) : Camera() {
        init {
            setPosition(position)
        }

        internal fun moveTo(position: Vec3): Unit {
            setPosition(position)
        }
    }

    private class TestFrustum(
        internal var visible: Boolean,
    ) : Frustum(identityMatrix(), identityMatrix()) {
        internal val observed: MutableList<AABB> = mutableListOf()

        override fun isVisible(box: AABB): Boolean {
            observed += box
            return visible
        }
    }

    public companion object {
        private val TRANSFORM = RenderTransform(Vec3.ZERO, 0.0f, Vec3.ZERO, 1L)

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private fun singleSectionContent(): SchematicContentSnapshot {
            return snapshot(mapOf(BlockPos.ZERO to Blocks.GLASS.defaultBlockState()))
        }

        private fun twoBlockContent(): SchematicContentSnapshot {
            return snapshot(
                mapOf(
                    BlockPos.ZERO to Blocks.GLASS.defaultBlockState(),
                    BlockPos(1, 0, 0) to Blocks.GLASS.defaultBlockState(),
                ),
            )
        }

        private fun snapshot(blocks: Map<BlockPos, BlockState>): SchematicContentSnapshot {
            return SchematicContentSnapshot.copyOf(blocks, Blocks.AIR.defaultBlockState())
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

        private fun awaitCondition(condition: () -> Boolean): Unit {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "timed out waiting for section runtime" }
                Thread.yield()
            }
        }
    }
}
