package com.nubasu.nuchematica.schematic.`object`

import com.nubasu.nuchematica.tag.CompoundTag

public data class EntityObject(
    val pos: List<Double>,
    val id: String,
    val data: CompoundTag?
)
