package com.nubasu.nuchematica.tag

public data class DoubleTag(override val value: Double) : Tag() {

    override fun toString(): String {
        return "TAG_Double($value)"
    }
}