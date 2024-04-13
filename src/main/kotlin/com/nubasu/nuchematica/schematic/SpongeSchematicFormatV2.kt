package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.schematic.`object`.BlockEntityObject
import com.nubasu.nuchematica.schematic.`object`.EntityObject
import com.nubasu.nuchematica.schematic.`object`.MetadataObject
import com.nubasu.nuchematica.schematic.`object`.PaletteObject
import com.nubasu.nuchematica.tag.ByteArrayTag
import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.IntTag

public data class SpongeSchematicFormatV2(
    val version: IntTag,
    val dataVersion: IntTag,
    val metadata: MetadataObject?,
    val width: IntTag,
    val height: IntTag,
    val length: IntTag,
    val offset: IntArrayTag?,
    val paletteMax: IntTag?,
    val palette: PaletteObject?,
    val blockData: ByteArrayTag,
    val blockEntities: List<BlockEntityObject>?,
    val entities: List<EntityObject>?,
    val biomePaletteMax: IntTag?,
    val biomePalette: PaletteObject?,
    val biomeData: ByteArrayTag?
)
