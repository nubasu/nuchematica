package com.nubasu.nuchematica.renderer

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Bounds the ghost mesh's working set to a horizontal radius around the camera. */
internal object GhostRenderDistance {
    internal const val MAX_CHUNKS: Int = 8

    /** Horizontal radius in blocks: the world render distance, capped so dense schematics stay bounded. */
    internal fun blocks(renderDistanceChunks: Int): Int =
        minOf(renderDistanceChunks, MAX_CHUNKS).coerceAtLeast(1) * 16

    /**
     * Reads the client option; safe to call every frame on the render thread.
     * Falls back to the widest allowed radius if no client instance is available yet.
     */
    internal fun currentBlocks(): Int {
        val options = Minecraft.getInstance()?.options ?: return blocks(MAX_CHUNKS)
        return blocks(options.renderDistance)
    }

    /** True when [box] comes within [radiusBlocks] of [camera] measured on the XZ plane only. */
    internal fun withinHorizontal(camera: Vec3, box: AABB, radiusBlocks: Int): Boolean {
        val dx = axisDistance(camera.x, box.minX, box.maxX)
        val dz = axisDistance(camera.z, box.minZ, box.maxZ)
        val radius = radiusBlocks.toDouble()
        return dx * dx + dz * dz <= radius * radius
    }

    private fun axisDistance(value: Double, minimum: Double, maximum: Double): Double {
        return when {
            value < minimum -> minimum - value
            value > maximum -> value - maximum
            else -> 0.0
        }
    }
}
