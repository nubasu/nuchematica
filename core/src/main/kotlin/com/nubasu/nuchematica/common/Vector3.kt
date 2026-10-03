package com.nubasu.nuchematica.common

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
public data class Vector3(
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0,
) {
    public constructor(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) : this(x.toInt(), y.toInt(), z.toInt())

    public fun getMinimum(v2: Vector3): Vector3 {
        return Vector3(
            min(x, v2.x),
            min(y, v2.y),
            min(z, v2.z)
        )
    }

    public fun getMaximum(v2: Vector3): Vector3 {
        return Vector3(
            max(x, v2.x),
            max(y, v2.y),
            max(z, v2.z)
        )
    }

    public companion object {
        public val ONE: Vector3 = Vector3(1, 1, 1)
        public val ZERO: Vector3 = Vector3(0, 0, 0)
    }
}