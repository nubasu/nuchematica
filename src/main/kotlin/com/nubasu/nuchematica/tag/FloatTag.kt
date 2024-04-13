package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class FloatTag(override val value: Float) : Tag() {

    override fun toString(): String {
        return "TAG_Float($value)"
    }
}