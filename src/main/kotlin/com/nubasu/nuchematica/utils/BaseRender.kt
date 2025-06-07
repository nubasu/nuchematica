package com.nubasu.nuchematica.utils

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Vector3f
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

public object BaseRender {
    public fun drawVisibleFacesCubeWithBuffer(
        builder: BufferBuilder,
        poseStack: PoseStack,
        pos: Vec3,
        color: Vector3f,
        visibleFaces: Set<Direction>
    ) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        val r = color.x()
        val g = color.y()
        val b = color.z()
        val a = 0.2f // 半透明

        val matrix = poseStack.last().pose()

        fun vertex(x: Double, y: Double, z: Double) {
            builder.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
                .color(r, g, b, a)
                .endVertex()
        }

        if (Direction.DOWN in visibleFaces) {
            vertex(x,     y,     z)
            vertex(x,     y,     z + 1)
            vertex(x + 1, y,     z + 1)
            vertex(x + 1, y,     z)
        }
        if (Direction.UP in visibleFaces) {
            vertex(x,     y + 1, z)
            vertex(x + 1, y + 1, z)
            vertex(x + 1, y + 1, z + 1)
            vertex(x,     y + 1, z + 1)
        }
        if (Direction.NORTH in visibleFaces) {
            vertex(x,     y,     z)
            vertex(x + 1, y,     z)
            vertex(x + 1, y + 1, z)
            vertex(x,     y + 1, z)
        }
        if (Direction.SOUTH in visibleFaces) {
            vertex(x,     y,     z + 1)
            vertex(x,     y + 1, z + 1)
            vertex(x + 1, y + 1, z + 1)
            vertex(x + 1, y,     z + 1)
        }
        if (Direction.WEST in visibleFaces) {
            vertex(x,     y,     z)
            vertex(x,     y + 1, z)
            vertex(x,     y + 1, z + 1)
            vertex(x,     y,     z + 1)
        }
        if (Direction.EAST in visibleFaces) {
            vertex(x + 1, y,     z)
            vertex(x + 1, y,     z + 1)
            vertex(x + 1, y + 1, z + 1)
            vertex(x + 1, y + 1, z)
        }
    }
}