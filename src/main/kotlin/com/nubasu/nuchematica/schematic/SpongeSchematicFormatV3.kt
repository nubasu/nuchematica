package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.schematic.container.BiomeContainer
import com.nubasu.nuchematica.schematic.container.BlockContainer
import com.nubasu.nuchematica.schematic.`object`.EntityObject
import com.nubasu.nuchematica.schematic.`object`.MetadataObject
import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.IntTag
import com.nubasu.nuchematica.tag.ShortTag

public data class SpongeSchematicFormatV3 (
    val version: IntTag,
    val dataVersion: IntTag,
    val metadata: MetadataObject?,
    val width: ShortTag,
    val height: ShortTag,
    val length: ShortTag,
    val offset: IntArrayTag?,
    val blocks: BlockContainer?,
    val biomes: BiomeContainer?,
    val entities: EntityObject?
): SchematicFormat