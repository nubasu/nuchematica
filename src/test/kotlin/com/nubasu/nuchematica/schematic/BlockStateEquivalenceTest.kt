package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.printer.PrinterSettings
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
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
    public fun fencesWithDifferentWaterloggedStateDoNotMatch(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = false
        val dryFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false)
        val waterloggedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)

        assertFalse(BlockStateEquivalence.matches(dryFence, waterloggedFence))
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
    public fun fenceConnectionDifferenceStillDoesNotMatchWhenDryPlacementEnabled(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = true
        val waterloggedNorthFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)
            .setValue(BlockStateProperties.NORTH, true)
        val dryUnconnectedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false)
            .setValue(BlockStateProperties.NORTH, false)

        assertFalse(BlockStateEquivalence.matches(waterloggedNorthFence, dryUnconnectedFence))
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
