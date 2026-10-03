package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterSupportPolicyTest {
    @Test
    public fun ordinaryBuildingBlocksAreSafeSupports(): Unit {
        val safeStates = listOf(
            Blocks.STONE.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(),
            Blocks.OAK_PLANKS.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState(),
            Blocks.OAK_SAPLING.defaultBlockState(),
        )

        safeStates.forEach { state ->
            assertTrue(PrinterSupportPolicy.isInteractionSafe(state), state.toString())
        }
    }

    @Test
    public fun interactiveBlocksAreUnsafeSupports(): Unit {
        val unsafeStates = listOf(
            Blocks.CHEST.defaultBlockState(),
            Blocks.FURNACE.defaultBlockState(),
            Blocks.BARREL.defaultBlockState(),
            Blocks.CRAFTING_TABLE.defaultBlockState(),
            Blocks.ANVIL.defaultBlockState(),
            Blocks.OAK_DOOR.defaultBlockState(),
            Blocks.OAK_TRAPDOOR.defaultBlockState(),
            Blocks.OAK_FENCE_GATE.defaultBlockState(),
            Blocks.STONE_BUTTON.defaultBlockState(),
            Blocks.LEVER.defaultBlockState(),
            Blocks.REPEATER.defaultBlockState(),
            Blocks.COMPARATOR.defaultBlockState(),
            Blocks.NOTE_BLOCK.defaultBlockState(),
            Blocks.COMPOSTER.defaultBlockState(),
            Blocks.CAULDRON.defaultBlockState(),
            Blocks.FLOWER_POT.defaultBlockState(),
            Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
            Blocks.RESPAWN_ANCHOR.defaultBlockState(),
            Blocks.CAKE.defaultBlockState(),
        )

        unsafeStates.forEach { state ->
            assertFalse(PrinterSupportPolicy.isInteractionSafe(state), state.toString())
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
