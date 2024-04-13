package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class IntTag(override val value: Int) : Tag() {

    override fun toString(): String {
        return "TAG_Int($value)"
    }
}