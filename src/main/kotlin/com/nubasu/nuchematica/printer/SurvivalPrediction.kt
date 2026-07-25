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

// A named, never-swallowed signal that some vanilla code path touched a member this
// planning-time adapter deliberately does not back with real data. canSurvive and
// updateFromNeighbourShapes only ever need to read neighbouring block states (plus, for
// some blocks, schedule a future tick -- recorded rather than acted on, see
// SurvivalLevelAccessorAdapter's own scheduleTick overloads); hitting this at all means
// either a block type that reads further state than that, or a future MC/Forge internal
// change, and both are exactly the "unknown -> conservative failure" case
// survivesWithoutScaffold's own catch converts into a per-position false rather than a
// crash or a silently wrong default.
private fun unsupportedMember(methodName: String): Nothing =
    throw UnsupportedOperationException("SurvivalPrediction adapter: $methodName is not backed (read-only getBlockState adapter)")

// Minimal read-only LevelReader for BlockState.canSurvive: getBlockState delegates to the
// caller's own view (the plan's placedInSlot-gated frozen-world view, so a scaffold cell's
// removal is already reflected -- see survivesWithoutScaffold's own doc for why no
// separate "post-removal" view is needed). Every other member throws: canSurvive never
// needs more than neighbouring block states.
private class SurvivalLevelReaderAdapter(private val view: (BlockPos) -> BlockState) : LevelReader {
    override fun getBlockState(pos: BlockPos): BlockState = view(pos)

    // A BlockState's own fluid state (e.g. a waterlogged property, or the fluid a source
    // block like WATER itself represents) is derivable purely from the state view already
    // backs getBlockState with -- no separate world-fluid storage is ever consulted.
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

// Isomorphic LevelAccessor for Block.updateFromNeighbourShapes: same delegate/throw split
// as SurvivalLevelReaderAdapter, just against the much larger LevelAccessor member set the
// static method's signature happens to require. updateFromNeighbourShapes only ever reads
// neighbouring block states and, for some blocks (e.g. leaves rescheduling their own decay
// recheck), schedules a future tick -- both handled below -- despite LevelAccessor's much
// larger write-capable surface.
private class SurvivalLevelAccessorAdapter(private val view: (BlockPos) -> BlockState) : LevelAccessor {
    // Set by any of the four scheduleTick overloads below -- see their own doc for why a
    // request is recorded rather than silently dropped.
    internal var tickRequested: Boolean = false
        private set

    override fun getBlockState(pos: BlockPos): BlockState = view(pos)

    // Same derivation as SurvivalLevelReaderAdapter.getFluidState -- purely a function of
    // the state view already backs getBlockState with.
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

    // scheduleTick is how a block asks to be re-evaluated on a LATER tick (e.g. sand
    // scheduling its own fall check, or leaves scheduling their own decay recheck) --
    // the returned BlockState from this single static call is already final regardless of
    // whether that future tick ever runs, so the request itself is recorded rather than
    // acted on. A block that schedules a tick as a side effect of computing its shape is
    // asking the world to re-examine it later, which this one-shot predictor cannot
    // simulate; survivesWithoutScaffold treats that as its own conservative failure
    // unless the block is on its own short allowlist of ticks known not to threaten the
    // schematic's expected state (see that allowlist's own doc).
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

// Whether `expected` at `pos` would keep holding its own expected state once a scaffold
// chain that helped place it is removed. This is a two-part check: canSurvive alone is not
// enough, since a block can canSurvive with reduced support yet recompute to a DIFFERENT
// state than the schematic expects (e.g. vine losing one of two attached faces -- see
// updateFromNeighbourShapes below). `view` is the plan's own classification-time view
// (placedInSlot-gated, see PrintPlanner.plan): scaffold cells are never placedInSlot (they
// are a transient action, never a plan-first target of their own), so this view already
// reads as "scaffold cells never existed" -- there is no separate "post-removal" view to
// construct.
//
// Both checks are run against `placed` -- the state the printer would actually place, via
// effectivePlacementState's lookalike substitution -- rather than the schematic's raw
// `expected`: the printer never places some raw expected states as-is (e.g. a double slab
// is placed as its full-block lookalike), so a survival check against the raw state would
// be asking whether a block the printer never places would survive. The final equivalence
// check still compares the predicted outcome against the original `expected`, since
// BlockStateEquivalence.matches already knows how to relate a placed lookalike back to the
// schematic's own expected state -- including a schematic's own natural (worldgen)
// PERSISTENT=false leaf state, which is never what actually gets placed either.
//
// Any exception during either check -- including one of the adapters' own named
// UnsupportedOperationException -- is treated as this position's own conservative
// failure, never as an optimistic pass and never propagated to abort the whole plan: an
// unknown block touching an unbacked adapter member is exactly the signal this design
// wants to surface as "cannot confirm survival," not a crash.
internal fun survivesWithoutScaffold(
    pos: BlockPos,
    expected: BlockState,
    view: (BlockPos) -> BlockState,
    // No caller outside PrintPlanner exists (PrintPlanner always passes its own
    // PrintPlanParams.behavior explicitly) -- this default exists only so every existing
    // test call keeps its prior behavior, and deliberately mirrors PrinterSettings()'s own
    // class-level defaults rather than reading the live PrinterSettingsHolder, matching
    // PrintPlanParams.behavior's own hardcoded default (a planner input is never allowed
    // to fall back to a mid-run holder read).
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

// BaseRailBlock.canSurvive only checks canSupportRigidBlock(pos.below()) (a sturdy floor)
// -- confirmed via its own bytecode, which reads no other position. The real vanilla
// drop check for an ASCENDING shape additionally requires canSupportRigidBlock at
// pos.relative(ascendingDirection) (the same-Y-level step the rail's raised edge leans
// against), but that second check lives in BaseRailBlock's own private shouldBeRemoved,
// reached only from the live-world reactive neighborChanged path -- never from canSurvive
// or updateShape -- so this predictor's two static calls never see it on their own.
// Direction mapping (ASCENDING_NORTH -> north(), ASCENDING_SOUTH -> south(),
// ASCENDING_EAST -> east(), ASCENDING_WEST -> west()) confirmed via shouldBeRemoved's own
// per-shape canSupportRigidBlock(pos.relative(direction)) bytecode, one case per
// direction; NORTH_SOUTH/EAST_WEST and every curve shape fall to shouldBeRemoved's
// default case (no extra check beyond the floor). getRailDirection(state, getter, pos,
// null) is used rather than reading getShapeProperty() directly -- it is the exact call
// vanilla's own neighborChanged makes before feeding the result into shouldBeRemoved, applies
// uniformly to every BaseRailBlock subclass (plain, powered, detector, activator), and
// leaves room for a Forge-side override of the rail's effective shape (the null
// AbstractMinecart is the same "no specific cart" case vanilla's own reactive path allows
// for). Not a BaseRailBlock at all is the overwhelmingly common case, returned true
// immediately without touching the adapter.
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

// Blocks whose own scheduleTick request during shape recomputation is known not to
// threaten the schematic's own expected state, so a tick request from one of them is not
// treated as this position's conservative failure. LEAVES is the only member: the tick
// LeavesBlock.updateShape schedules is its own future decay recheck, which can only ever
// change DISTANCE and PERSISTENT -- both properties BlockStateEquivalence.matches already
// ignores for a leaves block regardless of what that unsimulated future tick would settle
// them to, so the tick request itself can never change whether this position matches.
private fun tickRequestIsBenign(placed: BlockState): Boolean = placed.block is LeavesBlock
