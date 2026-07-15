package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

public class MissingBlockHolderTest {
    private lateinit var previousRenderingBlocks: SchematicCache
    private lateinit var previousSchematicSize: Vector3
    private lateinit var previousDirection: Direction
    private lateinit var previousPosition: Vec3

    @BeforeEach
    public fun setUp(): Unit {
        previousRenderingBlocks = SchematicHolder.renderingBlocks
        previousSchematicSize = SchematicHolder.schematicSize
        previousDirection = SchematicRenderManager.initialDirection
        previousPosition = SchematicRenderManager.initialPosition
        MissingBlockHolder.airPos.clear()
        MissingBlockHolder.blockPos.clear()
        SchematicHolder.schematicSize = Vector3.ZERO
        SchematicRenderManager.initialDirection = Direction.EAST
        SchematicRenderManager.initialPosition = Vec3.ZERO
        SchematicRenderManager.setOffset(Vec3.ZERO)
        SchematicRenderManager.setRotation(0f, Vec3.ZERO)
    }

    @AfterEach
    public fun tearDown(): Unit {
        MissingBlockHolder.airPos.clear()
        MissingBlockHolder.blockPos.clear()
        SchematicHolder.renderingBlocks = previousRenderingBlocks
        SchematicHolder.schematicSize = previousSchematicSize
        SchematicRenderManager.initialDirection = previousDirection
        SchematicRenderManager.initialPosition = previousPosition
        SchematicRenderManager.setOffset(Vec3.ZERO)
        SchematicRenderManager.setRotation(0f, Vec3.ZERO)
    }

    @Test
    public fun placedAndRemovedReportEveryMissingSatisfiedAndWrongTransition(): Unit {
        val localPos = BlockPos(2, 3, 4)
        val outsidePos = BlockPos(99, 3, 4)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        MissingBlockHolder.airPos += localPos

        val missingToSatisfied = MissingBlockHolder.placed(localPos, expected)
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = true, satisfied = true),
            missingToSatisfied,
        )
        assertTrue(MissingBlockHolder.airPos.isEmpty())
        assertTrue(MissingBlockHolder.blockPos.isEmpty())

        val satisfiedToMissing = MissingBlockHolder.removed(localPos)
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = true, satisfied = false),
            satisfiedToMissing,
        )
        assertEquals(listOf(localPos), MissingBlockHolder.airPos)

        val wrongPlacement = MissingBlockHolder.placed(localPos, Blocks.DIRT.defaultBlockState())
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = false, satisfied = false),
            wrongPlacement,
        )
        assertTrue(MissingBlockHolder.airPos.isEmpty())
        assertEquals(listOf(localPos), MissingBlockHolder.blockPos)

        val wrongBreak = MissingBlockHolder.removed(localPos)
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = false, satisfied = false),
            wrongBreak,
        )
        assertEquals(listOf(localPos), MissingBlockHolder.airPos)
        assertTrue(MissingBlockHolder.blockPos.isEmpty())

        assertNull(MissingBlockHolder.placed(outsidePos, expected))
        assertNull(MissingBlockHolder.removed(outsidePos))
        assertEquals(listOf(localPos), MissingBlockHolder.airPos)
        assertTrue(MissingBlockHolder.blockPos.isEmpty())
    }

    @Test
    public fun repeatedWrongPlacementIsANoopAndSatisfiedSnapshotIsIndependent(): Unit {
        val satisfiedPos = BlockPos.ZERO
        val wrongPos = BlockPos(1, 0, 0)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(
            mapOf(satisfiedPos to expected, wrongPos to expected),
            emptyMap(),
        )
        MissingBlockHolder.blockPos += wrongPos

        val snapshot = MissingBlockHolder.satisfiedPositions()
        val unchanged = MissingBlockHolder.placed(wrongPos, Blocks.DIRT.defaultBlockState())

        assertEquals(setOf(satisfiedPos), snapshot)
        assertEquals(
            MissingBlockChange(wrongPos, overlayChanged = false, satisfiedChanged = false, satisfied = false),
            unchanged,
        )
        assertFalse(wrongPos in snapshot)

        MissingBlockHolder.removed(satisfiedPos)
        assertEquals(setOf(satisfiedPos), snapshot)
        assertTrue(MissingBlockHolder.satisfiedPositions().isEmpty())
    }

    @Test
    public fun rotatedOffsetWorldCoordinateMatchesTheFormerManualMapping(): Unit {
        val localPos = BlockPos(2, 1, 3)
        val expectedWorldPos = BlockPos(15, 20, 36)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.schematicSize = Vector3(4, 2, 7)
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        SchematicRenderManager.initialDirection = Direction.EAST
        SchematicRenderManager.initialPosition = Vec3(10.0, 20.0, 30.0)
        SchematicRenderManager.setOffset(Vec3(2.0, -1.0, 4.0))
        SchematicRenderManager.setRotation(DirectionSetting.CLOCKWISE_90)
        MissingBlockHolder.airPos += localPos

        assertEquals(expectedWorldPos, SchematicRenderManager.localBlockToWorld(localPos))
        assertEquals(localPos, SchematicRenderManager.worldBlockToLocal(expectedWorldPos))
        assertEquals(localPos, MissingBlockHolder.placed(expectedWorldPos, expected)?.localPos)
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
