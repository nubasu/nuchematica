package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.SignItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
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

// Yaw/pitch a client must be facing at the moment of placement for the item's
// getPlacementState prediction to produce the schematic's expected state (stairs,
// chests, and other orientation-driven blocks). Float to match Entity.setYRot/setXRot.
public data class PlacementRotation(public val yaw: Float, public val pitch: Float)

// Fixed trial order for oriented placement: the four cardinal yaws first (covers
// facing-only blocks like stairs/chests), then two steep pitches at the caller's
// current yaw (covers half/shape decisions driven by looking near-up or near-down).
internal fun orientedPlacementTrials(currentYaw: Float): List<PlacementRotation> = listOf(
    PlacementRotation(0f, 0f),
    PlacementRotation(90f, 0f),
    PlacementRotation(180f, 0f),
    PlacementRotation(-90f, 0f),
    PlacementRotation(currentYaw, 89f),
    PlacementRotation(currentYaw, -89f),
)

// Tries each trial rotation via setRotation, keeping the first one for which matches
// reports success. Always restores the original rotation afterward: the printer tick
// runs after the player's own tick has already sent its movement packet this frame, so
// any rotation left in place here would never reach the server.
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

// Whether the printer could place into this world state at all: air or a replaceable
// material (plants, snow, fluids). A missing position whose world block is neither is
// a wrong-state block the printer cannot break, so it can never make progress; the
// layer gate and the mover must treat it exactly like the selector does,
// otherwise they count it as pending work, pin the gate on its layer forever, and the
// mover route over such positions ends in a spurious COMPLETE.
internal fun isReplaceableTarget(state: BlockState): Boolean =
    state.isAir || state.material.isReplaceable

// Vertical half implied by the schematic's expected state, generalized from the
// slab-only SLAB_TYPE bias to also cover StairBlock/TrapDoorBlock's HALF property.
// Confirmed against StairBlock.getStateForPlacement / TrapDoorBlock.getStateForPlacement
// bytecode (javap): a DOWN-face hit always yields TOP, an UP-face hit always yields
// BOTTOM, and a side-face hit yields TOP only when (clickLocation.y - clickedPos.y) >
// 0.5 -- the same rule SlabBlock uses for SLAB_TYPE. Blocks outside this set return
// null and leave supportHits/hasSupportNeighbor fully unbiased.
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

// Shared placement-face filter for the selector's hits and every support-only
// consumer (gate, mover, and classification). The half and pillar constraints are
// independent: pillars have no vertical half, while slabs/stairs/trapdoors have no
// pillar axis. Verified with javap -c -p against the mapped
// forge-1.18.2-40.3.0_mapped_official_1.18.2.jar: RotatedPillarBlock.getStateForPlacement
// reads BlockPlaceContext.getClickedFace().getAxis() and writes that value to AXIS, so
// a usable clicked face must lie on the expected pillar axis.
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
    return allowedByHalf && allowedByPillarAxis
}

// Half-aware support check: mirrors supportHits' own exclusion rule exactly
// -- a TOP half can never be placed by clicking the UP face of the block below (that
// click always yields BOTTOM, per expectedVerticalHalf's doc above), so the neighbor
// directly below does not count as support; symmetrically a BOTTOM half excludes the
// neighbor directly above. A naive 6-direction check would report a slab island
// resting only on the block below as supported even when expectedVerticalHalf was TOP,
// disagreeing with the selector's own supportHits (which already excludes that face)
// and pinning the printer's layer gate on layers it could never actually place from
// below.
internal fun hasSupportNeighbor(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
): Boolean {
    return Direction.values().any { direction ->
        isUsableSupportFace(expectedState, direction.opposite) &&
            isSupportingState(stateAt(worldPos.relative(direction)))
    }
}

// Single source of truth for "is this missing position workable right now,
// ignoring reach/deferral/gateY" -- without it, the same three-term conjunction
// (eligible item, replaceable target, supported neighbor) would need independent
// re-derivation in the selector, the layer gate, and the mover, where the three
// copies could silently drift apart. Deferral (PrinterDeferralLedger) and gateY are intentionally NOT part of
// this predicate: both are dynamic and callers must keep consulting them separately
// (see SchematicPrinter.tick's isLayerSupported/inReach-loop and SchematicMover's
// isPlaceable, which AND this predicate's result with their own deferral/gateY checks).
// hasSupport defaults to hasSupportNeighbor itself; callers that already maintain a
// support memo (LayerGateSupportMemo) pass its cached lookup instead, so unifying the
// term list here does not regress the memoized-support fast path in the layer gate.
//
// canScaffold is the equally shared OR term for "no real support,
// but ScaffoldPlanner could still place it" (see isScaffoldAssistable in
// ScaffoldPlanner.kt). It defaults to always-false so every existing caller that does not
// pass one keeps its exact prior behavior; callers that DO opt in (the layer gate's
// isLayerSupported/inReach calculations, the mover's isPlaceable) make a scaffold-
// plannable position actionable in every phase, not just RECOVERY/the final sweep.
// findScaffoldPlan's own two isActionableMissing calls deliberately do NOT pass one: the
// first needs "eligible + replaceable" alone (hasSupport hardcoded true), and the second
// needs "is this ALREADY supported without scaffolding" to decide whether a scaffold
// search is even needed -- folding canScaffold into either would make the second call
// always true for scaffoldable positions and defeat its purpose.
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

// The exact per-direction face-center + HIT_EPSILON +
// VERTICAL_HALF_OFFSET math supportHits builds its world-checked hits from, factored
// out so potentialSupportHitPoints (the pure envelope) and supportHits (the
// world-aware selector hits) share one geometry function instead of two copies that
// could drift apart. Returns null when the direction's face fails isUsableSupportFace
// -- no world read here, callers decide separately whether a support block actually
// exists at supportPos.
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

// Selector-aligned reach envelope -- for every face that
// isUsableSupportFace allows for this expectedState, the exact hit point supportHits
// would generate IF a support block existed at that neighbor (pure geometry, no world
// read: this does not check whether one actually does). Planner coverage
// (LayerRoutePlanner.coveredMissing) and the layer gate's reach-based counts use this
// as their distance model instead of block-center distance, so a position the
// selector can actually reach is never misjudged by a cruder model.
internal fun potentialSupportHitPoints(worldPos: BlockPos, expectedState: BlockState): List<Vec3> {
    return Direction.values().mapNotNull { supportDirection ->
        supportHitPoint(worldPos, expectedState, supportDirection)
    }
}

private const val HIT_EPSILON: Double = 0.0001
private const val VERTICAL_HALF_OFFSET: Double = 0.25

// The player occupies its feet cell and the cell above (head); the
// extra +1 cell above that covers fractional hover (e.g. the mover's own +0.4 hover
// clearance can put the player's feet mid-cell, with their bounding box reaching one
// cell higher than the floored feet cell alone would suggest). Promoted to top-level
// so ScaffoldPlanner's scaffold-cell selection can reuse the exact same guard the
// selector applies to ordinary candidates.
internal fun isInPlayerColumn(worldPos: BlockPos, playerFeetPos: Vec3?): Boolean {
    if (playerFeetPos == null) return false
    val feetCellX = floor(playerFeetPos.x).toInt()
    val feetCellZ = floor(playerFeetPos.z).toInt()
    if (worldPos.x != feetCellX || worldPos.z != feetCellZ) return false
    val feetCellY = floor(playerFeetPos.y).toInt()
    return worldPos.y in feetCellY..(feetCellY + 2)
}

public class PrinterCandidateSelector(
    private val orderStrategy: PlacementOrderStrategy = BottomUpPlacementOrderStrategy,
    private val skipLog: PrinterSkipLog = PrinterSkipLog(),
    private val onSkip: (PrinterSkipReason, BlockPos) -> Unit = { _, _ -> },
    private val predictPlacement: (BlockItem, BlockPlaceContext) -> BlockState? =
        { item, context -> item.getPlacementState(context) },
    // Fallback for blocks whose placement result depends on the player's facing
    // (stairs, chests, ...) rather than only on the clicked face: tried only when the
    // baseline predictPlacement above does not already match. Default never contributes,
    // preserving pre-M2 behavior for callers that do not supply one.
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
        // The player's own feet position, used only for the
        // player-column placement guard below. Null (the default) disables the guard
        // for callers that do not supply one.
        playerFeetPos: Vec3? = null,
    ): List<PrinterCandidate> {
        require(reach >= 0.0)
        val candidates = mutableListOf<PrinterCandidate>()

        for (localPos in missingLocal) {
            val expectedState = expectedStateAt(localPos) ?: continue
            val worldPos = localToWorld(localPos).immutable()

            // A candidate in the
            // player's own column (feet cell through head cell + 1, covering
            // fractional hover) is a transient, non-structural obstruction -- the
            // mover will move the player away and the printer retries it on its own --
            // so this must not touch the skip log/telemetry the way every rejection
            // below does.
            if (isInPlayerColumn(worldPos, playerFeetPos)) continue

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
                is PlacementResolution.Resolved -> candidates.add(
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
                // Not logged: a wrong-state world block is a transient/structural
                // condition the printer cannot break, not a rejection worth counting.
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
