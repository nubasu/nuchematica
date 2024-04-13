package com.nubasu.nuchematica.original

import com.nubasu.nuchematica.original.Vector3
import net.minecraft.world.entity.Entity
import kotlin.math.abs

public class SelectedRegion(public var pos1: Vector3 = Vector3.ZERO, public var pos2: Vector3 = Vector3.ZERO) {
    public val minimumPoint: Vector3
        get() = pos1.getMinimum(pos2)
    public val maximumPoint: Vector3
        get() = pos1.getMaximum(pos2)

    public val width: Int
        get() {
            return abs(pos1.x - pos2.x)
        }

    public val height: Int
        get() {
            return abs(pos1.y - pos2.y)
        }

    public val length: Int
        get() {
            return abs(pos1.z - pos2.z)
        }
}