package com.nubasu.nuchematica.printer

import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CakeBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CandleCakeBlock
import net.minecraft.world.level.block.CartographyTableBlock
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.block.CraftingTableBlock
import net.minecraft.world.level.block.DiodeBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FletchingTableBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.GrindstoneBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LoomBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.SmithingTableBlock
import net.minecraft.world.level.block.StonecutterBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState

public object PrinterSupportPolicy {
    public fun isInteractionSafe(state: BlockState): Boolean {
        val block = state.block
        return block !is BaseEntityBlock &&
            block !is CraftingTableBlock &&
            block !is AnvilBlock &&
            block !is GrindstoneBlock &&
            block !is StonecutterBlock &&
            block !is LoomBlock &&
            block !is CartographyTableBlock &&
            block !is SmithingTableBlock &&
            block !is FletchingTableBlock &&
            block !is DoorBlock &&
            block !is TrapDoorBlock &&
            block !is FenceGateBlock &&
            block !is ButtonBlock &&
            block !is LeverBlock &&
            block !is NoteBlock &&
            block !is DiodeBlock &&
            block !is CakeBlock &&
            block !is CandleCakeBlock &&
            block !is FlowerPotBlock &&
            block !is ComposterBlock &&
            block !is AbstractCauldronBlock &&
            block !is RespawnAnchorBlock &&
            block !is SweetBerryBushBlock &&
            block !is CandleBlock
    }
}
