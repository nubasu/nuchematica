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

// Per-axis size = leg extent + 2*SEARCH_MARGIN is allowed up to
// MAX_AXIS_CELLS, with an additional TOTAL volume cap so a long-but-thin leg and a
// short-but-fat one are both bounded independently.
internal const val MAX_AXIS_CELLS: Long = 128L
internal const val MAX_VOLUME_CELLS: Long = 600_000L

// The expansion budget scales with leg length instead of a flat constant so long
// legs (batch recovery across a schematic) get enough room to actually search,
// while short legs keep the old cheap floor.
internal const val MIN_EXPANSION_BUDGET: Int = 4096
internal const val MAX_EXPANSION_BUDGET: Int = 16384

// Half the player's ~0.6-wide collision AABB. resolveEnterableCell uses
// this to decide whether a fractional coordinate is close enough to a cell boundary
// that the AABB plausibly overlaps the neighboring cell too.
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
    // Fitting feet height for each waypoint in `path`, aligned by index (same size as
    // `path` whenever `path` is non-null). Every v3 caller (the legacy profile) reports
    // each waypoint's own integer floor here, exactly matching the height it already
    // derives from the BlockPos itself -- this field only carries new information once a
    // collision-aware profile lands a waypoint on a fractional height (e.g. a slab top).
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

// Real Minecraft player collision box (0.6 wide, 1.8 tall) -- conservative for the 1.75-block
// player premise this pathfinder serves, and the box the engine actually checks against.
internal const val PLAYER_HEIGHT: Double = 1.8

// collisionFittingFeetHeight scans this many cells upward from the candidate (0, 1, 2):
// derived in FlightPathfinder's own doc from the maximum possible free-interval base
// (just under candidateY + 1) plus PLAYER_HEIGHT (1.8), which never exceeds candidateY + 2.8
// -- always inside the layer at offset 2, so a 4th upward layer is never load-bearing.
private const val UPPER_SCAN_LAYERS: Int = 3

// One extra layer below the candidate: fences/walls report their own 1.5-tall collision
// shape entirely at their OWN position (their getCollisionShape call already returns a
// shape whose local Y max is 1.5, not 1.0), so a cell one layer above a fence must still
// see that overhang -- querying only the candidate's own position and upward would miss it,
// since the (likely air) block AT the candidate position knows nothing about its neighbor.
private const val LOWER_SCAN_MARGIN: Int = 1

private const val COLLISION_EPSILON: Double = 1.0E-9

// A fitted feet height sits exactly flush with the free interval's own boundary (the
// collision span just below it, or the scan window's own top) -- hovering there produces
// integrated server-side position corrections on rounding. Lifting by this small amount
// keeps every plan-mode fitted height off that exact boundary. Always safe: the interval
// is at least requiredHeight tall by construction (the `>=` checks below), and liftFittedHeight
// clamps the lift to availableTop - requiredHeight, so an interval with no slack beyond
// requiredHeight (the boundary case) is left un-lifted rather than pushed through its own
// ceiling.
private const val FITTED_HEIGHT_EPSILON: Double = 0.05

// Lifts a fitted feet height off the exact boundary it was fit against, clamped so the
// lift never eats into the free interval's own guaranteed clearance (see
// FITTED_HEIGHT_EPSILON's own doc).
private fun liftFittedHeight(base: Double, availableTop: Double, requiredHeight: Double): Double {
    return minOf(base + FITTED_HEIGHT_EPSILON, availableTop - requiredHeight).coerceAtLeast(base)
}

// SurvivalPrediction's adapters (LevelReader/LevelAccessor) go further than this predicate
// needs; getCollisionShape only ever reads neighbouring block states (fences/walls read
// neighbours to decide their own connected shape) via BlockGetter, the smallest interface
// that offers getBlockState. getHeight/getMinBuildHeight are LevelHeightAccessor's own
// abstract members (confirmed via this project's BlockAndTintGetter implementation,
// MainThreadSchematicRenderView) -- placeholder overworld-shaped values, never actually
// read by any collision-shape computation in this predicate's scope (slabs/full
// blocks/fences/walls/doors never consult world height).
private const val MIN_BUILD_HEIGHT: Int = -64
private const val LEVEL_HEIGHT: Int = 384

// Not private: FlightPathSimulationTest's shared AABB-collision path validator reuses this
// plumbing (the same package) to independently re-derive collision boxes via toAabbs(),
// a different code path than this predicate's own bounds()/min/max interval sweep.
internal class CollisionBlockGetterAdapter(private val view: (BlockPos) -> BlockState) : BlockGetter {
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
    override fun getBlockState(pos: BlockPos): BlockState = view(pos)
    override fun getFluidState(pos: BlockPos): FluidState = view(pos).fluidState
    override fun getHeight(): Int = LEVEL_HEIGHT
    override fun getMinBuildHeight(): Int = MIN_BUILD_HEIGHT
}

// Collision-shape passability predicate: can the player hover with feet at some continuous
// height in column (x,z), candidate cell y? Returns the fitting feet height (the free
// interval's own base, clamped to the candidate cell) or null.
//
// Footprint model (product decision, made the arbiter by FlightPathSimulationTest): the
// player's 0.6-wide AABB, hovered CELL-CENTERED (x+0.5, z+0.5) as every A* node already is,
// spans local [0.2, 0.8] on both horizontal axes -- strictly inside its own cell, with 0.2
// clearance from every face. Since no vanilla block's collision shape reaches past its own
// owning column's face (fences/walls only ever protrude vertically, into the cell directly
// above their own position, never sideways), a neighbouring column's shape can never reach
// this footprint: sampling the 3x3 would be strictly redundant here. This function still
// checks each scanned layer's own horizontal extent against the footprint (skipping a shape
// that doesn't reach the centered box at all) so it stays correct as a general predicate,
// not merely for this call pattern.
//
// Doors are passable by premise (recorded product decision): a DoorBlock state contributes
// no collision at all, open or closed. This does not extend to trapdoors or fence gates
// (open question, see the task report).
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
        // Iterated as component boxes (toAabbs()), not the shape's own single overall
        // bounds() envelope -- a compound shape with a hollow interior (e.g. a composter's
        // walls-around-an-open-top) would otherwise have its cavity folded into one
        // blocking span spanning the whole envelope, falsely treating open space as solid.
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

// Whether the player's footprint (horizontal half-width `halfWidth`, vertical span
// [feetY, feetY + requiredHeight)) at this exact continuous position intersects any
// collision geometry, checking every block column the footprint can reach -- unlike
// collisionFittingFeetHeight's own cell-centered shortcut (see its doc), a position
// interpolated along a diagonal segment, or widened past the real footprint to probe
// for nearby (not necessarily touching) geometry, is not generally cell-centered, so a
// neighbouring column's shape genuinely can reach it here.
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
    val minY = floor(feetY).toInt()
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

// Samples a player footprint of the given half-width (the real PLAYER_HALF_WIDTH by
// default; margin-widened by plan-mode smoothing) along the straight segment
// from `from` to `to` at a step no larger than SWEPT_VOLUME_SAMPLE_STEP, interpolating
// the feet height linearly between the two endpoints (each already a fitted height) --
// a straight-line smoothing hop is only genuinely safe if every sampled point along it
// clears real collision geometry, not merely the two endpoints.
internal fun sweptVolumeCollisionFree(
    from: Vec3,
    to: Vec3,
    stateAt: (BlockPos) -> BlockState,
    requiredHeight: Double = PLAYER_HEIGHT,
    sampleStep: Double = SWEPT_VOLUME_SAMPLE_STEP,
    halfWidth: Double = PLAYER_HALF_WIDTH,
): Boolean {
    val delta = to.subtract(from)
    val length = delta.length()
    val samples = if (length <= 0.0) 0 else ceil(length / sampleStep).toInt().coerceAtLeast(1)
    for (index in 0..samples) {
        val t = if (samples == 0) 0.0 else index.toDouble() / samples
        val point = from.add(delta.scale(t))
        if (!isFootprintCollisionFree(point.x, point.y, point.z, stateAt, requiredHeight, halfWidth)) return false
    }
    return true
}

internal const val SWEPT_VOLUME_SAMPLE_STEP: Double = 0.25

// Extra clearance plan-mode smoothing demands on top of the real player footprint/height
// before collapsing a hop -- widening (never narrowing) the swept volume so a straight
// line that merely grazes past real collision geometry still keeps its A*-cell-center
// waypoints instead of being smoothed into a corner clip. Vertical widening only ever
// raises the checked ceiling from the same feet height; it never lowers the feet height.
internal const val SMOOTHING_CLEARANCE_MARGIN: Double = 0.3

// How far past the player's own footprint edge (PLAYER_HALF_WIDTH) a "hugging" check
// reaches to detect nearby collision geometry -- widening the checked footprint by this
// margin (rather than checking for actual contact) is what turns this into a bias
// instead of a rejection: a node whose real footprint is clear but sits within this
// margin of a wall still costs more to enter, never becomes unenterable.
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

// Injectable neighbor-validity policy for findFlightPath, defaulting to the exact v3
// behavior (see Companion.legacy) so every existing call site is byte-identical without
// passing this parameter at all.
internal fun interface FlightPassabilityProfile {
    // Fitting feet height for hovering in this cell, or null if the cell cannot be
    // entered at all (out of bounds, solid, or insufficient clearance -- the profile
    // itself decides which).
    fun feetHeightAt(pos: BlockPos): Double?

    // Explicit search-space bounds this profile's caller wants findFlightPath's A* to
    // cover, or null to keep the legacy start/goal +/- SEARCH_MARGIN sizing (see
    // SearchBounds.create). A lambda-constructed profile (every legacy() call site)
    // inherits this default unchanged -- only collisionAware overrides it.
    fun searchBounds(): AABB? = null

    // Whether this profile can ever report a fractional (non-integer) feet height --
    // gates findFlightPath's own per-cell height cache (see feetHeightCache), which a
    // profile whose every answer is trivially pos.y.toDouble() (legacy) has no use for.
    fun tracksFractionalHeight(): Boolean = true

    // Extra cost for ENTERING an already-enterable node (added on top of the base step
    // cost of 1) -- never called for a node feetHeightAt already rejected. Legacy
    // profile: always 0, so v3's search cost stays byte-identical.
    fun steppingCost(pos: BlockPos): Double = 0.0

    companion object {
        internal fun legacy(isPassableCell: (BlockPos) -> Boolean): FlightPassabilityProfile {
            return object : FlightPassabilityProfile {
                override fun feetHeightAt(pos: BlockPos): Double? {
                    return if (isPassableCell(pos) && isPassableCell(pos.above())) pos.y.toDouble() else null
                }

                override fun tracksFractionalHeight(): Boolean = false
            }
        }

        // Search-space bounds are part of the profile (not SearchBounds' own start/goal +
        // margin array sizing, an unrelated implementation detail): a candidate outside
        // `bounds` is reported not-enterable regardless of its collision geometry.
        // requiredHeight defaults to the ordinary leg clearance (PLAYER_HEIGHT); a caller
        // validating a STAND (not merely passing through) passes the stricter headroom
        // requirement instead, using this same bounds-gated collision geometry.
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

                // Widens the real footprint by HUGGING_MARGIN and re-checks at this node's
                // own fitted height -- a positive result means solid geometry sits close
                // enough to bias the search away from this node, without the real
                // (unwidened) footprint feetHeightAt already validated ever being rejected.
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

// A naive floor() of the player's feet Vec3 can select a solid neighbor
// cell that the ~0.6-wide player AABB merely grazes -- pressed against a wall, sitting
// exactly on a cell boundary, or hovering at a fractional height -- so A* sees a
// blocked start/goal and dies instantly even though the player's body actually
// occupies an adjacent, open cell. This probes, in a fixed deterministic order, the
// neighbor cells the AABB can plausibly occupy at `position` and returns the nearest
// one `isEnterableCell` (the same 2-tall feet+head check findFlightPath uses) accepts.
//
// Candidate generation order is x outermost, then z, then y (matching this doc's
// enumeration order); the resulting list is then stable-sorted nearest-first by
// squared distance from `position` to each candidate cell's center, so ties keep the
// x/z/y generation order -- deterministic regardless of call order, no Set involved.
// x gets a -1 alternative when the x fraction is within halfWidth of the cell's low
// edge, or a +1 alternative when within halfWidth of the high edge (never both, since
// 2*halfWidth < 1); z is symmetric. y only ever gets a +1 alternative, gated on a
// nonzero y fraction (fractional/hovering feet) -- there is no y-1 alternative because
// a floored cell is never entered from below.
internal fun resolveEnterableCell(
    position: Vec3,
    halfWidth: Double,
    isEnterableCell: (BlockPos) -> Boolean,
): BlockPos? {
    val naive = floorCell(position)
    if (isEnterableCell(naive)) return naive

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
        .firstOrNull { candidate -> isEnterableCell(candidate) }
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
    // Only allocated for a profile that can actually report a fractional height (see
    // tracksFractionalHeight's own doc) -- a legacy-profile search's every feet height is
    // trivially its own cell's y, so this per-cell cache would carry no information a
    // legacy search ever reads back out.
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

    // Double, not Int: the hugging penalty (FlightPassabilityProfile.steppingCost) adds
    // a fractional cost on top of each step's base cost of 1, so a wall-hugging route
    // can total more than an equal-hop-count alternative that stays clear of walls. The
    // legacy profile's steppingCost is always 0, so every v3 search still accumulates
    // exactly integer-valued distances -- byte-identical outcomes.
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
        // A node is kept as its own waypoint whenever the travel direction turns OR the
        // fitted feet height changes (e.g. stepping onto/off a slab mid-corridor) --
        // either one is a real shape a straight-line hop between the surrounding
        // waypoints would not reproduce.
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
        // explicitBounds, when the caller's profile carries one (see
        // FlightPassabilityProfile.searchBounds), sizes the search space to that AABB's own
        // cell range directly -- the legacy start/goal +/- SEARCH_MARGIN detour cap below
        // applies only when a profile has no bounds of its own (the legacy profile, always).
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
