package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.schematic.container.BiomeContainer
import com.nubasu.nuchematica.schematic.container.BlockContainer
import com.nubasu.nuchematica.schematic.`object`.EntityObject
import com.nubasu.nuchematica.tag.IntArrayTag
import com.nubasu.nuchematica.tag.IntTag

public data class SpongeSchematicFormatV3 (
    val version: IntTag,
    val dataVersion: IntTag,
    val width: IntTag,
    val height: IntTag,
    val length: IntTag,
    val offset: IntArrayTag?,
    val blocks: BlockContainer?,
    val biomes: BiomeContainer?,
    val entities: EntityObject?
): SchematicFormat