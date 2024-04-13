package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public class EndTag(override val value: Any? = null) : Tag() {

    override fun toString(): String {
        return "TAG_End"
    }
}