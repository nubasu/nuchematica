package com.nubasu.nuchematica.printer

import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PlacementResolverTest {
    private val defaultSettings: PlacementBehaviorSettings =
        PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)

    @Test
    public fun supportedReplaceableTargetReturnsResolvedWithMatchingHit(): Unit {
        val worldPos = BlockPos(0, 1, 0)
        val expected = Blocks.STONE.defaultBlockState()
        val states = mapOf(worldPos.below() to Blocks.STONE.defaultBlockState())

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = expected,
            stateAt = stateReader(states),
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            settings = defaultSettings,
            placementContext = { _, _ -> mockk(relaxed = true) },
            predictPlacement = { _, _ -> expected },
            orientedPrediction = { _, _, _ -> null },
        )

        check(resolution is PlacementResolution.Resolved)
        assertEquals(worldPos.below(), resolution.hit.blockPos)
        assertEquals(expected, resolution.placementState)
        assertNull(resolution.requiredRotation)
    }

    @Test
    public fun matchingDirectionalPredictionStillRequiresCurrentRotationSync(): Unit {
        val worldPos = BlockPos(0, 1, 0)
        val expected = Blocks.PISTON.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.NORTH)
        val states = mapOf(worldPos.below() to Blocks.STONE.defaultBlockState())
        val currentRotation = PlacementRotation(yaw = 37f, pitch = -12f)
        val player: Player = mockk(relaxed = true)
        every { player.yRot } returns currentRotation.yaw
        every { player.xRot } returns currentRotation.pitch
        val context: BlockPlaceContext = mockk(relaxed = true)
        every { context.player } returns player

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = expected,
            stateAt = stateReader(states),
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            settings = defaultSettings,
            placementContext = { _, _ -> context },
            predictPlacement = { _, _ -> expected },
            orientedPrediction = { _, _, _ -> null },
        )

        check(resolution is PlacementResolution.Resolved)
        assertEquals(currentRotation, resolution.requiredRotation)
    }

    @Test
    public fun positionBeyondPrefilterMarginReturnsOutOfReach(): Unit {
        val worldPos = BlockPos(100, 1, 0)

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = Blocks.STONE.defaultBlockState(),
            stateAt = { Blocks.AIR.defaultBlockState() },
            eyePosition = Vec3.ZERO,
            reach = 4.5,
            settings = defaultSettings,
            placementContext = { _, _ -> mockk(relaxed = true) },
            predictPlacement = { _, _ -> Blocks.STONE.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )

        assertEquals(PlacementResolution.OutOfReach, resolution)
    }

    @Test
    public fun solidWorldTargetReturnsTargetNotReplaceable(): Unit {
        val worldPos = BlockPos(0, 1, 0)
        val states = mapOf(
            worldPos to Blocks.COBBLESTONE.defaultBlockState(),
            worldPos.below() to Blocks.STONE.defaultBlockState(),
        )

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = Blocks.STONE.defaultBlockState(),
            stateAt = stateReader(states),
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            settings = defaultSettings,
            placementContext = { _, _ -> mockk(relaxed = true) },
            predictPlacement = { _, _ -> Blocks.STONE.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )

        assertEquals(PlacementResolution.TargetNotReplaceable, resolution)
    }

    @Test
    public fun noSupportingNeighborReturnsNoSupportFace(): Unit {
        val worldPos = BlockPos(0, 1, 0)

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = Blocks.STONE.defaultBlockState(),
            stateAt = { Blocks.AIR.defaultBlockState() },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            settings = defaultSettings,
            placementContext = { _, _ -> mockk(relaxed = true) },
            predictPlacement = { _, _ -> Blocks.STONE.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )

        assertEquals(PlacementResolution.NoSupportFace, resolution)
    }

    @Test
    public fun doorExpectedStateReturnsCategoryExcluded(): Unit {
        val worldPos = BlockPos(0, 1, 0)
        val states = mapOf(worldPos.below() to Blocks.STONE.defaultBlockState())

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = Blocks.OAK_DOOR.defaultBlockState(),
            stateAt = stateReader(states),
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            settings = defaultSettings,
            placementContext = { _, _ -> mockk(relaxed = true) },
            predictPlacement = { _, _ -> Blocks.OAK_DOOR.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )

        assertEquals(PlacementResolution.CategoryExcluded, resolution)
    }

    @Test
    public fun mismatchedPredictionWithoutOrientedFallbackReturnsPredictionMismatch(): Unit {
        val worldPos = BlockPos(0, 1, 0)
        val states = mapOf(worldPos.below() to Blocks.STONE.defaultBlockState())

        val resolution = resolvePlacement(
            worldPos = worldPos,
            expectedState = Blocks.STONE.defaultBlockState(),
            stateAt = stateReader(states),
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            settings = defaultSettings,
            placementContext = { _, _ -> mockk(relaxed = true) },
            predictPlacement = { _, _ -> Blocks.DIRT.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )

        assertEquals(PlacementResolution.PredictionMismatch, resolution)
    }

    @Test
    public fun availableSupportHitsExcludeSameSlabFaceThatWouldMergeExistingNeighbor(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.JUNGLE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
        val oppositeHalfSupport = Blocks.JUNGLE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)

        val hits = availableSupportHitPoints(
            target,
            expected,
            stateReader(mapOf(target.north() to oppositeHalfSupport)),
        )

        assertTrue(hits.isEmpty())
    }

    @Test
    public fun availableSupportHitsForTrapdoorKeepOnlyMatchingHorizontalFacing(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        val states = mapOf(
            target.north() to Blocks.STONE.defaultBlockState(),
            target.west() to Blocks.STONE.defaultBlockState(),
        )

        val hits = availableSupportHitPoints(target, expected, stateReader(states))

        assertEquals(listOf(requireNotNull(supportHitPoint(target, expected, Direction.WEST))), hits)
    }

    @Test
    public fun resolvedHitAndRotationMatchSelectorsCandidateForSameFixture(): Unit {
        val target = BlockPos(0, 1, 0)
        val rotation = PlacementRotation(yaw = 90f, pitch = 0f)
        val states = mapOf(target.below() to Blocks.STONE.defaultBlockState())
        val expected = Blocks.STONE.defaultBlockState()
        val predictPlacement: (BlockItem, BlockPlaceContext) -> BlockState? =
            { _, _ -> Blocks.DIRT.defaultBlockState() }
        val orientedPrediction: (BlockItem, BlockPlaceContext, BlockState) -> PlacementRotation? =
            { _, _, _ -> rotation }
        val placementContext: (BlockState, BlockHitResult) -> BlockPlaceContext =
            { _, _ -> mockk(relaxed = true) }
        val eyePosition = Vec3.ZERO

        val resolution = resolvePlacement(
            worldPos = target,
            expectedState = expected,
            stateAt = stateReader(states),
            eyePosition = eyePosition,
            reach = 10.0,
            settings = defaultSettings,
            placementContext = placementContext,
            predictPlacement = predictPlacement,
            orientedPrediction = orientedPrediction,
        )
        check(resolution is PlacementResolution.Resolved)

        val candidate = PrinterCandidateSelector(
            predictPlacement = predictPlacement,
            orientedPrediction = orientedPrediction,
        ).select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(states),
            placementContext = placementContext,
            eyePosition = eyePosition,
            reach = 10.0,
        ).single()

        assertEquals(candidate.hit.location, resolution.hit.location)
        assertEquals(candidate.hit.direction, resolution.hit.direction)
        assertEquals(candidate.hit.blockPos, resolution.hit.blockPos)
        assertEquals(candidate.distanceSquared, resolution.distanceSquared, 0.0000001)
        assertEquals(candidate.requiredRotation, resolution.requiredRotation)
        assertEquals(candidate.placementState, resolution.placementState)
    }

    private fun stateReader(states: Map<BlockPos, BlockState>): (BlockPos) -> BlockState {
        return { pos -> states[pos] ?: Blocks.AIR.defaultBlockState() }
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
