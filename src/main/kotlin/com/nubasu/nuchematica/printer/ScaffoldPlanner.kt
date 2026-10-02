package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

internal val SCAFFOLD_BLOCK_STATE: BlockState = Blocks.SLIME_BLOCK.defaultBlockState()

private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()

/**
 * Non-empty scaffold chain ordered from supported anchor to target-adjacent cell.
 */
internal data class ScaffoldPlan(
    internal val cells: List<BlockPos>,
    internal val targetPos: BlockPos,
) {
    init {
        require(cells.isNotEmpty()) { "ScaffoldPlan requires at least one scaffold cell" }
    }
}

internal data class ScaffoldStep(
    internal val scaffoldPos: BlockPos,
    internal val targetPos: BlockPos,
)

/** Returns the first untracked anchor-first step, or null when the chain is built. */
internal fun ScaffoldPlan.nextStep(isScaffoldCell: (BlockPos) -> Boolean): ScaffoldStep? {
    for (index in cells.indices) {
        val cell = cells[index]
        if (isScaffoldCell(cell)) continue
        val stepTarget = if (index + 1 < cells.size) cells[index + 1] else targetPos
        return ScaffoldStep(scaffoldPos = cell, targetPos = stepTarget)
    }
    return null
}

private fun targetAdjacentCandidates(
    targetPos: BlockPos,
    expectedState: BlockState,
    isSchematicPosition: (BlockPos) -> Boolean,
    stateAt: (BlockPos) -> BlockState,
    playerFeetPos: Vec3?,
    isInBounds: (BlockPos) -> Boolean = { true },
): List<BlockPos> {
    val candidates = mutableListOf<BlockPos>()
    for (direction in Direction.values()) {
        val face = direction.opposite
        if (!isUsableSupportFace(expectedState, face)) continue
        if (!isUsableSupportNeighbor(expectedState, SCAFFOLD_BLOCK_STATE, face)) continue
        val candidate = targetPos.relative(direction).immutable()
        if (!isInBounds(candidate)) continue
        if (isSchematicPosition(candidate)) continue
        if (!isReplaceableTarget(stateAt(candidate))) continue
        if (isInPlayerColumn(candidate, playerFeetPos)) continue
        candidates.add(candidate)
    }
    return candidates
}

/** Resolves a single supported scaffold cell adjacent to the target. */
internal object ScaffoldPlanner {
    internal fun plan(
        targetPos: BlockPos,
        expectedState: BlockState,
        isSchematicPosition: (BlockPos) -> Boolean,
        stateAt: (BlockPos) -> BlockState,
        playerFeetPos: Vec3?,
        isInBounds: (BlockPos) -> Boolean = { true },
    ): ScaffoldPlan? {
        val boundedStateAt: (BlockPos) -> BlockState = { pos -> if (isInBounds(pos)) stateAt(pos) else AIR_STATE }
        val candidates =
            targetAdjacentCandidates(targetPos, expectedState, isSchematicPosition, boundedStateAt, playerFeetPos, isInBounds)
        for (candidate in candidates) {
            if (!hasSupportNeighbor(candidate, SCAFFOLD_BLOCK_STATE, boundedStateAt)) continue
            return ScaffoldPlan(cells = listOf(candidate), targetPos = targetPos.immutable())
        }
        return null
    }
}

internal const val SCAFFOLD_CHAIN_LIMIT: Int = 8

private const val SCAFFOLD_CHAIN_NODE_BUDGET: Int = 4096

/** Resolves a deterministic bounded shortest chain from support to the target. */
internal object ScaffoldChainPlanner {
    internal fun plan(
        targetPos: BlockPos,
        expectedState: BlockState,
        isSchematicPosition: (BlockPos) -> Boolean,
        stateAt: (BlockPos) -> BlockState,
        playerFeetPos: Vec3?,
        isInBounds: (BlockPos) -> Boolean = { true },
    ): ScaffoldPlan? {
        val boundedStateAt: (BlockPos) -> BlockState = { pos -> if (isInBounds(pos)) stateAt(pos) else AIR_STATE }
        val goalCells =
            targetAdjacentCandidates(targetPos, expectedState, isSchematicPosition, boundedStateAt, playerFeetPos, isInBounds)
        if (goalCells.isEmpty()) return null

        val depthOf = HashMap<BlockPos, Int>()
        val cameFrom = HashMap<BlockPos, BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        var nodesVisited = 0

        for (goal in goalCells) {
            if (goal in depthOf) continue
            depthOf[goal] = 1
            nodesVisited++
            if (hasSupportNeighbor(goal, SCAFFOLD_BLOCK_STATE, boundedStateAt)) {
                return buildPlan(goal, cameFrom, targetPos)
            }
            queue.add(goal)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentDepth = depthOf.getValue(current)
            if (currentDepth >= SCAFFOLD_CHAIN_LIMIT) continue
            for (direction in Direction.values()) {
                val next = current.relative(direction).immutable()
                if (next in depthOf) continue
                if (!isInBounds(next)) continue
                if (isSchematicPosition(next)) continue
                if (!isReplaceableTarget(boundedStateAt(next))) continue
                if (isInPlayerColumn(next, playerFeetPos)) continue
                if (nodesVisited >= SCAFFOLD_CHAIN_NODE_BUDGET) return null
                nodesVisited++
                depthOf[next] = currentDepth + 1
                cameFrom[next] = current
                if (hasSupportNeighbor(next, SCAFFOLD_BLOCK_STATE, boundedStateAt)) {
                    return buildPlan(next, cameFrom, targetPos)
                }
                queue.add(next)
            }
        }
        return null
    }

    private fun buildPlan(
        anchor: BlockPos,
        cameFrom: Map<BlockPos, BlockPos>,
        targetPos: BlockPos,
    ): ScaffoldPlan {
        val cells = mutableListOf(anchor)
        var current = anchor
        while (true) {
            val parent = cameFrom[current] ?: break
            cells.add(parent)
            current = parent
        }
        return ScaffoldPlan(cells = cells, targetPos = targetPos.immutable())
    }
}

/** Checks direct scaffolding before falling back to a bounded chain search. */
internal fun isScaffoldAssistable(
    worldPos: BlockPos,
    expectedState: BlockState,
    stateAt: (BlockPos) -> BlockState,
    isSchematicPosition: (BlockPos) -> Boolean,
    playerFeetPos: Vec3?,
): Boolean {
    val direct = ScaffoldPlanner.plan(
        targetPos = worldPos,
        expectedState = expectedState,
        isSchematicPosition = isSchematicPosition,
        stateAt = stateAt,
        playerFeetPos = playerFeetPos,
    )
    if (direct != null) return true
    return ScaffoldChainPlanner.plan(
        targetPos = worldPos,
        expectedState = expectedState,
        isSchematicPosition = isSchematicPosition,
        stateAt = stateAt,
        playerFeetPos = playerFeetPos,
    ) != null
}

/**
 * Finds scaffold blocks not expected by the schematic inside the supplied bounds.
 *
 * The scan returns no positions when its volume exceeds [ORPHAN_SWEEP_MAX_CELLS].
 */
internal fun findOrphanScaffolds(
    boundsMin: BlockPos,
    boundsMax: BlockPos,
    expectedAt: (BlockPos) -> BlockState?,
    stateAt: (BlockPos) -> BlockState,
    onBoundsExceeded: () -> Unit = {},
): List<BlockPos> {
    val sizeX = (boundsMax.x - boundsMin.x + 1).toLong()
    val sizeY = (boundsMax.y - boundsMin.y + 1).toLong()
    val sizeZ = (boundsMax.z - boundsMin.z + 1).toLong()
    if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return emptyList()
    if (sizeX * sizeY * sizeZ > ORPHAN_SWEEP_MAX_CELLS) {
        onBoundsExceeded()
        return emptyList()
    }

    val scaffoldBlock = SCAFFOLD_BLOCK_STATE.block
    val orphans = ArrayList<BlockPos>()
    for (x in boundsMin.x..boundsMax.x) {
        for (y in boundsMin.y..boundsMax.y) {
            for (z in boundsMin.z..boundsMax.z) {
                val pos = BlockPos(x, y, z)
                if (stateAt(pos).block != scaffoldBlock) continue
                val expected = expectedAt(pos)
                if (expected != null && expected.block == scaffoldBlock) continue
                orphans.add(pos)
            }
        }
    }
    return orphans
}

internal fun schematicWorldBoundingBox(
    localPositions: Collection<BlockPos>,
    localToWorld: (BlockPos) -> BlockPos,
    margin: Int,
): Pair<BlockPos, BlockPos>? {
    if (localPositions.isEmpty()) return null
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var minZ = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    var maxZ = Int.MIN_VALUE
    for (localPos in localPositions) {
        val worldPos = localToWorld(localPos)
        if (worldPos.x < minX) minX = worldPos.x
        if (worldPos.y < minY) minY = worldPos.y
        if (worldPos.z < minZ) minZ = worldPos.z
        if (worldPos.x > maxX) maxX = worldPos.x
        if (worldPos.y > maxY) maxY = worldPos.y
        if (worldPos.z > maxZ) maxZ = worldPos.z
    }
    return BlockPos(minX - margin, minY - margin, minZ - margin) to
        BlockPos(maxX + margin, maxY + margin, maxZ + margin)
}

internal const val ORPHAN_SWEEP_MAX_CELLS: Long = 512L * 1024L
