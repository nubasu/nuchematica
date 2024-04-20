package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.schematic.schemaobject.PaletteObject
import com.nubasu.nuchematica.schematic.schemaobject.TileEntityObject
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
): SchematicFormat