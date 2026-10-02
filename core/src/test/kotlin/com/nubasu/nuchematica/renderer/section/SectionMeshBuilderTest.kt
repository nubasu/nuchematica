package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class SectionMeshBuilderTest {

    @Test
    public fun solidTranslucentAndEmptyReturnOnlyUsedOpaqueBuffers(): Unit {
        val solidService = singleLayerService(SectionSourceLayer.SOLID)
        val solid = buildFully(
            mapOf(BlockPos.ZERO to Blocks.STONE.defaultBlockState()),
            SectionKey(0, 0, 0),
            solidService,
        )

        assertNotNull(solid)
        assertNotNull(solid?.solid)
        assertNull(solid?.translucent)
        assertNull(solid?.translucentSortState)
        assertOpaqueVertices(solid!!.solid!!, expectedVertices = 4)

        val translucentService = singleLayerService(SectionSourceLayer.TRANSLUCENT)
        val translucent = buildFully(
            mapOf(BlockPos.ZERO to Blocks.GLASS.defaultBlockState()),
            SectionKey(0, 0, 0),
            translucentService,
        )

        assertNotNull(translucent)
        assertNull(translucent?.solid)
        assertNotNull(translucent?.translucent)
        assertNotNull(translucent?.translucentSortState)
        assertOpaqueVertices(translucent!!.translucent!!, expectedVertices = 4)

        val empty = buildFully(emptyMap(), SectionKey(0, 0, 0), solidService)
        assertNull(empty)
    }

    @Test
    public fun builderResumesThroughTheB1CursorWithoutLosingGeometry(): Unit {
        val content = snapshot(
            mapOf(
                BlockPos.ZERO to Blocks.STONE.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.STONE.defaultBlockState(),
            ),
        )
        val service = singleLayerService(SectionSourceLayer.SOLID)
        val builder = SectionMeshBuilder(
            request(content, SectionKey(0, 0, 0)),
            service,
            newGeometryBuffers(),
        )

        val first = builder.build(deadlineNanos = 0L, nanoTime = { 1L })
        assertEquals(CursorAdvanceResult(1, complete = false, deadlineReached = true), first.cursor)
        assertNull(first.geometry)

        val second = builder.build(deadlineNanos = Long.MAX_VALUE, nanoTime = { 0L })
        assertEquals(CursorAdvanceResult(1, complete = true, deadlineReached = false), second.cursor)
        assertNotNull(second.geometry?.solid)
        assertEquals(2, service.geometryBuilds)
        assertVertexCount(second.geometry!!.solid!!, expectedVertices = 8)
    }

    @Test
    public fun blockFaceCullingReadsNeighborsAcrossSectionBoundaries(): Unit {
        val service = FakeMeshingService(
            passes = { listOf(SectionLayerPass(SectionSourceLayer.SOLID, true, false)) },
            renderer = { pass, pos, _, view, target ->
                val rendered = view.getBlockState(pos.east()).isAir
                if (rendered) emitQuad(target, pos, pass.sourceLayer.ordinal.toFloat())
                SectionLayerRenderResult(blockRendered = rendered, fluidRendered = false)
            },
        )
        val boundary = BlockPos(15, 0, 0)
        val neighbor = BlockPos(16, 0, 0)

        val culled = buildFully(
            mapOf(
                boundary to Blocks.STONE.defaultBlockState(),
                neighbor to Blocks.STONE.defaultBlockState(),
            ),
            SectionKey(0, 0, 0),
            service,
        )
        val exposed = buildFully(
            mapOf(boundary to Blocks.STONE.defaultBlockState()),
            SectionKey(0, 0, 0),
            service,
        )

        assertNull(culled)
        assertNotNull(exposed?.solid)
        assertVertexCount(exposed!!.solid!!, expectedVertices = 4)
    }

    @Test
    public fun syntheticFluidCullingReadsSchematicFluidNeighbors(): Unit {
        val service = FakeMeshingService(
            passes = { listOf(SectionLayerPass(SectionSourceLayer.TRANSLUCENT, false, true)) },
            renderer = { pass, pos, _, view, target ->
                val rendered = view.getFluidState(pos.east()).isEmpty
                if (rendered) emitQuad(target, pos, pass.sourceLayer.ordinal.toFloat())
                SectionLayerRenderResult(blockRendered = false, fluidRendered = rendered)
            },
        )
        val boundary = BlockPos(15, 0, 0)
        val neighbor = BlockPos(16, 0, 0)

        val culled = buildFully(
            mapOf(
                boundary to Blocks.WATER.defaultBlockState(),
                neighbor to Blocks.WATER.defaultBlockState(),
            ),
            SectionKey(0, 0, 0),
            service,
        )
        val exposed = buildFully(
            mapOf(boundary to Blocks.WATER.defaultBlockState()),
            SectionKey(0, 0, 0),
            service,
        )

        assertNull(culled)
        assertNotNull(exposed?.translucent)
        assertVertexCount(exposed!!.translucent!!, expectedVertices = 4)
    }

    @Test
    public fun frozenRoutingSendsEverySourceLayerExactlyOnce(): Unit {
        val passes = SectionSourceLayer.values().map { sourceLayer ->
            SectionLayerPass(sourceLayer, renderBlock = true, renderFluid = false)
        }
        val service = FakeMeshingService(
            passes = { passes },
            renderer = { pass, pos, _, _, target ->
                emitQuad(target, pos, pass.sourceLayer.ordinal.toFloat())
                SectionLayerRenderResult(blockRendered = true, fluidRendered = false)
            },
        )

        val geometry = buildFully(
            mapOf(BlockPos.ZERO to Blocks.STONE.defaultBlockState()),
            SectionKey(0, 0, 0),
            service,
        )

        assertEquals(SectionSourceLayer.values().toList(), service.renderedLayers)
        assertVertexCount(geometry!!.solid!!, expectedVertices = 16)
        assertVertexCount(geometry.translucent!!, expectedVertices = 4)
    }

    @Test
    public fun fluidAndBlockOutputsBothSurviveOneLayerPass(): Unit {
        val outputOrder = mutableListOf<String>()
        val service = FakeMeshingService(
            passes = { listOf(SectionLayerPass(SectionSourceLayer.TRANSLUCENT, true, true)) },
            renderer = { _, pos, _, _, target ->
                outputOrder += "fluid"
                emitQuad(target, pos, 0f)
                outputOrder += "block"
                emitQuad(target, pos, 1f)
                SectionLayerRenderResult(blockRendered = true, fluidRendered = true)
            },
        )

        val geometry = buildFully(
            mapOf(BlockPos.ZERO to Blocks.WATER.defaultBlockState()),
            SectionKey(0, 0, 0),
            service,
        )

        assertEquals(listOf("fluid", "block"), outputOrder)
        assertVertexCount(geometry!!.translucent!!, expectedVertices = 8)
    }

    @Test
    public fun sectionBuffersAreByteIdenticalRegardlessOfJobSubmissionOrder(): Unit {
        val content = snapshot(
            linkedMapOf(
                BlockPos(17, 0, 0) to Blocks.STONE.defaultBlockState(),
                BlockPos(2, 0, 0) to Blocks.STONE.defaultBlockState(),
                BlockPos(16, 0, 0) to Blocks.STONE.defaultBlockState(),
                BlockPos.ZERO to Blocks.STONE.defaultBlockState(),
            ),
        )
        val service = singleLayerService(SectionSourceLayer.SOLID)
        val firstOrder = listOf(SectionKey(0, 0, 0), SectionKey(1, 0, 0)).associateWith { key ->
            solidBytes(buildFully(content, key, service)!!)
        }
        val reverseOrder = listOf(SectionKey(1, 0, 0), SectionKey(0, 0, 0)).associateWith { key ->
            solidBytes(buildFully(content, key, service)!!)
        }

        for (key in firstOrder.keys) {
            assertArrayEquals(firstOrder[key], reverseOrder[key], "key=$key")
        }
    }

    @Test
    public fun sortStateKeepsEveryQuadAndResortDoesNotBuildGeometry(): Unit {
        val service = singleLayerService(SectionSourceLayer.TRANSLUCENT)
        val geometry = buildFully(
            mapOf(
                BlockPos.ZERO to Blocks.GLASS.defaultBlockState(),
                BlockPos(4, 0, 0) to Blocks.GLASS.defaultBlockState(),
            ),
            SectionKey(0, 0, 0),
            service,
        )!!
        val sortState = geometry.translucentSortState!!
        val initial = geometry.translucent!!.popNextBuffer()

        assertFalse(initial.first.indexOnly())
        assertEquals(8, initial.first.vertexCount())
        assertEquals(12, initial.first.indexCount())
        val buildsBeforeResort = service.geometryBuilds

        val resort = SectionMeshBuilder.resort(
            sortState,
            Vec3(0.0, 0.0, -32.0),
            BufferBuilder(TEST_BUFFER_BYTES),
        ).popNextBuffer()

        assertEquals(buildsBeforeResort, service.geometryBuilds)
        assertTrue(resort.first.indexOnly())
        assertEquals(8, resort.first.vertexCount())
        assertEquals(12, resort.first.indexCount())
    }

    @Test
    public fun bufferPoolAllocatesExactlyThreeBuildersAndReusesEveryIdentity(): Unit {
        val allocated = mutableListOf<BufferBuilder>()
        val pool = SectionMeshBufferPool {
            BufferBuilder(TEST_BUFFER_BYTES).also(allocated::add)
        }

        assertEquals(3, allocated.size)
        val firstGeometry = pool.acquireGeometry()
        pool.releaseGeometry(firstGeometry)
        val secondGeometry = pool.acquireGeometry()
        assertSame(firstGeometry, secondGeometry)
        assertSame(firstGeometry.solid, secondGeometry.solid)
        assertSame(firstGeometry.translucent, secondGeometry.translucent)
        pool.releaseGeometry(secondGeometry)

        val firstSort = pool.acquireSort()
        firstSort.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        pool.discardSort(firstSort)
        val secondSort = pool.acquireSort()
        assertSame(firstSort, secondSort)
        pool.releaseSort(secondSort)
        assertEquals(3, allocated.size)
    }

    @Test
    public fun cancelledAndFailedBuildsReturnTheSameGeometryBuffersForReuse(): Unit {
        val content = snapshot(
            mapOf(
                BlockPos.ZERO to Blocks.STONE.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.STONE.defaultBlockState(),
            ),
        )
        val pool = SectionMeshBufferPool { BufferBuilder(TEST_BUFFER_BYTES) }
        val service = singleLayerService(SectionSourceLayer.SOLID)

        val cancelledBuffers = pool.acquireGeometry()
        val cancelled = SectionMeshBuilder(
            request(content, SectionKey(0, 0, 0)),
            service,
            cancelledBuffers,
        )
        assertFalse(cancelled.build(deadlineNanos = 0L, nanoTime = { 1L }).cursor.complete)
        cancelled.discard()
        pool.releaseGeometry(cancelledBuffers)

        val failedBuffers = pool.acquireGeometry()
        assertSame(cancelledBuffers, failedBuffers)
        val failingService = FakeMeshingService(
            passes = { listOf(SectionLayerPass(SectionSourceLayer.SOLID, true, false)) },
            renderer = { _, pos, _, _, target ->
                target.vertex(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                    .color(255, 255, 255, 255)
                throw IllegalStateException("partial vertex failure")
            },
        )
        val failed = SectionMeshBuilder(
            request(content, SectionKey(0, 0, 0)),
            failingService,
            failedBuffers,
        )
        assertEquals(
            "partial vertex failure",
            assertThrows(IllegalStateException::class.java) {
                failed.build(deadlineNanos = Long.MAX_VALUE, nanoTime = { 0L })
            }.message,
        )
        pool.releaseGeometry(failedBuffers)

        val reusedBuffers = pool.acquireGeometry()
        assertSame(cancelledBuffers, reusedBuffers)
        val result = SectionMeshBuilder(
            request(content, SectionKey(0, 0, 0)),
            service,
            reusedBuffers,
        ).build(deadlineNanos = Long.MAX_VALUE, nanoTime = { 0L })
        assertTrue(result.cursor.complete)
        assertVertexCount(result.geometry!!.solid!!, expectedVertices = 8)
        pool.releaseGeometry(reusedBuffers)
    }

    private fun singleLayerService(sourceLayer: SectionSourceLayer): FakeMeshingService {
        return FakeMeshingService(
            passes = {
                listOf(
                    SectionLayerPass(
                        sourceLayer,
                        renderBlock = true,
                        renderFluid = false,
                    ),
                )
            },
            renderer = { pass, pos, _, _, target ->
                emitQuad(target, pos, pass.sourceLayer.ordinal.toFloat(), alpha = 17)
                SectionLayerRenderResult(blockRendered = true, fluidRendered = false)
            },
        )
    }

    private fun buildFully(
        blocks: Map<BlockPos, BlockState>,
        key: SectionKey,
        service: SectionMeshingService,
    ): SectionGeometry? {
        return buildFully(snapshot(blocks), key, service)
    }

    private fun buildFully(
        content: SchematicContentSnapshot,
        key: SectionKey,
        service: SectionMeshingService,
    ): SectionGeometry? {
        val result = SectionMeshBuilder(request(content, key), service, newGeometryBuffers()).build(
            deadlineNanos = Long.MAX_VALUE,
            nanoTime = { 0L },
        )
        assertTrue(result.cursor.complete)
        return result.geometry
    }

    private fun request(
        content: SchematicContentSnapshot,
        key: SectionKey,
    ): MainThreadSectionBuildRequest {
        return MainThreadSectionBuildRequestFactory().create(
            world = NoWorldAccess,
            key = key,
            content = content,
            transform = IDENTITY_TRANSFORM,
            sortOrigin = Vec3(0.0, 0.0, 32.0),
            meshEpoch = 1L,
            sectionGeometryGeneration = 1L,
            cameraSortRevision = 1L,
        )
    }

    private fun snapshot(blocks: Map<BlockPos, BlockState>): SchematicContentSnapshot {
        return SchematicContentSnapshot.copyOf(blocks, Blocks.AIR.defaultBlockState())
    }

    private fun newGeometryBuffers(): SectionGeometryBufferLease {
        return SectionGeometryBufferLease(
            solid = BufferBuilder(TEST_BUFFER_BYTES),
            translucent = BufferBuilder(TEST_BUFFER_BYTES),
        )
    }

    private fun assertOpaqueVertices(builder: BufferBuilder, expectedVertices: Int): Unit {
        val built = builder.popNextBuffer()
        val drawState = built.first
        assertEquals(expectedVertices, drawState.vertexCount())
        val format = drawState.format()
        val colorIndex = format.elements.indexOf(DefaultVertexFormat.ELEMENT_COLOR)
        val alphaOffset = format.elements.take(colorIndex).sumOf { element -> element.byteSize } + 3
        for (vertex in 0 until drawState.vertexCount()) {
            val alpha = built.second.get(vertex * format.vertexSize + alphaOffset).toInt() and 0xFF
            assertEquals(255, alpha, "vertex=$vertex")
        }
    }

    private fun assertVertexCount(builder: BufferBuilder, expectedVertices: Int): Unit {
        assertEquals(expectedVertices, builder.popNextBuffer().first.vertexCount())
    }

    private fun solidBytes(geometry: SectionGeometry): ByteArray {
        val data = geometry.solid!!.popNextBuffer().second.duplicate()
        return ByteArray(data.remaining()).also(data::get)
    }

    private class FakeMeshingService(
        private val passes: (BlockState) -> List<SectionLayerPass>,
        private val renderer: (
            SectionLayerPass,
            BlockPos,
            BlockState,
            BlockAndTintGetter,
            VertexConsumer,
        ) -> SectionLayerRenderResult,
    ) : SectionMeshingService {
        override val resourceEpoch: Long = 1L
        var geometryBuilds: Int = 0
            private set
        val renderedLayers: MutableList<SectionSourceLayer> = mutableListOf()

        override fun passesFor(blockState: BlockState): List<SectionLayerPass> {
            return passes(blockState)
        }

        override fun renderLayer(
            pass: SectionLayerPass,
            pos: BlockPos,
            blockState: BlockState,
            view: BlockAndTintGetter,
            target: VertexConsumer,
        ): SectionLayerRenderResult {
            geometryBuilds++
            renderedLayers += pass.sourceLayer
            return renderer(pass, pos, blockState, view, target)
        }
    }

    private object NoWorldAccess : MainThreadWorldAccess {
        override fun height(): Int = 384

        override fun minBuildHeight(): Int = -64

        override fun shade(direction: Direction, shade: Boolean): Float = 1.0f

        override fun lightEngine(): LevelLightEngine {
            error("world light engine is not used by headless mesh tests")
        }

        override fun blockTint(worldPos: BlockPos, resolver: ColorResolver): Int {
            error("world tint is not used by headless mesh tests")
        }

        override fun brightness(layer: LightLayer, worldPos: BlockPos): Int {
            error("world brightness is not used by headless mesh tests")
        }

        override fun rawBrightness(worldPos: BlockPos, ambientDarkness: Int): Int {
            error("world raw brightness is not used by headless mesh tests")
        }

        override fun canSeeSky(worldPos: BlockPos): Boolean {
            error("world sky visibility is not used by headless mesh tests")
        }
    }

    public companion object {
        private const val TEST_BUFFER_BYTES: Int = 512
        private val IDENTITY_TRANSFORM = RenderTransform(Vec3.ZERO, 0f, Vec3.ZERO, 0L)

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private fun emitQuad(
            target: VertexConsumer,
            pos: BlockPos,
            zOffset: Float,
            alpha: Int = 255,
        ): Unit {
            val x = pos.x.toFloat()
            val y = pos.y.toFloat()
            val z = pos.z + zOffset
            emitVertex(target, x, y, z, 0f, 0f, alpha)
            emitVertex(target, x + 1f, y, z, 1f, 0f, alpha)
            emitVertex(target, x + 1f, y + 1f, z, 1f, 1f, alpha)
            emitVertex(target, x, y + 1f, z, 0f, 1f, alpha)
        }

        private fun emitVertex(
            target: VertexConsumer,
            x: Float,
            y: Float,
            z: Float,
            u: Float,
            v: Float,
            alpha: Int,
        ): Unit {
            target.vertex(x.toDouble(), y.toDouble(), z.toDouble())
                .color(255, 255, 255, alpha)
                .uv(u, v)
                .uv2(15, 15)
                .normal(0f, 1f, 0f)
                .endVertex()
        }
    }
}
