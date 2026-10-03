package com.nubasu.nuchematica.renderer.section

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

public class MainThreadSectionBuildTest {

    @Test
    public fun cursorStopsAfterDeadlineAndResumesWithoutRepeatingBlocks(): Unit {
        val request = requestWithBlocks(
            mapOf(
                BlockPos(2, 0, 0) to Blocks.GLASS.defaultBlockState(),
                BlockPos(0, 0, 0) to Blocks.STONE.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.WATER.defaultBlockState(),
            ),
        )
        val consumed = mutableListOf<BlockPos>()

        val first = request.cursor.advanceWithinBudget(
            deadlineNanos = 100L,
            nanoTime = { 150L },
        ) { pos, _ -> consumed += pos }

        assertEquals(CursorAdvanceResult(1, complete = false, deadlineReached = true), first)
        assertEquals(listOf(BlockPos(0, 0, 0)), consumed)
        assertEquals(2, request.cursor.remainingBlocks)

        val second = request.cursor.advanceWithinBudget(
            deadlineNanos = Long.MAX_VALUE,
            nanoTime = { 0L },
        ) { pos, _ -> consumed += pos }

        assertEquals(CursorAdvanceResult(2, complete = true, deadlineReached = false), second)
        assertEquals(
            listOf(BlockPos(0, 0, 0), BlockPos(1, 0, 0), BlockPos(2, 0, 0)),
            consumed,
        )
        assertTrue(request.cursor.isComplete)
    }

    @Test
    public fun cursorProcessesOneBlockWhenDeadlineAlreadyPassed(): Unit {
        val request = requestWithBlocks(
            mapOf(
                BlockPos.ZERO to Blocks.STONE.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.GLASS.defaultBlockState(),
            ),
        )

        val result = request.cursor.advanceWithinBudget(
            deadlineNanos = 0L,
            nanoTime = { 1L },
        ) { _, _ -> }

        assertEquals(CursorAdvanceResult(1, complete = false, deadlineReached = true), result)
    }

    @Test
    public fun requestAndCursorRejectAccessFromAnotherThread(): Unit {
        val request = requestWithBlocks(mapOf(BlockPos.ZERO to Blocks.STONE.defaultBlockState()))
        val thrown = AtomicReference<Throwable?>()
        val thread = Thread {
            thrown.set(
                runCatching {
                    request.cursor.advanceWithinBudget(Long.MAX_VALUE) { _, _ -> }
                }.exceptionOrNull(),
            )
        }

        thread.start()
        thread.join()

        assertInstanceOf(IllegalStateException::class.java, thrown.get())
        assertFalse(request.cursor.isComplete)
    }

    @Test
    public fun renderViewUsesContentForNeighborsAndWorldCellsForSamples(): Unit {
        val local = BlockPos(1, 2, 3)
        val expectedWorld = BlockPos(13, 2, 18)
        val world = RecordingWorldAccess()
        val transform = RenderTransform(
            renderBase = Vec3(10.0, 0.0, 20.0),
            rotateDeg = 90f,
            rotateAxis = Vec3.ZERO,
            revision = 1L,
        )
        val content = SchematicContentSnapshot.copyOf(
            mapOf(local to Blocks.WATER.defaultBlockState()),
            Blocks.AIR.defaultBlockState(),
        )
        val request = factory().create(
            world = world,
            key = SectionKey.of(local),
            content = content,
            transform = transform,
            sortOrigin = Vec3.ZERO,
            meshEpoch = 1L,
            sectionGeometryGeneration = 2L,
            cameraSortRevision = 3L,
        )
        val resolver = ColorResolver { _, _, _ -> 0 }

        assertSame(Blocks.WATER.defaultBlockState(), request.view.getBlockState(local))
        assertSame(Blocks.WATER.defaultBlockState().fluidState, request.view.getFluidState(local))
        assertSame(Blocks.AIR.defaultBlockState(), request.view.getBlockState(local.east()))
        assertNull(request.view.getBlockEntity(local))
        assertEquals(384, request.view.height)
        assertEquals(-64, request.view.minBuildHeight)
        assertEquals(0.75f, request.view.getShade(Direction.NORTH, true))
        assertEquals(0x123456, request.view.getBlockTint(local, resolver))
        assertEquals(12, request.view.getBrightness(LightLayer.SKY, local))
        assertEquals(9, request.view.getRawBrightness(local, 3))
        assertTrue(request.view.canSeeSky(local))
        assertEquals(listOf(expectedWorld, expectedWorld, expectedWorld, expectedWorld), world.sampledPositions)
    }

    @Test
    public fun suppressionFiltersOnlyTheCursorAndKeepsRenderViewNeighbors(): Unit {
        val suppressedPos = BlockPos.ZERO
        val renderedPos = suppressedPos.east()
        val content = SchematicContentSnapshot.copyOf(
            mapOf(
                suppressedPos to Blocks.STONE.defaultBlockState(),
                renderedPos to Blocks.GLASS.defaultBlockState(),
            ),
            Blocks.AIR.defaultBlockState(),
        )
        val request = factory().create(
            world = RecordingWorldAccess(),
            key = SectionKey.of(suppressedPos),
            content = content,
            transform = IDENTITY_TRANSFORM,
            sortOrigin = Vec3.ZERO,
            meshEpoch = 1L,
            sectionGeometryGeneration = 1L,
            cameraSortRevision = 1L,
            suppressed = setOf(suppressedPos),
        )
        val consumed = mutableListOf<BlockPos>()

        request.cursor.advanceWithinBudget(Long.MAX_VALUE) { pos, _ -> consumed += pos }

        assertEquals(listOf(renderedPos), consumed)
        assertSame(Blocks.STONE.defaultBlockState(), request.view.getBlockState(suppressedPos))
    }

    @Test
    public fun multipleRequestsReuseOnePublishedContentSnapshot(): Unit {
        val content = SchematicContentSnapshot.copyOf(
            mapOf(BlockPos.ZERO to Blocks.STONE.defaultBlockState()),
            Blocks.AIR.defaultBlockState(),
        )
        val factory = factory()
        val firstWorld = RecordingWorldAccess()
        val secondWorld = RecordingWorldAccess()
        val first = factory.create(
            firstWorld,
            SectionKey(0, 0, 0),
            content,
            IDENTITY_TRANSFORM,
            Vec3.ZERO,
            1L,
            1L,
            1L,
        )
        val second = factory.create(
            secondWorld,
            SectionKey(0, 0, 0),
            content,
            IDENTITY_TRANSFORM,
            Vec3.ZERO,
            1L,
            2L,
            1L,
        )

        assertSame(content, first.content)
        assertSame(content, second.content)
        assertSame(first.content.allBlocks, second.content.allBlocks)
        assertSame(first.content.blocksBySection, second.content.blocksBySection)
        assertTrue(firstWorld.sampledPositions.isEmpty())
        assertTrue(secondWorld.sampledPositions.isEmpty())
    }

    private fun requestWithBlocks(
        blocks: Map<BlockPos, net.minecraft.world.level.block.state.BlockState>,
    ): MainThreadSectionBuildRequest {
        val content = SchematicContentSnapshot.copyOf(blocks, Blocks.AIR.defaultBlockState())
        return factory().create(
            RecordingWorldAccess(),
            SectionKey(0, 0, 0),
            content,
            IDENTITY_TRANSFORM,
            Vec3.ZERO,
            1L,
            1L,
            1L,
        )
    }

    private fun factory(): MainThreadSectionBuildRequestFactory {
        return MainThreadSectionBuildRequestFactory()
    }

    private class RecordingWorldAccess : MainThreadWorldAccess {
        val sampledPositions = mutableListOf<BlockPos>()

        override fun height(): Int = 384

        override fun minBuildHeight(): Int = -64

        override fun shade(direction: Direction, shade: Boolean): Float = 0.75f

        override fun lightEngine(): LevelLightEngine {
            error("lightEngine is not used by this focused mapping test")
        }

        override fun blockTint(worldPos: BlockPos, resolver: ColorResolver): Int {
            sampledPositions += worldPos
            return 0x123456
        }

        override fun brightness(layer: LightLayer, worldPos: BlockPos): Int {
            sampledPositions += worldPos
            return 12
        }

        override fun rawBrightness(worldPos: BlockPos, ambientDarkness: Int): Int {
            sampledPositions += worldPos
            return 9
        }

        override fun canSeeSky(worldPos: BlockPos): Boolean {
            sampledPositions += worldPos
            return true
        }
    }

    public companion object {
        private val IDENTITY_TRANSFORM = RenderTransform(Vec3.ZERO, 0f, Vec3.ZERO, 0L)

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
