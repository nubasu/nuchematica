package com.nubasu.nuchematica.printer

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType

internal val placementSubstitutionBlocks: Map<Block, Block> = mapOf(
    Blocks.INFESTED_STONE to Blocks.STONE,
    Blocks.INFESTED_COBBLESTONE to Blocks.COBBLESTONE,
    Blocks.INFESTED_STONE_BRICKS to Blocks.STONE_BRICKS,
    Blocks.INFESTED_MOSSY_STONE_BRICKS to Blocks.MOSSY_STONE_BRICKS,
    Blocks.INFESTED_CRACKED_STONE_BRICKS to Blocks.CRACKED_STONE_BRICKS,
    Blocks.INFESTED_CHISELED_STONE_BRICKS to Blocks.CHISELED_STONE_BRICKS,
    Blocks.INFESTED_DEEPSLATE to Blocks.DEEPSLATE,

    Blocks.OAK_SLAB to Blocks.OAK_PLANKS,
    Blocks.SPRUCE_SLAB to Blocks.SPRUCE_PLANKS,
    Blocks.BIRCH_SLAB to Blocks.BIRCH_PLANKS,
    Blocks.JUNGLE_SLAB to Blocks.JUNGLE_PLANKS,
    Blocks.ACACIA_SLAB to Blocks.ACACIA_PLANKS,
    Blocks.DARK_OAK_SLAB to Blocks.DARK_OAK_PLANKS,
    Blocks.CRIMSON_SLAB to Blocks.CRIMSON_PLANKS,
    Blocks.WARPED_SLAB to Blocks.WARPED_PLANKS,
    Blocks.PETRIFIED_OAK_SLAB to Blocks.OAK_PLANKS,

    Blocks.STONE_SLAB to Blocks.STONE,
    Blocks.SMOOTH_STONE_SLAB to Blocks.SMOOTH_STONE,
    Blocks.COBBLESTONE_SLAB to Blocks.COBBLESTONE,
    Blocks.MOSSY_COBBLESTONE_SLAB to Blocks.MOSSY_COBBLESTONE,
    Blocks.STONE_BRICK_SLAB to Blocks.STONE_BRICKS,
    Blocks.MOSSY_STONE_BRICK_SLAB to Blocks.MOSSY_STONE_BRICKS,
    Blocks.GRANITE_SLAB to Blocks.GRANITE,
    Blocks.POLISHED_GRANITE_SLAB to Blocks.POLISHED_GRANITE,
    Blocks.DIORITE_SLAB to Blocks.DIORITE,
    Blocks.POLISHED_DIORITE_SLAB to Blocks.POLISHED_DIORITE,
    Blocks.ANDESITE_SLAB to Blocks.ANDESITE,
    Blocks.POLISHED_ANDESITE_SLAB to Blocks.POLISHED_ANDESITE,

    Blocks.SANDSTONE_SLAB to Blocks.SANDSTONE,
    Blocks.CUT_SANDSTONE_SLAB to Blocks.CUT_SANDSTONE,
    Blocks.SMOOTH_SANDSTONE_SLAB to Blocks.SMOOTH_SANDSTONE,
    Blocks.RED_SANDSTONE_SLAB to Blocks.RED_SANDSTONE,
    Blocks.CUT_RED_SANDSTONE_SLAB to Blocks.CUT_RED_SANDSTONE,
    Blocks.SMOOTH_RED_SANDSTONE_SLAB to Blocks.SMOOTH_RED_SANDSTONE,
    Blocks.BRICK_SLAB to Blocks.BRICKS,
    Blocks.NETHER_BRICK_SLAB to Blocks.NETHER_BRICKS,
    Blocks.RED_NETHER_BRICK_SLAB to Blocks.RED_NETHER_BRICKS,
    Blocks.QUARTZ_SLAB to Blocks.QUARTZ_BLOCK,
    Blocks.SMOOTH_QUARTZ_SLAB to Blocks.SMOOTH_QUARTZ,
    Blocks.PURPUR_SLAB to Blocks.PURPUR_BLOCK,
    Blocks.END_STONE_BRICK_SLAB to Blocks.END_STONE_BRICKS,
    Blocks.PRISMARINE_SLAB to Blocks.PRISMARINE,
    Blocks.PRISMARINE_BRICK_SLAB to Blocks.PRISMARINE_BRICKS,
    Blocks.DARK_PRISMARINE_SLAB to Blocks.DARK_PRISMARINE,

    Blocks.BLACKSTONE_SLAB to Blocks.BLACKSTONE,
    Blocks.POLISHED_BLACKSTONE_SLAB to Blocks.POLISHED_BLACKSTONE,
    Blocks.POLISHED_BLACKSTONE_BRICK_SLAB to Blocks.POLISHED_BLACKSTONE_BRICKS,
    Blocks.COBBLED_DEEPSLATE_SLAB to Blocks.COBBLED_DEEPSLATE,
    Blocks.POLISHED_DEEPSLATE_SLAB to Blocks.POLISHED_DEEPSLATE,
    Blocks.DEEPSLATE_BRICK_SLAB to Blocks.DEEPSLATE_BRICKS,
    Blocks.DEEPSLATE_TILE_SLAB to Blocks.DEEPSLATE_TILES,

    Blocks.CUT_COPPER_SLAB to Blocks.CUT_COPPER,
    Blocks.EXPOSED_CUT_COPPER_SLAB to Blocks.EXPOSED_CUT_COPPER,
    Blocks.WEATHERED_CUT_COPPER_SLAB to Blocks.WEATHERED_CUT_COPPER,
    Blocks.OXIDIZED_CUT_COPPER_SLAB to Blocks.OXIDIZED_CUT_COPPER,
    Blocks.WAXED_CUT_COPPER_SLAB to Blocks.WAXED_CUT_COPPER,
    Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB to Blocks.WAXED_EXPOSED_CUT_COPPER,
    Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB to Blocks.WAXED_WEATHERED_CUT_COPPER,
    Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB to Blocks.WAXED_OXIDIZED_CUT_COPPER,
)

internal fun substitutePlacement(expected: BlockState): BlockState? {
    val expectedBlock = expected.block
    val substituteBlock = placementSubstitutionBlocks[expectedBlock] ?: return null
    if (
        expectedBlock is SlabBlock &&
        expected.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE
    ) {
        return null
    }

    val substitute = substituteBlock.defaultBlockState()
    return if (expectedBlock === Blocks.INFESTED_DEEPSLATE) {
        substitute.setValue(
            BlockStateProperties.AXIS,
            expected.getValue(BlockStateProperties.AXIS),
        )
    } else {
        substitute
    }
}

internal data class PlacementBehaviorSettings(
    internal val substituteLookalikes: Boolean,
    internal val placeWaterloggedDry: Boolean,
)

internal fun currentPlacementBehaviorSettings(): PlacementBehaviorSettings {
    val settings = PrinterSettingsHolder.printerSettings
    return PlacementBehaviorSettings(settings.substituteLookalikes, settings.placeWaterloggedDry)
}

internal fun effectivePlacementState(expected: BlockState, settings: PlacementBehaviorSettings): BlockState {
    if (!settings.substituteLookalikes) return expected
    return substitutePlacement(expected) ?: expected
}

internal fun effectivePlacementState(expected: BlockState): BlockState {
    return effectivePlacementState(expected, currentPlacementBehaviorSettings())
}
