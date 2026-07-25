package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

// Outcome of resolving a single missing position into a placeable candidate. Each
// non-Resolved variant corresponds to one of PrinterCandidateSelector.select's
// per-position early-continue points, so the selector can turn a variant back into its
// existing skip-log/candidate handling without re-deriving the reason.
internal sealed class PlacementResolution {
    internal data class Resolved(
        val hit: BlockHitResult,
        val distanceSquared: Double,
        val requiredRotation: PlacementRotation?,
        val placementState: BlockState,
    ) : PlacementResolution()

    // Covers both the coarse distance prefilter and every support hit falling outside
    // the real reach sphere: both are "nothing here was in reach" from the caller's
    // point of view.
    internal object OutOfReach : PlacementResolution()
    internal object TargetNotReplaceable : PlacementResolution()
    internal object NoSupportFace : PlacementResolution()
    internal object CategoryExcluded : PlacementResolution()
    internal object PredictionMismatch : PlacementResolution()
}

// Margin added to reach for the coarse prefilter: a hit can land on the face of a block
// adjacent to the missing position, up to ~1 block farther from the eye than the missing
// position's own center.
private const val PREFILTER_MARGIN: Double = 1.0

// Pure per-position placement pipeline extracted from PrinterCandidateSelector.select:
// coarse distance prefilter, replaceable-target check, support-face hit generation, reach
// filtering, item eligibility, then prediction/oriented-rotation trial. No logging, no
// holder reads, no mutation -- every input the decision depends on is an argument,
// including the behavior settings snapshot the selector reads on the caller's behalf.
//
// The settings snapshot is taken ONCE per position and reused for both the eligibility
// derivation and the prediction equivalence check -- deliberately atomic, so a settings
// change can never produce a mixed-behavior verdict within one position. This holds only
// under an invariant the callers must keep: the three injected callbacks never mutate the
// live settings holder (they are coordinate/prediction machinery, not configuration), and
// any world/player state they touch is restored before returning. A caller that wires in a
// callback violating that forfeits this function's purity, not just its determinism.
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

    // Coarse distance prefilter before any state read: on large schematics, reading
    // target + 6 neighbor states for every missing block every tick is the dominant
    // cost. Positions that cannot possibly be in reach (even accounting for a hit
    // landing on an adjacent block's face) are rejected here without touching
    // stateAt/supportHits/prediction at all.
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
            ResolvedHit(hit, distanceSquared, requiredRotation = null)
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

private data class ResolvedHit(
    val hit: BlockHitResult,
    val distanceSquared: Double,
    val requiredRotation: PlacementRotation?,
)

// Builds its hits from the shared supportHitPoint geometry in PrinterCandidateSelector.kt,
// adding only the world-dependent filtering (does a support block actually exist at the
// neighbor) on top -- kept aligned with potentialSupportHitPoints' pure envelope so the
// two never drift apart.
private fun supportHits(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
): List<BlockHitResult> {
    return Direction.values().mapNotNull { supportDirection ->
        val location = supportHitPoint(worldPos, expectedState, supportDirection)
            ?: return@mapNotNull null
        val supportPos = worldPos.relative(supportDirection)
        if (!isSupportingState(stateAt(supportPos))) return@mapNotNull null
        BlockHitResult(location, supportDirection.opposite, supportPos, false)
    }
}
