package com.nubasu.nuchematica.schematic.container

import com.nubasu.nuchematica.schematic.schemaobject.BlockEntityObject
import com.nubasu.nuchematica.schematic.schemaobject.PaletteObject

public data class BlockContainer(
    val palette: PaletteObject,
    val data: ByteArray,
    val blockEntities: List<BlockEntityObject>?
)