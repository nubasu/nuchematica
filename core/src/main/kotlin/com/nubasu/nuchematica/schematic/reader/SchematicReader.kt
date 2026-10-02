package com.nubasu.nuchematica.schematic.reader

import com.nubasu.nuchematica.schematic.Clipboard
import com.nubasu.nuchematica.tag.CompoundTag

public interface SchematicReader {
    public fun read(tag: CompoundTag): Clipboard
}