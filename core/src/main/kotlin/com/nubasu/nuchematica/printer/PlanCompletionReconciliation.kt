package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** One real schematic target owned by the adopted plan. */
internal data class PlanTargetExpectation(
    internal val pos: BlockPos,
    internal val expected: BlockState,
)

/** Deduplicated plan targets and the live-world subset still mismatched. */
internal data class PlanCompletionReconciliation(
    internal val targetCount: Int,
    internal val mismatchedTargets: List<PlanTargetExpectation>,
)

/**
 * Refreshes every schematic cell and reports mismatches among this plan's targets.
 *
 * Scanning all cells captures neighbor updates that may invalidate already completed work.
 */
internal fun reconcilePlanCompletion(
    schematicLocalPositions: Collection<BlockPos>,
    plannedTargets: List<PlanTargetExpectation>,
    localToWorld: (BlockPos) -> BlockPos,
    liveStateAt: (BlockPos) -> BlockState,
    onObserved: (BlockPos, BlockState) -> Unit,
    matches: (BlockState, BlockState) -> Boolean,
): PlanCompletionReconciliation {
    val targetsByPos = LinkedHashMap<BlockPos, PlanTargetExpectation>()
    for (target in plannedTargets) targetsByPos[target.pos] = target

    val unmatchedPositions = LinkedHashSet(targetsByPos.keys)
    val mismatches = mutableListOf<PlanTargetExpectation>()
    for (localPos in schematicLocalPositions) {
        val worldPos = localToWorld(localPos)
        val actual = liveStateAt(worldPos)
        onObserved(worldPos, actual)
        val target = targetsByPos[worldPos] ?: continue
        unmatchedPositions.remove(worldPos)
        if (!matches(target.expected, actual)) mismatches += target
    }
    check(unmatchedPositions.isEmpty()) {
        "planned targets outside schematic content: ${unmatchedPositions.take(5)}"
    }
    return PlanCompletionReconciliation(
        targetCount = targetsByPos.size,
        mismatchedTargets = mismatches,
    )
}
