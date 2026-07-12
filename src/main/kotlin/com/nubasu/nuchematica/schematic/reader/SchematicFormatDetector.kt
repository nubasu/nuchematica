package com.nubasu.nuchematica.schematic.reader

import com.nubasu.nuchematica.io.NbtReader
import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.IntTag
import com.nubasu.nuchematica.tag.StringTag
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

public enum class DetectedSchematicFormat {
    WORLD_EDIT,
    SPONGE_V1,
    SPONGE_V2,
    SPONGE_V3,
}

public object SchematicFormatDetector {
    private const val GZIP_MAGIC_1 = 0x1f
    private const val GZIP_MAGIC_2 = 0x8b

    public fun readRootTag(file: File): CompoundTag {
        val stream = BufferedInputStream(FileInputStream(file))
        stream.mark(2)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        val input = if (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2) GZIPInputStream(stream) else stream
        return DataInputStream(input).use { NbtReader(it).readCompoundTag() }
    }

    public fun detect(tag: CompoundTag): DetectedSchematicFormat? {
        val direct = tag.value["Schematic"] as? CompoundTag
        if (direct != null) {
            if ((direct.value["Materials"] as? StringTag)?.value == "Alpha") {
                return DetectedSchematicFormat.WORLD_EDIT
            }
            when ((direct.value["Version"] as? IntTag)?.value) {
                1 -> return DetectedSchematicFormat.SPONGE_V1
                2 -> return DetectedSchematicFormat.SPONGE_V2
            }
        }
        val nested = (tag.value[""] as? CompoundTag)?.value?.get("Schematic") as? CompoundTag
        if ((nested?.value?.get("Version") as? IntTag)?.value == 3) {
            return DetectedSchematicFormat.SPONGE_V3
        }
        return null
    }
}
