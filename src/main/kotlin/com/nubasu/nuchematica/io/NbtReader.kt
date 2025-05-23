package com.nubasu.nuchematica.io

import com.nubasu.nuchematica.tag.*
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException

public class NbtReader(private val input: DataInputStream): Closeable {
    public fun readCompoundTag(): CompoundTag {
        var tagId = input.readByte().toInt()
        val content: MutableMap<String, Tag> = mutableMapOf()
        while (tagId != 0) {
            val tagName = readTagName()
            val tag = parseTag(tagId)
            content[tagName] = tag

            if (input.available() == 0){
                break
            }
            tagId = input.readByte().toInt()
        }

        return CompoundTag(content)
    }

    private fun readByte(): ByteTag {
        val b = input.readByte()
        return ByteTag(b)
    }

    private fun readShort(): ShortTag {
        val s = input.readShort()
        return ShortTag(s)
    }

    private fun readInt(): IntTag {
        val i = input.readInt()
        return IntTag(i)
    }

    private fun readLong(): LongTag {
        val l = input.readLong()
        return LongTag(l)
    }

    private fun readFloat(): FloatTag {
        val f = input.readFloat()
        return FloatTag(f)
    }

    private fun readDouble(): DoubleTag {
        val d = input.readDouble()
        return DoubleTag(d)
    }

    private fun readByteArray(): ByteArrayTag {
        val length = input.readInt()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return ByteArrayTag(bytes)
    }

    private fun readString(): StringTag {
        val length = input.readShort()
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        val s = String(bytes, TagDefinition.CHARSET)
        return StringTag(s)
    }

    private fun readList(): ListTag {
        val tagId = input.readByte().toInt()
        val length = input.readInt()
        val tagList = arrayListOf<Tag>()
        for (i in 0 until length) {
            tagList.add(parseTag(tagId))
        }
        return ListTag(TagDefinition.getTagClass(tagId), tagList)
    }

    private fun readIntArray(): IntArrayTag {
        val length = input.readInt()
        val intArray = arrayListOf<Int>()
        for (i in 0 until length) {
            intArray.add(input.readInt())
        }
        return IntArrayTag(intArray.toIntArray())
    }

    private fun readLongArray(): LongArrayTag {
        val length = input.readInt()
        val longArray = arrayListOf<Long>()
        for (i in 0 until length) {
            longArray.add(input.readLong())
        }
        return LongArrayTag(longArray.toLongArray())
    }

    private fun readTagName(): String {
        val nameLength = input.readShort()
        val bytes = ByteArray(nameLength.toInt() and 0xff)
        input.readFully(bytes)
        return String(bytes, TagDefinition.CHARSET)
    }

    private fun parseTag(tagId: Int): Tag {
        return when (tagId) {
            TagDefinition.TAG_END.id -> EndTag()
            TagDefinition.TAG_BYTE.id -> readByte()
            TagDefinition.TAG_SHORT.id -> readShort()
            TagDefinition.TAG_INT.id -> readInt()
            TagDefinition.TAG_LONG.id -> readLong()
            TagDefinition.TAG_FLOAT.id -> readFloat()
            TagDefinition.TAG_DOUBLE.id -> readDouble()
            TagDefinition.TAG_BYTE_ARRAY.id -> readByteArray()
            TagDefinition.TAG_STRING.id -> readString()
            TagDefinition.TAG_LIST.id -> readList()
            TagDefinition.TAG_COMPOUND.id -> readCompoundTag()
            TagDefinition.TAG_INT_ARRAY.id -> readIntArray()
            TagDefinition.TAG_LONG_ARRAY.id -> readLongArray()
            else -> throw IOException("Invalid tag type: $tagId.")
        }
    }

    override fun close() {
        input.close()
    }
}