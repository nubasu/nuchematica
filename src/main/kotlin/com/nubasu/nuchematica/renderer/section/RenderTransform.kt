package com.nubasu.nuchematica.renderer.section

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal data class RenderTransform(
    internal val renderBase: Vec3,
    internal val rotateDeg: Float,
    internal val rotateAxis: Vec3,
    internal val revision: Long,
) {
    private val quarterTurns: Int = normalizeQuarterTurns(rotateDeg)

    init {
        require(isBlockAligned(renderBase)) { "renderBase must be aligned to block boundaries" }
        require(isBlockAligned(rotateAxis)) { "rotateAxis must be aligned to block boundaries" }
    }

    internal fun localPointToWorld(pos: Vec3): Vec3 {
        val shiftedX = pos.x + rotateAxis.x
        val shiftedY = pos.y + rotateAxis.y
        val shiftedZ = pos.z + rotateAxis.z
        return when (quarterTurns) {
            0 -> Vec3(renderBase.x + shiftedX, renderBase.y + shiftedY, renderBase.z + shiftedZ)
            1 -> Vec3(renderBase.x + shiftedZ, renderBase.y + shiftedY, renderBase.z - shiftedX)
            2 -> Vec3(renderBase.x - shiftedX, renderBase.y + shiftedY, renderBase.z - shiftedZ)
            3 -> Vec3(renderBase.x - shiftedZ, renderBase.y + shiftedY, renderBase.z + shiftedX)
            else -> error("unreachable quarter turn")
        }
    }

    internal fun worldPointToLocal(pos: Vec3): Vec3 {
        val relativeX = pos.x - renderBase.x
        val relativeY = pos.y - renderBase.y
        val relativeZ = pos.z - renderBase.z
        return when (quarterTurns) {
            0 -> Vec3(
                relativeX - rotateAxis.x,
                relativeY - rotateAxis.y,
                relativeZ - rotateAxis.z,
            )
            1 -> Vec3(
                -relativeZ - rotateAxis.x,
                relativeY - rotateAxis.y,
                relativeX - rotateAxis.z,
            )
            2 -> Vec3(
                -relativeX - rotateAxis.x,
                relativeY - rotateAxis.y,
                -relativeZ - rotateAxis.z,
            )
            3 -> Vec3(
                relativeZ - rotateAxis.x,
                relativeY - rotateAxis.y,
                -relativeX - rotateAxis.z,
            )
            else -> error("unreachable quarter turn")
        }
    }

    internal fun localBlockToWorld(pos: BlockPos): BlockPos {
        return blockCellContaining(localPointToWorld(Vec3.atCenterOf(pos)))
    }

    internal fun worldBlockToLocal(pos: BlockPos): BlockPos {
        return blockCellContaining(worldPointToLocal(Vec3.atCenterOf(pos)))
    }

    internal fun sectionWorldAabb(key: SectionKey): AABB {
        val localMin = key.minBlock()
        val localMax = key.maxExclusiveBlock()
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        for (x in intArrayOf(localMin.x, localMax.x)) {
            for (y in intArrayOf(localMin.y, localMax.y)) {
                for (z in intArrayOf(localMin.z, localMax.z)) {
                    val world = localPointToWorld(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
                    minX = min(minX, world.x)
                    minY = min(minY, world.y)
                    minZ = min(minZ, world.z)
                    maxX = max(maxX, world.x)
                    maxY = max(maxY, world.y)
                    maxZ = max(maxZ, world.z)
                }
            }
        }

        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun blockCellContaining(point: Vec3): BlockPos {
        return BlockPos(
            floor(point.x).toInt(),
            floor(point.y).toInt(),
            floor(point.z).toInt(),
        )
    }

    private companion object {
        private const val QUARTER_TURN_COUNT = 4
        private const val DEGREES_PER_QUARTER_TURN = 90f
        private const val ROTATION_EPSILON = 0.0001f

        private fun normalizeQuarterTurns(degrees: Float): Int {
            val turns = round(degrees / DEGREES_PER_QUARTER_TURN).toInt()
            require(abs(degrees - turns * DEGREES_PER_QUARTER_TURN) <= ROTATION_EPSILON) {
                "rotateDeg must be a multiple of 90 degrees"
            }
            return Math.floorMod(turns, QUARTER_TURN_COUNT)
        }

        private fun isBlockAligned(pos: Vec3): Boolean {
            return isBlockAligned(pos.x) && isBlockAligned(pos.y) && isBlockAligned(pos.z)
        }

        private fun isBlockAligned(value: Double): Boolean {
            return value.isFinite() && floor(value) == value
        }
    }
}
