package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class StringTag(override val value: String) : Tag() {

    override fun toString(): String {
        return "TAG_String($value)"
    }
}