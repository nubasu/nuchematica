package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.printer.PrinterSettings
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.RedstoneSide
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.block.state.properties.StairsShape
import net.minecraft.world.level.block.state.properties.WallSide
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class BlockStateEquivalenceTest {
    @AfterEach
    public fun resetPrinterSettings(): Unit {
        PrinterSettingsHolder.printerSettings = PrinterSettings()
    }

    @Test
    public fun leavesIgnorePersistentAndDistance(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = false
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
            .setValue(BlockStateProperties.DISTANCE, 1)

        assertTrue(BlockStateEquivalence.matches(naturalLeaves, placedLeaves))
    }

    @Test
    public fun differentLeavesBlocksDoNotMatch(): Unit {
        val oakLeaves = Blocks.OAK_LEAVES.defaultBlockState()
        val birchLeaves = Blocks.BIRCH_LEAVES.defaultBlockState()

        assertFalse(BlockStateEquivalence.matches(oakLeaves, birchLeaves))
    }

    @Test
    public fun identicalStoneStatesMatch(): Unit {
        val stone = Blocks.STONE.defaultBlockState()

        assertTrue(BlockStateEquivalence.matches(stone, stone))
    }

    @Test
    public fun infestedStoneBricksExpectedMatchesNormalStoneBricks(): Unit {
        assertTrue(
            BlockStateEquivalence.matches(
                Blocks.INFESTED_STONE_BRICKS.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(),
            ),
        )
    }

    @Test
    public fun doubleSmoothStoneSlabExpectedMatchesSmoothStone(): Unit {
        val expected = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)

        assertTrue(
            BlockStateEquivalence.matches(expected, Blocks.SMOOTH_STONE.defaultBlockState()),
        )
    }

    @Test
    public fun lookalikeSubstitutesDoNotMatchWhenSettingIsDisabled(): Unit {
        PrinterSettingsHolder.printerSettings.substituteLookalikes = false

        assertFalse(
            BlockStateEquivalence.matches(
                Blocks.INFESTED_STONE_BRICKS.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(),
            ),
        )
    }

    @Test
    public fun differentNonLeavesBlocksDoNotMatch(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = false
        assertFalse(
            BlockStateEquivalence.matches(
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
            ),
        )
    }

    @Test
    public fun stairsWithDifferentFacingDoNotMatch(): Unit {
        val northFacing = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        val southFacing = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)

        assertFalse(BlockStateEquivalence.matches(northFacing, southFacing))
    }

    @Test
    public fun stairsWithDifferentShapeMatch(): Unit {
        val straight = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
        val innerLeft = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT)

        assertTrue(BlockStateEquivalence.matches(straight, innerLeft))
    }

    @Test
    public fun stairsWithDifferentHalfDoNotMatch(): Unit {
        val bottom = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
        val top = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP)

        assertFalse(BlockStateEquivalence.matches(bottom, top))
    }

    @Test
    public fun fenceConnectionsAreIgnoredButWaterloggedDifferenceDoesNotMatch(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = false
        val connectedWaterloggedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)
            .setValue(BlockStateProperties.NORTH, true)
            .setValue(BlockStateProperties.EAST, true)
        val dryUnconnectedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false)
            .setValue(BlockStateProperties.NORTH, false)
            .setValue(BlockStateProperties.EAST, false)

        assertFalse(
            BlockStateEquivalence.matches(connectedWaterloggedFence, dryUnconnectedFence),
        )
    }

    @Test
    public fun fencesWithDifferentWaterloggedStateMatchWhenDryPlacementEnabled(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = true
        val waterloggedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)
        val dryFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false)

        assertTrue(BlockStateEquivalence.matches(waterloggedFence, dryFence))
    }

    @Test
    public fun fenceConnectionsDoNotAffectMatch(): Unit {
        val connectedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH, true)
            .setValue(BlockStateProperties.EAST, true)
        val unconnectedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH, false)
            .setValue(BlockStateProperties.EAST, false)

        assertTrue(BlockStateEquivalence.matches(connectedFence, unconnectedFence))
    }

    @Test
    public fun differentFenceBlocksDoNotMatch(): Unit {
        val oakFence = Blocks.OAK_FENCE.defaultBlockState()
        val spruceFence = Blocks.SPRUCE_FENCE.defaultBlockState()

        assertFalse(BlockStateEquivalence.matches(oakFence, spruceFence))
    }

    @Test
    public fun wallConnectionsAndUpDoNotAffectMatch(): Unit {
        val connectedWall = Blocks.COBBLESTONE_WALL.defaultBlockState()
            .setValue(BlockStateProperties.NORTH_WALL, WallSide.LOW)
            .setValue(BlockStateProperties.EAST_WALL, WallSide.TALL)
            .setValue(BlockStateProperties.SOUTH_WALL, WallSide.LOW)
            .setValue(BlockStateProperties.WEST_WALL, WallSide.TALL)
            .setValue(BlockStateProperties.UP, false)
        val unconnectedWall = Blocks.COBBLESTONE_WALL.defaultBlockState()
            .setValue(BlockStateProperties.NORTH_WALL, WallSide.NONE)
            .setValue(BlockStateProperties.EAST_WALL, WallSide.NONE)
            .setValue(BlockStateProperties.SOUTH_WALL, WallSide.NONE)
            .setValue(BlockStateProperties.WEST_WALL, WallSide.NONE)
            .setValue(BlockStateProperties.UP, true)

        assertTrue(BlockStateEquivalence.matches(connectedWall, unconnectedWall))
    }

    @Test
    public fun glassPaneConnectionsDoNotAffectMatch(): Unit {
        val connectedPane = Blocks.GLASS_PANE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH, true)
            .setValue(BlockStateProperties.EAST, true)
            .setValue(BlockStateProperties.SOUTH, true)
            .setValue(BlockStateProperties.WEST, true)
        val unconnectedPane = Blocks.GLASS_PANE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH, false)
            .setValue(BlockStateProperties.EAST, false)
            .setValue(BlockStateProperties.SOUTH, false)
            .setValue(BlockStateProperties.WEST, false)

        assertTrue(BlockStateEquivalence.matches(connectedPane, unconnectedPane))
    }

    @Test
    public fun redstoneConnectionsAndPowerDoNotAffectMatch(): Unit {
        val poweredWire = Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.SIDE)
            .setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.UP)
            .setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.NONE)
            .setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.SIDE)
            .setValue(BlockStateProperties.POWER, 15)
        val unpoweredWire = Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.NONE)
            .setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.NONE)
            .setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.SIDE)
            .setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.UP)
            .setValue(BlockStateProperties.POWER, 0)

        assertTrue(BlockStateEquivalence.matches(poweredWire, unpoweredWire))
    }

    @Test
    public fun chestTypeDoesNotAffectMatch(): Unit {
        val singleChest = Blocks.CHEST.defaultBlockState()
            .setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE)
        val leftChest = Blocks.CHEST.defaultBlockState()
            .setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)

        assertTrue(BlockStateEquivalence.matches(singleChest, leftChest))
    }

    @Test
    public fun chestsWithDifferentFacingDoNotMatch(): Unit {
        val northFacing = Blocks.CHEST.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        val southFacing = Blocks.CHEST.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)

        assertFalse(BlockStateEquivalence.matches(northFacing, southFacing))
    }

    @Test
    public fun fenceGateInWallDoesNotAffectMatch(): Unit {
        val inWall = Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(BlockStateProperties.IN_WALL, true)
        val notInWall = Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(BlockStateProperties.IN_WALL, false)

        assertTrue(BlockStateEquivalence.matches(inWall, notInWall))
    }

    @Test
    public fun fenceGatesWithDifferentFacingDoNotMatch(): Unit {
        val northFacing = Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        val southFacing = Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)

        assertFalse(BlockStateEquivalence.matches(northFacing, southFacing))
    }

    @Test
    public fun hugeMushroomFacesDoNotAffectMatch(): Unit {
        val coveredFaces = Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState()
            .setValue(BlockStateProperties.UP, true)
            .setValue(BlockStateProperties.DOWN, true)
            .setValue(BlockStateProperties.NORTH, true)
            .setValue(BlockStateProperties.EAST, true)
            .setValue(BlockStateProperties.SOUTH, true)
            .setValue(BlockStateProperties.WEST, true)
        val exposedFaces = Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState()
            .setValue(BlockStateProperties.UP, false)
            .setValue(BlockStateProperties.DOWN, false)
            .setValue(BlockStateProperties.NORTH, false)
            .setValue(BlockStateProperties.EAST, false)
            .setValue(BlockStateProperties.SOUTH, false)
            .setValue(BlockStateProperties.WEST, false)

        assertTrue(BlockStateEquivalence.matches(coveredFaces, exposedFaces))
    }

    @Test
    public fun trapdoorIgnoresOpenAndPoweredWhenFacingAndHalfMatch(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = false
        val openAndPowered = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.OPEN, true)
            .setValue(BlockStateProperties.POWERED, true)
        val freshlyPlaced = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.OPEN, false)
            .setValue(BlockStateProperties.POWERED, false)

        assertTrue(BlockStateEquivalence.matches(openAndPowered, freshlyPlaced))
    }

    @Test
    public fun trapdoorsWithDifferentFacingDoNotMatch(): Unit {
        val northFacing = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        val southFacing = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)

        assertFalse(BlockStateEquivalence.matches(northFacing, southFacing))
    }

    @Test
    public fun redstoneLampIgnoresLitDifference(): Unit {
        val lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true)
        val unlit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, false)

        assertTrue(BlockStateEquivalence.matches(lit, unlit))
    }

    @Test
    public fun saplingStageGrowthDoesNotAffectMatch(): Unit {
        val expectedStageZero = Blocks.OAK_SAPLING.defaultBlockState()
            .setValue(BlockStateProperties.STAGE, 0)
        val actualStageOne = Blocks.OAK_SAPLING.defaultBlockState()
            .setValue(BlockStateProperties.STAGE, 1)

        assertTrue(BlockStateEquivalence.matches(expectedStageZero, actualStageOne))
    }

    @Test
    public fun differentBlocksDoNotMatchWhenDryPlacementEnabled(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = true

        assertFalse(
            BlockStateEquivalence.matches(
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
            ),
        )
    }

    @Test
    public fun leavesStillIgnorePersistentAndDistanceWhenDryPlacementEnabled(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = true
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
            .setValue(BlockStateProperties.DISTANCE, 1)

        assertTrue(BlockStateEquivalence.matches(naturalLeaves, placedLeaves))
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
