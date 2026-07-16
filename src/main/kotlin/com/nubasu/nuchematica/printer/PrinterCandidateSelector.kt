package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

public data class PrinterCandidate(
    public val localPos: BlockPos,
    public val worldPos: BlockPos,
    public val hit: BlockHitResult,
    public val expectedState: BlockState,
    public val distanceSquared: Double,
)

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

public class PrinterCandidateSelector(
    private val orderStrategy: PlacementOrderStrategy = EyeDistancePlacementOrderStrategy,
    private val skipLog: PrinterSkipLog = PrinterSkipLog(),
    private val predictPlacement: (BlockItem, BlockPlaceContext) -> BlockState? =
        { item, context -> item.getPlacementState(context) },
    private val supportBlockSafe: (BlockState) -> Boolean = PrinterSupportPolicy::isInteractionSafe,
) {
    public fun select(
        missingLocal: List<BlockPos>,
        expectedStateAt: (BlockPos) -> BlockState?,
        localToWorld: (BlockPos) -> BlockPos,
        stateAt: (BlockPos) -> BlockState,
        placementContext: (BlockState, BlockHitResult) -> BlockPlaceContext,
        eyePosition: Vec3,
        reach: Double,
    ): List<PrinterCandidate> {
        require(reach >= 0.0)
        val reachSquared = reach * reach
        val prefilterReachSquared = (reach + PREFILTER_MARGIN) * (reach + PREFILTER_MARGIN)
        val candidates = mutableListOf<PrinterCandidate>()

        for (localPos in missingLocal) {
            val expectedState = expectedStateAt(localPos) ?: continue
            val worldPos = localToWorld(localPos).immutable()

            // Coarse distance prefilter before any state read: on large schematics,
            // reading target + 6 neighbor states for every missing block every tick
            // is the dominant cost. Positions that cannot possibly be in reach (even
            // accounting for a hit landing on an adjacent block's face) are rejected
            // here without touching stateAt/supportHits/prediction at all.
            val center = Vec3(worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5)
            if (eyePosition.distanceToSqr(center) > prefilterReachSquared) {
                skipLog.record(PrinterSkipReason.OUT_OF_REACH, worldPos)
                continue
            }

            val targetState = stateAt(worldPos)
            if (!targetState.isAir && !targetState.material.isReplaceable) continue

            val supportHits = supportHits(worldPos, stateAt)
            if (supportHits.isEmpty()) {
                skipLog.record(PrinterSkipReason.NO_SUPPORT_FACE, worldPos)
                continue
            }

            val reachableHits = supportHits.map { hit ->
                hit to eyePosition.distanceToSqr(hit.location)
            }.filter { (_, distanceSquared) ->
                distanceSquared <= reachSquared
            }.sortedBy { (_, distanceSquared) -> distanceSquared }
            if (reachableHits.isEmpty()) {
                skipLog.record(PrinterSkipReason.OUT_OF_REACH, worldPos)
                continue
            }

            val item = expectedState.block.asItem()
            val block = expectedState.block
            if (
                item !is BlockItem ||
                block is DoublePlantBlock ||
                block is DoorBlock ||
                block is BedBlock
            ) {
                skipLog.record(PrinterSkipReason.CATEGORY_EXCLUDED, worldPos)
                continue
            }

            val selected = reachableHits.firstOrNull { (hit, _) ->
                val predicted = predictPlacement(item, placementContext(expectedState, hit))
                predicted != null && BlockStateEquivalence.matches(expectedState, predicted)
            }
            if (selected == null) {
                skipLog.record(PrinterSkipReason.PREDICTION_MISMATCH, worldPos)
                continue
            }

            candidates.add(
                PrinterCandidate(
                    localPos = localPos.immutable(),
                    worldPos = worldPos,
                    hit = selected.first,
                    expectedState = expectedState,
                    distanceSquared = selected.second,
                ),
            )
        }

        return orderStrategy.order(candidates, eyePosition)
    }

    private fun supportHits(
        worldPos: BlockPos,
        stateAt: (BlockPos) -> BlockState,
    ): List<BlockHitResult> {
        return Direction.values().mapNotNull { supportDirection ->
            val supportPos = worldPos.relative(supportDirection)
            val supportState = stateAt(supportPos)
            if (
                supportState.isAir ||
                supportState.material.isReplaceable ||
                !supportBlockSafe(supportState)
            ) {
                null
            } else {
                val face = supportDirection.opposite
                val normal = face.normal
                val faceOffset = 0.5 - HIT_EPSILON
                BlockHitResult(
                    Vec3(
                        supportPos.x + 0.5 + normal.x * faceOffset,
                        supportPos.y + 0.5 + normal.y * faceOffset,
                        supportPos.z + 0.5 + normal.z * faceOffset,
                    ),
                    face,
                    supportPos,
                    false,
                )
            }
        }
    }

    private companion object {
        private const val HIT_EPSILON: Double = 0.0001

        // Margin added to reach for the coarse prefilter: a hit can land on the
        // face of a block adjacent to the missing position, up to ~1 block farther
        // from the eye than the missing position's own center.
        private const val PREFILTER_MARGIN: Double = 1.0
    }
}
