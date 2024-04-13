package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.schematic.`object`.PaletteObject
import com.nubasu.nuchematica.schematic.`object`.TileEntityObject
import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.IntTag
import com.nubasu.nuchematica.tag.ShortTag

public data class SpongeSchematicFormatV1 (
    val version: IntTag,
    val dataVersion: IntTag,
    val width: ShortTag,
    val height: ShortTag,
    val length: ShortTag,
    val offset: IntArrayTag?,
    val palette: PaletteObject?,
    val blockData: List<CompoundTag>,
    val tileEntities: TileEntityObject?
)