package com.nubasu.nuchematica.schematic.`object`

public data class MetadataObject(
    val name: String,
    val author: String?,
    val date: Long?,
    val requiredMods: List<String>
)
