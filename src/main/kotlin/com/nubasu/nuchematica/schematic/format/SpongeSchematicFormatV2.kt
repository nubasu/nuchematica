package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.schematic.`object`.BlockEntityObject
import com.nubasu.nuchematica.schematic.`object`.EntityObject
import com.nubasu.nuchematica.schematic.`object`.MetadataObject
import com.nubasu.nuchematica.schematic.`object`.PaletteObject
import com.nubasu.nuchematica.tag.ByteArrayTag
import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.IntTag
import com.nubasu.nuchematica.tag.ShortTag

public data class SpongeSchematicFormatV2(
    val version: Int,
    val dataVersion: Int,
    val metadata: MetadataObject?,
    val width: Short,
    val height: Short,
    val length: Short,
    val offset: IntArray?,
    val paletteMax: Int?,
    val palette: PaletteObject?,
    val blockData: ByteArray,
    val blockEntities: List<BlockEntityObject>?,
    val entities: List<EntityObject>?,
    val biomePaletteMax: Int?,
    val biomePalette: PaletteObject?,
    val biomeData: ByteArray?
): SchematicFormat
