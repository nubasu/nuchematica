package com.nubasu.nuchematica.renderer

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.Level

public class ClientBlockInteractHandler {
    public fun onLeftClickBlock(level: Level, pos: BlockPos): Unit {
        if (!level.isClientSide()) return

        pendingBreakPositions.add(pos.immutable())
    }

    public fun onRightClickBlock(level: Level, player: Player, hand: InteractionHand, pos: BlockPos, face: Direction?): Unit {
        if (!level.isClientSide()) return
        val world = Minecraft.getInstance().level
        if (world == null) return
        val handItem = player.getItemInHand(hand)
        if (handItem.item is BlockItem) {
            val isReplaceable = world.getBlockState(pos).material.isReplaceable
            val placePos = if (isReplaceable)
                pos
            else
                pos.relative(face)
            pendingPlacePositions.add(placePos.immutable())
        }
    }

    public companion object{
        public val pendingPlacePositions: MutableSet<BlockPos> = mutableSetOf<BlockPos>()
        public val pendingBreakPositions: MutableSet<BlockPos> = mutableSetOf<BlockPos>()
    }
}