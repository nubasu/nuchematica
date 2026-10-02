package com.nubasu.nuchematica.schematic.container

import com.nubasu.nuchematica.schematic.schemaobject.PaletteObject

public data class BiomeContainer(
    val palette: PaletteObject,
    val data: ByteArray
)
