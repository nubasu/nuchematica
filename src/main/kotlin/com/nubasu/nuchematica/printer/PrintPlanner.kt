package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.state.BlockState

// The state a scaffold cell settles back to once its own break cycle is confirmed (see
// ScaffoldLedger.tickBreaks' own AIR_STATE) -- never whatever replaceable world block
// (a real, still-standing VINE, a flower, ...) it happened to overwrite. A chain cell is
// never schematic content, so it is never covered by the plan's own placedInSlot gating;
// without this overlay a survival check reading straight off the frozen world would see
// that original content as if it were still there permanently.
private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()

// bandHeight/tileSize defaults are the measured-safe planning granularity on the largest
// real fixture (a ~3.5M-block schematic classifies fully in about a second at 8/48) --
// both stay caller-overridable for smaller fixtures and future tuning.
internal data class PrintPlanParams(
    internal val bandHeight: Int = 8,
    internal val tileSize: Int = 48,
    // Constrains every scaffold chain call's goal/expansion/anchor cells to this region
    // (the frozen PrintWorldModel envelope, with margin) -- null (the default) means
    // unconstrained, matching the pre-envelope behavior every existing caller relies on.
    internal val bounds: ((BlockPos) -> Boolean)? = null,
    // Immutable substitution/equivalence settings for this plan's whole classification
    // pass -- never PrinterSettingsHolder's own live, mutable value, so a setting change
    // mid-plan can never produce a mixed-behavior result (see effectivePlacementState/
    // BlockStateEquivalence.matches' own param overloads, both threaded from this field).
    internal val behavior: PlacementBehaviorSettings = PlacementBehaviorSettings(
        substituteLookalikes = true,
        placeWaterloggedDry = false,
    ),
) {
    init {
        require(bandHeight > 0) { "bandHeight must be positive, was $bandHeight" }
        require(tileSize > 0) { "tileSize must be positive, was $tileSize" }
    }
}

// The frozen-content bounding box as a flat IntArray of palette indices (never a
// BlockPos-keyed HashMap for the bulk cell storage -- see PrintWorldModel's own doc
// comment for why that would mean hundreds of MB of boxed entries at 0_all scale).
// palette/stateIdx only ever hold the schematic's OWN (non-air) expected states; the
// live/frozen world itself is read through the caller-supplied worldState lambda, never
// stored here.
private class PlanRegion(content: List<Pair<BlockPos, BlockState>>) {
    internal val minX: Int
    internal val minY: Int
    internal val minZ: Int
    internal val sizeX: Int
    internal val sizeY: Int
    internal val sizeZ: Int
    internal val stateIdx: IntArray
    internal val palette: List<BlockState>

    init {
        var lowX = Int.MAX_VALUE
        var lowY = Int.MAX_VALUE
        var lowZ = Int.MAX_VALUE
        var highX = Int.MIN_VALUE
        var highY = Int.MIN_VALUE
        var highZ = Int.MIN_VALUE
        for ((pos, _) in content) {
            if (pos.x < lowX) lowX = pos.x
            if (pos.y < lowY) lowY = pos.y
            if (pos.z < lowZ) lowZ = pos.z
            if (pos.x > highX) highX = pos.x
            if (pos.y > highY) highY = pos.y
            if (pos.z > highZ) highZ = pos.z
        }
        minX = lowX
        minY = lowY
        minZ = lowZ
        sizeX = highX - lowX + 1
        sizeY = highY - lowY + 1
        sizeZ = highZ - lowZ + 1

        val idx = IntArray(sizeX * sizeY * sizeZ) { NO_CONTENT_INDEX }
        val paletteList = ArrayList<BlockState>()
        val paletteLookup = HashMap<BlockState, Int>()
        for ((pos, state) in content) {
            val flat = flatIndexUnchecked(pos)
            idx[flat] = paletteLookup.getOrPut(state) {
                paletteList.add(state)
                paletteList.size - 1
            }
        }
        stateIdx = idx
        palette = paletteList
    }

    // Only valid for a position already known to be inside the region (the content's
    // own cells, by construction). Neighbor queries that might land outside the
    // bounding box must go through flatIndexOrNegative instead.
    internal fun flatIndexUnchecked(pos: BlockPos): Int {
        return (pos.x - minX) + sizeX * ((pos.y - minY) + sizeY * (pos.z - minZ))
    }

    // -1 (never a valid array index) for any position outside the captured bounding
    // box -- the shared bounds guard every neighbor query needs, since a target's
    // neighbor can fall just past the content's own tight bounding box.
    internal fun flatIndexOrNegative(pos: BlockPos): Int {
        val x = pos.x - minX
        val y = pos.y - minY
        val z = pos.z - minZ
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) return -1
        return x + sizeX * (y + sizeY * z)
    }

    internal fun isContent(flatIdx: Int): Boolean = flatIdx >= 0 && stateIdx[flatIdx] != NO_CONTENT_INDEX

    internal companion object {
        internal const val NO_CONTENT_INDEX: Int = -1
    }
}

// One (band, tile) execution unit's coordinates, in the order PrintPlanner visits them.
private data class UnitSlot(internal val band: Int, internal val tileX: Int, internal val tileZ: Int)

// The bottom-up build order (band from the ground up x tile snake x in-tile y-then-x-
// then-z) assigned as parallel arrays over PlanRegion's flat cells, plus which unit
// (index into `units`) each order index belongs to.
private class PlanOrder(
    internal val totalContent: Int,
    internal val flatIndexForOrder: IntArray,
    internal val orderIdx: IntArray,
    internal val unitIndexForOrder: IntArray,
    internal val units: List<UnitSlot>,
)

// Band-major, tile-snake-within-band, y-then-x-then-z-within-tile -- the product-
// mandated build order (bottom-up, tiled to bound reservation/scaffold search cost).
// Every (band, tileX, tileZ) combination becomes a unit even when it holds no content,
// since the runtime still needs an empty unit's slot to advance through.
private fun assignOrder(region: PlanRegion, params: PrintPlanParams, totalContent: Int): PlanOrder {
    val orderIdx = IntArray(region.stateIdx.size) { -1 }
    val flatIndexForOrder = IntArray(totalContent)
    val unitIndexForOrder = IntArray(totalContent)
    val units = ArrayList<UnitSlot>()

    val numBands = ceilDiv(region.sizeY, params.bandHeight)
    val numTilesX = ceilDiv(region.sizeX, params.tileSize)
    val numTilesZ = ceilDiv(region.sizeZ, params.tileSize)

    var nextOrder = 0
    for (band in 0 until numBands) {
        val yStart = band * params.bandHeight
        val yEnd = minOf(yStart + params.bandHeight - 1, region.sizeY - 1)
        for (tileXIndex in 0 until numTilesX) {
            val xStart = tileXIndex * params.tileSize
            val xEnd = minOf(xStart + params.tileSize - 1, region.sizeX - 1)
            // Snake: even tile-x rows walk tile-z ascending, odd rows walk it
            // descending, so consecutive units in build order are always adjacent
            // tiles rather than jumping back across the whole row.
            val tileZRange = if (tileXIndex % 2 == 0) 0 until numTilesZ else (numTilesZ - 1) downTo 0
            for (tileZIndex in tileZRange) {
                val zStart = tileZIndex * params.tileSize
                val zEnd = minOf(zStart + params.tileSize - 1, region.sizeZ - 1)
                val unitIndex = units.size
                units.add(UnitSlot(band, tileXIndex, tileZIndex))
                for (y in yStart..yEnd) {
                    for (x in xStart..xEnd) {
                        for (z in zStart..zEnd) {
                            val flat = x + region.sizeX * (y + region.sizeY * z)
                            if (!region.isContent(flat)) continue
                            orderIdx[flat] = nextOrder
                            flatIndexForOrder[nextOrder] = flat
                            unitIndexForOrder[nextOrder] = unitIndex
                            nextOrder++
                        }
                    }
                }
            }
        }
    }

    return PlanOrder(totalContent, flatIndexForOrder, orderIdx, unitIndexForOrder, units)
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private fun worldPosForFlat(flat: Int, region: PlanRegion): BlockPos {
    val x = flat % region.sizeX
    val remaining = flat / region.sizeX
    val y = remaining % region.sizeY
    val z = remaining / region.sizeY
    return BlockPos(region.minX + x, region.minY + y, region.minZ + z)
}

// Overlays `chain`'s own cells as AIR_STATE on top of `base`, for every survival check run
// against a scaffold-reachable target: `base` alone (the plan's placedInSlot-gated view, or
// confirmedView) reports a chain cell's CURRENT world content whenever that cell is not
// itself schematic content -- which a chain cell never is -- so a still-standing
// replaceable world block the chain happens to route through would otherwise look like
// permanent support forever, when it is really about to be scaffolded over and then
// removed. Callers that also need a hypothetical candidate/neighbor override should build
// it on top of THIS view's result, never the other way around, so the override still wins
// on a position that happens to coincide with one of the chain's own cells.
private fun chainRemovedView(chain: ScaffoldPlan, base: (BlockPos) -> BlockState): (BlockPos) -> BlockState = { queryPos ->
    if (chain.cells.contains(queryPos)) AIR_STATE else base(queryPos)
}

// Per-cell verdict from the single classification pass. ALREADY_PLACED is not one of
// the five plan buckets (it is out of plan entirely, counted but never
// actioned) but still needs its own slot in cellOutcome so later cells' RESERVED search
// and the fixpoint's failure check can tell it apart from a genuine EXCLUDED/UNREACHABLE
// cell -- it is not the same shared array as placedInSlot; matches() on the frozen
// world already reflects an already-placed cell whether or not this array marks it.
private enum class CellOutcome {
    ALREADY_PLACED, DIRECT, SCAFFOLD, RESERVED,
    EXCLUDED_CATEGORY, EXCLUDED_OCCUPIED, EXCLUDED_FALLING, UNREACHABLE,
}

// A RESERVED cell's full set of viable support candidates, recorded during the single
// pass (neighborOrder ascending) and resolved afterward by the reservation worklist.
// Keeping every candidate -- not just the lowest-order one -- is what lets resolution
// fall through to a second candidate when the first turns out to never actually get
// placed; a single-candidate design has no such fallback and dead-ends at UNREACHABLE
// the moment its one recorded neighbor fails, even when another neighbor was viable too.
// survivalChain is non-null only for a reservation born from a scaffold-reachable cell
// that could not survive the scaffold's own removal (see the main pass's post-chain
// survivesWithoutScaffold check): it carries the chain already found -- using the plain
// single-pass view, at this cell's own classification time -- through to resolution, so
// resolving the reservation never needs to re-derive placement feasibility, only decide
// WHEN (which trigger unit) it is safe to run. It is always null for the pre-existing raw-
// support reservation shape (a cell with neither real support nor any scaffold route at
// classification time), whose resolution instead places the target directly against
// whichever candidate confirms it, needing no scaffold at all.
private data class PendingReservation(
    internal val pos: BlockPos,
    internal val order: Int,
    internal val expected: BlockState,
    internal val candidates: List<ReservationCandidate>,
    internal val survivalChain: ScaffoldPlan? = null,
)

private enum class ReservationOutcome { PLACED_DIRECT, PLACED_SCAFFOLD, FAILED }

// A neighbor cell whose hypothetical placement would give a RESERVED cell real support.
// No trigger is recorded here -- unlike the candidate's own natural order (fixed at
// record time, purely geometric), the unit that actually confirms this candidate can only
// be known once the candidate itself is resolved (it may itself be a reservation whose
// effective trigger keeps shifting later through its own chain), so the trigger is always
// derived at resolution time via availability(), never cached alongside the candidate.
private data class ReservationCandidate(internal val pos: BlockPos, internal val order: Int)

// Three-valued readiness of a position the reservation worklist might depend on, replacing
// an earlier "infinity means not-yet-resolved" encoding that could not tell "still pending"
// apart from "resolved, and never becomes true" -- both looked identical to a caller
// comparing against Int.MAX_VALUE, silently treating a truly impossible support as merely
// slow to arrive. AVAILABLE(unit) is the unit whose completion actually confirms this
// position holds its expected state: a non-content (frozen, unchanging) position is always
// unit 0; a content cell placed in its own natural single-pass slot is its own home unit;
// a resolved reservation is its own effective trigger, transitively (never its raw home
// order/unit, since that is exactly the stale value the old design used to get wrong).
private sealed interface Availability {
    data class Available(internal val unit: Int) : Availability
    object Pending : Availability
    object Failed : Availability
}

// Plan-first classifier: given a schematic's expected content and a frozen-world read,
// statically decides every cell's fate (direct / scaffold-assisted / reserved / excluded
// / unreachable) up front instead of the live, one-position-at-a-time classification
// classifyMissing performs during a run. The design
// is a single order-t pass whose view of "what is placed so far" is the
// plan's OWN placedInSlot truth (never an optimistic orderIdx-only guess), followed by a
// monotone reservation fixpoint for the handful of cells whose only support at pass time
// was a not-yet-decided sibling.
internal object PrintPlanner {
    internal fun plan(
        content: List<Pair<BlockPos, BlockState>>,
        worldState: (BlockPos) -> BlockState,
        params: PrintPlanParams = PrintPlanParams(),
    ): PrintPlan {
        if (content.isEmpty()) {
            return PrintPlan(
                units = emptyList(),
                reservations = emptyMap(),
                report = BuildabilityReport(
                    directCount = 0,
                    scaffoldCount = 0,
                    reservedCount = 0,
                    excludedCategoryCount = 0,
                    excludedOccupiedCount = 0,
                    excludedFallingCount = 0,
                    unreachableCount = 0,
                    alreadyPlacedCount = 0,
                    excludedPositions = emptyList(),
                    unreachablePositions = emptyList(),
                    scaffoldMaterialPositions = emptyList(),
                ),
            )
        }

        val region = PlanRegion(content)
        val order = assignOrder(region, params, content.size)

        val placedInSlot = BooleanArray(order.totalContent)
        val cellOutcome = arrayOfNulls<CellOutcome>(order.totalContent)
        val unitActions = Array(order.units.size) { mutableListOf<PlanAction>() }
        val reservations = ArrayList<PendingReservation>()
        val reservationByOrder = HashMap<Int, PendingReservation>()
        val scaffoldMaterialPositions = ArrayList<BlockPos>()
        val excludedPositions = ArrayList<ExcludedPosition>()
        val unreachablePositions = ArrayList<BlockPos>()

        var directCount = 0
        var scaffoldCount = 0
        var excludedCategoryCount = 0
        var excludedOccupiedCount = 0
        var excludedFallingCount = 0
        var unreachableCount = 0
        var alreadyPlacedCount = 0

        // Plan-wide-unique positive id for each real target's own scaffold transaction (or
        // plain PlaceTarget) -- allocated in the order this plan actually schedules a target
        // (single-pass classification, then reservation resolution order), which is itself
        // fully deterministic, so two runs over the same input assign the same ids.
        var nextGroupId = 1

        // Shared across every cell in the hot loop -- advances via currentT rather than
        // allocating one closure per cell (one shared closure keeps multi-million-cell
        // schematics allocation-light). A preceding content cell is only shown as its
        // expected state once this plan actually decided to place it there
        // (placedInSlot), never merely because its order comes first -- that
        // distinction is the whole point of the single-pass design.
        var currentT = 0
        val view: (BlockPos) -> BlockState = { queryPos ->
            val flat = region.flatIndexOrNegative(queryPos)
            if (region.isContent(flat)) {
                val neighborOrder = order.orderIdx[flat]
                if (neighborOrder < currentT && placedInSlot[neighborOrder]) {
                    region.palette[region.stateIdx[flat]]
                } else {
                    worldState(queryPos)
                }
            } else {
                worldState(queryPos)
            }
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { queryPos ->
            region.isContent(region.flatIndexOrNegative(queryPos))
        }
        val isInBounds: (BlockPos) -> Boolean = params.bounds ?: { true }

        for (t in 0 until order.totalContent) {
            currentT = t
            val flat = order.flatIndexForOrder[t]
            val pos = worldPosForFlat(flat, region)
            val expected = region.palette[region.stateIdx[flat]]
            val unitIndex = order.unitIndexForOrder[t]
            val worldNow = worldState(pos)

            if (BlockStateEquivalence.matches(expected, worldNow, params.behavior)) {
                alreadyPlacedCount++
                cellOutcome[t] = CellOutcome.ALREADY_PLACED
                continue
            }
            if (!isReplaceableTarget(worldNow)) {
                excludedOccupiedCount++
                cellOutcome[t] = CellOutcome.EXCLUDED_OCCUPIED
                excludedPositions.add(ExcludedPosition(pos, ExclusionReason.OCCUPIED))
                continue
            }
            if (eligiblePrinterBlockItem(expected, params.behavior) == null) {
                excludedCategoryCount++
                cellOutcome[t] = CellOutcome.EXCLUDED_CATEGORY
                excludedPositions.add(ExcludedPosition(pos, ExclusionReason.CATEGORY))
                continue
            }
            if (expected.block is FallingBlock && isReplaceableTarget(view(pos.below()))) {
                excludedFallingCount++
                cellOutcome[t] = CellOutcome.EXCLUDED_FALLING
                excludedPositions.add(ExcludedPosition(pos, ExclusionReason.FALLING))
                continue
            }
            if (hasSupportNeighbor(pos, expected, view)) {
                placedInSlot[t] = true
                cellOutcome[t] = CellOutcome.DIRECT
                directCount++
                unitActions[unitIndex].add(PlanAction.PlaceTarget(pos, expected, groupId = nextGroupId++))
                continue
            }
            val chain = ScaffoldChainPlanner.plan(pos, expected, isSchematicPosition, view, null, isInBounds)
            if (chain != null) {
                val postRemovalView = chainRemovedView(chain, view)
                if (survivesWithoutScaffold(pos, expected, postRemovalView, params.behavior)) {
                    placedInSlot[t] = true
                    cellOutcome[t] = CellOutcome.SCAFFOLD
                    scaffoldCount++
                    appendScaffoldActions(unitActions[unitIndex], chain, expected, scaffoldMaterialPositions, groupId = nextGroupId++)
                    continue
                }
                // A scaffold-reachable cell that would fall/revert the moment its own
                // scaffold is removed -- placement is possible, but nothing permanent
                // holds it yet. Defer the whole scaffold-assisted placement (chain
                // already found, reused as-is) to whichever future/pending schematic
                // neighbor's eventual placement would make it survive on its own.
                val survivalCandidates = findSurvivalReservationSupport(
                    pos,
                    expected,
                    t,
                    region,
                    order,
                    postRemovalView,
                    reservationByOrder,
                    params.behavior,
                )
                if (survivalCandidates.isNotEmpty()) {
                    cellOutcome[t] = CellOutcome.RESERVED
                    val reservation = PendingReservation(
                        pos = pos,
                        order = t,
                        expected = expected,
                        candidates = survivalCandidates,
                        survivalChain = chain,
                    )
                    reservations.add(reservation)
                    reservationByOrder[t] = reservation
                    continue
                }
                cellOutcome[t] = CellOutcome.UNREACHABLE
                unreachableCount++
                unreachablePositions.add(pos)
                continue
            }

            val candidates = findReservationSupport(pos, expected, t, region, order, view, reservationByOrder)
            if (candidates.isNotEmpty()) {
                cellOutcome[t] = CellOutcome.RESERVED
                val reservation = PendingReservation(pos = pos, order = t, expected = expected, candidates = candidates)
                reservations.add(reservation)
                reservationByOrder[t] = reservation
                continue
            }

            cellOutcome[t] = CellOutcome.UNREACHABLE
            unreachableCount++
            unreachablePositions.add(pos)
        }

        val reservationOutcome = HashMap<Int, ReservationOutcome>()
        val reservationTrigger = HashMap<Int, Int>()
        val reservationActionsByTrigger = HashMap<Int, MutableList<PlanAction>>()
        // The exact dependsOn this reservation's own resolved action(s) carry -- recorded
        // alongside reservationOutcome/reservationTrigger at each of resolveReservation's
        // three success branches, purely so the ReservationDetail built after resolution
        // does not need to re-derive it from reservationActionsByTrigger's action lists.
        val reservationDependsOn = HashMap<Int, List<BlockPos>>()

        // Every content cell's readiness, independent of whether it happens to be a
        // reservation: DIRECT/SCAFFOLD are always available from their own home unit
        // (unconditionally true, no timing dependency); the excluded/unreachable buckets
        // never become true (FAILED); a RESERVED cell defers to its own resolution outcome,
        // still PENDING until the worklist below decides it. Only ever called for an order
        // whose single classification pass has already run (every order in
        // 0 until totalContent, by construction), so cellOutcome[targetOrder] is never null.
        fun availability(targetOrder: Int): Availability {
            return when (cellOutcome[targetOrder]!!) {
                // Already holds its expected state in the frozen world at the very start of
                // the run -- unlike DIRECT/SCAFFOLD, nothing about it depends on this plan
                // ever reaching its own home unit, so waiting for that unit would be waiting
                // on a placement that was never going to happen; unit 0 is available from
                // the first tick onward, same as any other pre-existing frozen-world block.
                CellOutcome.ALREADY_PLACED -> Availability.Available(0)
                CellOutcome.DIRECT, CellOutcome.SCAFFOLD ->
                    Availability.Available(order.unitIndexForOrder[targetOrder])
                CellOutcome.EXCLUDED_CATEGORY, CellOutcome.EXCLUDED_OCCUPIED,
                CellOutcome.EXCLUDED_FALLING, CellOutcome.UNREACHABLE,
                -> Availability.Failed
                CellOutcome.RESERVED -> when (reservationTrigger[targetOrder]) {
                    null -> if (reservationOutcome[targetOrder] == ReservationOutcome.FAILED) {
                        Availability.Failed
                    } else {
                        Availability.Pending
                    }
                    else -> Availability.Available(reservationTrigger.getValue(targetOrder))
                }
            }
        }

        // Whether a content cell is guaranteed to hold its expected state at all, with no
        // regard for WHEN -- the predicate a scaffold-chain retry's own BFS needs (its
        // cell-by-cell world reads are timing-blind), never used on its own to decide a
        // trigger (see bestSupportOf, which separately looks up availability() for that).
        fun isConfirmedPlaced(targetOrder: Int): Boolean {
            return when (cellOutcome[targetOrder]) {
                CellOutcome.ALREADY_PLACED, CellOutcome.DIRECT, CellOutcome.SCAFFOLD -> true
                CellOutcome.RESERVED -> {
                    val resolved = reservationOutcome[targetOrder]
                    resolved == ReservationOutcome.PLACED_DIRECT || resolved == ReservationOutcome.PLACED_SCAFFOLD
                }
                else -> false
            }
        }
        // Every cell guaranteed placed so far in the worklist, regardless of order --
        // grows monotonically as reservations resolve, so a chain retry always sees
        // everything the plan has committed to up to that point (including OTHER
        // reservations' resolutions, not just this one's own candidates).
        val confirmedView: (BlockPos) -> BlockState = { queryPos ->
            val flat = region.flatIndexOrNegative(queryPos)
            if (region.isContent(flat)) {
                val neighborOrder = order.orderIdx[flat]
                if (isConfirmedPlaced(neighborOrder)) region.palette[region.stateIdx[flat]] else worldState(queryPos)
            } else {
                worldState(queryPos)
            }
        }

        // The specific neighbor providing a scaffold anchor's own real support, and the
        // earliest unit that neighbor is actually available by -- ScaffoldChainPlanner.plan
        // only reports THAT an anchor has support, not which neighbor or when, so this
        // redoes the same 6-direction scan to recover both. A non-content neighbor is the
        // frozen world itself (unit 0, print-range-fixed for the whole run); a content
        // neighbor's unit comes from availability(), which is only ever Available here
        // (confirmedView already filtered to neighbors it reports as supporting).
        fun bestSupportOf(pos: BlockPos, expectedState: BlockState): Pair<BlockPos, Int>? {
            var best: Pair<BlockPos, Int>? = null
            for (direction in Direction.values()) {
                if (!isUsableSupportFace(expectedState, direction.opposite)) continue
                val neighborPos = pos.relative(direction).immutable()
                // Checked before confirmedView's own read: a neighbor outside the plan's
                // configured region is never a valid support to depend/trigger on, even
                // when it happens to read as solid in the live/frozen world -- without
                // this, a non-content out-of-bounds neighbor's always-available unit 0
                // would win bestSupportOf's own smallest-unit tie-break over a real,
                // in-bounds content-cell candidate every time.
                if (!isInBounds(neighborPos)) continue
                if (!isSupportingState(confirmedView(neighborPos))) continue
                val flat = region.flatIndexOrNegative(neighborPos)
                val unit = if (region.isContent(flat)) {
                    val avail = availability(order.orderIdx[flat])
                    if (avail !is Availability.Available) continue
                    avail.unit
                } else {
                    0
                }
                val current = best
                if (current == null || unit < current.second) best = neighborPos to unit
            }
            return best
        }

        // Resolves one reservation once every reserved-typed candidate it depends on has
        // itself settled (see remainingUnsettled below). A survival-origin reservation
        // (survivalChain != null) walks its Available candidates smallest-unit-first and
        // RE-RUNS survivesWithoutScaffold against confirmedView with that one candidate
        // hypothetically placed before accepting it -- the candidate's own viability was
        // only ever checked once, at classification time, against the plan's narrower
        // single-pass view; confirmedView reflects everything the fixpoint has since
        // confirmed, and a candidate that looked sufficient against the earlier, sparser
        // view is not re-guaranteed to still be sufficient here. A candidate that fails
        // this recheck is skipped in favour of the next Available one, never chosen
        // outright. The plain (non-survival) reservation shape keeps its original
        // single-pass selection unchanged: findReservationSupport's own hypothetical
        // check already used real support (hasSupportNeighbor), not a removable scaffold,
        // so there is nothing later to invalidate. Both shapes fall back to a fresh
        // scaffold-chain retry only once every recorded candidate is FAILED or fails
        // recheck; that retry's effective trigger is deliberately NOT this reservation's
        // own recorded candidates' units -- it is the unit of whichever neighbor actually
        // supports the chosen anchor, so a target never gets scheduled before the real
        // world position it structurally depends on is guaranteed to exist.
        fun resolveReservation(pending: PendingReservation) {
            val survivalChain = pending.survivalChain
            if (survivalChain != null) {
                val postRemovalConfirmedView = chainRemovedView(survivalChain, confirmedView)
                val availableCandidates = pending.candidates
                    .mapNotNull { candidate ->
                        val avail = availability(candidate.order)
                        if (avail is Availability.Available) candidate to avail.unit else null
                    }
                    .sortedBy { it.second }
                for ((candidate, unit) in availableCandidates) {
                    val candidateFlat = region.flatIndexOrNegative(candidate.pos)
                    val candidateExpected = region.palette[region.stateIdx[candidateFlat]]
                    val hypotheticalView: (BlockPos) -> BlockState = { queryPos ->
                        if (queryPos == candidate.pos) candidateExpected else postRemovalConfirmedView(queryPos)
                    }
                    if (!survivesWithoutScaffold(pending.pos, pending.expected, hypotheticalView, params.behavior)) continue
                    reservationTrigger[pending.order] = unit
                    // The candidate is a survival-only support (see the main pass): the
                    // target still has no real placement face of its own, so it is placed
                    // the same way it would have been at classification time (via the
                    // chain already found then), just deferred to the candidate's unit.
                    reservationOutcome[pending.order] = ReservationOutcome.PLACED_SCAFFOLD
                    reservationDependsOn[pending.order] = listOf(candidate.pos)
                    val actions = reservationActionsByTrigger.getOrPut(unit) { mutableListOf() }
                    appendScaffoldActions(
                        actions,
                        survivalChain,
                        pending.expected,
                        scaffoldMaterialPositions,
                        dependsOn = listOf(candidate.pos),
                        groupId = nextGroupId++,
                    )
                    return
                }
            } else {
                var chosen: ReservationCandidate? = null
                var chosenUnit = Int.MAX_VALUE
                for (candidate in pending.candidates) {
                    val avail = availability(candidate.order)
                    if (avail is Availability.Available && avail.unit < chosenUnit) {
                        chosenUnit = avail.unit
                        chosen = candidate
                    }
                }
                if (chosen != null) {
                    reservationTrigger[pending.order] = chosenUnit
                    reservationOutcome[pending.order] = ReservationOutcome.PLACED_DIRECT
                    reservationDependsOn[pending.order] = listOf(chosen.pos)
                    reservationActionsByTrigger.getOrPut(chosenUnit) { mutableListOf() }
                        .add(
                            PlanAction.PlaceTarget(
                                pending.pos,
                                pending.expected,
                                dependsOn = listOf(chosen.pos),
                                groupId = nextGroupId++,
                            ),
                        )
                    return
                }
            }
            val chain =
                ScaffoldChainPlanner.plan(pending.pos, pending.expected, isSchematicPosition, confirmedView, null, isInBounds)
            val support = if (chain != null) bestSupportOf(chain.cells.first(), SCAFFOLD_BLOCK_STATE) else null
            // A survival-origin reservation whose every recorded candidate failed still
            // needs a fresh survival check even when a chain/support retry succeeds --
            // being scaffold-placeable was never in question for it (it already was, at
            // classification time); what failed is having anything permanent to hold it
            // once the scaffold comes back off, and confirmedView is the fixpoint's own
            // up-to-date answer to that.
            val survivesIfScaffolded = survivalChain == null || (
                chain != null &&
                    survivesWithoutScaffold(pending.pos, pending.expected, chainRemovedView(chain, confirmedView), params.behavior)
                )
            if (chain == null || support == null || !survivesIfScaffolded) {
                reservationOutcome[pending.order] = ReservationOutcome.FAILED
                return
            }
            val (supportPos, supportUnit) = support
            val dependsOn = if (region.isContent(region.flatIndexOrNegative(supportPos))) listOf(supportPos) else emptyList()
            reservationOutcome[pending.order] = ReservationOutcome.PLACED_SCAFFOLD
            reservationTrigger[pending.order] = supportUnit
            reservationDependsOn[pending.order] = dependsOn
            val actions = reservationActionsByTrigger.getOrPut(supportUnit) { mutableListOf() }
            appendScaffoldActions(actions, chain, pending.expected, scaffoldMaterialPositions, dependsOn, groupId = nextGroupId++)
        }

        // Reverse-adjacency worklist (candidate order -> the reservations that depend on
        // it) instead of a repeated full filter+sort every round: a reservation enters the
        // queue once every one of ITS OWN reserved-typed candidates has individually
        // settled (Available or Failed), and settling one reservation decrements exactly
        // the dependents that were waiting on it. Deterministic throughout: the initial
        // queue and every dependency list are populated by iterating `reservations` in its
        // own order-ascending build sequence, and resolveReservation's own candidate/
        // direction scans are order-ascending / Direction.values()-ordered, so nothing here
        // ever depends on HashMap iteration order.
        val remainingUnsettled = HashMap<Int, Int>()
        val reverseDeps = HashMap<Int, MutableList<Int>>()
        for (reservation in reservations) {
            val pendingCandidateOrders = reservation.candidates
                .map { it.order }
                .filter { cellOutcome[it] == CellOutcome.RESERVED }
            remainingUnsettled[reservation.order] = pendingCandidateOrders.size
            for (candidateOrder in pendingCandidateOrders) {
                reverseDeps.getOrPut(candidateOrder) { mutableListOf() }.add(reservation.order)
            }
        }
        val queue = ArrayDeque<Int>()
        for (reservation in reservations) {
            if (remainingUnsettled.getValue(reservation.order) == 0) queue.add(reservation.order)
        }
        // Resolves one reservation and fans its settlement out to dependents, queuing
        // whichever of them just reached zero pending candidates. The queue-count gate
        // above is deliberately stricter than what resolveReservation itself needs (it
        // only needs ONE settled-Available candidate, not all of them) -- settle() is
        // reused by both the queue drain and the rescue sweep below precisely because
        // resolving a reservation and propagating that fact to its dependents is the same
        // operation regardless of which path decided this reservation was ready. Guarded
        // idempotent: two reservations that list each other (a symmetric reverseDeps pair)
        // both decrement each other's remainingUnsettled to zero once the pair resolves,
        // which requeues an already-resolved order -- the guard makes that redundant
        // requeue a no-op instead of resolving (and appending its actions) a second time.
        fun settle(order: Int) {
            if (order in reservationOutcome) return
            resolveReservation(reservationByOrder.getValue(order))
            for (dependent in reverseDeps[order].orEmpty()) {
                val remaining = remainingUnsettled.getValue(dependent) - 1
                remainingUnsettled[dependent] = remaining
                if (remaining == 0) queue.add(dependent)
            }
        }
        fun drainQueue() {
            while (queue.isNotEmpty()) {
                settle(queue.removeFirst())
            }
        }
        drainQueue()

        // Rescue sweep: the queue-count gate above only admits a reservation once EVERY
        // one of its RESERVED-typed candidates has settled, even when an earlier candidate
        // in its list is already Available and would win resolveReservation's own
        // smallest-unit selection outright -- a reservation stuck waiting on a sibling that
        // itself only depends back on this same reservation therefore never reaches zero
        // and never gets queued, despite already having a usable candidate right now. This
        // sweep finds the smallest-order such reservation, resolves it directly (breaking
        // the cycle), and lets settle()'s normal fan-out requeue whatever that unblocks --
        // repeating until a full order-ascending scan makes no further progress. Order-
        // ascending scan over `reservations` (never HashMap iteration) keeps this
        // deterministic; a reservation with no Available candidate anywhere in its list is
        // left for the terminal FAILED pass below, unchanged from before.
        var rescued = true
        while (rescued) {
            rescued = false
            for (reservation in reservations) {
                if (reservation.order in reservationOutcome) continue
                val hasAvailableCandidate = reservation.candidates.any { candidate ->
                    availability(candidate.order) is Availability.Available
                }
                if (!hasAvailableCandidate) continue
                settle(reservation.order)
                drainQueue()
                rescued = true
                break
            }
        }

        // A reservation that never reaches zero pending candidates and is never rescued --
        // every path out of it loops back through another still-pending reservation with
        // no independently-available candidate anywhere in the group -- is precisely the
        // mutually-waiting group with no way out; it is resolved FAILED here rather than
        // retried, the same terminal semantics the old no-progress round used.
        for (reservation in reservations) {
            if (reservation.order !in reservationOutcome) {
                reservationOutcome[reservation.order] = ReservationOutcome.FAILED
            }
        }

        var reservedCount = 0
        val reservationDetails = ArrayList<ReservationDetail>()
        for (reservation in reservations) {
            when (val outcome = reservationOutcome.getValue(reservation.order)) {
                ReservationOutcome.PLACED_DIRECT, ReservationOutcome.PLACED_SCAFFOLD -> {
                    reservedCount++
                    reservationDetails.add(
                        ReservationDetail(
                            pos = reservation.pos,
                            originOrder = reservation.order,
                            origin = if (reservation.survivalChain != null) {
                                ReservationOrigin.SURVIVAL
                            } else {
                                ReservationOrigin.SUPPORT
                            },
                            effectiveMethod = if (outcome == ReservationOutcome.PLACED_DIRECT) {
                                ReservationMethod.DIRECT
                            } else {
                                ReservationMethod.SCAFFOLD
                            },
                            triggerUnit = reservationTrigger.getValue(reservation.order),
                            dependsOn = reservationDependsOn.getValue(reservation.order),
                        ),
                    )
                }
                ReservationOutcome.FAILED -> {
                    unreachableCount++
                    unreachablePositions.add(reservation.pos)
                }
            }
        }

        val planUnits = order.units.mapIndexed { index, slot ->
            PlanUnit(slot.band, slot.tileX, slot.tileZ, unitActions[index].toList())
        }
        val report = BuildabilityReport(
            directCount = directCount,
            scaffoldCount = scaffoldCount,
            reservedCount = reservedCount,
            excludedCategoryCount = excludedCategoryCount,
            excludedOccupiedCount = excludedOccupiedCount,
            excludedFallingCount = excludedFallingCount,
            unreachableCount = unreachableCount,
            alreadyPlacedCount = alreadyPlacedCount,
            excludedPositions = excludedPositions,
            unreachablePositions = unreachablePositions,
            scaffoldMaterialPositions = scaffoldMaterialPositions,
        )
        // Built trigger-unit ascending into a LinkedHashMap rather than mapValues'd
        // straight off reservationActionsByTrigger (a plain HashMap, whose iteration order
        // is hash-bucket order, not insertion or key order) -- callers that iterate
        // PrintPlan.reservations (e.g. a run applying every trigger's actions) must see a
        // fixed, reproducible order for the same plan, not one that can differ by JVM/run.
        val sortedReservations = LinkedHashMap<Int, List<PlanAction>>()
        for (trigger in reservationActionsByTrigger.keys.sorted()) {
            sortedReservations[trigger] = reservationActionsByTrigger.getValue(trigger).toList()
        }
        return PrintPlan(
            units = planUnits,
            reservations = sortedReservations,
            report = report,
            reservationDetails = reservationDetails,
        )
    }

    // Interleaves the chain's own placements (anchor-first) with the real target and
    // the reverse-order removal (target-adjacent cell first), so the temporary material
    // never outlives the target it was there to support. dependsOn is only meaningful for
    // a reservation-resolved chain (see PlanAction.PlaceTarget); the natural single-pass
    // scaffold call site never needs one, since a chain found via the plain single-pass
    // view can only ever rely on cells already placed earlier in the same total order. The
    // same dependsOn is attached to the anchor's own PlaceScaffold (chain.cells' first
    // entry) rather than every chain cell: only the anchor is placed directly against the
    // support, so only its own submission needs to wait on it.
    private fun appendScaffoldActions(
        actions: MutableList<PlanAction>,
        chain: ScaffoldPlan,
        expected: BlockState,
        scaffoldMaterialPositions: MutableList<BlockPos>,
        dependsOn: List<BlockPos> = emptyList(),
        groupId: Int,
    ): Unit {
        chain.cells.forEachIndexed { index, cell ->
            actions.add(
                PlanAction.PlaceScaffold(cell, dependsOn = if (index == 0) dependsOn else emptyList(), groupId = groupId),
            )
            scaffoldMaterialPositions.add(cell)
        }
        actions.add(PlanAction.PlaceTarget(chain.targetPos, expected, dependsOn, groupId = groupId))
        for (cell in chain.cells.asReversed()) {
            actions.add(PlanAction.RemoveScaffold(cell, groupId = groupId))
        }
    }

    // Searches pos's 6 neighbors for every content cell not yet decided in its own slot (a
    // pure future cell PrintPlanner has not reached yet, or a preceding cell already
    // recorded as its own RESERVED) whose hypothetical placement would give pos real
    // support, keeping every one of them rather than only the lowest-order match -- a
    // candidate that looks viable here can still fail to ever actually get placed (it may
    // itself be excluded, unreachable, or a reservation that never resolves), and only
    // keeping the single earliest one would strand pos at UNREACHABLE the moment that one
    // candidate fails even when a later one was perfectly usable. Sorted order-ascending
    // so callers processing candidates in list order see the same order-ascending tie-break
    // resolution itself uses.
    private fun findReservationSupport(
        pos: BlockPos,
        expected: BlockState,
        t: Int,
        region: PlanRegion,
        order: PlanOrder,
        view: (BlockPos) -> BlockState,
        reservationByOrder: Map<Int, PendingReservation>,
    ): List<ReservationCandidate> {
        val found = ArrayList<ReservationCandidate>()
        for (direction in Direction.values()) {
            val neighborPos = pos.relative(direction).immutable()
            val flat = region.flatIndexOrNegative(neighborPos)
            if (!region.isContent(flat)) continue
            val neighborOrder = order.orderIdx[flat]
            val isFuture = neighborOrder > t
            val isPendingReservation = neighborOrder < t && reservationByOrder[neighborOrder] != null
            if (!isFuture && !isPendingReservation) continue

            val neighborExpected = region.palette[region.stateIdx[flat]]
            val hypotheticalView: (BlockPos) -> BlockState = { queryPos ->
                if (queryPos == neighborPos) neighborExpected else view(queryPos)
            }
            if (!hasSupportNeighbor(pos, expected, hypotheticalView)) continue

            found.add(ReservationCandidate(neighborPos, neighborOrder))
        }
        found.sortBy { it.order }
        return found
    }

    // Same future/pending-neighbor scan as findReservationSupport, but for a cell that
    // already has a scaffold-assisted placement route and needs a PERMANENT support
    // instead of a placement face: a candidate qualifies when hypothetically placing its
    // own expected state would make survivesWithoutScaffold -- not hasSupportNeighbor --
    // true for pos. The two predicates are deliberately independent (see
    // survivesWithoutScaffold's own doc): this scan is only ever reached once the plain
    // hasSupportNeighbor/chain path already failed to produce a DIRECT placement, so it
    // is never called for the raw-support reservation shape findReservationSupport
    // itself handles. `view` is expected to already be chainRemovedView'd by the caller
    // (the scaffold chain that failed the plain survival check is the same one this scan
    // is trying to rescue) -- the per-candidate hypothetical override below still wins on
    // a position that happens to coincide with one of those chain cells, since it is
    // checked before falling back to `view`.
    private fun findSurvivalReservationSupport(
        pos: BlockPos,
        expected: BlockState,
        t: Int,
        region: PlanRegion,
        order: PlanOrder,
        view: (BlockPos) -> BlockState,
        reservationByOrder: Map<Int, PendingReservation>,
        behavior: PlacementBehaviorSettings,
    ): List<ReservationCandidate> {
        val found = ArrayList<ReservationCandidate>()
        for (direction in Direction.values()) {
            val neighborPos = pos.relative(direction).immutable()
            val flat = region.flatIndexOrNegative(neighborPos)
            if (!region.isContent(flat)) continue
            val neighborOrder = order.orderIdx[flat]
            val isFuture = neighborOrder > t
            val isPendingReservation = neighborOrder < t && reservationByOrder[neighborOrder] != null
            if (!isFuture && !isPendingReservation) continue

            val neighborExpected = region.palette[region.stateIdx[flat]]
            val hypotheticalView: (BlockPos) -> BlockState = { queryPos ->
                if (queryPos == neighborPos) neighborExpected else view(queryPos)
            }
            if (!survivesWithoutScaffold(pos, expected, hypotheticalView, behavior)) continue

            found.add(ReservationCandidate(neighborPos, neighborOrder))
        }
        found.sortBy { it.order }
        return found
    }
}
