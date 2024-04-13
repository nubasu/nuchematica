package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class DoubleTag(override val value: Double) : Tag() {

    override fun toString(): String {
        return "TAG_Double($value)"
    }
}