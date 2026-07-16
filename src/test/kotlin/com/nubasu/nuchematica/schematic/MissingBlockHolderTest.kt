package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    public fun equivalentPlacedLeavesAreSatisfied(): Unit {
        val localPos = BlockPos.ZERO
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
            .setValue(BlockStateProperties.DISTANCE, 1)
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to naturalLeaves), emptyMap())
        MissingBlockHolder.airPos += localPos

        val change = MissingBlockHolder.placed(localPos, placedLeaves)

        assertTrue(change?.satisfied == true)
        assertTrue(MissingBlockHolder.airPos.isEmpty())
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

    @Test
    public fun revisionChangesOnlyWhenApplyStatusChangesMissingState(): Unit {
        val localPos = BlockPos.ZERO
        val outsidePos = BlockPos(10, 0, 0)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        val initialRevision = MissingBlockHolder.missingSnapshot().revision

        assertNull(MissingBlockHolder.removed(outsidePos))
        assertEquals(initialRevision, MissingBlockHolder.missingSnapshot().revision)

        MissingBlockHolder.removed(localPos)
        val firstMissing = MissingBlockHolder.missingSnapshot()
        assertEquals(initialRevision + 1L, firstMissing.revision)
        assertEquals(listOf(localPos), firstMissing.missingLocal)

        MissingBlockHolder.removed(localPos)
        assertEquals(firstMissing.revision, MissingBlockHolder.missingSnapshot().revision)

        MissingBlockHolder.placed(localPos, Blocks.DIRT.defaultBlockState())
        val wrongBlock = MissingBlockHolder.missingSnapshot()
        assertEquals(firstMissing.revision + 1L, wrongBlock.revision)

        MissingBlockHolder.placed(localPos, Blocks.DIRT.defaultBlockState())
        assertEquals(wrongBlock.revision, MissingBlockHolder.missingSnapshot().revision)

        MissingBlockHolder.placed(localPos, expected)
        assertEquals(wrongBlock.revision + 1L, MissingBlockHolder.missingSnapshot().revision)
        assertEquals(listOf(localPos), firstMissing.missingLocal)
        assertThrows(UnsupportedOperationException::class.java) {
            (firstMissing.missingLocal as MutableList<BlockPos>).add(outsidePos)
        }
    }

    @Test
    public fun initializeIncrementsRevisionOnlyWhenScanResultChanges(): Unit {
        val localPos = BlockPos.ZERO
        val expected = Blocks.STONE.defaultBlockState()
        var actualState = Blocks.AIR.defaultBlockState()
        val level = mockk<ClientLevel>()
        every { level.getBlockState(any()) } answers { actualState }
        val minecraft = mockk<Minecraft>(relaxed = true)
        val instanceField = Minecraft::class.java.getDeclaredField("instance")
        val levelField = Minecraft::class.java.getField("level")
        instanceField.isAccessible = true
        val previousMinecraft = instanceField.get(null)
        levelField.set(minecraft, level)
        instanceField.set(null, minecraft)
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        val initialRevision = MissingBlockHolder.missingSnapshot().revision

        try {
            MissingBlockHolder.initialize()
            val firstScan = MissingBlockHolder.missingSnapshot()
            assertEquals(initialRevision + 1L, firstScan.revision)
            assertEquals(listOf(localPos), firstScan.missingLocal)

            MissingBlockHolder.initialize()
            assertEquals(firstScan.revision, MissingBlockHolder.missingSnapshot().revision)

            actualState = expected
            MissingBlockHolder.initialize()
            val satisfiedScan = MissingBlockHolder.missingSnapshot()
            assertEquals(firstScan.revision + 1L, satisfiedScan.revision)
            assertTrue(satisfiedScan.missingLocal.isEmpty())
        } finally {
            instanceField.set(null, previousMinecraft)
        }
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
