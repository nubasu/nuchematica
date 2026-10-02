package com.nubasu.nuchematica.renderer.section

import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.SchematicEditor
import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class RenderTransformTest {

    @Test
    public fun pointAndBlockRoundTripsCoverAllQuarterTurns(): Unit {
        val localPoint = Vec3(-3.25, 7.5, 11.75)
        val localBlocks = listOf(
            BlockPos(-17, -1, 31),
            BlockPos.ZERO,
            BlockPos(4, 9, 7),
        )

        for (rotation in ROTATIONS) {
            val transform = RenderTransform(
                renderBase = Vec3(20.0, -8.0, 40.0),
                rotateDeg = rotation,
                rotateAxis = Vec3(-8.0, 0.0, -5.0),
                revision = 1L,
            )
            assertVecEquals(localPoint, transform.worldPointToLocal(transform.localPointToWorld(localPoint)))
            for (localBlock in localBlocks) {
                assertEquals(
                    localBlock,
                    transform.worldBlockToLocal(transform.localBlockToWorld(localBlock)),
                    "rotation=$rotation local=$localBlock",
                )
            }
        }
    }

    @Test
    public fun sectionAabbTransformsAllEightCorners(): Unit {
        val transform = RenderTransform(
            renderBase = Vec3(100.0, 20.0, -30.0),
            rotateDeg = 90f,
            rotateAxis = Vec3.ZERO,
            revision = 2L,
        )

        assertEquals(
            AABB(116.0, 20.0, -30.0, 132.0, 36.0, -14.0),
            transform.sectionWorldAabb(SectionKey(-1, 0, 1)),
        )
    }

    @Test
    public fun blockCenterMappingSelectsRotatedCubeMinimumCell(): Unit {
        val transform = RenderTransform(Vec3.ZERO, 90f, Vec3.ZERO, 3L)

        assertEquals(BlockPos(0, 0, -1), transform.localBlockToWorld(BlockPos.ZERO))
        assertEquals(BlockPos.ZERO, transform.worldBlockToLocal(BlockPos(0, 0, -1)))
    }

    @Test
    public fun discreteCellsMatchCurrentManagerForEveryFacingAndRotation(): Unit {
        val previousSize = SchematicHolder.schematicSize
        val previousDirection = SchematicRenderManager.initialDirection
        val previousPosition = SchematicRenderManager.initialPosition
        val schematicSize = Vector3(4, 2, 7)
        val localBlocks = listOf(
            BlockPos.ZERO,
            BlockPos(4, 0, 0),
            BlockPos(0, 2, 7),
            BlockPos(3, 1, 5),
        )

        try {
            SchematicHolder.schematicSize = schematicSize
            SchematicRenderManager.initialPosition = Vec3.ZERO
            SchematicRenderManager.setOffset(Vec3.ZERO)
            for (direction in HORIZONTAL_DIRECTIONS) {
                SchematicRenderManager.initialDirection = direction
                for (setting in DirectionSetting.values()) {
                    SchematicEditor.rotate(setting)
                    val transform = RenderTransform(
                        renderBase = Vec3.ZERO,
                        rotateDeg = setting.degrees,
                        rotateAxis = rotationAxis(direction, setting, schematicSize),
                        revision = 4L,
                    )
                    for (local in localBlocks) {
                        val managerWorld = SchematicRenderManager.localBlockToWorld(local)
                        assertEquals(
                            managerWorld,
                            transform.localBlockToWorld(local),
                            "direction=$direction rotation=$setting local=$local",
                        )
                        assertEquals(
                            local,
                            transform.worldBlockToLocal(managerWorld),
                            "inverse direction=$direction rotation=$setting world=$managerWorld",
                        )
                        assertEquals(local, SchematicRenderManager.worldBlockToLocal(managerWorld))
                    }
                }
            }
        } finally {
            SchematicHolder.schematicSize = previousSize
            SchematicRenderManager.initialDirection = previousDirection
            SchematicRenderManager.initialPosition = previousPosition
            SchematicRenderManager.setOffset(Vec3.ZERO)
            SchematicRenderManager.setRotation(0f, Vec3.ZERO)
        }
    }

    private fun assertVecEquals(expected: Vec3, actual: Vec3): Unit {
        assertEquals(expected.x, actual.x, EPSILON, "x")
        assertEquals(expected.y, actual.y, EPSILON, "y")
        assertEquals(expected.z, actual.z, EPSILON, "z")
    }

    private fun rotationAxis(
        direction: Direction,
        rotation: DirectionSetting,
        schematicSize: Vector3,
    ): Vec3 {
        val sizeX = schematicSize.x + 1.0
        val sizeZ = schematicSize.z + 1.0
        return when (direction) {
            Direction.EAST -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeX, 0.0, 0.0)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -sizeZ)
            }
            Direction.SOUTH -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeX, 0.0, sizeX - sizeZ)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -sizeZ - (sizeX - sizeZ))
            }
            Direction.WEST -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeZ, 0.0, sizeX - sizeZ)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(sizeZ - sizeX, 0.0, -sizeZ - (sizeX - sizeZ))
            }
            Direction.NORTH -> when (rotation) {
                DirectionSetting.CLOCKWISE_0 -> Vec3.ZERO
                DirectionSetting.CLOCKWISE_90 -> Vec3(-sizeZ, 0.0, 0.0)
                DirectionSetting.CLOCKWISE_180 -> Vec3(-sizeX, 0.0, -sizeZ)
                DirectionSetting.CLOCKWISE_270 -> Vec3(sizeZ - sizeX, 0.0, -sizeZ)
            }
            else -> error("unsupported test direction: $direction")
        }
    }

    private val DirectionSetting.degrees: Float
        get() = when (this) {
            DirectionSetting.CLOCKWISE_0 -> 0f
            DirectionSetting.CLOCKWISE_90 -> 90f
            DirectionSetting.CLOCKWISE_180 -> 180f
            DirectionSetting.CLOCKWISE_270 -> 270f
        }

    private companion object {
        private const val EPSILON = 1.0e-9
        private val ROTATIONS = floatArrayOf(0f, 90f, 180f, 270f)
        private val HORIZONTAL_DIRECTIONS = arrayOf(
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST,
            Direction.NORTH,
        )
    }
}
