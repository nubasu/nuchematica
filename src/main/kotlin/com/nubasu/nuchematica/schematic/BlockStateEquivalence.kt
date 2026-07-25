package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.printer.PlacementBehaviorSettings
import com.nubasu.nuchematica.printer.currentPlacementBehaviorSettings
import com.nubasu.nuchematica.printer.effectivePlacementState
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.HugeMushroomBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property

public object BlockStateEquivalence {
    // Properties that world updates or redstone/interaction events change at runtime
    // (saplings growing, a player opening a trapdoor, a lamp lighting up) rather than the
    // printer's own placement action. A freshly placed block will virtually never carry the
    // same value as the schematic's snapshot for these, so they are ignored whenever the
    // target block has them at all -- no per-block-type condition needed.
    private val DYNAMICALLY_SET_PROPERTIES: List<Property<*>> = listOf(
        BlockStateProperties.POWERED,
        BlockStateProperties.OPEN,
        BlockStateProperties.LIT,
        BlockStateProperties.TRIGGERED,
        BlockStateProperties.EXTENDED,
        BlockStateProperties.STAGE,
    )

    public fun matches(expected: BlockState, actual: BlockState): Boolean {
        return matches(expected, actual, currentPlacementBehaviorSettings())
    }

    internal fun matches(expected: BlockState, actual: BlockState, settings: PlacementBehaviorSettings): Boolean {
        if (expected == actual) return true
        val effectiveExpected = effectivePlacementState(expected, settings)
        if (effectiveExpected == actual) return true
        if (effectiveExpected.block != actual.block) return false

        val ignoredProperties = mutableSetOf<Property<*>>()
        if (effectiveExpected.block is LeavesBlock) {
            ignoredProperties += BlockStateProperties.DISTANCE
            ignoredProperties += BlockStateProperties.PERSISTENT
        }
        if (effectiveExpected.block is CrossCollisionBlock) {
            ignoredProperties += BlockStateProperties.NORTH
            ignoredProperties += BlockStateProperties.EAST
            ignoredProperties += BlockStateProperties.SOUTH
            ignoredProperties += BlockStateProperties.WEST
        }
        if (effectiveExpected.block is WallBlock) {
            ignoredProperties += BlockStateProperties.NORTH_WALL
            ignoredProperties += BlockStateProperties.EAST_WALL
            ignoredProperties += BlockStateProperties.SOUTH_WALL
            ignoredProperties += BlockStateProperties.WEST_WALL
            ignoredProperties += BlockStateProperties.UP
        }
        if (effectiveExpected.block is StairBlock) {
            ignoredProperties += BlockStateProperties.STAIRS_SHAPE
        }
        if (effectiveExpected.block is FenceGateBlock) {
            ignoredProperties += BlockStateProperties.IN_WALL
        }
        if (effectiveExpected.block is RedStoneWireBlock) {
            ignoredProperties += BlockStateProperties.NORTH_REDSTONE
            ignoredProperties += BlockStateProperties.EAST_REDSTONE
            ignoredProperties += BlockStateProperties.SOUTH_REDSTONE
            ignoredProperties += BlockStateProperties.WEST_REDSTONE
            ignoredProperties += BlockStateProperties.POWER
        }
        if (effectiveExpected.block is ChestBlock) {
            ignoredProperties += BlockStateProperties.CHEST_TYPE
        }
        if (effectiveExpected.block is HugeMushroomBlock) {
            ignoredProperties += BlockStateProperties.UP
            ignoredProperties += BlockStateProperties.DOWN
            ignoredProperties += BlockStateProperties.NORTH
            ignoredProperties += BlockStateProperties.EAST
            ignoredProperties += BlockStateProperties.SOUTH
            ignoredProperties += BlockStateProperties.WEST
        }
        if (
            settings.placeWaterloggedDry &&
            BlockStateProperties.WATERLOGGED in effectiveExpected.properties
        ) {
            ignoredProperties += BlockStateProperties.WATERLOGGED
        }
        for (dynamicProperty in DYNAMICALLY_SET_PROPERTIES) {
            if (dynamicProperty in effectiveExpected.properties) {
                ignoredProperties += dynamicProperty
            }
        }
        if (ignoredProperties.isEmpty()) return false

        val expectedMatches = effectiveExpected.properties
            .asSequence()
            .filterNot { property -> property in ignoredProperties }
            .all { property -> effectiveExpected.getValue(property) == actual.getValue(property) }
        val actualMatches = actual.properties
            .asSequence()
            .filterNot { property -> property in ignoredProperties }
            .all { property -> actual.getValue(property) == effectiveExpected.getValue(property) }
        return expectedMatches && actualMatches
    }
}
