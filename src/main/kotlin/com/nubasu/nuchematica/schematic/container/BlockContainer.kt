package com.nubasu.nuchematica.schematic.container

import com.nubasu.nuchematica.schematic.`object`.BlockEntityObject
import com.nubasu.nuchematica.schematic.`object`.PaletteObject
import com.nubasu.nuchematica.tag.ByteArrayTag

public data class BlockContainer(
    val palette: PaletteObject,
    val data: ByteArray,
    val blockEntities: List<BlockEntityObject>?
)