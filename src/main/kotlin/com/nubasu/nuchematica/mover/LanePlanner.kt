package com.nubasu.nuchematica.mover

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

// One straight horizontal leg of a serpentine lane sweep, at the layer's fixed
// cruise altitude. The mover flies start -> end in a straight line (probed and
// A*-recovered exactly like any other leg); moving from one segment's end to the next
// segment's start -- whether that next segment continues the same lane across a
// skipped empty stretch, or begins the next lane over -- is itself just another such
// leg, which is the "junction waypoint" a lane plan needs. No separate waypoint
// type is needed.
internal data class LaneSegment(
    internal val start: Vec3,
    internal val end: Vec3,
)

internal data class LanePlan(
    internal val segments: List<LaneSegment>,
)

internal const val LANE_PITCH: Int = 4

// By the bottom-up invariant the airspace above the gate layer is empty (aside from
// pre-existing terrain/trees), so sweeping fixed serpentine lanes at a constant altitude
// covers the whole layer without the vertical zigzag a per-position work-position route
// produces by construction. Lanes run along Z at X intervals of lanePitch, alternating
// sweep direction per lane index (serpentine); each lane is clipped to the stretch(es)
// that actually have placeable positions nearby, so empty stretches are skipped rather
// than flown in full.
internal fun planLanes(
    placeableMissing: List<BlockPos>,
    gateY: Int,
    lanePitch: Int = LANE_PITCH,
): LanePlan {
    require(lanePitch > 0)
    if (placeableMissing.isEmpty()) return LanePlan(emptyList())

    val minX = placeableMissing.minOf { position -> position.x }
    val maxX = placeableMissing.maxOf { position -> position.x }
    // Horizontal distance threshold for lane membership/clipping (LANE_PITCH/2 + 1):
    // treated as the perpendicular distance from a position to the lane's Z-aligned
    // line (i.e. |dx|), which decides both which lane a position is relevant to and,
    // via clusterByGap below, how far apart two positions can be along Z and still
    // share one covered segment.
    val radius = lanePitch / 2 + 1
    // Feet altitude gateY + 2, an INTEGER -- not gateY + 1 + the discrete planner's 0.4
    // hover clearance -- restoring the exact 2-cell clearance the A*/pathProbe geometry
    // expects.
    val feetY = (gateY + 2).toDouble()

    val segments = mutableListOf<LaneSegment>()
    var laneX = minX
    var laneIndex = 0
    while (laneX <= maxX) {
        val relevant = placeableMissing.filter { position -> abs(position.x - laneX) <= radius }
        if (relevant.isNotEmpty()) {
            val ascending = laneIndex % 2 == 0
            val clusters = clusterByGap(
                values = relevant.map { position -> position.z }.distinct().sorted(),
                maxGap = 2 * radius,
            )
            val orderedClusters = if (ascending) clusters else clusters.asReversed()
            for (cluster in orderedClusters) {
                val fromZ = if (ascending) cluster.first() else cluster.last()
                val toZ = if (ascending) cluster.last() else cluster.first()
                segments += LaneSegment(
                    start = Vec3(laneX + 0.5, feetY, fromZ + 0.5),
                    end = Vec3(laneX + 0.5, feetY, toZ + 0.5),
                )
            }
        }
        laneX += lanePitch
        laneIndex++
    }
    return LanePlan(segments)
}

// Groups sorted distinct values into contiguous runs, splitting only where two
// consecutive values are farther apart than maxGap. Each value's coverage extends
// radius in either direction, so two values bridge into one segment exactly when their
// circles touch or overlap (gap <= 2*radius); a wider gap is a genuine uncovered
// stretch and becomes a lane-internal junction between two segments.
private fun clusterByGap(values: List<Int>, maxGap: Int): List<List<Int>> {
    val clusters = mutableListOf<MutableList<Int>>()
    for (value in values) {
        val current = clusters.lastOrNull()
        if (current != null && value - current.last() <= maxGap) {
            current.add(value)
        } else {
            clusters.add(mutableListOf(value))
        }
    }
    return clusters
}
