package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RailBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.RailShape
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

internal class SurvivalPredictionTest {
    @Test
    internal fun torchSurvivesOnlyWithSolidFloorBelow() {
        val pos = BlockPos(0, 5, 0)
        val torch = Blocks.TORCH.defaultBlockState()
        val supported: (BlockPos) -> BlockState = { queryPos ->
            if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val unsupported: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        assertTrue(survivesWithoutScaffold(pos, torch, supported))
        assertFalse(survivesWithoutScaffold(pos, torch, unsupported))
    }

    @Test
    internal fun railSurvivesOnlyWithSolidFloorBelow() {
        val pos = BlockPos(0, 5, 0)
        val rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH)
        val supported: (BlockPos) -> BlockState = { queryPos ->
            if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val unsupported: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        assertTrue(survivesWithoutScaffold(pos, rail, supported))
        assertFalse(survivesWithoutScaffold(pos, rail, unsupported))
    }

    @Test
    internal fun ascendingRailAlsoRequiresSturdyUphillNeighbor() {
        val pos = BlockPos(0, 5, 0)
        val ascendingRail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.ASCENDING_NORTH)
        val floorOnly: (BlockPos) -> BlockState = { queryPos ->
            if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val floorAndUphill: (BlockPos) -> BlockState = { queryPos ->
            when (queryPos) {
                pos.below() -> Blocks.STONE.defaultBlockState()
                pos.north() -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
        }

        assertFalse(survivesWithoutScaffold(pos, ascendingRail, floorOnly))
        assertTrue(survivesWithoutScaffold(pos, ascendingRail, floorAndUphill))
    }

    @Test
    internal fun ascendingRailUphillDirectionMappingCoversAllFourDirections() {
        val pos = BlockPos(0, 5, 0)
        val cases = listOf(
            RailShape.ASCENDING_SOUTH to pos.south(),
            RailShape.ASCENDING_EAST to pos.east(),
            RailShape.ASCENDING_WEST to pos.west(),
        )
        for ((shape, uphillPos) in cases) {
            val rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape)
            val floorOnly: (BlockPos) -> BlockState = { queryPos ->
                if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
            }
            val floorAndUphill: (BlockPos) -> BlockState = { queryPos ->
                when (queryPos) {
                    pos.below() -> Blocks.STONE.defaultBlockState()
                    uphillPos -> Blocks.STONE.defaultBlockState()
                    else -> Blocks.AIR.defaultBlockState()
                }
            }

            assertFalse(survivesWithoutScaffold(pos, rail, floorOnly), "$shape without uphill support")
            assertTrue(survivesWithoutScaffold(pos, rail, floorAndUphill), "$shape with uphill support")
        }
    }

    @Test
    internal fun stoneSurvivesRegardlessOfNeighbors() {
        val pos = BlockPos(0, 5, 0)
        val stone = Blocks.STONE.defaultBlockState()
        val noNeighbors: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        assertTrue(survivesWithoutScaffold(pos, stone, noNeighbors))
    }

    @Test
    internal fun vineSurvivesButLosesAnExpectedFaceFailsEquivalence() {
        val pos = BlockPos(0, 5, 0)
        val expected = Blocks.VINE.defaultBlockState()
            .setValue(VineBlock.NORTH, true)
            .setValue(VineBlock.EAST, true)
        val northLost: (BlockPos) -> BlockState = { queryPos ->
            when (queryPos) {
                pos.north() -> Blocks.AIR.defaultBlockState()
                pos.east() -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
        }

        assertFalse(survivesWithoutScaffold(pos, expected, northLost))
    }

    @Test
    internal fun vineSurvivesAndMatchesWhenBothExpectedFacesStillSupported() {
        val pos = BlockPos(0, 5, 0)
        val expected = Blocks.VINE.defaultBlockState()
            .setValue(VineBlock.NORTH, true)
            .setValue(VineBlock.EAST, true)
        val bothSupported: (BlockPos) -> BlockState = { queryPos ->
            when (queryPos) {
                pos.north() -> Blocks.STONE.defaultBlockState()
                pos.east() -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
        }

        assertTrue(survivesWithoutScaffold(pos, expected, bothSupported))
    }

    @Test
    internal fun exceptionDuringEvaluationIsConservativeFailure() {
        val pos = BlockPos(0, 5, 0)
        val torch = Blocks.TORCH.defaultBlockState()
        val throwingView: (BlockPos) -> BlockState = { throw RuntimeException("boom") }

        assertFalse(survivesWithoutScaffold(pos, torch, throwingView))
    }

    @Test
    internal fun naturalLeafWithMismatchedNeighbourDistanceSurvivesWithoutScaffold() {
        val pos = BlockPos(0, 3, 0)
        val naturalLeaf = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.DISTANCE, 1)
            .setValue(LeavesBlock.PERSISTENT, false)
        val view: (BlockPos) -> BlockState = { queryPos ->
            when (queryPos) {
                pos.below() -> Blocks.STONE.defaultBlockState()
                pos.north() -> Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.DISTANCE, 2)
                    .setValue(LeavesBlock.PERSISTENT, false)
                else -> Blocks.AIR.defaultBlockState()
            }
        }

        assertTrue(survivesWithoutScaffold(pos, naturalLeaf, view))
    }

    @Test
    internal fun coralWithoutWaterloggedFlagReachesRealFluidStateAdapterThenFailsConservativelyOnUnbackedRandomSource() {
        val pos = BlockPos(0, 5, 0)
        val coral = Blocks.TUBE_CORAL.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false)
        val view: (BlockPos) -> BlockState = { queryPos ->
            if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        assertFalse(survivesWithoutScaffold(pos, coral, view))
    }

    @Test
    internal fun sandWithAirBelowFailsConservativelyOnUnbackedTickRequest() {
        val pos = BlockPos(0, 5, 0)
        val sand = Blocks.SAND.defaultBlockState()
        val view: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        assertFalse(survivesWithoutScaffold(pos, sand, view))
    }

    @Test
    internal fun scaffoldingFailsConservativelyOnUnbackedIsClientSide() {
        val pos = BlockPos(0, 5, 0)
        val scaffolding = Blocks.SCAFFOLDING.defaultBlockState()
        val view: (BlockPos) -> BlockState = { queryPos ->
            if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        assertFalse(survivesWithoutScaffold(pos, scaffolding, view))
    }

    @Test
    internal fun pointedDripstoneSurvivesOnSolidFloorWithoutTouchingAnyUnbackedMember() {
        val pos = BlockPos(0, 5, 0)
        val dripstone = Blocks.POINTED_DRIPSTONE.defaultBlockState()
        val view: (BlockPos) -> BlockState = { queryPos ->
            if (queryPos == pos.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        assertTrue(survivesWithoutScaffold(pos, dripstone, view))
    }

    internal companion object {
        @JvmStatic
        @BeforeAll
        internal fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
