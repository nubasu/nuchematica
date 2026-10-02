package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.Direction.Axis
import net.minecraft.core.Registry
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterBlockSubstitutionsTest {
    @Test
    public fun everyMappedPairHasAPlaceableFullBlockSubstitute(): Unit {
        assertEquals(59, placementSubstitutionBlocks.size)

        placementSubstitutionBlocks.forEach { (expectedBlock, substituteBlock) ->
            val expected = substitutionSourceState(expectedBlock)
            val substitute = requireNotNull(substitutePlacement(expected))

            assertEquals(substituteBlock, substitute.block, registryName(expectedBlock))
            assertTrue(expectedBlock.asItem() is BlockItem, registryName(expectedBlock))
            assertTrue(substitute.block.asItem() is BlockItem, registryName(substituteBlock))
            assertFalse(substitute.block is SlabBlock, registryName(substituteBlock))
            assertFalse(registryName(substitute.block).startsWith("infested_"))
        }
    }

    @Test
    public fun textureIdentityPairsResolveToNamedFullBlocks(): Unit {
        assertSubstitutionName(Blocks.INFESTED_STONE_BRICKS.defaultBlockState(), "stone_bricks")
        assertSubstitutionName(doubleSlab(Blocks.OAK_SLAB), "oak_planks")
        assertSubstitutionName(doubleSlab(Blocks.PETRIFIED_OAK_SLAB), "oak_planks")
        assertSubstitutionName(doubleSlab(Blocks.SMOOTH_STONE_SLAB), "smooth_stone")
        assertSubstitutionName(doubleSlab(Blocks.QUARTZ_SLAB), "quartz_block")
        assertSubstitutionName(
            doubleSlab(Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB),
            "waxed_weathered_cut_copper",
        )
    }

    @Test
    public fun infestedDeepslateCarriesAxisToNormalDeepslate(): Unit {
        val expected = Blocks.INFESTED_DEEPSLATE.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Axis.X)

        val substitute = requireNotNull(substitutePlacement(expected))

        assertEquals(Blocks.DEEPSLATE, substitute.block)
        assertEquals(Axis.X, substitute.getValue(BlockStateProperties.AXIS))
    }

    @Test
    public fun singleSlabsAndUnmappedBlockCategoriesAreNeverSubstituted(): Unit {
        assertNull(substitutePlacement(Blocks.OAK_SLAB.defaultBlockState()))
        assertNull(
            substitutePlacement(
                Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
            ),
        )
        assertNull(substitutePlacement(Blocks.OAK_STAIRS.defaultBlockState()))
    }

    private fun substitutionSourceState(block: Block): BlockState {
        return if (block is SlabBlock) doubleSlab(block) else block.defaultBlockState()
    }

    private fun doubleSlab(block: Block): BlockState = block.defaultBlockState()
        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)

    private fun assertSubstitutionName(expected: BlockState, substituteName: String): Unit {
        assertEquals(substituteName, registryName(requireNotNull(substitutePlacement(expected)).block))
    }

    private fun registryName(block: Block): String {
        return requireNotNull(Registry.BLOCK.getKey(block)).path
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
