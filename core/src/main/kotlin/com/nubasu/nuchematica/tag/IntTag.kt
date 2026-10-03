package com.nubasu.nuchematica.tag

public data class IntTag(override val value: Int) : Tag() {

    override fun toString(): String {
        return "TAG_Int($value)"
    }
}