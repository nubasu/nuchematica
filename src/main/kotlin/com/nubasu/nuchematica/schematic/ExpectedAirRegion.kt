package com.nubasu.nuchematica.schematic

import net.minecraft.core.BlockPos

/** Inclusive local-coordinate box in which a position without a schematic block is expected to be air. */
public data class ExpectedAirRegion(
    public val minX: Int, public val minY: Int, public val minZ: Int,
    public val maxX: Int, public val maxY: Int, public val maxZ: Int,
) {
    public fun contains(pos: BlockPos): Boolean {
        return pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
    }

    /** Count of positions inside the box. */
    public val volume: Long
        get() = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong() * (maxZ - minZ + 1).toLong()

    /** Position at [ordinal] in an enumeration ordered x fastest, then z, then y. */
    public fun positionAt(ordinal: Long): BlockPos {
        val sizeX = (maxX - minX + 1).toLong()
        val sizeZ = (maxZ - minZ + 1).toLong()
        val x = ordinal % sizeX
        val remainder = ordinal / sizeX
        val z = remainder % sizeZ
        val y = remainder / sizeZ
        return BlockPos(minX + x.toInt(), minY + y.toInt(), minZ + z.toInt())
    }

    public companion object {
        /** Bounding box of [positions] clamped to [yMin], [yMax]; null when empty. */
        public fun of(positions: Collection<BlockPos>, yMin: Int = Int.MIN_VALUE, yMax: Int = Int.MAX_VALUE): ExpectedAirRegion? {
            if (positions.isEmpty()) return null
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var maxZ = Int.MIN_VALUE
            for (pos in positions) {
                if (pos.x < minX) minX = pos.x
                if (pos.y < minY) minY = pos.y
                if (pos.z < minZ) minZ = pos.z
                if (pos.x > maxX) maxX = pos.x
                if (pos.y > maxY) maxY = pos.y
                if (pos.z > maxZ) maxZ = pos.z
            }
            val clampedMinY = maxOf(minY, yMin)
            val clampedMaxY = minOf(maxY, yMax)
            if (clampedMinY > clampedMaxY) return null
            return ExpectedAirRegion(minX, clampedMinY, minZ, maxX, clampedMaxY, maxZ)
        }
    }
}
