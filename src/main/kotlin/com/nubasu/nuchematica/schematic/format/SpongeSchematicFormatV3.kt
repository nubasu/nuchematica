package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.schematic.container.BiomeContainer
import com.nubasu.nuchematica.schematic.container.BlockContainer
import com.nubasu.nuchematica.schematic.`object`.EntityObject
import com.nubasu.nuchematica.schematic.`object`.MetadataObject

public data class SpongeSchematicFormatV3 (
    val version: Int,
    val dataVersion: Int,
    val metadata: MetadataObject?,
    val width: Short,
    val height: Short,
    val length: Short,
    val offset: IntArray?,
    val blocks: BlockContainer?,
    val biomes: BiomeContainer?,
    val entities: EntityObject?
): SchematicFormat