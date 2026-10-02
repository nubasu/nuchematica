package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** Typed outcome of resolving one missing position into a placement candidate. */
internal sealed class PlacementResolution {
    internal data class Resolved(
        val hit: BlockHitResult,
        val distanceSquared: Double,
        val requiredRotation: PlacementRotation?,
        val placementState: BlockState,
    ) : PlacementResolution()

    internal object OutOfReach : PlacementResolution()
    internal object TargetNotReplaceable : PlacementResolution()
    internal object NoSupportFace : PlacementResolution()
    internal object CategoryExcluded : PlacementResolution()
    internal object PredictionMismatch : PlacementResolution()
}

private const val PREFILTER_MARGIN: Double = 1.0

/**
 * Resolves one position without logging or mutating printer state.
 *
 * The supplied [settings] snapshot is used for both eligibility and prediction matching.
 */
internal fun resolvePlacement(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
    eyePosition: Vec3,
    reach: Double,
    settings: PlacementBehaviorSettings,
    placementContext: (BlockState, BlockHitResult) -> BlockPlaceContext,
    predictPlacement: (BlockItem, BlockPlaceContext) -> BlockState?,
    orientedPrediction: (BlockItem, BlockPlaceContext, BlockState) -> PlacementRotation?,
): PlacementResolution {
    val reachSquared = reach * reach
    val prefilterReachSquared = (reach + PREFILTER_MARGIN) * (reach + PREFILTER_MARGIN)
    val effectiveExpected = effectivePlacementState(expectedState, settings)

    val deltaX = eyePosition.x - (worldPos.x + 0.5)
    val deltaY = eyePosition.y - (worldPos.y + 0.5)
    val deltaZ = eyePosition.z - (worldPos.z + 0.5)
    val centerDistanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
    if (centerDistanceSquared > prefilterReachSquared) return PlacementResolution.OutOfReach

    if (!isReplaceableTarget(stateAt(worldPos))) return PlacementResolution.TargetNotReplaceable

    val hits = supportHits(worldPos, effectiveExpected, stateAt)
    if (hits.isEmpty()) return PlacementResolution.NoSupportFace

    val reachableHits = hits.map { hit ->
        hit to eyePosition.distanceToSqr(hit.location)
    }.filter { (_, distanceSquared) ->
        distanceSquared <= reachSquared
    }.sortedBy { (_, distanceSquared) -> distanceSquared }
    if (reachableHits.isEmpty()) return PlacementResolution.OutOfReach

    val item = eligiblePlacementBlockItem(effectiveExpected) ?: return PlacementResolution.CategoryExcluded

    val resolved = reachableHits.firstNotNullOfOrNull { (hit, distanceSquared) ->
        val context = placementContext(effectiveExpected, hit)
        val predicted = predictPlacement(item, context)
        if (predicted != null && BlockStateEquivalence.matches(effectiveExpected, predicted, settings)) {
            ResolvedHit(
                hit,
                distanceSquared,
                requiredRotation = explicitCurrentRotationForMatchingState(effectiveExpected, context),
            )
        } else {
            val rotation = orientedPrediction(item, context, effectiveExpected)
            if (rotation != null) ResolvedHit(hit, distanceSquared, rotation) else null
        }
    } ?: return PlacementResolution.PredictionMismatch

    return PlacementResolution.Resolved(
        hit = resolved.hit,
        distanceSquared = resolved.distanceSquared,
        requiredRotation = resolved.requiredRotation,
        placementState = effectiveExpected,
    )
}

private fun explicitCurrentRotationForMatchingState(
    expectedState: BlockState,
    context: BlockPlaceContext,
): PlacementRotation? {
    val properties = expectedState.properties
    val rotationSensitive =
        BlockStateProperties.HORIZONTAL_FACING in properties ||
            BlockStateProperties.FACING in properties ||
            BlockStateProperties.ATTACH_FACE in properties ||
            BlockStateProperties.ROTATION_16 in properties
    if (!rotationSensitive) return null
    val player = context.player ?: return null
    return PlacementRotation(player.yRot, player.xRot)
}

private data class ResolvedHit(
    val hit: BlockHitResult,
    val distanceSquared: Double,
    val requiredRotation: PlacementRotation?,
)

/** Returns hit points only for support faces present in the supplied world view. */
internal fun availableSupportHitPoints(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
): List<Vec3> {
    return Direction.values().mapNotNull { supportDirection ->
        val supportPos = worldPos.relative(supportDirection)
        val face = supportDirection.opposite
        if (!isUsableSupportNeighbor(expectedState, stateAt(supportPos), face)) return@mapNotNull null
        supportHitPoint(worldPos, expectedState, supportDirection)
    }
}

private fun supportHits(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
): List<BlockHitResult> {
    return Direction.values().mapNotNull { supportDirection ->
        val location = supportHitPoint(worldPos, expectedState, supportDirection)
            ?: return@mapNotNull null
        val supportPos = worldPos.relative(supportDirection)
        if (!isUsableSupportNeighbor(expectedState, stateAt(supportPos), supportDirection.opposite)) {
            return@mapNotNull null
        }
        BlockHitResult(location, supportDirection.opposite, supportPos, false)
    }
}
