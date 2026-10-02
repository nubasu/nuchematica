package com.nubasu.nuchematica.tag

public class EndTag(override val value: Any? = null) : Tag() {

    override fun toString(): String {
        return "TAG_End"
    }
}