package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.tag.*

public data class WorldEditSchematicFormat(
    val name: String,
    val materials: String,
    val width: Short,
    val height: Short,
    val length: Short,
    val weOriginX: Int?,        // unsupported in this mod
    val weOriginY: Int?,        // unsupported in this mod
    val weOriginZ: Int?,        // unsupported in this mod
    val weOffsetX: Int?,        // unsupported in this mod
    val weOffsetY: Int?,        // unsupported in this mod
    val weOffsetZ: Int?,        // unsupported in this mod
    val blockIds: ByteArray,
    val blockData: ByteArray,
    val addBlocks: ByteArray?,  // unsupported in this mod
    val tileEntities: List<Tag>,
    val entities: List<Tag>
): SchematicFormat
