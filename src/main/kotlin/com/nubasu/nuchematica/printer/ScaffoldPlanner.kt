package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

// Scaffold material: a single const so swapping it stays a one-line change (no config
// UI needed). SLIME_BLOCK, not TNT -- hardness 0
// (instantly breakable) but with no ignition risk, which is
// why the ignition-hazard adjacency guard was dropped entirely rather than
// reimplemented for this material. javap-verified (forge-1.18.2-40.3.0_mapped_official
// _1.18.2.jar): SlimeBlock extends HalfTransparentBlock and declares no blockstate
// properties of its own, so its defaultBlockState() has no HALF/AXIS/SLAB_TYPE -- exactly
// like the plain-block case expectedVerticalHalf/isUsableSupportFace already fall back to
// -- and Blocks.SLIME_BLOCK is a public static Block field backed by a registered
// BlockItem, so it is eligible through eligiblePlacementBlockItem the same as any other
// full block.
internal val SCAFFOLD_BLOCK_STATE: BlockState = Blocks.SLIME_BLOCK.defaultBlockState()

// What a bounds-restricted read resolves to instead of the real world/plan content --
// never solid, so isReplaceableTarget/isSupportingState both already treat it as "not a
// real support" without needing their own bounds awareness.
private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()

// An ordered scaffold plan, anchor-first ... target-adjacent-last.
// cells is never empty: a single-cell plan is a chain of length 1, where
// the one cell is both anchor and target-adjacent. targetPos is always the REAL missing
// position the whole plan exists to support -- never an intermediate chain cell.
internal data class ScaffoldPlan(
    internal val cells: List<BlockPos>,
    internal val targetPos: BlockPos,
) {
    init {
        require(cells.isNotEmpty()) { "ScaffoldPlan requires at least one scaffold cell" }
    }
}

// One cell's own submission: the specific scaffold position to place THIS tick, and
// what the ledger should record as its dependent target once it lands (either the next
// chain cell, or the plan's real targetPos for the last cell -- see ScaffoldPlan.nextStep).
internal data class ScaffoldStep(
    internal val scaffoldPos: BlockPos,
    internal val targetPos: BlockPos,
)

// Resolves which cell of the plan still needs to be submitted, given
// which ones the ledger already tracks (isScaffoldCell -- true from the tick a cell's own
// placement is ACCEPTED until its break is confirmed). Cells are walked anchor-first, so
// the chain is always built in that order, one cell at a time ("keep one scaffold
// placement in flight"). Null once every cell is already tracked: the chain is fully
// built and the real target itself becomes an ordinary actionable candidate through the
// normal placement path the moment its own support is observed, so nothing further needs
// submitting here.
internal fun ScaffoldPlan.nextStep(isScaffoldCell: (BlockPos) -> Boolean): ScaffoldStep? {
    for (index in cells.indices) {
        val cell = cells[index]
        if (isScaffoldCell(cell)) continue
        val stepTarget = if (index + 1 < cells.size) cells[index + 1] else targetPos
        return ScaffoldStep(scaffoldPos = cell, targetPos = stepTarget)
    }
    return null
}

// Shared by ScaffoldPlanner (a single qualifying neighbor) and
// ScaffoldChainPlanner (the same neighbor set, but reached via a bridge
// instead of directly) -- the target-adjacent candidate filter itself has exactly one
// implementation so the two paths can never drift apart on what counts as a usable cell
// next to the target. Tries the 6 neighbor cells of targetPos in Direction.values() order
// -- the same deterministic order hasSupportNeighbor/neighborSnapshot already rely on
// elsewhere in this package, so the result is reproducible run to run. A candidate
// qualifies when it is:
//   (face) usable per isUsableSupportFace(expectedState, direction.opposite) -- face =
//       direction-from-target-to-scaffold .opposite, the exact
//       supportHits convention (see PrinterCandidateSelector.supportHitPoint). A pure,
//       world-read-free check, so it is tried first.
//   (a) NOT an expected schematic position (schematic-air) -- isSchematicPosition must
//       be false, so the scaffold can never collide with a real, still-to-be-placed
//       structure block or with MissingBlockHolder bookkeeping.
//   (b) currently air/replaceable in the world (isReplaceableTarget) -- the printer can
//       only place into it, never displace an existing block.
//   (c) outside the player's protection column (isInPlayerColumn) -- reuses the exact
//       guard PrinterCandidateSelector.select applies to ordinary candidates.
private fun targetAdjacentCandidates(
    targetPos: BlockPos,
    expectedState: BlockState,
    isSchematicPosition: (BlockPos) -> Boolean,
    stateAt: (BlockPos) -> BlockState,
    playerFeetPos: Vec3?,
    // Rejects a candidate before its own stateAt read (isReplaceableTarget below), so a
    // bounds-restricted caller's out-of-bounds neighbor of the target is never physically
    // read at all -- never merely excluded from the returned list afterward, by which
    // point the read has already happened. Defaults to unconstrained so every pre-existing
    // (non-plan) caller keeps its exact prior behavior.
    isInBounds: (BlockPos) -> Boolean = { true },
): List<BlockPos> {
    val candidates = mutableListOf<BlockPos>()
    for (direction in Direction.values()) {
        if (!isUsableSupportFace(expectedState, direction.opposite)) continue
        val candidate = targetPos.relative(direction).immutable()
        if (!isInBounds(candidate)) continue
        if (isSchematicPosition(candidate)) continue
        if (!isReplaceableTarget(stateAt(candidate))) continue
        if (isInPlayerColumn(candidate, playerFeetPos)) continue
        candidates.add(candidate)
    }
    return candidates
}

// Pure scaffold-cell selection for a missing position
// whose only blocking factor is the lack of a usable support face (isActionableMissing
// fails only on the support term). Returns the first targetAdjacentCandidates() entry
// that additionally has a usable support face of its OWN for a plain full block
// (hasSupportNeighbor with SCAFFOLD_BLOCK_STATE) -- since the scaffold material has no
// HALF/AXIS properties, isUsableSupportFace is unconditionally true for it, so this
// reduces to "at least one of its own 6 neighbors is solid and interaction-safe". Null
// when no candidate cell qualifies (ScaffoldChainPlanner is the fallback for that case).
internal object ScaffoldPlanner {
    internal fun plan(
        targetPos: BlockPos,
        expectedState: BlockState,
        isSchematicPosition: (BlockPos) -> Boolean,
        stateAt: (BlockPos) -> BlockState,
        playerFeetPos: Vec3?,
        // Constrains the single candidate cell to a caller-supplied region (the frozen
        // PrintWorldModel envelope, with margin) -- defaults to unconstrained so every
        // existing (in-game, non-plan) caller keeps its exact prior behavior.
        isInBounds: (BlockPos) -> Boolean = { true },
    ): ScaffoldPlan? {
        // Wraps every internal stateAt read so a bounds-restricted caller's out-of-bounds
        // position is never physically read: it resolves to AIR instead, which
        // isReplaceableTarget/isSupportingState both already treat as "not a real support"
        // -- structurally true even against a future reader that would throw outside its
        // own snapshot's bounds, not just against the current always-answering one.
        // Identity when isInBounds is the unconstrained default.
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

// Max number of cells a single chain plan may contain (bridge length
// cap). Chosen generously enough to bridge the pendant/inverted-U shape
// without letting a pathological void turn this into an unbounded search.
internal const val SCAFFOLD_CHAIN_LIMIT: Int = 8

// Max number of graph nodes ScaffoldChainPlanner may ever visit while
// searching for ONE plan -- independent of SCAFFOLD_CHAIN_LIMIT (which bounds the
// RESULT's length), this bounds the SEARCH itself so a large enclosed void surrounded by
// schematic-air cannot make a single planning call expensive.
private const val SCAFFOLD_CHAIN_NODE_BUDGET: Int = 4096

// Bridges from existing support through schematic-air cells to a
// usable-face-adjacent cell of the target, for the case ScaffoldPlanner.plan cannot
// resolve directly (the pendant/inverted-U case: no target-adjacent cell has its OWN
// real support yet). Multi-source BFS seeded from every targetAdjacentCandidates() cell
// (the "goal" set, using the same exact filter), expanding outward through schematic-
// air+replaceable+non-player-column cells (Direction.values() order, an ArrayDeque FIFO
// queue) until a cell that already has real support (an "anchor") is reached -- BFS over
// an unweighted grid guarantees the FIRST anchor found is on a shortest such path.
// Deterministic: neighbor order and queue order are the only things that decide which
// node is visited first: no Set is ever iterated to decide processing order (visited/
// depth bookkeeping uses maps purely for O(1) membership tests). Bounded by
// SCAFFOLD_CHAIN_LIMIT (result length) and SCAFFOLD_CHAIN_NODE_BUDGET (search size), so
// an enclosed void can never make this loop run unboundedly; returns null if either
// bound is hit before an anchor is found, or if the target itself offers no usable-face
// neighbor cell at all (goal set empty).
internal object ScaffoldChainPlanner {
    internal fun plan(
        targetPos: BlockPos,
        expectedState: BlockState,
        isSchematicPosition: (BlockPos) -> Boolean,
        stateAt: (BlockPos) -> BlockState,
        playerFeetPos: Vec3?,
        // Constrains every goal cell and every BFS-expanded cell (so the anchor itself,
        // being one of those, is constrained too) to a caller-supplied region -- defaults
        // to unconstrained so every existing (in-game, non-plan) caller keeps its exact
        // prior behavior. A cell outside the region is never even added to the search.
        isInBounds: (BlockPos) -> Boolean = { true },
    ): ScaffoldPlan? {
        // See ScaffoldPlanner.plan's own doc for why this wrap exists and what it
        // structurally guarantees; identical here for the BFS's own reads.
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
            // Defensive: a goal cell that already has its own real support is exactly
            // the case ScaffoldPlanner.plan already resolves, so callers that try that
            // first never actually hit this branch -- kept so this function is correct
            // standalone (unit-testable without depending on call order).
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
                // Bounds rejection comes before every other judgement or read on `next`
                // (including its own isReplaceableTarget world read below), so an
                // out-of-bounds cell is never expanded into and never physically read.
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

    // Walks from the found anchor back to whichever goal cell seeded it (goal cells have
    // no cameFrom entry, so the walk stops there), producing an anchor-first, target-
    // adjacent-last cell list -- exactly ScaffoldPlan.cells' documented order.
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

// The single shared boolean form of "could a
// scaffold plan (single-cell OR chain) place this position right now" -- every
// actionability consumer (the shared isActionableMissing's optional canScaffold term,
// classifyMissing's scaffoldAssisted bucket, the layer gate's and mover's memoized
// checks) routes through this one function rather than re-deriving "is this position
// scaffold-assistable" per call site, avoiding a definition-drift class of bug
// (see SchematicPrinter.findScaffoldPlan for the
// separate, non-memoized "resolve an actual plan to submit" call, which stays a direct
// ScaffoldPlanner.plan/ScaffoldChainPlanner.plan call since it needs the ScaffoldPlan
// itself, not just a boolean). Tries the direct single-cell plan first (cheaper: no BFS)
// and only falls back to the chain search when that fails -- a position whose plan
// requires a chain still counts scaffoldAssisted, it is just resolved
// through the fallback rather than the direct path.
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

// Pure orphan-scaffold detection. A disconnect/rejoin can lose the
// ClientLevel before SchematicPrinter.cleanupScaffolds ever runs against it, clearing the
// ScaffoldLedger while a placed scaffold cell is still physically standing in the world;
// the player then bounces off it and the mover reads that as an instant SERVER_CORRECTION
// abort. This finds those orphans within an already-computed world-space box: a position
// qualifies when the world state IS the scaffold block (SCAFFOLD_BLOCK_STATE) and the
// schematic itself does not expect slime there (expectedAt null, or a different block) --
// so a schematic that legitimately contains slime blocks of its own is never touched.
// Bounds are supplied by the caller (see schematicWorldBoundingBox /
// SchematicPrinter.sweepOrphanScaffolds for the +2-margin AABB) rather than derived here,
// keeping this a single cheap pass with no allocation beyond the result list. A huge box
// is guarded by ORPHAN_SWEEP_MAX_CELLS -- onBoundsExceeded lets the caller log it (this
// function stays logging-free, mirroring ScaffoldLedger's onBroken/onRetryExhausted
// callback pattern) and the sweep is skipped entirely rather than iterating it.
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

// The schematic's world-space AABB (from its local content
// positions, mapped through the live localToWorld transform) expanded by `margin` in every
// direction, for findOrphanScaffolds' bounds. Null when the schematic has no content to
// bound (nothing to sweep).
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
