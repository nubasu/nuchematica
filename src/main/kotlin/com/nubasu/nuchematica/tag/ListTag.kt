package com.nubasu.nuchematica.tag

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public class ListTag(public val type: Class<out Tag>, override val value: List<Tag>) : Tag() {

    public fun setValue(list: List<Tag>): ListTag {
        return ListTag(type, list)
    }

    private fun getIfExistsAt(index: Int): Tag? {
        return if (index >= value.size) {
            null
        } else value[index]
    }

    public fun getByteArray(index: Int): ByteArray {
        val tag = getIfExistsAt(index)
        return if (tag is ByteArrayTag) {
            tag.value
        } else {
            ByteArray(0)
        }
    }

    public fun getByte(index: Int): Byte {
        val tag = getIfExistsAt(index)
        return if (tag is ByteTag) {
            tag.value
        } else {
            0.toByte()
        }
    }

    public fun getDouble(index: Int): Double {
        val tag = getIfExistsAt(index)
        return if (tag is DoubleTag) {
            tag.value
        } else {
            0.0
        }
    }

    public fun asDouble(index: Int): Double {
        return when (val tag = getIfExistsAt(index)) {
            is ByteTag -> {
                tag.value.toDouble()
            }

            is ShortTag -> {
                tag.value.toDouble()
            }

            is IntTag -> {
                tag.value.toDouble()
            }

            is LongTag -> {
                tag.value.toDouble()
            }

            is FloatTag -> {
                tag.value.toDouble()
            }

            is DoubleTag -> {
                tag.value
            }

            else -> {
                0.0
            }
        }
    }

    public fun getFloat(index: Int): Float {
        val tag = getIfExistsAt(index)
        return if (tag is FloatTag) {
            tag.value
        } else {
            0.0f
        }
    }

    public fun getIntArray(index: Int): IntArray {
        val tag = getIfExistsAt(index)
        return if (tag is IntArrayTag) {
            tag.value
        } else {
            IntArray(0)
        }
    }

    public fun getInt(index: Int): Int {
        val tag = getIfExistsAt(index)
        return if (tag is IntTag) {
            tag.value
        } else {
            0
        }
    }

    public fun asInt(index: Int): Int {
        return when (val tag = getIfExistsAt(index)) {
            is ByteTag -> {
                tag.value.toInt()
            }

            is ShortTag -> {
                tag.value.toInt()
            }

            is IntTag -> {
                tag.value
            }

            is LongTag -> {
                tag.value.toInt()
            }

            is FloatTag -> {
                tag.value.toInt()
            }

            is DoubleTag -> {
                tag.value.toInt()
            }

            else -> {
                0
            }
        }
    }

    public fun getList(index: Int): List<Tag> {
        val tag = getIfExistsAt(index)
        return if (tag is ListTag) {
            tag.value
        } else {
            emptyList()
        }
    }

    public fun getListTag(index: Int): ListTag {
        val tag = getIfExistsAt(index)
        return if (tag is ListTag) {
            tag
        } else {
            ListTag(
                StringTag::class.java,
                listOf()
            )
        }
    }

    public fun <T: Tag> getList(index: Int, listType: Class<T>): List<T> {
        val tag = getIfExistsAt(index)
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

    public fun getLong(index: Int): Long {
        val tag = getIfExistsAt(index)
        return if (tag is LongTag) {
            tag.value
        } else {
            0L
        }
    }

    public fun asLong(index: Int): Long {
        return when (val tag = getIfExistsAt(index)) {
            is ByteTag -> {
                tag.value.toLong()
            }

            is ShortTag -> {
                tag.value.toLong()
            }

            is IntTag -> {
                tag.value.toLong()
            }

            is LongTag -> {
                tag.value
            }

            is FloatTag -> {
                tag.value.toLong()
            }

            is DoubleTag -> {
                tag.value.toLong()
            }

            else -> {
                0L
            }
        }
    }

    public fun getShort(index: Int): Short {
        val tag = getIfExistsAt(index)
        return if (tag is ShortTag) {
            tag.value
        } else {
            0
        }
    }

    public fun getString(index: Int): String {
        val tag = getIfExistsAt(index)
        return if (tag is StringTag) {
            tag.value
        } else {
            ""
        }
    }

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("TAG_List: ${value.size} entries of type ${TagDefinition.getTagNameFromClass(type)} {\n")
        value.forEach { builder.append("    $it\n") }
        builder.append("}")
        return builder.toString()
    }
}