package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.tag.*

data class SchematicaSchematicFormat(
    val materials: StringTag,
    val icon: CompoundTag,
    val width: ShortTag,
    val height: ShortTag,
    val length: ShortTag,
    val blocks: ByteArrayTag,
    val data: ByteArrayTag,
    val addBlocks: ByteArrayTag?,
    val entities: ListTag,
    val tileEntities: ListTag,
    val mappingSchematic: CompoundTag,
    val extendedMetadata: CompoundTag?
): SchematicFormat
