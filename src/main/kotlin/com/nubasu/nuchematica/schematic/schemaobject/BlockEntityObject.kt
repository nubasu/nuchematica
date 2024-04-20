package com.nubasu.nuchematica.schematic.schemaobject

import com.nubasu.nuchematica.tag.CompoundTag

public data class BlockEntityObject(
    val pos:IntArray,
    val id: String,
    val data: CompoundTag?
)