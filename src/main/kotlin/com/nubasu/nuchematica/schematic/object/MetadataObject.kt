package com.nubasu.nuchematica.schematic.`object`

import com.nubasu.nuchematica.tag.LongTag
import com.nubasu.nuchematica.tag.StringTag

public data class MetadataObject(
    val name: String,
    val author: String?,
    val date: Long?,
    val requiredMods: List<String>
)
