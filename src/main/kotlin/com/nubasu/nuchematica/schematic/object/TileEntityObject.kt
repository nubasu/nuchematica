package com.nubasu.nuchematica.schematic.`object`

import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.IntTag
import com.nubasu.nuchematica.tag.StringTag

public data class TileEntityObject(
    val contentVersion: IntTag,
    val pos: IntArrayTag,
    val id: StringTag
)
