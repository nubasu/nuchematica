package com.nubasu.nuchematica.schematic.`object`

import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.DoubleTag
import com.nubasu.nuchematica.tag.StringTag

public data class EntityObject(
    val pos: List<Double>,
    val id: String,
    val data: CompoundTag?
)
