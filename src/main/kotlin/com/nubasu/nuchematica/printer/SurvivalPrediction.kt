package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.SupportType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.RailShape
import net.minecraft.world.level.border.WorldBorder
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkSource
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.ticks.LevelTickAccess
import net.minecraft.world.ticks.TickPriority
import java.util.Random
import java.util.function.Predicate

private fun unsupportedMember(methodName: String): Nothing =
    throw UnsupportedOperationException("SurvivalPrediction adapter: $methodName is not backed (read-only getBlockState adapter)")

private class SurvivalLevelReaderAdapter(private val view: (BlockPos) -> BlockState) : LevelReader {
    override fun getBlockState(pos: BlockPos): BlockState = view(pos)

    override fun getFluidState(pos: BlockPos): FluidState = view(pos).fluidState
    override fun getBlockEntity(pos: BlockPos): BlockEntity = unsupportedMember("getBlockEntity")
    override fun getChunk(x: Int, z: Int, status: ChunkStatus, load: Boolean): ChunkAccess =
        unsupportedMember("getChunk")
    override fun hasChunk(x: Int, z: Int): Boolean = unsupportedMember("hasChunk")
    override fun getHeight(type: Heightmap.Types, x: Int, z: Int): Int = unsupportedMember("getHeight")
    override fun getSkyDarken(): Int = unsupportedMember("getSkyDarken")
    override fun getBiomeManager(): BiomeManager = unsupportedMember("getBiomeManager")
    override fun getUncachedNoiseBiome(x: Int, y: Int, z: Int): Holder<Biome> =
        unsupportedMember("getUncachedNoiseBiome")
    override fun isClientSide(): Boolean = unsupportedMember("isClientSide")
    override fun getSeaLevel(): Int = unsupportedMember("getSeaLevel")
    override fun dimensionType(): DimensionType = unsupportedMember("dimensionType")
    override fun getShade(direction: Direction, shade: Boolean): Float = unsupportedMember("getShade")
    override fun getLightEngine(): LevelLightEngine = unsupportedMember("getLightEngine")
    override fun getWorldBorder(): WorldBorder = unsupportedMember("getWorldBorder")
    override fun getEntityCollisions(entity: Entity?, aabb: AABB): List<VoxelShape> =
        unsupportedMember("getEntityCollisions")
}

private class SurvivalLevelAccessorAdapter(private val view: (BlockPos) -> BlockState) : LevelAccessor {
    internal var tickRequested: Boolean = false
        private set

    override fun getBlockState(pos: BlockPos): BlockState = view(pos)

    override fun getFluidState(pos: BlockPos): FluidState = view(pos).fluidState
    override fun getBlockEntity(pos: BlockPos): BlockEntity = unsupportedMember("getBlockEntity")
    override fun getChunk(x: Int, z: Int, status: ChunkStatus, load: Boolean): ChunkAccess =
        unsupportedMember("getChunk")
    override fun hasChunk(x: Int, z: Int): Boolean = unsupportedMember("hasChunk")
    override fun getHeight(type: Heightmap.Types, x: Int, z: Int): Int = unsupportedMember("getHeight")
    override fun getSkyDarken(): Int = unsupportedMember("getSkyDarken")
    override fun getBiomeManager(): BiomeManager = unsupportedMember("getBiomeManager")
    override fun getUncachedNoiseBiome(x: Int, y: Int, z: Int): Holder<Biome> =
        unsupportedMember("getUncachedNoiseBiome")
    override fun isClientSide(): Boolean = unsupportedMember("isClientSide")
    override fun getSeaLevel(): Int = unsupportedMember("getSeaLevel")
    override fun dimensionType(): DimensionType = unsupportedMember("dimensionType")
    override fun getShade(direction: Direction, shade: Boolean): Float = unsupportedMember("getShade")
    override fun getLightEngine(): LevelLightEngine = unsupportedMember("getLightEngine")
    override fun getWorldBorder(): WorldBorder = unsupportedMember("getWorldBorder")

    override fun getEntities(entity: Entity?, aabb: AABB, predicate: Predicate<in Entity>): List<Entity> =
        unsupportedMember("getEntities")
    override fun <T : Entity> getEntities(
        entityTypeTest: EntityTypeTest<Entity, T>,
        aabb: AABB,
        predicate: Predicate<in T>,
    ): List<T> = unsupportedMember("getEntities(EntityTypeTest)")
    override fun players(): List<Player> = unsupportedMember("players")

    override fun isStateAtPosition(pos: BlockPos, predicate: Predicate<BlockState>): Boolean =
        unsupportedMember("isStateAtPosition")
    override fun isFluidAtPosition(pos: BlockPos, predicate: Predicate<FluidState>): Boolean =
        unsupportedMember("isFluidAtPosition")

    override fun setBlock(pos: BlockPos, state: BlockState, flags: Int, recursionLeft: Int): Boolean =
        unsupportedMember("setBlock")
    override fun removeBlock(pos: BlockPos, isMoving: Boolean): Boolean = unsupportedMember("removeBlock")
    override fun destroyBlock(pos: BlockPos, drop: Boolean, entity: Entity?, recursionLeft: Int): Boolean =
        unsupportedMember("destroyBlock")

    override fun nextSubTickCount(): Long = unsupportedMember("nextSubTickCount")

    override fun scheduleTick(pos: BlockPos, block: Block, delay: Int): Unit {
        tickRequested = true
    }
    override fun scheduleTick(pos: BlockPos, block: Block, delay: Int, priority: TickPriority): Unit {
        tickRequested = true
    }
    override fun scheduleTick(pos: BlockPos, fluid: Fluid, delay: Int): Unit {
        tickRequested = true
    }
    override fun scheduleTick(pos: BlockPos, fluid: Fluid, delay: Int, priority: TickPriority): Unit {
        tickRequested = true
    }

    override fun getBlockTicks(): LevelTickAccess<Block> = unsupportedMember("getBlockTicks")
    override fun getFluidTicks(): LevelTickAccess<Fluid> = unsupportedMember("getFluidTicks")
    override fun getLevelData(): LevelData = unsupportedMember("getLevelData")
    override fun getCurrentDifficultyAt(pos: BlockPos): DifficultyInstance =
        unsupportedMember("getCurrentDifficultyAt")
    override fun getServer(): MinecraftServer? = unsupportedMember("getServer")
    override fun getChunkSource(): ChunkSource = unsupportedMember("getChunkSource")
    override fun getRandom(): Random = unsupportedMember("getRandom")
    override fun playSound(
        player: Player?,
        pos: BlockPos,
        sound: SoundEvent,
        source: SoundSource,
        volume: Float,
        pitch: Float,
    ): Unit = unsupportedMember("playSound")
    override fun addParticle(
        particle: ParticleOptions,
        x: Double,
        y: Double,
        z: Double,
        xSpeed: Double,
        ySpeed: Double,
        zSpeed: Double,
    ): Unit = unsupportedMember("addParticle")
    override fun levelEvent(player: Player?, type: Int, pos: BlockPos, data: Int): Unit =
        unsupportedMember("levelEvent")
    override fun gameEvent(entity: Entity?, event: GameEvent, pos: BlockPos): Unit =
        unsupportedMember("gameEvent")

    override fun registryAccess(): RegistryAccess = unsupportedMember("registryAccess")
}

/**
 * Predicts whether the effective placed state remains valid without scaffold support.
 *
 * Survival, neighbor-shape updates, scheduled instability, and ascending rail support are
 * checked. Unsupported world reads and other exceptions conservatively return false.
 */
internal fun survivesWithoutScaffold(
    pos: BlockPos,
    expected: BlockState,
    view: (BlockPos) -> BlockState,
    settings: PlacementBehaviorSettings = PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false),
): Boolean {
    return try {
        val placed = effectivePlacementState(expected, settings)
        if (!placed.canSurvive(SurvivalLevelReaderAdapter(view), pos)) return false
        if (!ascendingRailHasUphillSupport(placed, pos, view)) return false
        val accessor = SurvivalLevelAccessorAdapter(view)
        val predicted = Block.updateFromNeighbourShapes(placed, accessor, pos)
        if (accessor.tickRequested && !tickRequestIsBenign(placed)) return false
        BlockStateEquivalence.matches(expected, predicted, settings)
    } catch (e: Exception) {
        false
    }
}

private fun ascendingRailHasUphillSupport(
    placed: BlockState,
    pos: BlockPos,
    view: (BlockPos) -> BlockState,
): Boolean {
    val block = placed.block
    if (block !is BaseRailBlock) return true
    val shape = block.getRailDirection(placed, SurvivalLevelReaderAdapter(view), pos, null)
    val direction = when (shape) {
        RailShape.ASCENDING_NORTH -> Direction.NORTH
        RailShape.ASCENDING_SOUTH -> Direction.SOUTH
        RailShape.ASCENDING_EAST -> Direction.EAST
        RailShape.ASCENDING_WEST -> Direction.WEST
        else -> return true
    }
    val uphillPos = pos.relative(direction)
    return view(uphillPos).isFaceSturdy(SurvivalLevelReaderAdapter(view), uphillPos, Direction.UP, SupportType.RIGID)
}

private fun tickRequestIsBenign(placed: BlockState): Boolean = placed.block is LeavesBlock
