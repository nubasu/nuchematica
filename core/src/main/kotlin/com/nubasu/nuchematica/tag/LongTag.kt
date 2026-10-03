package com.nubasu.nuchematica.tag

public data class LongTag(override val value: Long) : Tag() {

    override fun toString(): String {
        return "TAG_Long($value)"
    }
}