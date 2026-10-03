package com.nubasu.nuchematica.tag

public data class FloatTag(override val value: Float) : Tag() {

    override fun toString(): String {
        return "TAG_Float($value)"
    }
}