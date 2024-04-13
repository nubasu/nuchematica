package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class ByteTag(override val value: Byte) : Tag() {

    override fun toString(): String {
        return "TAG_Byte($value)"
    }
}
