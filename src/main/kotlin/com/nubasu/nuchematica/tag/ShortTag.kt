package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class ShortTag(override val value: Short) : Tag() {

    override fun toString(): String {
        return "TAG_Short($value)"
    }
}
