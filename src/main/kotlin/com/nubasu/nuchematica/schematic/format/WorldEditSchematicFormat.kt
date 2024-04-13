package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.tag.*

public data class WorldEditSchematicFormat(
    val name: StringTag,
    val materials: StringTag,
    val width: ShortTag,
    val height: ShortTag,
    val length: ShortTag,
    val weOriginX: IntTag?,
    val weOriginY: IntTag?,
    val weOriginZ: IntTag?,
    val weOffsetX: IntTag?,
    val weOffsetY: IntTag?,
    val weOffsetZ: IntTag?,
    val blocks: ByteArrayTag,
    val data: ByteArrayTag,
    val addBlocks: ByteArrayTag?,
    val tileEntities: CompoundTag?,
    val entities: CompoundTag?
): SchematicFormat
