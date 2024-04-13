package com.nubasu.nuchematica.schematic.`object`

import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.StringTag

public data class BlockEntityObject(
    val pos:IntArrayTag,
    val id: StringTag,
    val data: CompoundTag?
)