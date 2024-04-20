package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.schematic.schemaobject.MetadataObject
import com.nubasu.nuchematica.schematic.schemaobject.PaletteObject
import com.nubasu.nuchematica.schematic.schemaobject.TileEntityObject

public data class SpongeSchematicFormatV1(
    val version: Int,
    val metadata: MetadataObject?,
    val width: Short,
    val height: Short,
    val length: Short,
    val offset: IntArray?,
    val paletteMax: Int,
    val palette: PaletteObject?,
    val blockData: ByteArray,
    val tileEntities: List<TileEntityObject>?
): SchematicFormat