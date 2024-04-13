package com.nubasu.nuchematica.schematic.`object`

import com.nubasu.nuchematica.tag.LongTag
import com.nubasu.nuchematica.tag.StringTag

public data class MetadataObject(
    val name: StringTag?,
    val author: StringTag?,
    val date: LongTag?,
    val requiredMods: List<StringTag>
)
