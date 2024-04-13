package com.nubasu.nuchematica.common

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

public class Vector3 {
    public var x: Int = 0
    public var y: Int = 0
    public var z: Int = 0

    public constructor(x: Int = 0, y: Int = 0, z: Int = 0) {
        this.x = x
        this.y = y
        this.z = z
    }

    public constructor(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) {
        this.x = x.toInt()
        this.y = y.toInt()
        this.z = z.toInt()
    }

    public operator fun plus(v2: Vector3): Vector3 {
        return Vector3(this.x + v2.x, this.y + v2.y, this.z + v2.z)
    }

    public operator fun minus(v2: Vector3): Vector3 {
        return Vector3(this.x - v2.x, this.y - v2.y, this.z - v2.z)
    }

    public fun getMinimum(v2: Vector3): Vector3 {
        return Vector3(
            min(x, v2.x),
            min(y, v2.y),
            min(z, v2.z)
        )
    }

    /**
     * Gets the maximum components of two vectors.
     *
     * @param v2 the second vector
     * @return maximum
     */
    public fun getMaximum(v2: Vector3): Vector3 {
        return Vector3(
            max(x, v2.x),
            max(y, v2.y),
            max(z, v2.z)
        )
    }

    /**
     * Get this vector's pitch as used within the game.
     *
     * @return pitch in radians
     */
    fun toPitch(): Double {
        val x: Double = x.toDouble()
        val z: Double = z.toDouble()
        return if (x == 0.0 && z == 0.0) {
            if (y.toDouble() > 0) -90.0 else 90.0
        } else {
            val x2 = x * x
            val z2 = z * z
            val xz = Math.sqrt(x2 + z2)
            Math.toDegrees(Math.atan(-y.toDouble() / xz))
        }
    }

    /**
     * Get this vector's yaw as used within the game.
     *
     * @return yaw in radians
     */
    fun toYaw(): Double {
        val x: Double = x.toDouble()
        val z: Double = z.toDouble()
        val t = Math.atan2(-x, z)
        val tau = 2 * Math.PI
        return Math.toDegrees((t + tau) % tau)
    }

    /**
     * Set the X coordinate.
     *
     * @param x the new X
     * @return a new vector
     */
    fun withX(x: Double): Vector3 {
        return Vector3(x, y.toDouble(), z.toDouble())
    }
    /**
     * Set the Y coordinate.
     *
     * @param y the new Y
     * @return a new vector
     */
    fun withY(y: Double): Vector3 {
        return Vector3(x.toDouble(), y, z.toDouble())
    }

    /**
     * Set the Z coordinate.
     *
     * @param z the new Z
     * @return a new vector
     */
    fun withZ(z: Double): Vector3 {
        return Vector3(x.toDouble(), y.toDouble(), z)
    }

    public fun lengthSq(): Double {
        return (x * x + y * y + z * z).toDouble()
    }

    fun length(): Double {
        return sqrt(lengthSq())
    }

    fun normalize(): Vector3 {
        return divide(length())
    }

    fun divide(n: Double): Vector3 {
        return divide(n, n, n)
    }

    fun dot(other: Vector3): Int {
        return x * other.x + y * other.y + z * other.z
    }

    public fun divide(x: Double, y: Double, z: Double): Vector3 {
        return Vector3(this.x / x, this.y / y, this.z / z)
    }

    public companion object {
        public val ONE: Vector3 = Vector3(1, 1, 1)
        public val ZERO: Vector3 = Vector3(0, 0, 0)
    }
}