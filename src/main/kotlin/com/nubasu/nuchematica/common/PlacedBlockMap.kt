package com.nubasu.nuchematica.common

import net.minecraft.world.level.block.Block

object PlacedBlockMap {
    var blockList: MutableMap<Block, Int> = mutableMapOf()
    var placedBlockList: MutableMap<Block, Int> = mutableMapOf()
}