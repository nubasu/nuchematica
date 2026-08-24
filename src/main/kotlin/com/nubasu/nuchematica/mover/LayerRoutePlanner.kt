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
    expectedStateAt: (BlockPos) -> BlockState? = { null },
    coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3> = ::potentialSupportHitPoints,
    coverageMargin: Double = DEFAULT_HIT_COVERAGE_MARGIN,
    centerCandidateForScoring: Boolean = false,
    scoringPositionForCandidate: ((Vec3) -> Vec3)? = null,
    excludeOwnColumnFromCoverage: Boolean = false,
    rejectPendingTargetColumns: Boolean = true,
    preserveInputOrder: Boolean = false,
    includePlanFallbackCandidates: Boolean = false,
    isCandidateBanned: (Vec3) -> Boolean = { false },
    isStandUsable: (Vec3, List<BlockPos>) -> Boolean = { _, _ -> true },
    coverContiguousPrefix: Boolean = false,
): RoutePlan {
    require(coverageMargin >= 0.0)
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
    val envelopeCache = HashMap<BlockPos, List<Vec3>>()
    val remainingColumnCounts: HashMap<Long, Int>? = if (
        excludeOwnColumnFromCoverage && rejectPendingTargetColumns
    ) {
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
    val candidateOffsets = if (includePlanFallbackCandidates) {
        CANDIDATE_OFFSETS + PLAN_FALLBACK_CANDIDATE_OFFSETS
    } else {
        CANDIDATE_OFFSETS
    }

    while (remaining.isNotEmpty()) {
        val anchor = remaining.first()
        val best = candidateOffsets.mapIndexed { offsetIndex, offset ->
            val target = Vec3(
                (anchor.x + offset.x).toDouble(),
                (anchor.y + offset.y).toDouble() + HOVER_CLEARANCE,
                (anchor.z + offset.z).toDouble(),
            )
            val scoringPosition = scoringPositionForCandidate?.invoke(target) ?: if (centerCandidateForScoring) {
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
                    coverageHitPointsAt = coverageHitPointsAt,
                    coverageMargin = coverageMargin,
                    envelopeCache = envelopeCache,
                    isOwnColumn = if (excludeOwnColumnFromCoverage) isOwnColumn else { _ -> false },
                )
            } else {
                val covered = coveredMissing(
                    scoringPosition,
                    remaining,
                    reach,
                    expectedStateAt,
                    coverageHitPointsAt,
                    coverageMargin,
                    envelopeCache,
                )
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
                remainingColumnCounts != null && remainingColumnCounts.containsKey(candidate.ownColumnKey)
            }
            .filter { candidate -> isStandUsable(candidate.target, candidate.covered) }
            .filter { candidate -> candidate.covered.isNotEmpty() }
            .minWithOrNull(CANDIDATE_ORDER)

        if (best == null) {
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

private fun coveredMissing(
    target: Vec3,
    missingWorld: List<BlockPos>,
    reach: Double,
    expectedStateAt: (BlockPos) -> BlockState?,
    coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3>,
    coverageMargin: Double,
    envelopeCache: MutableMap<BlockPos, List<Vec3>>,
): List<BlockPos> {
    val effectiveReach = (reach - coverageMargin).coerceAtLeast(0.0)
    val reachSquared = effectiveReach * effectiveReach
    val eyePosition = Vec3(target.x, target.y + PLAYER_EYE_HEIGHT, target.z)
    val prefilterDistance = effectiveReach + COVERED_PREFILTER_MARGIN
    val prefilterDistanceSquared = prefilterDistance * prefilterDistance
    return missingWorld.filter { missing ->
        val center = Vec3(missing.x + 0.5, missing.y + 0.5, missing.z + 0.5)
        if (eyePosition.distanceToSqr(center) > prefilterDistanceSquared) {
            false
        } else {
            val envelope = envelopeCache.getOrPut(missing) {
                val expectedState = expectedStateAt(missing) ?: FALLBACK_EXPECTED_STATE
                coverageHitPointsAt(missing, expectedState)
            }
            envelope.any { point -> eyePosition.distanceToSqr(point) <= reachSquared }
        }
    }.map { position -> position.immutable() }
}

private fun coveredMissingPrefix(
    target: Vec3,
    remaining: List<BlockPos>,
    reach: Double,
    expectedStateAt: (BlockPos) -> BlockState?,
    coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3>,
    coverageMargin: Double,
    envelopeCache: MutableMap<BlockPos, List<Vec3>>,
    isOwnColumn: (BlockPos) -> Boolean,
): List<BlockPos> {
    val effectiveReach = (reach - coverageMargin).coerceAtLeast(0.0)
    val reachSquared = effectiveReach * effectiveReach
    val eyePosition = Vec3(target.x, target.y + PLAYER_EYE_HEIGHT, target.z)
    val feetCellY = floor(target.y).toInt()
    val prefilterDistance = effectiveReach + COVERED_PREFILTER_MARGIN
    val prefilterDistanceSquared = prefilterDistance * prefilterDistance
    val covered = mutableListOf<BlockPos>()
    for (missing in remaining) {
        if (isOwnColumn(missing)) continue
        if (missing.y > feetCellY) break
        val center = Vec3(missing.x + 0.5, missing.y + 0.5, missing.z + 0.5)
        val inReach = if (eyePosition.distanceToSqr(center) > prefilterDistanceSquared) {
            false
        } else {
            val envelope = envelopeCache.getOrPut(missing) {
                val expectedState = expectedStateAt(missing) ?: FALLBACK_EXPECTED_STATE
                coverageHitPointsAt(missing, expectedState)
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

// Route coverage and live reach validation must use the same eye height.
internal const val PLAYER_EYE_HEIGHT: Double = 1.62
private const val DEFAULT_HIT_COVERAGE_MARGIN: Double = 0.25
internal const val PLAN_HIT_COVERAGE_MARGIN: Double = 0.500001
private const val COVERED_PREFILTER_MARGIN: Double = 1.0
// This offsets the mover's vertical dead zone above the work layer.
internal const val HOVER_CLEARANCE: Double = 0.4

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

private val PLAN_FALLBACK_CANDIDATE_OFFSETS: List<BlockPos> = listOf(
    BlockPos(2, 0, 0),
    BlockPos(-2, 0, 0),
    BlockPos(0, 0, 2),
    BlockPos(0, 0, -2),
    BlockPos(2, 0, 2),
    BlockPos(2, 0, -2),
    BlockPos(-2, 0, 2),
    BlockPos(-2, 0, -2),
    BlockPos(3, 1, 0),
    BlockPos(-3, 1, 0),
    BlockPos(0, 1, 3),
    BlockPos(0, 1, -3),
    BlockPos(2, 1, 2),
    BlockPos(2, 1, -2),
    BlockPos(-2, 1, 2),
    BlockPos(-2, 1, -2),
    BlockPos(1, 2, 0),
    BlockPos(-1, 2, 0),
    BlockPos(0, 2, 1),
    BlockPos(0, 2, -1),
)

// Alternating z direction keeps adjacent x columns connected.
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

internal data class AnchorRejectionBreakdown(
    internal val bannedTargetsCount: Int,
    internal val candidateBannedCount: Int,
    internal val temporalColumnCount: Int,
    internal val standUnusableCount: Int,
    internal val emptyCoverageCount: Int,
)

internal fun diagnosePlanRouteFirstAnchorRejections(
    placeableMissing: List<BlockPos>,
    reach: Double,
    expectedStateAt: (BlockPos) -> BlockState?,
    coverageHitPointsAt: (BlockPos, BlockState) -> List<Vec3> = ::potentialSupportHitPoints,
    coverageMargin: Double = DEFAULT_HIT_COVERAGE_MARGIN,
    scoringPositionForCandidate: ((Vec3) -> Vec3)? = null,
    rejectPendingTargetColumns: Boolean = true,
    includePlanFallbackCandidates: Boolean = false,
    bannedTargets: Set<Vec3>,
    isCandidateBanned: (Vec3) -> Boolean,
    isStandUsable: (Vec3, List<BlockPos>) -> Boolean,
): AnchorRejectionBreakdown? {
    val remaining = placeableMissing.map { position -> position.immutable() }.distinct()
    val anchor = remaining.firstOrNull() ?: return null

    val remainingColumnCounts = if (rejectPendingTargetColumns) {
        HashMap<Long, Int>().also { counts ->
            for (position in remaining) {
                val key = columnKey(position.x, position.z)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
    } else {
        null
    }
    val envelopeCache = HashMap<BlockPos, List<Vec3>>()
    var bannedTargetsCount = 0
    var candidateBannedCount = 0
    var temporalColumnCount = 0
    var standUnusableCount = 0
    var emptyCoverageCount = 0
    val candidateOffsets = if (includePlanFallbackCandidates) {
        CANDIDATE_OFFSETS + PLAN_FALLBACK_CANDIDATE_OFFSETS
    } else {
        CANDIDATE_OFFSETS
    }
    for (offset in candidateOffsets) {
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
        if (remainingColumnCounts?.containsKey(columnKey(ownColumnX, ownColumnZ)) == true) {
            temporalColumnCount++
            continue
        }
        val scoringPosition = scoringPositionForCandidate?.invoke(target)
            ?: Vec3(target.x + 0.5, target.y, target.z + 0.5)
        val covered = coveredMissingPrefix(
            target = scoringPosition,
            remaining = remaining,
            reach = reach,
            expectedStateAt = expectedStateAt,
            coverageHitPointsAt = coverageHitPointsAt,
            coverageMargin = coverageMargin,
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
