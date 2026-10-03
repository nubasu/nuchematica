package com.nubasu.nuchematica.tag

public data class ShortTag(override val value: Short) : Tag() {

    override fun toString(): String {
        return "TAG_Short($value)"
    }
}
