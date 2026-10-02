package com.nubasu.nuchematica.mover

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import kotlin.math.abs

private const val LOCAL_ESCAPE_RADIUS: Int = 5

private val ESCAPE_NEIGHBOR_OFFSETS: List<BlockPos> = listOf(
    BlockPos(1, 0, 0),
    BlockPos(-1, 0, 0),
    BlockPos(0, 0, 1),
    BlockPos(0, 0, -1),
    BlockPos(0, 1, 0),
    BlockPos(0, -1, 0),
)

/**
 * Checks for a bounded collision-aware escape route from the player's current footprint.
 *
 * The supplied world view may overlay imminent placements and in-flight reservations.
 */
internal fun playerHasLocalEscapeRoute(
    playerFeetPos: Vec3,
    stateAt: (BlockPos) -> BlockState,
): Boolean {
    val origin = floorCell(playerFeetPos)
    val radius = LOCAL_ESCAPE_RADIUS
    val bounds = AABB(
        (origin.x - radius).toDouble(),
        (origin.y - radius).toDouble(),
        (origin.z - radius).toDouble(),
        (origin.x + radius + 1).toDouble(),
        (origin.y + radius + 1).toDouble(),
        (origin.z + radius + 1).toDouble(),
    )
    val profile = FlightPassabilityProfile.collisionAware(bounds, stateAt)
    val isEnterable: (BlockPos) -> Boolean = { cell -> profile.feetHeightAt(cell) != null }
    val start = resolveEnterableCell(
        playerFeetPos,
        PLAYER_HALF_WIDTH,
        isEnterable,
    ) { cell ->
        val feetY = profile.feetHeightAt(cell) ?: return@resolveEnterableCell false
        sweptVolumeCollisionFree(
            playerFeetPos,
            Vec3(cell.x + 0.5, feetY, cell.z + 0.5),
            stateAt,
        )
    } ?: return false

    val open = ArrayDeque<BlockPos>()
    val visited = HashSet<BlockPos>()
    open.add(start)
    visited.add(start)
    while (open.isNotEmpty()) {
        val current = open.removeFirst()
        if (
            abs(current.x - origin.x) == radius ||
            abs(current.y - origin.y) == radius ||
            abs(current.z - origin.z) == radius
        ) {
            return true
        }
        val currentFeetY = profile.feetHeightAt(current) ?: continue
        for (offset in ESCAPE_NEIGHBOR_OFFSETS) {
            val neighbor = current.offset(offset.x, offset.y, offset.z)
            if (neighbor in visited) continue
            val neighborFeetY = profile.feetHeightAt(neighbor) ?: continue
            if (!profile.transitionClear(current, currentFeetY, neighbor, neighborFeetY)) continue
            visited.add(neighbor)
            open.add(neighbor)
        }
    }
    return false
}
