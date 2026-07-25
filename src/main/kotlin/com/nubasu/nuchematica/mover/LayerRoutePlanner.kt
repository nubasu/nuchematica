package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.potentialSupportHitPoints
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

internal data class PlannedWorkPosition(
    internal val target: Vec3,
    internal val covered: Set<BlockPos>,
)

internal data class RoutePlan(
    internal val route: List<PlannedWorkPosition>,
    internal val uncoverable: List<BlockPos>,
)

internal fun planRoute(
    placeableMissing: List<BlockPos>,
    reach: Double,
    bannedTargets: Set<Vec3> = emptySet(),
    // Expected state per missing position, needed to build the
    // selector-aligned hit envelope (potentialSupportHitPoints) coveredMissing scores
    // candidates against. A null entry falls back to FALLBACK_EXPECTED_STATE (a plain
    // full block, so isUsableSupportFace excludes nothing and the envelope is just the
    // 6 face centers) -- production always has a real expectedState for every missing
    // position; this default only spares tests that plan generic geometry from wiring
    // one up, and it still goes through the exact same envelope function.
    expectedStateAt: (BlockPos) -> BlockState? = { null },
    // Plan mode only: scores each candidate's coverage from its cell-centered (x+0.5,
    // z+0.5) pose -- the exact hover position the mover actually flies to and stands at
    // (see MoverCore.standFittedTarget) -- instead of the raw candidate corner this
    // function otherwise both scores from and returns. v3 callers never pass this
    // (default false keeps them byte-identical: scoring pose == returned target, exactly
    // as before).
    centerCandidateForScoring: Boolean = false,
    // Plan mode only: a target sharing its (x, z) column with the candidate's own stand
    // cell can never actually be placed while the mover stands there -- the printer's own
    // column-occupancy check defers it regardless of reach, so crediting it to this
    // candidate's coverage would let a stand claim work it can never perform. Excluding it
    // here lets a genuinely reachable, non-conflicting candidate score (and win) on its own
    // merit instead. v3 callers never pass this (default false keeps them byte-identical).
    excludeOwnColumnFromCoverage: Boolean = false,
    // Plan mode only: consumes placeableMissing in its own given order instead of
    // re-sorting it into the snake sweep -- the caller's list is already the frontier's
    // own plan-dispatch order, and re-sorting it here would discard that ordering before
    // it ever reaches the anchor selection below. v3 callers never pass this (default
    // false keeps them byte-identical: the snake sweep still runs exactly as before).
    preserveInputOrder: Boolean = false,
    // Plan mode only: excludes a candidate outright by its raw (un-fitted) target --
    // a previously banned stand cell, a run-scoped banned target, or any other
    // caller-defined exclusion. v3 callers never pass this (default rejects nothing,
    // byte-identical).
    isCandidateBanned: (Vec3) -> Boolean = { false },
    // Plan mode only: a candidate must additionally satisfy this before it can win --
    // MoverCore uses it for the stand-headroom/no-overhang rule (isStandAcceptable).
    // Evaluated against the candidate's own raw target and its (already own-column-
    // filtered, see excludeOwnColumnFromCoverage) covered set. v3 callers never pass
    // this (default accepts everything, byte-identical).
    isStandUsable: (Vec3, List<BlockPos>) -> Boolean = { _, _ -> true },
    // Plan mode only: scores each candidate's coverage as a plan-order-CONTIGUOUS prefix
    // of `remaining` instead of the full geometric reach disk -- walking `remaining` from
    // its own head (the anchor) forward, taking every in-reach, non-own-column cell, and
    // STOPPING at the first in-order cell that is out of reach (an own-column cell is
    // skipped without stopping the walk -- see excludeOwnColumnFromCoverage's own doc for
    // why it is never this candidate's to claim). A disk-shaped candidate can otherwise
    // skip a plan-order-adjacent target while still reaching one far ahead in plan order
    // (snake columns fold back close together in space), leaving the covering-route-entry
    // index non-monotonic along plan order -- exactly what lets a later dispatch position
    // land back inside an EARLIER, already-passed route entry's coverage, which the
    // forward-only in-place advance (tickPlanArrivalHold) can never find. A contiguous
    // prefix makes that index non-decreasing by construction: every entry's coverage is
    // carved off the front of whatever plan order remains after every earlier entry's own
    // prefix was removed. v3 callers never pass this (default false keeps them
    // byte-identical: coverage is still the full reach disk, exactly as before).
    coverContiguousPrefix: Boolean = false,
): RoutePlan {
    val distinctMissing = placeableMissing
        .map { position -> position.immutable() }
        .distinct()
    val remaining = if (preserveInputOrder) {
        distinctMissing.toMutableList()
    } else {
        distinctMissing.sortedWith(SWEEP_POSITION_ORDER).toMutableList()
    }
    val route = mutableListOf<PlannedWorkPosition>()
    val uncoverable = mutableListOf<BlockPos>()
    // Envelope cache scoped to this single planRoute call: potentialSupportHitPoints
    // depends only on (position, its own expected state), both fixed for the life of
    // this call, so every one of the 9 candidates scoring the same anchor iteration --
    // and every later iteration that still has this position in remaining -- reuses the
    // same computed envelope instead of rebuilding it from scratch. Never shared across
    // calls: a fresh planRoute invocation gets a fresh cache.
    val envelopeCache = HashMap<BlockPos, List<Vec3>>()
    // Column (packed x/z) -> count of `remaining` entries currently sitting in that
    // column, kept in sync with every removal below -- lets the temporal column check
    // (excludeOwnColumnFromCoverage only) test "does this column still have pending
    // work" in O(1) instead of rescanning remaining per candidate. Unused (null) for v3
    // callers, matching excludeOwnColumnFromCoverage's own default-false scope.
    val remainingColumnCounts: HashMap<Long, Int>? = if (excludeOwnColumnFromCoverage) {
        HashMap<Long, Int>().also { counts ->
            for (position in remaining) {
                val key = columnKey(position.x, position.z)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
    } else {
        null
    }

    fun releaseColumn(position: BlockPos): Unit {
        val counts = remainingColumnCounts ?: return
        val key = columnKey(position.x, position.z)
        val next = (counts[key] ?: 0) - 1
        if (next <= 0) counts.remove(key) else counts[key] = next
    }

    while (remaining.isNotEmpty()) {
        val anchor = remaining.first()
        val best = CANDIDATE_OFFSETS.mapIndexed { offsetIndex, offset ->
            val target = Vec3(
                (anchor.x + offset.x).toDouble(),
                (anchor.y + offset.y).toDouble() + HOVER_CLEARANCE,
                (anchor.z + offset.z).toDouble(),
            )
            val scoringPosition = if (centerCandidateForScoring) {
                Vec3(target.x + 0.5, target.y, target.z + 0.5)
            } else {
                target
            }
            val ownColumnX = anchor.x + offset.x
            val ownColumnZ = anchor.z + offset.z
            val isOwnColumn: (BlockPos) -> Boolean = { position ->
                position.x == ownColumnX && position.z == ownColumnZ
            }
            val scoredCovered = if (coverContiguousPrefix) {
                coveredMissingPrefix(
                    target = scoringPosition,
                    remaining = remaining,
                    reach = reach,
                    expectedStateAt = expectedStateAt,
                    envelopeCache = envelopeCache,
                    isOwnColumn = if (excludeOwnColumnFromCoverage) isOwnColumn else { _ -> false },
                )
            } else {
                val covered = coveredMissing(scoringPosition, remaining, reach, expectedStateAt, envelopeCache)
                if (excludeOwnColumnFromCoverage) covered.filterNot(isOwnColumn) else covered
            }
            CandidateScore(
                offsetIndex = offsetIndex,
                target = target,
                covered = scoredCovered,
                ownColumnKey = columnKey(ownColumnX, ownColumnZ),
            )
        }
            .filterNot { candidate -> candidate.target in bannedTargets }
            .filterNot { candidate -> isCandidateBanned(candidate.target) }
            .filterNot { candidate ->
                // Temporal column rule: a candidate may never park on a column that
                // still has a target pending once THIS entry's own coverage is
                // consumed -- excludeOwnColumnFromCoverage already keeps any target
                // sharing the candidate's own column out of `covered`, so such a
                // target is never removed from `remaining` by this pick, and its
                // presence in `remaining` right now already answers "does it survive
                // this consumption". A column emptied by an EARLIER route entry is no
                // longer in remainingColumnCounts at all, so a later candidate on that
                // same column is legal -- this is what lets one planRoute call plan a
                // whole layer instead of only its unbuilt frontier.
                remainingColumnCounts != null && remainingColumnCounts.containsKey(candidate.ownColumnKey)
            }
            .filter { candidate -> isStandUsable(candidate.target, candidate.covered) }
            .minWithOrNull(CANDIDATE_ORDER)

        if (best == null || best.covered.isEmpty()) {
            uncoverable += anchor
            releaseColumn(anchor)
            remaining.removeAt(0)
            continue
        }

        val covered = best.covered.toSet()
        route += PlannedWorkPosition(
            target = best.target,
            covered = covered,
        )
        for (position in covered) releaseColumn(position)
        remaining.removeAll(covered)
    }

    return RoutePlan(route = route, uncoverable = uncoverable)
}

private fun columnKey(blockX: Int, blockZ: Int): Long {
    return (blockX.toLong() shl 32) xor (blockZ.toLong() and 0xFFFFFFFFL)
}

// A position is covered iff at least one of its selector-aligned hit
// points (potentialSupportHitPoints) sits within reach - HIT_COVERAGE_MARGIN of the
// eye at the candidate work position -- replacing the old block-center distance model,
// which underestimated the true hit distance for half/axis-offset geometries (TOP-half
// stairs, hanging blocks) and planned unreachable work positions as a result.
// The margin shrinks from the old
// center-model's 0.5 to 0.25: the envelope already measures to the real hit location,
// so it no longer needs to absorb the center-vs-hit offset itself.
private fun coveredMissing(
    target: Vec3,
    missingWorld: List<BlockPos>,
    reach: Double,
    expectedStateAt: (BlockPos) -> BlockState?,
    envelopeCache: MutableMap<BlockPos, List<Vec3>>,
): List<BlockPos> {
    val effectiveReach = (reach - HIT_COVERAGE_MARGIN).coerceAtLeast(0.0)
    val reachSquared = effectiveReach * effectiveReach
    val eyePosition = Vec3(target.x, target.y + EYE_HEIGHT, target.z)
    // Coarse prefilter: skip the envelope entirely (no potentialSupportHitPoints call,
    // no expectedStateAt lookup) once the block's own center is farther from the eye
    // than any of its hit points could possibly be. A hit point never sits more than
    // ~0.87 blocks from its block's center (the corner-to-center distance of a unit
    // cube); COVERED_PREFILTER_MARGIN adds a further buffer on top of that, so this can
    // only skip a block whose real per-point check would also have failed -- a
    // conservative approximation that never changes the result.
    val prefilterDistance = effectiveReach + COVERED_PREFILTER_MARGIN
    val prefilterDistanceSquared = prefilterDistance * prefilterDistance
    return missingWorld.filter { missing ->
        val center = Vec3(missing.x + 0.5, missing.y + 0.5, missing.z + 0.5)
        if (eyePosition.distanceToSqr(center) > prefilterDistanceSquared) {
            false
        } else {
            val envelope = envelopeCache.getOrPut(missing) {
                val expectedState = expectedStateAt(missing) ?: FALLBACK_EXPECTED_STATE
                potentialSupportHitPoints(missing, expectedState)
            }
            envelope.any { point -> eyePosition.distanceToSqr(point) <= reachSquared }
        }
    }.map { position -> position.immutable() }
}

// coverContiguousPrefix's own coverage rule (see planRoute's own doc for why): walks
// `remaining` in its own given order -- starting at its own head, the anchor -- taking
// every cell that is in reach (the exact same prefilter + envelope check coveredMissing
// uses above), not this candidate's own column, and one this candidate can actually stand
// STRICTLY ABOVE (see the y-aware cut below), and STOPS the walk the instant it meets an
// in-order cell that fails either the height test or reach. An own-column cell is skipped
// without stopping the walk -- see excludeOwnColumnFromCoverage's own doc for why it can
// never be this candidate's own coverage, and a later stand's own claim on it must not be
// cut short by a column this candidate merely happens to pass over. isOwnColumn is a
// constant-false predicate whenever the caller's own excludeOwnColumnFromCoverage is off,
// so no cell is ever skipped in that mode.
//
// Y-aware cut: a cell whose own top (y + 1) is at or above this candidate's own feet cell
// can never actually be placed while standing at this candidate (the caller's own
// strictly-above stand-headroom rule -- feet >= highest covered Y + 1 -- rejects the whole
// candidate the instant ANY covered cell violates it), so a plan-order-adjacent cell that
// merely happens to sit close enough in EUCLIDEAN reach must not be folded into the SAME
// prefix as a much lower anchor it can never share a stand with. Without this cut, a
// ground-level footing cell immediately followed (in plan order) by a much higher layer
// cell close enough to be geometrically in reach gets its own candidate's prefix stopped
// only by reach, bundling both heights into one covered set that fails the caller's
// height rule for every one of the 9 candidates alike -- the anchor itself then has no
// winning candidate and is reported uncoverable, even though a stand that covers ONLY the
// low anchor (leaving the higher cell for a later, taller stand) was available the whole
// time. Stopping here instead of skipping (unlike the own-column rule) is deliberate: an
// own-column cell is skipped because THIS candidate can never place it regardless of who
// covers it, but a too-high cell is a genuine plan-order boundary this candidate's own
// reach must not read past -- everything behind it in plan order is, by construction,
// even higher or level (a real schematic layer's own y only rises or holds across one
// footing-then-layer transition), so nothing further into `remaining` could pass the
// height test either.
private fun coveredMissingPrefix(
    target: Vec3,
    remaining: List<BlockPos>,
    reach: Double,
    expectedStateAt: (BlockPos) -> BlockState?,
    envelopeCache: MutableMap<BlockPos, List<Vec3>>,
    isOwnColumn: (BlockPos) -> Boolean,
): List<BlockPos> {
    val effectiveReach = (reach - HIT_COVERAGE_MARGIN).coerceAtLeast(0.0)
    val reachSquared = effectiveReach * effectiveReach
    val eyePosition = Vec3(target.x, target.y + EYE_HEIGHT, target.z)
    val prefilterDistance = effectiveReach + COVERED_PREFILTER_MARGIN
    val prefilterDistanceSquared = prefilterDistance * prefilterDistance
    val feetCellY = floor(target.y).toInt()
    val covered = mutableListOf<BlockPos>()
    for (missing in remaining) {
        if (isOwnColumn(missing)) continue
        if (missing.y + 1 > feetCellY) break
        val center = Vec3(missing.x + 0.5, missing.y + 0.5, missing.z + 0.5)
        val inReach = if (eyePosition.distanceToSqr(center) > prefilterDistanceSquared) {
            false
        } else {
            val envelope = envelopeCache.getOrPut(missing) {
                val expectedState = expectedStateAt(missing) ?: FALLBACK_EXPECTED_STATE
                potentialSupportHitPoints(missing, expectedState)
            }
            envelope.any { point -> eyePosition.distanceToSqr(point) <= reachSquared }
        }
        if (!inReach) break
        covered += missing.immutable()
    }
    return covered
}

private data class CandidateScore(
    val offsetIndex: Int,
    val target: Vec3,
    val covered: List<BlockPos>,
    val ownColumnKey: Long,
)

private const val EYE_HEIGHT: Double = 1.62
// Margin added on top of the hit-envelope distance (was REACH_MARGIN
// = 0.5 under the old block-center model, which needed the extra slack to absorb the
// center-vs-real-hit-point offset that model didn't otherwise account for).
private const val HIT_COVERAGE_MARGIN: Double = 0.25
// Buffer added on top of effectiveReach for coveredMissing's block-center prefilter --
// larger than a hit point's own max ~0.87-block offset from its block's center, so the
// prefilter can only ever be conservative (skip blocks the exact check would also reject).
private const val COVERED_PREFILTER_MARGIN: Double = 1.0
// The mover's +/-0.3 vertical dead zone can sag below an integer target. This
// clearance keeps the player's feet at least 0.1 above the work-layer cell top.
internal const val HOVER_CLEARANCE: Double = 0.4

// Envelope fallback for callers that plan generic geometry without a
// real schematic expectedState (existing LayerRoutePlannerTest cases exercising route
// shape/ordering, not block-specific reach). A plain full block has no HALF/AXIS
// property, so isUsableSupportFace excludes none of its 6 faces -- the closest thing to
// the old model's unconstrained block-center distance while still routing through the
// same potentialSupportHitPoints geometry every other caller uses.
private val FALLBACK_EXPECTED_STATE: BlockState = Blocks.STONE.defaultBlockState()

private val CANDIDATE_OFFSETS: List<BlockPos> = listOf(
    BlockPos(2, 1, 0),
    BlockPos(-2, 1, 0),
    BlockPos(0, 1, 2),
    BlockPos(0, 1, -2),
    BlockPos(0, 3, 0),
    BlockPos(3, 0, 0),
    BlockPos(-3, 0, 0),
    BlockPos(0, 0, 3),
    BlockPos(0, 0, -3),
)

// Layers sweep x ascending; even x columns traverse z ascending and odd columns
// traverse z descending, keeping adjacent columns connected in a deterministic snake.
private val SWEEP_POSITION_ORDER: Comparator<BlockPos> = Comparator { first, second ->
    val layerComparison = first.y.compareTo(second.y)
    if (layerComparison != 0) return@Comparator layerComparison
    val columnComparison = first.x.compareTo(second.x)
    if (columnComparison != 0) return@Comparator columnComparison
    if ((first.x and 1) == 0) first.z.compareTo(second.z) else second.z.compareTo(first.z)
}

private val CANDIDATE_ORDER: Comparator<CandidateScore> =
    compareByDescending<CandidateScore> { candidate -> candidate.covered.size }
        .thenBy { candidate -> candidate.offsetIndex }

// Per-reason tally of why each of a rejected anchor's 9 candidates never became its route
// entry -- see diagnosePlanRouteFirstAnchorRejections' own doc. Each candidate is counted
// under exactly one reason, the first one that would have eliminated it in planRoute's own
// filter chain order (bannedTargets -> isCandidateBanned -> temporal column -> isStandUsable
// -> empty coverage); a candidate that clears every one of those is not tallied at all (it
// would have won, so the anchor was never actually uncoverable).
internal data class AnchorRejectionBreakdown(
    internal val bannedTargetsCount: Int,
    internal val candidateBannedCount: Int,
    internal val temporalColumnCount: Int,
    internal val standUnusableCount: Int,
    internal val emptyCoverageCount: Int,
)

// Diagnostic-only re-evaluation of planRoute's own first anchor (placeableMissing's own
// head, after the same dedup planRoute itself applies), classifying why each of its 9
// candidates was rejected instead of just observing the empty result. Mirrors MoverCore's
// own plan-mode planRoute call exactly (centerCandidateForScoring/
// excludeOwnColumnFromCoverage/coverContiguousPrefix all on) since that is the only
// caller -- never used by planRoute itself, and reading its result can never influence a
// route decision. Returns null only when placeableMissing itself is empty (the caller is
// only ever expected to invoke this once buildPlanRoute already knows its own anchor list
// is non-empty).
internal fun diagnosePlanRouteFirstAnchorRejections(
    placeableMissing: List<BlockPos>,
    reach: Double,
    expectedStateAt: (BlockPos) -> BlockState?,
    bannedTargets: Set<Vec3>,
    isCandidateBanned: (Vec3) -> Boolean,
    isStandUsable: (Vec3, List<BlockPos>) -> Boolean,
): AnchorRejectionBreakdown? {
    val remaining = placeableMissing.map { position -> position.immutable() }.distinct()
    val anchor = remaining.firstOrNull() ?: return null

    val remainingColumnCounts = HashMap<Long, Int>().also { counts ->
        for (position in remaining) {
            val key = columnKey(position.x, position.z)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    val envelopeCache = HashMap<BlockPos, List<Vec3>>()
    var bannedTargetsCount = 0
    var candidateBannedCount = 0
    var temporalColumnCount = 0
    var standUnusableCount = 0
    var emptyCoverageCount = 0
    for (offset in CANDIDATE_OFFSETS) {
        val target = Vec3(
            (anchor.x + offset.x).toDouble(),
            (anchor.y + offset.y).toDouble() + HOVER_CLEARANCE,
            (anchor.z + offset.z).toDouble(),
        )
        if (target in bannedTargets) {
            bannedTargetsCount++
            continue
        }
        if (isCandidateBanned(target)) {
            candidateBannedCount++
            continue
        }
        val ownColumnX = anchor.x + offset.x
        val ownColumnZ = anchor.z + offset.z
        if (remainingColumnCounts.containsKey(columnKey(ownColumnX, ownColumnZ))) {
            temporalColumnCount++
            continue
        }
        val scoringPosition = Vec3(target.x + 0.5, target.y, target.z + 0.5)
        val covered = coveredMissingPrefix(
            target = scoringPosition,
            remaining = remaining,
            reach = reach,
            expectedStateAt = expectedStateAt,
            envelopeCache = envelopeCache,
            isOwnColumn = { position -> position.x == ownColumnX && position.z == ownColumnZ },
        )
        if (!isStandUsable(target, covered)) {
            standUnusableCount++
            continue
        }
        if (covered.isEmpty()) {
            emptyCoverageCount++
        }
    }
    return AnchorRejectionBreakdown(
        bannedTargetsCount = bannedTargetsCount,
        candidateBannedCount = candidateBannedCount,
        temporalColumnCount = temporalColumnCount,
        standUnusableCount = standUnusableCount,
        emptyCoverageCount = emptyCoverageCount,
    )
}
