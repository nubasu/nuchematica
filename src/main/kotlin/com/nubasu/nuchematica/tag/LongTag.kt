package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class LongTag(override val value: Long) : Tag() {

    override fun toString(): String {
        return "TAG_Long($value)"
    }
}