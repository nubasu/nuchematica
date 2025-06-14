package com.nubasu.nuchematica.utils

import net.minecraft.world.level.block.Block
import net.minecraftforge.registries.ForgeRegistries.BLOCKS

public object BlockToString {
    public fun getBlockId(block: Block): String? {
        val name = BLOCKS.getKey(block)?.toString()
        return name?.split(":")?.last()
    }
}