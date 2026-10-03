package com.nubasu.nuchematica.mover

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal const val SEARCH_MARGIN: Int = 4
internal const val MAX_SMOOTHING_PROBES: Int = 64

internal const val MAX_AXIS_CELLS: Long = 128L
internal const val MAX_VOLUME_CELLS: Long = 600_000L

internal const val MIN_EXPANSION_BUDGET: Int = 4096
internal const val MAX_EXPANSION_BUDGET: Int = 16384

internal const val PLAYER_HALF_WIDTH: Double = 0.3

internal enum class FlightPathFailureReason {
    VOLUME_CLAMPED,
    BUDGET_EXHAUSTED,
    NO_PATH,
    START_OR_GOAL_BLOCKED,
}

internal data class FlightPathResult(
    internal val path: List<BlockPos>?,
    internal val failureReason: FlightPathFailureReason?,
    internal val feetHeights: List<Double>? = null,
) {
    internal companion object {
        internal fun success(path: List<BlockPos>, feetHeights: List<Double>? = null): FlightPathResult {
            return FlightPathResult(
                path = path,
                failureReason = null,
                feetHeights = feetHeights ?: path.map { cell -> cell.y.toDouble() },
            )
        }

        internal fun failure(reason: FlightPathFailureReason): FlightPathResult {
            return FlightPathResult(path = null, failureReason = reason)
        }
    }
}

internal const val PLAYER_HEIGHT: Double = 1.8

private const val UPPER_SCAN_LAYERS: Int = 3

// Fence and wall collision shapes can extend above the cell below the player.
private const val LOWER_SCAN_MARGIN: Int = 1

private const val COLLISION_EPSILON: Double = 1.0E-9

private const val FITTED_HEIGHT_EPSILON: Double = 0.05

private fun liftFittedHeight(base: Double, availableTop: Double, requiredHeight: Double): Double {
    return minOf(base + FITTED_HEIGHT_EPSILON, availableTop - requiredHeight).coerceAtLeast(base)
}

private const val MIN_BUILD_HEIGHT: Int = -64
private const val LEVEL_HEIGHT: Int = 384

internal class CollisionBlockGetterAdapter(private val view: (BlockPos) -> BlockState) : BlockGetter {
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
    override fun getBlockState(pos: BlockPos): BlockState = view(pos)
    override fun getFluidState(pos: BlockPos): FluidState = view(pos).fluidState
    override fun getHeight(): Int = LEVEL_HEIGHT
    override fun getMinBuildHeight(): Int = MIN_BUILD_HEIGHT
}

/**
 * Returns a collision-free feet height in the cell, or null when none fits.
 *
 * Doors are treated as passable; other blocks use their voxel collision shapes.
 */
internal fun collisionFittingFeetHeight(
    x: Int,
    y: Int,
    z: Int,
    stateAt: (BlockPos) -> BlockState,
    requiredHeight: Double = PLAYER_HEIGHT,
): Double? {
    val getter = CollisionBlockGetterAdapter(stateAt)
    val footprintLow = 0.5 - PLAYER_HALF_WIDTH
    val footprintHigh = 0.5 + PLAYER_HALF_WIDTH
    val blocked = ArrayList<Pair<Double, Double>>(UPPER_SCAN_LAYERS + LOWER_SCAN_MARGIN)
    for (offset in -LOWER_SCAN_MARGIN until UPPER_SCAN_LAYERS) {
        val layerPos = BlockPos(x, y + offset, z)
        val state = stateAt(layerPos)
        if (state.block is DoorBlock) continue
        val shape = state.getCollisionShape(getter, layerPos)
        if (shape.isEmpty) continue
        for (box in shape.toAabbs()) {
            if (
                box.maxX <= footprintLow || box.minX >= footprintHigh ||
                box.maxZ <= footprintLow || box.minZ >= footprintHigh
            ) {
                continue
            }
            blocked.add((layerPos.y + box.minY) to (layerPos.y + box.maxY))
        }
    }

    val candidateTop = y + 1.0
    val windowTop = y + UPPER_SCAN_LAYERS.toDouble()
    var cursor = y.toDouble()
    for ((spanStart, spanEnd) in blocked.sortedBy { span -> span.first }) {
        if (spanStart > cursor) {
            val base = cursor
            if (base < candidateTop && spanStart - base >= requiredHeight - COLLISION_EPSILON) {
                return liftFittedHeight(base, spanStart, requiredHeight)
            }
        }
        if (spanEnd > cursor) cursor = spanEnd
    }
    if (cursor < candidateTop && windowTop - cursor >= requiredHeight - COLLISION_EPSILON) {
        return liftFittedHeight(cursor, windowTop, requiredHeight)
    }
    return null
}

internal fun isFootprintCollisionFree(
    x: Double,
    feetY: Double,
    z: Double,
    stateAt: (BlockPos) -> BlockState,
    requiredHeight: Double = PLAYER_HEIGHT,
    halfWidth: Double = PLAYER_HALF_WIDTH,
): Boolean {
    val getter = CollisionBlockGetterAdapter(stateAt)
    val playerBox = AABB(
        x - halfWidth,
        feetY,
        z - halfWidth,
        x + halfWidth,
        feetY + requiredHeight,
        z + halfWidth,
    )
    val minX = floor(x - halfWidth).toInt()
    val maxX = floor(x + halfWidth).toInt()
    val minY = floor(feetY).toInt() - LOWER_SCAN_MARGIN
    val maxY = floor(feetY + requiredHeight).toInt()
    val minZ = floor(z - halfWidth).toInt()
    val maxZ = floor(z + halfWidth).toInt()
    for (blockX in minX..maxX) {
        for (blockY in minY..maxY) {
            for (blockZ in minZ..maxZ) {
                val pos = BlockPos(blockX, blockY, blockZ)
                val state = stateAt(pos)
                if (state.block is DoorBlock) continue
                val shape = state.getCollisionShape(getter, pos)
                if (shape.isEmpty) continue
                for (box in shape.toAabbs()) {
                    val worldBox = box.move(blockX.toDouble(), blockY.toDouble(), blockZ.toDouble())
                    if (playerBox.intersects(worldBox)) return false
                }
            }
        }
    }
    return true
}

/** Checks the player's swept collision volume between two feet positions. */
internal fun sweptVolumeCollisionFree(
    from: Vec3,
    to: Vec3,
    stateAt: (BlockPos) -> BlockState,
    requiredHeight: Double = PLAYER_HEIGHT,
    sampleStep: Double = SWEPT_VOLUME_SAMPLE_STEP,
    halfWidth: Double = PLAYER_HALF_WIDTH,
): Boolean {
    require(sampleStep > 0.0)
    val delta = to.subtract(from)
    val length = delta.length()
    val samples = if (length <= 0.0) 1 else ceil(length / sampleStep).toInt().coerceAtLeast(1)
    val getter = CollisionBlockGetterAdapter(stateAt)
    var previous = from
    for (index in 1..samples) {
        val t = index.toDouble() / samples
        val point = from.add(delta.scale(t))
        if (
            !sweptStepCollisionFree(
                from = previous,
                to = point,
                stateAt = stateAt,
                getter = getter,
                requiredHeight = requiredHeight,
                halfWidth = halfWidth,
            )
        ) {
            return false
        }
        previous = point
    }
    return true
}

private fun sweptStepCollisionFree(
    from: Vec3,
    to: Vec3,
    stateAt: (BlockPos) -> BlockState,
    getter: BlockGetter,
    requiredHeight: Double,
    halfWidth: Double,
): Boolean {
    val minX = floor(minOf(from.x, to.x) - halfWidth).toInt()
    val maxX = floor(maxOf(from.x, to.x) + halfWidth).toInt()
    val minY = floor(minOf(from.y, to.y)).toInt() - LOWER_SCAN_MARGIN
    val maxY = floor(maxOf(from.y, to.y) + requiredHeight).toInt()
    val minZ = floor(minOf(from.z, to.z) - halfWidth).toInt()
    val maxZ = floor(maxOf(from.z, to.z) + halfWidth).toInt()
    for (blockX in minX..maxX) {
        for (blockY in minY..maxY) {
            for (blockZ in minZ..maxZ) {
                val pos = BlockPos(blockX, blockY, blockZ)
                val state = stateAt(pos)
                if (state.block is DoorBlock) continue
                val shape = state.getCollisionShape(getter, pos)
                if (shape.isEmpty) continue
                for (box in shape.toAabbs()) {
                    val worldBox = box.move(blockX.toDouble(), blockY.toDouble(), blockZ.toDouble())
                    if (segmentIntersectsExpandedCollisionBox(from, to, worldBox, requiredHeight, halfWidth)) {
                        return false
                    }
                }
            }
        }
    }
    return true
}

private fun segmentIntersectsExpandedCollisionBox(
    from: Vec3,
    to: Vec3,
    collisionBox: AABB,
    requiredHeight: Double,
    halfWidth: Double,
): Boolean {
    val lower = doubleArrayOf(
        collisionBox.minX - halfWidth + COLLISION_EPSILON,
        collisionBox.minY - requiredHeight + COLLISION_EPSILON,
        collisionBox.minZ - halfWidth + COLLISION_EPSILON,
    )
    val upper = doubleArrayOf(
        collisionBox.maxX + halfWidth - COLLISION_EPSILON,
        collisionBox.maxY - COLLISION_EPSILON,
        collisionBox.maxZ + halfWidth - COLLISION_EPSILON,
    )
    val start = doubleArrayOf(from.x, from.y, from.z)
    val end = doubleArrayOf(to.x, to.y, to.z)
    var enter = 0.0
    var exit = 1.0
    for (axis in 0..2) {
        if (lower[axis] > upper[axis]) return false
        val direction = end[axis] - start[axis]
        if (abs(direction) <= COLLISION_EPSILON) {
            if (start[axis] < lower[axis] || start[axis] > upper[axis]) return false
            continue
        }
        val first = (lower[axis] - start[axis]) / direction
        val second = (upper[axis] - start[axis]) / direction
        enter = maxOf(enter, minOf(first, second))
        exit = minOf(exit, maxOf(first, second))
        if (enter > exit) return false
    }
    return enter <= 1.0 && exit >= 0.0
}

internal const val SWEPT_VOLUME_SAMPLE_STEP: Double = 0.25

internal const val SMOOTHING_CLEARANCE_MARGIN: Double = 0.3

internal const val HUGGING_MARGIN: Double = 0.3
internal const val HUGGING_STEP_PENALTY: Double = 0.5

private fun cellCenterWithinBounds(bounds: AABB, pos: BlockPos): Boolean {
    val centerX = pos.x + 0.5
    val centerY = pos.y + 0.5
    val centerZ = pos.z + 0.5
    return centerX >= bounds.minX && centerX <= bounds.maxX &&
        centerY >= bounds.minY && centerY <= bounds.maxY &&
        centerZ >= bounds.minZ && centerZ <= bounds.maxZ
}

/** Supplies cell heights, bounds, costs, and transitions to [findFlightPath]. */
internal fun interface FlightPassabilityProfile {
    /** Returns a usable feet height, or null when the cell cannot be entered. */
    fun feetHeightAt(pos: BlockPos): Double?

    /** Returns optional bounds whose cell centers may be searched. */
    fun searchBounds(): AABB? = null

    /** Whether successful paths must retain exact fractional feet heights. */
    fun tracksFractionalHeight(): Boolean = true

    fun steppingCost(pos: BlockPos): Double = 0.0

    fun transitionClear(from: BlockPos, fromFeetY: Double, to: BlockPos, toFeetY: Double): Boolean = true

    companion object {
        internal fun legacy(isPassableCell: (BlockPos) -> Boolean): FlightPassabilityProfile {
            return object : FlightPassabilityProfile {
                override fun feetHeightAt(pos: BlockPos): Double? {
                    return if (isPassableCell(pos) && isPassableCell(pos.above())) pos.y.toDouble() else null
                }

                override fun tracksFractionalHeight(): Boolean = false
            }
        }

        internal fun collisionAware(
            bounds: AABB,
            stateAt: (BlockPos) -> BlockState,
            requiredHeight: Double = PLAYER_HEIGHT,
        ): FlightPassabilityProfile {
            return object : FlightPassabilityProfile {
                override fun feetHeightAt(pos: BlockPos): Double? {
                    return if (!cellCenterWithinBounds(bounds, pos)) {
                        null
                    } else {
                        collisionFittingFeetHeight(pos.x, pos.y, pos.z, stateAt, requiredHeight)
                    }
                }

                override fun searchBounds(): AABB = bounds

                override fun steppingCost(pos: BlockPos): Double {
                    val feetHeight = feetHeightAt(pos) ?: return 0.0
                    val hugging = !isFootprintCollisionFree(
                        pos.x + 0.5,
                        feetHeight,
                        pos.z + 0.5,
                        stateAt,
                        requiredHeight,
                        PLAYER_HALF_WIDTH + HUGGING_MARGIN,
                    )
                    return if (hugging) HUGGING_STEP_PENALTY else 0.0
                }

                override fun transitionClear(
                    from: BlockPos,
                    fromFeetY: Double,
                    to: BlockPos,
                    toFeetY: Double,
                ): Boolean {
                    return sweptVolumeCollisionFree(
                        Vec3(from.x + 0.5, fromFeetY, from.z + 0.5),
                        Vec3(to.x + 0.5, toFeetY, to.z + 0.5),
                        stateAt,
                        requiredHeight = requiredHeight,
                    )
                }
            }
        }
    }
}

internal fun defaultExpansionBudget(start: BlockPos, goal: BlockPos): Int {
    val scaled = 4L * manhattanDistance(start, goal)
    return scaled.coerceIn(MIN_EXPANSION_BUDGET.toLong(), MAX_EXPANSION_BUDGET.toLong()).toInt()
}

private fun manhattanDistance(start: BlockPos, goal: BlockPos): Long {
    return abs(goal.x.toLong() - start.x) +
        abs(goal.y.toLong() - start.y) +
        abs(goal.z.toLong() - start.z)
}

internal fun floorCell(position: Vec3): BlockPos {
    return BlockPos(
        floor(position.x).toInt(),
        floor(position.y).toInt(),
        floor(position.z).toInt(),
    )
}

/** Resolves [position] to the nearest overlapping enterable cell. */
internal fun resolveEnterableCell(
    position: Vec3,
    halfWidth: Double,
    isEnterableCell: (BlockPos) -> Boolean,
): BlockPos? {
    return resolveEnterableCell(position, halfWidth, isEnterableCell) { true }
}

/**
 * Resolves [position] deterministically across footprint-boundary cells.
 *
 * The selected cell must satisfy both [isEnterableCell] and [isConnectorClear].
 */
internal fun resolveEnterableCell(
    position: Vec3,
    halfWidth: Double,
    isEnterableCell: (BlockPos) -> Boolean,
    isConnectorClear: (BlockPos) -> Boolean,
): BlockPos? {
    val naive = floorCell(position)
    if (isEnterableCell(naive) && isConnectorClear(naive)) return naive

    val xOffsets = boundaryOffsets(position.x, halfWidth)
    val zOffsets = boundaryOffsets(position.z, halfWidth)
    val fractionY = position.y - floor(position.y)
    val yOffsets = if (fractionY > 0.0) intArrayOf(0, 1) else intArrayOf(0)

    val candidates = ArrayList<BlockPos>(xOffsets.size * zOffsets.size * yOffsets.size)
    for (xOffset in xOffsets) {
        for (zOffset in zOffsets) {
            for (yOffset in yOffsets) {
                if (xOffset == 0 && zOffset == 0 && yOffset == 0) continue
                candidates.add(naive.offset(xOffset, yOffset, zOffset))
            }
        }
    }

    return candidates
        .sortedBy { candidate -> cellCenterDistanceSqr(candidate, position) }
        .firstOrNull { candidate -> isEnterableCell(candidate) && isConnectorClear(candidate) }
}

private fun boundaryOffsets(coordinate: Double, halfWidth: Double): IntArray {
    val fraction = coordinate - floor(coordinate)
    return when {
        fraction < halfWidth -> intArrayOf(0, -1)
        fraction > 1.0 - halfWidth -> intArrayOf(0, 1)
        else -> intArrayOf(0)
    }
}

private fun cellCenterDistanceSqr(cell: BlockPos, position: Vec3): Double {
    val deltaX = cell.x + 0.5 - position.x
    val deltaY = cell.y + 0.5 - position.y
    val deltaZ = cell.z + 0.5 - position.z
    return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
}

/** Runs bounded six-neighbor A* and reports search failures by reason. */
internal fun findFlightPath(
    start: BlockPos,
    goal: BlockPos,
    isPassableCell: (BlockPos) -> Boolean,
    expansionBudget: Int = defaultExpansionBudget(start, goal),
    profile: FlightPassabilityProfile = FlightPassabilityProfile.legacy(isPassableCell),
): FlightPathResult {
    require(expansionBudget >= 0)
    val bounds = SearchBounds.create(start, goal, profile.searchBounds())
        ?: return FlightPathResult.failure(FlightPathFailureReason.VOLUME_CLAMPED)
    val passability = ByteArray(bounds.volume)
    val feetHeightCache = if (profile.tracksFractionalHeight()) DoubleArray(bounds.volume) else null

    fun isEnterable(index: Int): Boolean {
        when (passability[index].toInt()) {
            PASSABLE -> return true
            BLOCKED -> return false
        }
        val position = bounds.position(index)
        val feetHeight = profile.feetHeightAt(position)
        val enterable = feetHeight != null
        if (enterable) feetHeightCache?.set(index, feetHeight!!)
        passability[index] = if (enterable) PASSABLE.toByte() else BLOCKED.toByte()
        return enterable
    }

    fun feetHeightOf(index: Int): Double {
        return feetHeightCache?.get(index) ?: bounds.position(index).y.toDouble()
    }

    val startIndex = bounds.index(start)
    val goalIndex = bounds.index(goal)
    if (!isEnterable(startIndex) || !isEnterable(goalIndex)) {
        return FlightPathResult.failure(FlightPathFailureReason.START_OR_GOAL_BLOCKED)
    }
    if (startIndex == goalIndex) return FlightPathResult.success(emptyList())

    val distances = DoubleArray(bounds.volume) { Double.MAX_VALUE }
    val cameFrom = IntArray(bounds.volume) { NO_INDEX }
    val closed = BooleanArray(bounds.volume)
    val open = IndexMinHeap(bounds.volume)
    distances[startIndex] = 0.0
    open.addOrDecrease(startIndex, bounds.heuristic(startIndex, goal))

    var expansions = 0
    while (open.isNotEmpty() && expansions < expansionBudget) {
        val current = open.removeMin()
        if (current == goalIndex) {
            val (path, feetHeights) = simplifiedPath(startIndex, goalIndex, cameFrom, bounds, ::feetHeightOf)
            return FlightPathResult.success(path, feetHeights)
        }
        if (closed[current]) continue
        closed[current] = true
        expansions++

        val xOffset = bounds.xOffset(current)
        val yOffset = bounds.yOffset(current)
        val zOffset = bounds.zOffset(current)
        for (direction in 0 until DIRECTION_COUNT) {
            val neighbor = when (direction) {
                0 -> if (xOffset + 1 < bounds.sizeX) current + 1 else NO_INDEX
                1 -> if (xOffset > 0) current - 1 else NO_INDEX
                2 -> if (zOffset + 1 < bounds.sizeZ) current + bounds.sizeX else NO_INDEX
                3 -> if (zOffset > 0) current - bounds.sizeX else NO_INDEX
                4 -> if (yOffset + 1 < bounds.sizeY) current + bounds.layerSize else NO_INDEX
                else -> if (yOffset > 0) current - bounds.layerSize else NO_INDEX
            }
            if (neighbor == NO_INDEX || closed[neighbor] || !isEnterable(neighbor)) continue
            if (
                !profile.transitionClear(
                    bounds.position(current),
                    feetHeightOf(current),
                    bounds.position(neighbor),
                    feetHeightOf(neighbor),
                )
            ) {
                continue
            }

            val stepCost = 1.0 + profile.steppingCost(bounds.position(neighbor))
            val candidateDistance = distances[current] + stepCost
            if (candidateDistance >= distances[neighbor]) continue
            distances[neighbor] = candidateDistance
            cameFrom[neighbor] = current
            open.addOrDecrease(
                neighbor,
                candidateDistance + bounds.heuristic(neighbor, goal),
            )
        }
    }
    return FlightPathResult.failure(
        if (expansions >= expansionBudget) {
            FlightPathFailureReason.BUDGET_EXHAUSTED
        } else {
            FlightPathFailureReason.NO_PATH
        },
    )
}

/** Removes line-of-sight waypoints within [probeBudget]. */
internal fun smoothFlightPath(
    start: Vec3,
    waypoints: List<Vec3>,
    lineClear: (Vec3, Vec3) -> Boolean,
    probeBudget: Int = MAX_SMOOTHING_PROBES,
): List<Vec3> {
    require(probeBudget >= 0)
    if (waypoints.isEmpty() || probeBudget == 0) return waypoints.toList()

    val smoothed = ArrayList<Vec3>(waypoints.size)
    var anchor = start
    var firstCandidate = 0
    var probes = 0
    while (firstCandidate < waypoints.size) {
        var selected = firstCandidate
        for (candidate in waypoints.lastIndex downTo firstCandidate) {
            if (probes >= probeBudget) break
            probes++
            if (lineClear(anchor, waypoints[candidate])) {
                selected = candidate
                break
            }
        }
        val waypoint = waypoints[selected]
        smoothed.add(waypoint)
        anchor = waypoint
        firstCandidate = selected + 1
    }
    return smoothed
}

private fun simplifiedPath(
    startIndex: Int,
    goalIndex: Int,
    cameFrom: IntArray,
    bounds: SearchBounds,
    feetHeightOf: (Int) -> Double,
): Pair<List<BlockPos>, List<Double>> {
    var pathLength = 1
    var current = goalIndex
    while (current != startIndex) {
        current = cameFrom[current]
        check(current != NO_INDEX)
        pathLength++
    }

    val path = IntArray(pathLength)
    current = goalIndex
    for (index in path.lastIndex downTo 0) {
        path[index] = current
        if (index > 0) current = cameFrom[current]
    }
    if (pathLength == 1) return emptyList<BlockPos>() to emptyList()

    val waypoints = ArrayList<BlockPos>()
    val waypointFeetHeights = ArrayList<Double>()
    var previousDirection = path[1] - path[0]
    var previousHeight = feetHeightOf(path[0])
    for (index in 2 until pathLength) {
        val direction = path[index] - path[index - 1]
        val height = feetHeightOf(path[index - 1])
        if (direction != previousDirection || height != previousHeight) {
            waypoints.add(bounds.position(path[index - 1]))
            waypointFeetHeights.add(height)
            previousDirection = direction
            previousHeight = height
        }
    }
    waypoints.add(bounds.position(goalIndex))
    waypointFeetHeights.add(feetHeightOf(goalIndex))
    return waypoints to waypointFeetHeights
}

private class SearchBounds private constructor(
    private val minX: Int,
    private val minY: Int,
    private val minZ: Int,
    internal val sizeX: Int,
    internal val sizeY: Int,
    internal val sizeZ: Int,
) {
    internal val layerSize: Int = sizeX * sizeZ
    internal val volume: Int = layerSize * sizeY

    internal fun index(position: BlockPos): Int {
        return (position.y - minY) * layerSize +
            (position.z - minZ) * sizeX +
            position.x - minX
    }

    internal fun position(index: Int): BlockPos {
        val yOffset = yOffset(index)
        val inLayer = index - yOffset * layerSize
        val zOffset = inLayer / sizeX
        val xOffset = inLayer - zOffset * sizeX
        return BlockPos(minX + xOffset, minY + yOffset, minZ + zOffset)
    }

    internal fun xOffset(index: Int): Int {
        return index % sizeX
    }

    internal fun yOffset(index: Int): Int {
        return index / layerSize
    }

    internal fun zOffset(index: Int): Int {
        return (index % layerSize) / sizeX
    }

    internal fun heuristic(index: Int, goal: BlockPos): Double {
        val yOffset = yOffset(index)
        val inLayer = index - yOffset * layerSize
        val zOffset = inLayer / sizeX
        val xOffset = inLayer - zOffset * sizeX
        val deltaX = minX + xOffset - goal.x
        val deltaY = minY + yOffset - goal.y
        val deltaZ = minZ + zOffset - goal.z
        return sqrt(
            (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble(),
        )
    }

    internal companion object {
        internal fun create(start: BlockPos, goal: BlockPos, explicitBounds: AABB?): SearchBounds? {
            val minX: Long
            val minY: Long
            val minZ: Long
            val maxX: Long
            val maxY: Long
            val maxZ: Long
            if (explicitBounds != null) {
                minX = floor(explicitBounds.minX).toLong()
                minY = floor(explicitBounds.minY).toLong()
                minZ = floor(explicitBounds.minZ).toLong()
                maxX = ceil(explicitBounds.maxX).toLong() - 1
                maxY = ceil(explicitBounds.maxY).toLong() - 1
                maxZ = ceil(explicitBounds.maxZ).toLong() - 1
            } else {
                minX = minOf(start.x, goal.x).toLong() - SEARCH_MARGIN
                minY = minOf(start.y, goal.y).toLong() - SEARCH_MARGIN
                minZ = minOf(start.z, goal.z).toLong() - SEARCH_MARGIN
                maxX = maxOf(start.x, goal.x).toLong() + SEARCH_MARGIN
                maxY = maxOf(start.y, goal.y).toLong() + SEARCH_MARGIN
                maxZ = maxOf(start.z, goal.z).toLong() + SEARCH_MARGIN
            }
            if (
                minX < Int.MIN_VALUE || minY < Int.MIN_VALUE || minZ < Int.MIN_VALUE ||
                maxX > Int.MAX_VALUE || maxY > Int.MAX_VALUE || maxZ > Int.MAX_VALUE
            ) {
                return null
            }
            val sizeX = maxX - minX + 1
            val sizeY = maxY - minY + 1
            val sizeZ = maxZ - minZ + 1
            if (
                sizeX > MAX_AXIS_CELLS ||
                sizeY > MAX_AXIS_CELLS ||
                sizeZ > MAX_AXIS_CELLS ||
                sizeX * sizeY * sizeZ > MAX_VOLUME_CELLS
            ) {
                return null
            }
            return SearchBounds(
                minX = minX.toInt(),
                minY = minY.toInt(),
                minZ = minZ.toInt(),
                sizeX = sizeX.toInt(),
                sizeY = sizeY.toInt(),
                sizeZ = sizeZ.toInt(),
            )
        }
    }
}

private class IndexMinHeap(capacity: Int) {
    private val nodes: IntArray = IntArray(capacity)
    private val priorities: DoubleArray = DoubleArray(capacity)
    private val positions: IntArray = IntArray(capacity) { NO_INDEX }
    private var size: Int = 0

    internal fun isNotEmpty(): Boolean {
        return size > 0
    }

    internal fun addOrDecrease(node: Int, priority: Double): Unit {
        val existing = positions[node]
        if (existing != NO_INDEX) {
            if (priority >= priorities[existing]) return
            priorities[existing] = priority
            bubbleUp(existing)
            return
        }

        val index = size
        size++
        nodes[index] = node
        priorities[index] = priority
        positions[node] = index
        bubbleUp(index)
    }

    internal fun removeMin(): Int {
        check(size > 0)
        val result = nodes[0]
        positions[result] = NO_INDEX
        size--
        if (size > 0) {
            nodes[0] = nodes[size]
            priorities[0] = priorities[size]
            positions[nodes[0]] = 0
            bubbleDown(0)
        }
        return result
    }

    private fun bubbleUp(startIndex: Int): Unit {
        var index = startIndex
        while (index > 0) {
            val parent = (index - 1) / 2
            if (!less(index, parent)) return
            swap(index, parent)
            index = parent
        }
    }

    private fun bubbleDown(startIndex: Int): Unit {
        var index = startIndex
        while (true) {
            val left = index * 2 + 1
            if (left >= size) return
            val right = left + 1
            val smallest = if (right < size && less(right, left)) right else left
            if (!less(smallest, index)) return
            swap(index, smallest)
            index = smallest
        }
    }

    private fun less(first: Int, second: Int): Boolean {
        val priorityComparison = priorities[first].compareTo(priorities[second])
        return priorityComparison < 0 ||
            (priorityComparison == 0 && nodes[first] < nodes[second])
    }

    private fun swap(first: Int, second: Int): Unit {
        val firstNode = nodes[first]
        val firstPriority = priorities[first]
        nodes[first] = nodes[second]
        priorities[first] = priorities[second]
        nodes[second] = firstNode
        priorities[second] = firstPriority
        positions[nodes[first]] = first
        positions[nodes[second]] = second
    }
}

private const val DIRECTION_COUNT: Int = 6
private const val NO_INDEX: Int = -1
private const val PASSABLE: Int = 1
private const val BLOCKED: Int = 2
