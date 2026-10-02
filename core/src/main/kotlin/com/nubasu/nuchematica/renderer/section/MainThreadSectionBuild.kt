package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.vertex.BufferBuilder
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3

internal data class SectionGeometry(
    internal val solid: BufferBuilder?,
    internal val translucent: BufferBuilder?,
    internal val translucentSortState: BufferBuilder.SortState?,
)

internal data class CursorAdvanceResult(
    internal val processedBlocks: Int,
    internal val complete: Boolean,
    internal val deadlineReached: Boolean,
)

internal class MainThreadGuard private constructor(
    private val ownerThread: Thread,
) {
    internal fun checkOwnerThread(): Unit {
        check(Thread.currentThread() === ownerThread) {
            "section geometry access must stay on the client main thread"
        }
    }

    internal companion object {
        internal fun captureCurrentThread(): MainThreadGuard {
            return MainThreadGuard(Thread.currentThread())
        }
    }
}

internal interface MainThreadWorldAccess {
    fun height(): Int

    fun minBuildHeight(): Int

    fun shade(direction: Direction, shade: Boolean): Float

    fun lightEngine(): LevelLightEngine

    fun blockTint(worldPos: BlockPos, resolver: ColorResolver): Int

    fun brightness(layer: LightLayer, worldPos: BlockPos): Int

    fun rawBrightness(worldPos: BlockPos, ambientDarkness: Int): Int

    fun canSeeSky(worldPos: BlockPos): Boolean
}

internal class ClientLevelWorldAccess(
    private val level: ClientLevel,
    private val threadGuard: MainThreadGuard,
) : MainThreadWorldAccess {
    override fun height(): Int {
        threadGuard.checkOwnerThread()
        return level.height
    }

    override fun minBuildHeight(): Int {
        threadGuard.checkOwnerThread()
        return level.minBuildHeight
    }

    override fun shade(direction: Direction, shade: Boolean): Float {
        threadGuard.checkOwnerThread()
        return level.getShade(direction, shade)
    }

    override fun lightEngine(): LevelLightEngine {
        threadGuard.checkOwnerThread()
        return level.lightEngine
    }

    override fun blockTint(worldPos: BlockPos, resolver: ColorResolver): Int {
        threadGuard.checkOwnerThread()
        return level.getBlockTint(worldPos, resolver)
    }

    override fun brightness(layer: LightLayer, worldPos: BlockPos): Int {
        threadGuard.checkOwnerThread()
        return level.getBrightness(layer, worldPos)
    }

    override fun rawBrightness(worldPos: BlockPos, ambientDarkness: Int): Int {
        threadGuard.checkOwnerThread()
        return level.getRawBrightness(worldPos, ambientDarkness)
    }

    override fun canSeeSky(worldPos: BlockPos): Boolean {
        threadGuard.checkOwnerThread()
        return level.canSeeSky(worldPos)
    }
}

internal class MainThreadSchematicRenderView(
    private val content: SchematicContentSnapshot,
    private val transform: RenderTransform,
    private val world: MainThreadWorldAccess,
    private val threadGuard: MainThreadGuard,
) : BlockAndTintGetter {
    override fun getBlockEntity(pos: BlockPos): BlockEntity? {
        threadGuard.checkOwnerThread()
        return null
    }

    override fun getBlockState(pos: BlockPos): BlockState {
        threadGuard.checkOwnerThread()
        return content.blockStateAt(pos)
    }

    override fun getFluidState(pos: BlockPos): FluidState {
        threadGuard.checkOwnerThread()
        return content.fluidStateAt(pos)
    }

    override fun getHeight(): Int {
        threadGuard.checkOwnerThread()
        return world.height()
    }

    override fun getMinBuildHeight(): Int {
        threadGuard.checkOwnerThread()
        return world.minBuildHeight()
    }

    override fun getShade(direction: Direction, shade: Boolean): Float {
        threadGuard.checkOwnerThread()
        return world.shade(direction, shade)
    }

    override fun getLightEngine(): LevelLightEngine {
        threadGuard.checkOwnerThread()
        return world.lightEngine()
    }

    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int {
        threadGuard.checkOwnerThread()
        return world.blockTint(transform.localBlockToWorld(pos), resolver)
    }

    override fun getBrightness(layer: LightLayer, pos: BlockPos): Int {
        threadGuard.checkOwnerThread()
        return world.brightness(layer, transform.localBlockToWorld(pos))
    }

    override fun getRawBrightness(pos: BlockPos, ambientDarkness: Int): Int {
        threadGuard.checkOwnerThread()
        return world.rawBrightness(transform.localBlockToWorld(pos), ambientDarkness)
    }

    override fun canSeeSky(pos: BlockPos): Boolean {
        threadGuard.checkOwnerThread()
        return world.canSeeSky(transform.localBlockToWorld(pos))
    }
}

internal class MainThreadSectionBuildCursor(
    blocks: Map<BlockPos, BlockState>,
    private val threadGuard: MainThreadGuard,
) {
    private val entries: List<Map.Entry<BlockPos, BlockState>> = blocks.entries.toList()
    private var nextIndex: Int = 0

    internal val isComplete: Boolean
        get() {
            threadGuard.checkOwnerThread()
            return nextIndex >= entries.size
        }

    internal val remainingBlocks: Int
        get() {
            threadGuard.checkOwnerThread()
            return entries.size - nextIndex
        }

    internal fun advanceWithinBudget(
        deadlineNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
        consume: (BlockPos, BlockState) -> Unit,
    ): CursorAdvanceResult {
        threadGuard.checkOwnerThread()
        var processed = 0
        var deadlineReached = false
        while (nextIndex < entries.size) {
            val entry = entries[nextIndex]
            consume(entry.key, entry.value)
            nextIndex++
            processed++

            if (nextIndex < entries.size && nanoTime() >= deadlineNanos) {
                deadlineReached = true
                break
            }
        }
        return CursorAdvanceResult(
            processedBlocks = processed,
            complete = nextIndex >= entries.size,
            deadlineReached = deadlineReached,
        )
    }
}

internal class MainThreadSectionBuildRequest internal constructor(
    internal val key: SectionKey,
    internal val content: SchematicContentSnapshot,
    suppressed: Set<BlockPos>,
    internal val transform: RenderTransform,
    internal val sortOrigin: Vec3,
    internal val meshEpoch: Long,
    internal val sectionGeometryGeneration: Long,
    internal val cameraSortRevision: Long,
    world: MainThreadWorldAccess,
    threadGuard: MainThreadGuard,
) {
    internal val view: MainThreadSchematicRenderView =
        MainThreadSchematicRenderView(content, transform, world, threadGuard)

    internal val cursor: MainThreadSectionBuildCursor =
        MainThreadSectionBuildCursor(
            content.blocksInSection(key).filterKeys { it !in suppressed },
            threadGuard,
        )
}

internal class MainThreadSectionBuildRequestFactory(
    private val threadGuard: MainThreadGuard = MainThreadGuard.captureCurrentThread(),
) {
    internal fun create(
        level: ClientLevel,
        key: SectionKey,
        content: SchematicContentSnapshot,
        transform: RenderTransform,
        sortOrigin: Vec3,
        meshEpoch: Long,
        sectionGeometryGeneration: Long,
        cameraSortRevision: Long,
        suppressed: Set<BlockPos> = emptySet(),
    ): MainThreadSectionBuildRequest {
        threadGuard.checkOwnerThread()
        return create(
            world = ClientLevelWorldAccess(level, threadGuard),
            key = key,
            content = content,
            suppressed = suppressed,
            transform = transform,
            sortOrigin = sortOrigin,
            meshEpoch = meshEpoch,
            sectionGeometryGeneration = sectionGeometryGeneration,
            cameraSortRevision = cameraSortRevision,
        )
    }

    internal fun create(
        world: MainThreadWorldAccess,
        key: SectionKey,
        content: SchematicContentSnapshot,
        transform: RenderTransform,
        sortOrigin: Vec3,
        meshEpoch: Long,
        sectionGeometryGeneration: Long,
        cameraSortRevision: Long,
        suppressed: Set<BlockPos> = emptySet(),
    ): MainThreadSectionBuildRequest {
        threadGuard.checkOwnerThread()
        return MainThreadSectionBuildRequest(
            key = key,
            content = content,
            suppressed = suppressed,
            transform = transform,
            sortOrigin = sortOrigin,
            meshEpoch = meshEpoch,
            sectionGeometryGeneration = sectionGeometryGeneration,
            cameraSortRevision = cameraSortRevision,
            world = world,
            threadGuard = threadGuard,
        )
    }
}
