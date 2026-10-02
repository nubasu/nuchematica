package com.nubasu.nuchematica.mover

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** One straight leg of a fixed-altitude serpentine sweep. */
internal data class LaneSegment(
    internal val start: Vec3,
    internal val end: Vec3,
)

internal data class LanePlan(
    internal val segments: List<LaneSegment>,
)

internal const val LANE_PITCH: Int = 4

/**
 * Plans alternating Z-axis lanes near placeable cells on [gateY].
 *
 * Empty stretches are omitted instead of extending every lane across the full layer.
 */
internal fun planLanes(
    placeableMissing: List<BlockPos>,
    gateY: Int,
    lanePitch: Int = LANE_PITCH,
): LanePlan {
    require(lanePitch > 0)
    if (placeableMissing.isEmpty()) return LanePlan(emptyList())

    val minX = placeableMissing.minOf { position -> position.x }
    val maxX = placeableMissing.maxOf { position -> position.x }
    val radius = lanePitch / 2 + 1
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
