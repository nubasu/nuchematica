package com.nubasu.nuchematica.schematic

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

public data class Clipboard (
    val block: ArrayList<BlockState> = arrayListOf(),
    val tileEntity: ArrayList<BlockEntity?> = arrayListOf(),
    val position: ArrayList<BlockPos> = arrayListOf()
)