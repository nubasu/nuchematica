package com.nubasu.nuchematica.schematic.format

import com.nubasu.nuchematica.schematic.SchematicFormat
import com.nubasu.nuchematica.tag.*

public data class WorldEditSchematicFormat(
    val name: String,
    val materials: String,
    val width: Short,
    val height: Short,
    val length: Short,
    /** Parsed for format compatibility but not applied. */
    val weOriginX: Int?,
    val weOriginY: Int?,
    val weOriginZ: Int?,
    val weOffsetX: Int?,
    val weOffsetY: Int?,
    val weOffsetZ: Int?,
    val blockIds: ByteArray,
    val blockData: ByteArray,
    /** Parsed for format compatibility but not applied. */
    val addBlocks: ByteArray?,
    val tileEntities: List<Tag>,
    val entities: List<Tag>
): SchematicFormat
