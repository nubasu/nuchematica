package com.nubasu.nuchematica.tag

public data class StringTag(override val value: String) : Tag() {

    override fun toString(): String {
        return "TAG_String($value)"
    }
}