package com.nubasu.nuchematica.io

import com.nubasu.nuchematica.schematic.Clipboard
import com.nubasu.nuchematica.tag.CompoundTag

public interface SchematicReader {
    public fun read(tag: CompoundTag): Clipboard
}