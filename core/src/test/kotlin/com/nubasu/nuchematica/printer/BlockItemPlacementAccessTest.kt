package com.nubasu.nuchematica.printer

import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class BlockItemPlacementAccessTest {
    @Test
    public fun torchPlacementDispatchesToWallOverride(): Unit {
        val context = placementContext()

        val state = (Items.TORCH as BlockItem).getPlacementState(context)

        assertEquals(Blocks.WALL_TORCH, state?.block)
    }

    @Test
    public fun stonePlacementUsesBaseBlockItemState(): Unit {
        val context = placementContext()

        val state = (Items.STONE as BlockItem).getPlacementState(context)

        assertEquals(Blocks.STONE, state?.block)
    }

    private fun placementContext(): BlockPlaceContext {
        val level = mockk<Level>()
        val context = mockk<BlockPlaceContext>()
        every { context.level } returns level
        every { context.player } returns null
        every { context.clickedPos } returns BlockPos.ZERO
        every { context.nearestLookingDirections } returns arrayOf(Direction.NORTH)
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        every {
            level.isUnobstructed(any(), any(), any<CollisionContext>())
        } returns true
        return context
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
