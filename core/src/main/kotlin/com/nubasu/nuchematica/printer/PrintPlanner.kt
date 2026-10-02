package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs

private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()

internal data class PrintPlanParams(
    internal val bandHeight: Int = 8,
    internal val tileSize: Int = 48,
    internal val bounds: ((BlockPos) -> Boolean)? = null,
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

    internal fun flatIndexUnchecked(pos: BlockPos): Int {
        return (pos.x - minX) + sizeX * ((pos.y - minY) + sizeY * (pos.z - minZ))
    }

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

private data class UnitSlot(internal val band: Int, internal val tileX: Int, internal val tileZ: Int)

private class PlanOrder(
    internal val totalContent: Int,
    internal val flatIndexForOrder: IntArray,
    internal val orderIdx: IntArray,
    internal val unitIndexForOrder: IntArray,
    internal val units: List<UnitSlot>,
)

/**
 * Assigns band/tile order after evaluating both Z traversals for every column.
 *
 * The traversal exposing more direct placements wins; ties minimize the previous-column gap.
 */
private fun assignOrder(
    region: PlanRegion,
    params: PrintPlanParams,
    totalContent: Int,
    worldState: (BlockPos) -> BlockState,
): PlanOrder {
    val orderIdx = IntArray(region.stateIdx.size) { -1 }
    val flatIndexForOrder = IntArray(totalContent)
    val unitIndexForOrder = IntArray(totalContent)
    val units = ArrayList<UnitSlot>()
    val previewAvailable = BooleanArray(region.stateIdx.size)

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
            val tileZRange = if (tileXIndex % 2 == 0) 0 until numTilesZ else (numTilesZ - 1) downTo 0
            for (tileZIndex in tileZRange) {
                val zStart = tileZIndex * params.tileSize
                val zEnd = minOf(zStart + params.tileSize - 1, region.sizeZ - 1)
                val unitIndex = units.size
                units.add(UnitSlot(band, tileXIndex, tileZIndex))
                for (y in yStart..yEnd) {
                    var previousColumnEndZ: Int? = null
                    for (x in xStart..xEnd) {
                        val ascending = evaluateColumnTraversal(
                            region = region,
                            params = params,
                            worldState = worldState,
                            previewAvailable = previewAvailable,
                            y = y,
                            x = x,
                            zStart = zStart,
                            zEnd = zEnd,
                            ascending = true,
                        )
                        val descending = evaluateColumnTraversal(
                            region = region,
                            params = params,
                            worldState = worldState,
                            previewAvailable = previewAvailable,
                            y = y,
                            x = x,
                            zStart = zStart,
                            zEnd = zEnd,
                            ascending = false,
                        )
                        val chosen = chooseColumnTraversal(ascending, descending, previousColumnEndZ, region)
                        for (flat in chosen.availableFlats) previewAvailable[flat] = true
                        for (flat in chosen.orderedFlats) {
                            orderIdx[flat] = nextOrder
                            flatIndexForOrder[nextOrder] = flat
                            unitIndexForOrder[nextOrder] = unitIndex
                            nextOrder++
                        }
                        chosen.orderedFlats.lastOrNull()?.let { flat ->
                            previousColumnEndZ = localZForFlat(flat, region)
                        }
                    }
                }
            }
        }
    }

    return PlanOrder(totalContent, flatIndexForOrder, orderIdx, unitIndexForOrder, units)
}

private data class ColumnTraversalEvaluation(
    internal val orderedFlats: IntArray,
    internal val availableFlats: IntArray,
    internal val directScore: Int,
)

private fun chooseColumnTraversal(
    ascending: ColumnTraversalEvaluation,
    descending: ColumnTraversalEvaluation,
    previousColumnEndZ: Int?,
    region: PlanRegion,
): ColumnTraversalEvaluation {
    if (ascending.directScore != descending.directScore) {
        return if (ascending.directScore > descending.directScore) ascending else descending
    }
    if (previousColumnEndZ == null) return ascending
    val ascendingStartZ = ascending.orderedFlats.firstOrNull()?.let { flat -> localZForFlat(flat, region) }
    val descendingStartZ = descending.orderedFlats.firstOrNull()?.let { flat -> localZForFlat(flat, region) }
    if (ascendingStartZ == null || descendingStartZ == null) return ascending
    val ascendingGap = abs(ascendingStartZ - previousColumnEndZ)
    val descendingGap = abs(descendingStartZ - previousColumnEndZ)
    return if (descendingGap < ascendingGap) descending else ascending
}

private fun localZForFlat(flat: Int, region: PlanRegion): Int = flat / (region.sizeX * region.sizeY)

private fun evaluateColumnTraversal(
    region: PlanRegion,
    params: PrintPlanParams,
    worldState: (BlockPos) -> BlockState,
    previewAvailable: BooleanArray,
    y: Int,
    x: Int,
    zStart: Int,
    zEnd: Int,
    ascending: Boolean,
): ColumnTraversalEvaluation {
    val orderedBuffer = IntArray(zEnd - zStart + 1)
    var orderedCount = 0
    val zRange = if (ascending) zStart..zEnd else zEnd downTo zStart
    for (z in zRange) {
        val flat = x + region.sizeX * (y + region.sizeY * z)
        if (region.isContent(flat)) orderedBuffer[orderedCount++] = flat
    }
    val orderedFlats = orderedBuffer.copyOf(orderedCount)
    val trialAvailable = HashSet<Int>(orderedCount)
    val availableBuffer = IntArray(orderedCount)
    var availableCount = 0
    var directScore = 0
    val previewView: (BlockPos) -> BlockState = { queryPos ->
        val flat = region.flatIndexOrNegative(queryPos)
        if (region.isContent(flat) && (previewAvailable[flat] || flat in trialAvailable)) {
            region.palette[region.stateIdx[flat]]
        } else {
            worldState(queryPos)
        }
    }
    for (flat in orderedFlats) {
        val pos = worldPosForFlat(flat, region)
        val expected = region.palette[region.stateIdx[flat]]
        val worldNow = worldState(pos)
        val alreadyPlaced = BlockStateEquivalence.matches(expected, worldNow, params.behavior)
        val directlyPlaceable =
            !alreadyPlaced &&
                isReplaceableTarget(worldNow) &&
                eligiblePrinterBlockItem(expected, params.behavior) != null &&
                hasSupportNeighbor(pos, expected, previewView) &&
                survivesWithoutScaffold(pos, expected, previewView, params.behavior)
        if (!alreadyPlaced && !directlyPlaceable) continue
        trialAvailable.add(flat)
        availableBuffer[availableCount++] = flat
        if (directlyPlaceable) directScore++
    }
    return ColumnTraversalEvaluation(
        orderedFlats = orderedFlats,
        availableFlats = availableBuffer.copyOf(availableCount),
        directScore = directScore,
    )
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private fun worldPosForFlat(flat: Int, region: PlanRegion): BlockPos {
    val x = flat % region.sizeX
    val remaining = flat / region.sizeX
    val y = remaining % region.sizeY
    val z = remaining / region.sizeY
    return BlockPos(region.minX + x, region.minY + y, region.minZ + z)
}

private fun chainRemovedView(chain: ScaffoldPlan, base: (BlockPos) -> BlockState): (BlockPos) -> BlockState = { queryPos ->
    if (chain.cells.contains(queryPos)) AIR_STATE else base(queryPos)
}

private enum class CellOutcome {
    ALREADY_PLACED, DIRECT, SCAFFOLD, RESERVED,
    EXCLUDED_CATEGORY, EXCLUDED_OCCUPIED, EXCLUDED_FALLING, UNREACHABLE,
}

private data class PendingReservation(
    internal val pos: BlockPos,
    internal val order: Int,
    internal val expected: BlockState,
    internal val candidates: List<ReservationCandidate>,
    internal val survivalChain: ScaffoldPlan? = null,
)

private enum class ReservationOutcome { PLACED_DIRECT, PLACED_SCAFFOLD, FAILED }

private data class ReservationCandidate(internal val pos: BlockPos, internal val order: Int)

private sealed interface Availability {
    data class Available(internal val unit: Int) : Availability
    object Pending : Availability
    object Failed : Availability
}

/**
 * Deterministically classifies schematic content against a supplied world snapshot.
 *
 * Placement order and reservation resolution depend only on the content, world view,
 * and [PrintPlanParams] supplied to [plan].
 */
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
        val order = assignOrder(region, params, content.size, worldState)

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

        var nextGroupId = 1

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
            if (
                hasSupportNeighbor(pos, expected, view) &&
                survivesWithoutScaffold(pos, expected, view, params.behavior)
            ) {
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
        val reservationDependsOn = HashMap<Int, List<BlockPos>>()

        fun availability(targetOrder: Int): Availability {
            return when (cellOutcome[targetOrder]!!) {
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
        val confirmedView: (BlockPos) -> BlockState = { queryPos ->
            val flat = region.flatIndexOrNegative(queryPos)
            if (region.isContent(flat)) {
                val neighborOrder = order.orderIdx[flat]
                if (isConfirmedPlaced(neighborOrder)) region.palette[region.stateIdx[flat]] else worldState(queryPos)
            } else {
                worldState(queryPos)
            }
        }

        fun bestSupportOf(pos: BlockPos, expectedState: BlockState): Pair<BlockPos, Int>? {
            var best: Pair<BlockPos, Int>? = null
            for (direction in Direction.values()) {
                if (!isUsableSupportFace(expectedState, direction.opposite)) continue
                val neighborPos = pos.relative(direction).immutable()
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
