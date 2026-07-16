package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property

public object BlockStateEquivalence {
    public fun matches(expected: BlockState, actual: BlockState): Boolean {
        if (expected == actual) return true
        if (expected.block != actual.block) return false

        val ignoredProperties = mutableSetOf<Property<*>>()
        if (expected.block is LeavesBlock) {
            ignoredProperties += BlockStateProperties.DISTANCE
            ignoredProperties += BlockStateProperties.PERSISTENT
        }
        if (
            PrinterSettingsHolder.printerSettings.placeWaterloggedDry &&
            BlockStateProperties.WATERLOGGED in expected.properties
        ) {
            ignoredProperties += BlockStateProperties.WATERLOGGED
        }
        if (ignoredProperties.isEmpty()) return false

        val expectedMatches = expected.properties
            .asSequence()
            .filterNot { property -> property in ignoredProperties }
            .all { property -> expected.getValue(property) == actual.getValue(property) }
        val actualMatches = actual.properties
            .asSequence()
            .filterNot { property -> property in ignoredProperties }
            .all { property -> actual.getValue(property) == expected.getValue(property) }
        return expectedMatches && actualMatches
    }
}
