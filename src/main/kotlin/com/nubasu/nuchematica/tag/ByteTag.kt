package com.nubasu.nuchematica.tag

public data class ByteTag(override val value: Byte) : Tag() {

    override fun toString(): String {
        return "TAG_Byte($value)"
    }
}
