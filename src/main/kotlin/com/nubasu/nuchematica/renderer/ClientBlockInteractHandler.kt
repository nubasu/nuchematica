package com.nubasu.nuchematica.renderer

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.item.BlockItem
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock
import net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientBlockInteractHandler {
    @SubscribeEvent
    public fun onBlockLeftClick(event: LeftClickBlock) {
        if (!event.world.isClientSide()) return  // Ensure client side

        val targetPos = event.pos
        // Record this position as a block the player is attempting to break
        pendingBreakPositions.add(targetPos.immutable())
    }

    @SubscribeEvent
    public fun onBlockRightClick(event: RightClickBlock) {
        if (!event.world.isClientSide()) return
        val player = event.player
        val world = Minecraft.getInstance().level
        if (world == null) return
        val handItem = player.getItemInHand(event.hand)
        if (handItem.item is BlockItem) {
            // Compute where the block will be placed
            val targetPos = event.pos
            val isReplaceable = world.getBlockState(targetPos).material.isReplaceable
            val placePos = if (isReplaceable)
                targetPos
            else
                targetPos.relative(event.face)
            pendingPlacePositions.add(placePos.immutable())
        }
    }

    public companion object{
        public val pendingPlacePositions: MutableSet<BlockPos> = mutableSetOf<BlockPos>()
        public val pendingBreakPositions: MutableSet<BlockPos> = mutableSetOf<BlockPos>()
    }
}