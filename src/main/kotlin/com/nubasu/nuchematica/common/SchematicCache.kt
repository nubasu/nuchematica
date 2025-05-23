package com.nubasu.nuchematica.common

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

public data class SchematicCache(
    val blocks: Map<BlockPos, BlockState>,
    val blockEntities: Map<BlockPos, BlockEntity?>
)
