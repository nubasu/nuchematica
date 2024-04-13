package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class ByteArrayTag(override val value: ByteArray) : Tag() {

    override fun toString(): String {
        val hex = StringBuilder()
        for (b in value) {
            val hexDigits = Integer.toHexString(b.toInt()).uppercase()
            if (hexDigits.length == 1) {
                hex.append("0")
            }
            hex.append(hexDigits).append(" ")
        }
        return "TAG_Byte_Array($hex)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ByteArrayTag

        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }
}