package com.nubasu.nuchematica.utils

import net.minecraft.core.Registry
import net.minecraft.world.level.block.Block

public object BlockToString {
    public fun getBlockId(block: Block): String? {
        val name = Registry.BLOCK.getKey(block)?.toString()
        return name?.split(":")?.last()
    }
}