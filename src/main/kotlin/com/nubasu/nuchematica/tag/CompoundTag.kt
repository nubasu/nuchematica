package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public data class CompoundTag(override var value: Map<String, Tag>) : Tag() {

    public fun containsKey(key: String): Boolean {
        return value.containsKey(key)
    }

    public fun setValue(value: Map<String, Tag>): CompoundTag {
        return CompoundTag(value)
    }

    public fun createBuilder(): CompoundTagBuilder {
        return CompoundTagBuilder(HashMap(value))
    }

    public fun getByteArray(key: String): ByteArray {
        val tag = value[key]
        return if (tag is ByteArrayTag) {
            tag.value
        } else {
            ByteArray(0)
        }
    }

    public fun getByte(key: String): Byte {
        val tag = value[key]
        return if (tag is ByteTag) {
            tag.value
        } else {
            0.toByte()
        }
    }

    public fun getDouble(key: String): Double {
        val tag = value[key]
        return if (tag is DoubleTag) {
            tag.value
        } else {
            0.0
        }
    }

    public fun asDouble(key: String): Double {
        return when (val tag = value[key]) {
            is ByteTag -> tag.value.toDouble()
            is ShortTag -> tag.value.toDouble()
            is IntTag -> tag.value.toDouble()
            is LongTag -> tag.value.toDouble()
            is FloatTag -> tag.value.toDouble()
            is DoubleTag -> tag.value
            else -> 0.0
        }
    }

    public fun getFloat(key: String): Float {
        val tag = value[key]
        return if (tag is FloatTag) {
            tag.value
        } else {
            0.0f
        }
    }

    public fun getIntArray(key: String): IntArray {
        val tag = value[key]
        return if (tag is IntArrayTag) {
            tag.value
        } else {
            IntArray(0)
        }
    }

    public fun getInt(key: String): Int {
        val tag = value[key]
        return if (tag is IntTag) {
            tag.value
        } else {
            0
        }
    }

    public fun asInt(key: String): Int {
        return when (val tag = value[key]) {
            is ByteTag -> tag.value.toInt()
            is ShortTag -> tag.value.toInt()
            is IntTag -> tag.value
            is LongTag -> tag.value.toInt()
            is FloatTag -> tag.value.toInt()
            is DoubleTag -> tag.value.toInt()
            else -> 0
        }
    }

    public fun getList(key: String): List<Tag> {
        val tag = value[key]
        return if (tag is ListTag) {
            tag.value
        } else {
            emptyList()
        }
    }

    public fun getListTag(key: String): ListTag {
        val tag = value[key]
        return if (tag is ListTag) {
            tag
        } else {
            ListTag(StringTag::class.java, emptyList())
        }
    }

    public fun <T: Tag> getList(key: String, listType: Class<T>): List<T> {
        val tag = value[key]
        return if (tag is ListTag) {
            if (tag.type == listType) {
                tag.value as List<T>
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    public fun getLongArray(key: String): LongArray {
        val tag = value[key]
        return if (tag is LongArrayTag) {
            tag.value
        } else {
            LongArray(0)
        }
    }

    public fun getLong(key: String): Long {
        val tag = value[key]
        return if (tag is LongTag) {
            tag.value
        } else {
            0L
        }
    }

    public fun asLong(key: String): Long {
        return when (val tag = value[key]) {
            is ByteTag -> tag.value.toLong()
            is ShortTag -> tag.value.toLong()
            is IntTag -> tag.value.toLong()
            is LongTag -> tag.value
            is FloatTag -> tag.value.toLong()
            is DoubleTag -> tag.value.toLong()
            else -> 0L
        }
    }

    public fun getShort(key: String): Short {
        val tag = value[key]
        return if (tag is ShortTag) {
            tag.value
        } else {
            0
        }
    }

    public fun getString(key: String): String {
        val tag = value[key]
        return if (tag is StringTag) {
            tag.value
        } else {
            ""
        }
    }

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("TAG_Compound: ${value.size} entries \n {")
        value.forEach { (k, v) -> builder.append("    $k: $v\n") }
        builder.append("}")
        return builder.toString()
    }
}

