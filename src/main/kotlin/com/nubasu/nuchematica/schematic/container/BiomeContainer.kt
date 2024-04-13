package com.nubasu.nuchematica.schematic.container

import com.nubasu.nuchematica.schematic.`object`.PaletteObject
import com.nubasu.nuchematica.tag.ByteArrayTag

public data class BiomeContainer(
    val palette: PaletteObject,
    val data: ByteArrayTag
)
