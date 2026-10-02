package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.SignItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.block.state.properties.WallSide
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

public data class PrinterCandidate(
    public val localPos: BlockPos,
    public val worldPos: BlockPos,
    public val hit: BlockHitResult,
    public val expectedState: BlockState,
    public val distanceSquared: Double,
    public val requiredRotation: PlacementRotation? = null,
    public val placementState: BlockState = expectedState,
)

public data class PlacementRotation(public val yaw: Float, public val pitch: Float)

internal fun orientedPlacementTrials(currentYaw: Float): List<PlacementRotation> {
    val cardinalYaws = listOf(0f, 90f, 180f, -90f)
    val verticalYaws = (listOf(currentYaw) + cardinalYaws).distinct()
    return cardinalYaws.map { yaw -> PlacementRotation(yaw, 0f) } +
        verticalYaws.flatMap { yaw ->
            listOf(PlacementRotation(yaw, 89f), PlacementRotation(yaw, -89f))
        }
}

internal fun resolveOrientedRotation(
    currentYaw: Float,
    currentPitch: Float,
    setRotation: (Float, Float) -> Unit,
    matches: (PlacementRotation) -> Boolean,
): PlacementRotation? {
    try {
        return orientedPlacementTrials(currentYaw).firstOrNull { trial ->
            setRotation(trial.yaw, trial.pitch)
            matches(trial)
        }
    } finally {
        setRotation(currentYaw, currentPitch)
    }
}

public fun interface PlacementOrderStrategy {
    public fun order(candidates: List<PrinterCandidate>, eyePosition: Vec3): List<PrinterCandidate>
}

public object EyeDistancePlacementOrderStrategy : PlacementOrderStrategy {
    override fun order(
        candidates: List<PrinterCandidate>,
        eyePosition: Vec3,
    ): List<PrinterCandidate> {
        return candidates.sortedBy { candidate ->
            eyePosition.distanceToSqr(candidate.hit.location)
        }
    }
}

public object BottomUpPlacementOrderStrategy : PlacementOrderStrategy {
    override fun order(
        candidates: List<PrinterCandidate>,
        eyePosition: Vec3,
    ): List<PrinterCandidate> {
        return candidates.sortedWith(
            compareBy<PrinterCandidate> { candidate -> candidate.worldPos.y }
                .thenBy { candidate -> eyePosition.distanceToSqr(candidate.hit.location) }
                .thenBy { candidate -> candidate.worldPos.x }
                .thenBy { candidate -> candidate.worldPos.z },
        )
    }
}

internal fun eligiblePrinterBlockItem(state: BlockState, settings: PlacementBehaviorSettings): BlockItem? =
    eligiblePlacementBlockItem(effectivePlacementState(state, settings))

internal fun eligiblePrinterBlockItem(state: BlockState): BlockItem? =
    eligiblePrinterBlockItem(state, currentPlacementBehaviorSettings())

internal fun eligiblePlacementBlockItem(state: BlockState): BlockItem? {
    val item = state.block.asItem()
    if (item is SignItem) return null
    val block = state.block
    if (
        block is SlabBlock &&
        state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
    ) {
        return null
    }
    return if (
        item is BlockItem &&
        block !is DoublePlantBlock &&
        block !is DoorBlock &&
        block !is BedBlock
    ) {
        item
    } else {
        null
    }
}

internal fun isSupportingState(state: BlockState): Boolean =
    !state.isAir &&
        !state.material.isReplaceable &&
        PrinterSupportPolicy.isInteractionSafe(state)

internal fun isReplaceableTarget(state: BlockState): Boolean =
    state.isAir || state.material.isReplaceable

internal enum class VerticalHalf { TOP, BOTTOM }

internal fun expectedVerticalHalf(expectedState: BlockState): VerticalHalf? {
    return when (expectedState.block) {
        is SlabBlock -> when (expectedState.getValue(BlockStateProperties.SLAB_TYPE)) {
            SlabType.TOP -> VerticalHalf.TOP
            SlabType.BOTTOM -> VerticalHalf.BOTTOM
            SlabType.DOUBLE -> null
            else -> null
        }
        is StairBlock, is TrapDoorBlock -> when (expectedState.getValue(BlockStateProperties.HALF)) {
            Half.TOP -> VerticalHalf.TOP
            Half.BOTTOM -> VerticalHalf.BOTTOM
            else -> null
        }
        else -> null
    }
}

internal fun isUsableSupportFace(expectedState: BlockState, face: Direction): Boolean {
    val verticalHalf = expectedVerticalHalf(expectedState)
    val allowedByHalf =
        (verticalHalf != VerticalHalf.TOP || face != Direction.UP) &&
            (verticalHalf != VerticalHalf.BOTTOM || face != Direction.DOWN)
    val expectedPillarAxis = if (
        expectedState.block is RotatedPillarBlock &&
        BlockStateProperties.AXIS in expectedState.properties
    ) {
        expectedState.getValue(BlockStateProperties.AXIS)
    } else {
        null
    }
    val allowedByPillarAxis = expectedPillarAxis == null || face.axis == expectedPillarAxis
    val allowedByFaceAttachment = if (BlockStateProperties.ATTACH_FACE in expectedState.properties) {
        when (expectedState.getValue(BlockStateProperties.ATTACH_FACE)) {
            AttachFace.FLOOR -> face == Direction.UP
            AttachFace.CEILING -> face == Direction.DOWN
            AttachFace.WALL -> true
            else -> false
        }
    } else {
        true
    }
    val allowedByTorchAttachment = when (expectedState.block) {
        Blocks.TORCH,
        Blocks.SOUL_TORCH,
        Blocks.REDSTONE_TORCH,
        -> face == Direction.UP
        Blocks.WALL_TORCH,
        Blocks.SOUL_WALL_TORCH,
        Blocks.REDSTONE_WALL_TORCH,
        -> face == expectedState.getValue(BlockStateProperties.HORIZONTAL_FACING)
        else -> true
    }
    return allowedByHalf && allowedByPillarAxis && allowedByFaceAttachment && allowedByTorchAttachment
}

internal fun isUsableSupportNeighbor(
    expectedState: BlockState,
    supportState: BlockState,
    face: Direction,
): Boolean {
    if (!isSupportingState(supportState)) return false
    if (
        expectedState.block is TrapDoorBlock &&
        face.axis.isHorizontal &&
        face != expectedState.getValue(BlockStateProperties.HORIZONTAL_FACING)
    ) {
        return false
    }
    if (expectedState.block !is SlabBlock || supportState.block != expectedState.block) return true
    val supportType = supportState.getValue(BlockStateProperties.SLAB_TYPE)
    if (supportType == SlabType.DOUBLE) return true
    val horizontal = face.axis.isHorizontal
    val upperHalfSideClick = horizontal && expectedVerticalHalf(expectedState) == VerticalHalf.TOP
    val mergesSupport = when (supportType) {
        SlabType.BOTTOM -> face == Direction.UP || upperHalfSideClick
        SlabType.TOP -> face == Direction.DOWN || (horizontal && !upperHalfSideClick)
        SlabType.DOUBLE -> false
        else -> false
    }
    return !mergesSupport
}

internal fun hasSupportNeighbor(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
): Boolean {
    return Direction.values().any { direction ->
        val face = direction.opposite
        isUsableSupportFace(expectedState, face) &&
            isUsableSupportNeighbor(expectedState, stateAt(worldPos.relative(direction)), face)
    }
}

/**
 * Checks category, replacement, and support or scaffold eligibility.
 *
 * Reach, layer gating, deferral, and player collision are outside this predicate.
 */
internal fun isActionableMissing(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
    hasSupport: (BlockPos, BlockState, (BlockPos) -> BlockState) -> Boolean = ::hasSupportNeighbor,
    canScaffold: (BlockPos, BlockState, (BlockPos) -> BlockState) -> Boolean = { _, _, _ -> false },
): Boolean {
    val effectiveExpected = effectivePlacementState(expectedState)
    return eligiblePlacementBlockItem(effectiveExpected) != null &&
        isReplaceableTarget(stateAt(worldPos)) &&
        (hasSupport(worldPos, effectiveExpected, stateAt) || canScaffold(worldPos, effectiveExpected, stateAt))
}

internal fun supportHitPoint(
    worldPos: BlockPos,
    expectedState: BlockState,
    supportDirection: Direction,
): Vec3? {
    val face = supportDirection.opposite
    if (!isUsableSupportFace(expectedState, face)) return null
    val supportPos = worldPos.relative(supportDirection)
    val normal = face.normal
    val faceOffset = 0.5 - HIT_EPSILON
    val faceCenter = Vec3(
        supportPos.x + 0.5 + normal.x * faceOffset,
        supportPos.y + 0.5 + normal.y * faceOffset,
        supportPos.z + 0.5 + normal.z * faceOffset,
    )
    val verticalHalf = expectedVerticalHalf(expectedState)
    return when {
        face.axis.isHorizontal && verticalHalf == VerticalHalf.TOP ->
            faceCenter.add(0.0, VERTICAL_HALF_OFFSET, 0.0)
        face.axis.isHorizontal && verticalHalf == VerticalHalf.BOTTOM ->
            faceCenter.add(0.0, -VERTICAL_HALF_OFFSET, 0.0)
        else -> faceCenter
    }
}

internal fun potentialSupportHitPoints(worldPos: BlockPos, expectedState: BlockState): List<Vec3> {
    return Direction.values().mapNotNull { supportDirection ->
        supportHitPoint(worldPos, expectedState, supportDirection)
    }
}

private const val HIT_EPSILON: Double = 0.0001
private const val VERTICAL_HALF_OFFSET: Double = 0.25

/** Checks the player's full footprint and vertical body cells, not just its center. */
internal fun isInPlayerColumn(worldPos: BlockPos, playerFeetPos: Vec3?): Boolean {
    if (playerFeetPos == null) return false
    val overlapsX =
        worldPos.x.toDouble() < playerFeetPos.x + PLAYER_HALF_WIDTH &&
            (worldPos.x + 1).toDouble() > playerFeetPos.x - PLAYER_HALF_WIDTH
    val overlapsZ =
        worldPos.z.toDouble() < playerFeetPos.z + PLAYER_HALF_WIDTH &&
            (worldPos.z + 1).toDouble() > playerFeetPos.z - PLAYER_HALF_WIDTH
    if (!overlapsX || !overlapsZ) return false
    val feetCellY = floor(playerFeetPos.y).toInt()
    return worldPos.y in feetCellY..(feetCellY + 2)
}

private const val PLAYER_HALF_WIDTH: Double = 0.3
private const val PLAYER_COLLISION_HEIGHT: Double = 1.8
private const val PLAYER_COLLISION_MIN_BUILD_HEIGHT: Int = -64
private const val PLAYER_COLLISION_LEVEL_HEIGHT: Int = 384

private class PlacementCollisionBlockGetter(
    private val worldPos: BlockPos,
    private val placementState: BlockState,
    private val stateAt: (BlockPos) -> BlockState,
) : BlockGetter {
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
    override fun getBlockState(pos: BlockPos): BlockState = if (pos == worldPos) placementState else stateAt(pos)
    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
    override fun getHeight(): Int = PLAYER_COLLISION_LEVEL_HEIGHT
    override fun getMinBuildHeight(): Int = PLAYER_COLLISION_MIN_BUILD_HEIGHT
}

/** Widens neighbor-dependent shapes for conservative in-flight collision reservation. */
internal fun placementCollisionReservationState(placementState: BlockState): BlockState {
    return when (placementState.block) {
        is TrapDoorBlock -> Blocks.STONE.defaultBlockState()
        is CrossCollisionBlock -> placementState
            .setValue(BlockStateProperties.NORTH, true)
            .setValue(BlockStateProperties.EAST, true)
            .setValue(BlockStateProperties.SOUTH, true)
            .setValue(BlockStateProperties.WEST, true)
        is WallBlock -> placementState
            .setValue(BlockStateProperties.NORTH_WALL, WallSide.TALL)
            .setValue(BlockStateProperties.EAST_WALL, WallSide.TALL)
            .setValue(BlockStateProperties.SOUTH_WALL, WallSide.TALL)
            .setValue(BlockStateProperties.WEST_WALL, WallSide.TALL)
            .setValue(BlockStateProperties.UP, true)
        else -> placementState
    }
}

internal fun isPlacementBlockedByPlayer(
    worldPos: BlockPos,
    placementState: BlockState,
    playerFeetPos: Vec3?,
    stateAt: (BlockPos) -> BlockState,
): Boolean {
    if (playerFeetPos == null) return false
    if (isInPlayerColumn(worldPos, playerFeetPos)) return true
    val playerBox = AABB(
        playerFeetPos.x - PLAYER_HALF_WIDTH,
        playerFeetPos.y,
        playerFeetPos.z - PLAYER_HALF_WIDTH,
        playerFeetPos.x + PLAYER_HALF_WIDTH,
        playerFeetPos.y + PLAYER_COLLISION_HEIGHT,
        playerFeetPos.z + PLAYER_HALF_WIDTH,
    )
    val collisionState = placementCollisionReservationState(placementState)
    val getter = PlacementCollisionBlockGetter(worldPos, collisionState, stateAt)
    val shape = collisionState.getCollisionShape(getter, worldPos)
    if (shape.isEmpty) return false
    return shape.toAabbs().any { localBox ->
        playerBox.intersects(localBox.move(worldPos.x.toDouble(), worldPos.y.toDouble(), worldPos.z.toDouble()))
    }
}

public class PrinterCandidateSelector(
    private val orderStrategy: PlacementOrderStrategy = BottomUpPlacementOrderStrategy,
    private val skipLog: PrinterSkipLog = PrinterSkipLog(),
    private val onSkip: (PrinterSkipReason, BlockPos) -> Unit = { _, _ -> },
    private val predictPlacement: (BlockItem, BlockPlaceContext) -> BlockState? =
        { item, context -> item.getPlacementState(context) },
    private val orientedPrediction: (BlockItem, BlockPlaceContext, BlockState) -> PlacementRotation? =
        { _, _, _ -> null },
) {
    public fun select(
        missingLocal: List<BlockPos>,
        expectedStateAt: (BlockPos) -> BlockState?,
        localToWorld: (BlockPos) -> BlockPos,
        stateAt: (BlockPos) -> BlockState,
        placementContext: (BlockState, BlockHitResult) -> BlockPlaceContext,
        eyePosition: Vec3,
        reach: Double,
        playerFeetPos: Vec3? = null,
    ): List<PrinterCandidate> {
        require(reach >= 0.0)
        val candidates = mutableListOf<PrinterCandidate>()

        for (localPos in missingLocal) {
            val expectedState = expectedStateAt(localPos) ?: continue
            val worldPos = localToWorld(localPos).immutable()

            if (isPlacementBlockedByPlayer(worldPos, expectedState, playerFeetPos, stateAt)) continue

            when (
                val resolution = resolvePlacement(
                    worldPos = worldPos,
                    expectedState = expectedState,
                    stateAt = stateAt,
                    eyePosition = eyePosition,
                    reach = reach,
                    settings = currentPlacementBehaviorSettings(),
                    placementContext = placementContext,
                    predictPlacement = predictPlacement,
                    orientedPrediction = orientedPrediction,
                )
            ) {
                is PlacementResolution.Resolved -> {
                    if (
                        !isPlacementBlockedByPlayer(
                            worldPos,
                            resolution.placementState,
                            playerFeetPos,
                            stateAt,
                        )
                    ) {
                        candidates.add(
                            PrinterCandidate(
                                localPos = localPos.immutable(),
                                worldPos = worldPos,
                                hit = resolution.hit,
                                expectedState = expectedState,
                                placementState = resolution.placementState,
                                distanceSquared = resolution.distanceSquared,
                                requiredRotation = resolution.requiredRotation,
                            ),
                        )
                    }
                }
                PlacementResolution.TargetNotReplaceable -> Unit
                PlacementResolution.OutOfReach -> recordSkip(PrinterSkipReason.OUT_OF_REACH, worldPos)
                PlacementResolution.NoSupportFace -> recordSkip(PrinterSkipReason.NO_SUPPORT_FACE, worldPos)
                PlacementResolution.CategoryExcluded -> recordSkip(PrinterSkipReason.CATEGORY_EXCLUDED, worldPos)
                PlacementResolution.PredictionMismatch ->
                    recordSkip(PrinterSkipReason.PREDICTION_MISMATCH, worldPos)
            }
        }

        return orderStrategy.order(candidates, eyePosition)
    }

    private fun recordSkip(reason: PrinterSkipReason, worldPos: BlockPos): Unit {
        skipLog.record(reason, worldPos)
        onSkip(reason, worldPos)
    }
}
