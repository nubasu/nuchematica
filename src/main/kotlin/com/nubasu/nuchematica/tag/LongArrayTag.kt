package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class LongArrayTag(override val value: LongArray) : Tag() {

    override fun toString(): String {
        val hex = StringBuilder()
        for (b in value) {
            val hexDigits = java.lang.Long.toHexString(b).uppercase()
            if (hexDigits.length == 1) {
                hex.append("0")
            }
            hex.append(hexDigits).append(" ")
        }
        return "TAG_Long_Array($hex)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LongArrayTag

        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }
}
