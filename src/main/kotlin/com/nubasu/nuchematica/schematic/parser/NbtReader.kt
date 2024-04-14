package com.nubasu.nuchematica.schematic.parser

import com.nubasu.nuchematica.tag.*
import java.io.DataInputStream
import java.io.IOException

public class NbtReader(private val input: DataInputStream) {
    public fun readByte(): ByteTag {
        val b = input.readByte()
        println("readByte: $b")
        return ByteTag(b)
    }

    public fun readShort(): ShortTag {
        val s = input.readShort()
        println("readShort: $s")
        return ShortTag(s)
    }

    public fun readInt(): IntTag {
        val i = input.readInt()
        println("readInt: $i")
        return IntTag(i)
    }

    public fun readLong(): LongTag {
        val l = input.readLong()
        println("readLong: $l")
        return LongTag(l)
    }

    public fun readFloat(): FloatTag {
        val f = input.readFloat()
        println("readFloat: $f")
        return FloatTag(f)
    }

    public fun readDouble(): DoubleTag {
        val d = input.readDouble()
        println("readDouble: $d")
        return DoubleTag(d)
    }

    public fun readByteArray(): ByteArrayTag {
        println("readByteArray")
        val length = input.readInt()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return ByteArrayTag(bytes)
    }

    public fun readString(): StringTag {
        val length = input.readShort()
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        val s = String(bytes, TagDefinition.CHARSET)
        println("readString: $s")
        return StringTag(s)
    }

    public fun readList(): ListTag {
        println("readList")
        val tagId = input.readByte().toInt()
        val length = input.readInt()
        val tagList = arrayListOf<Tag>()
        for (i in 0 until length) {
            parseTag(tagId)
        }
        return ListTag(TagDefinition.getTagClass(tagId), tagList)
    }

    public fun readCompoundTag(): CompoundTag {
        println("readCompoundTag")
        var tagId = input.readByte().toInt()
        val content: MutableMap<String, Tag> = mutableMapOf()
        while (tagId != 0) {
            println("tag id: $tagId")
            val nameLength = input.readShort()
            val bytes = ByteArray(nameLength.toInt())
            input.readFully(bytes)
            val tagName = String(bytes, TagDefinition.CHARSET)
            println("tag name: $tagName")

            val tag = parseTag(tagId)
            content[tagName] = tag

            if (input.available() == 0){
                break
            }
            tagId = input.readByte().toInt()
        }

        return CompoundTag(content)
    }

    public fun readIntArray(): IntArrayTag {
        println("readIntArray")
        val length = input.readInt()
        val intArray = arrayListOf<Int>()
        for (i in 0 until length) {
            intArray.add(input.readInt())
        }
        return IntArrayTag(intArray.toIntArray())
    }

    public fun readLongArray(): LongArrayTag {
        println("readLongArray")
        val length = input.readInt()
        val longArray = arrayListOf<Long>()
        for (i in 0 until length) {
            longArray.add(input.readLong())
        }
        return LongArrayTag(longArray.toLongArray())
    }

    private fun parseTag(tagId: Int): Tag {
        return when (tagId) {
            TagDefinition.TAG_END.id -> {
                println("tag end")
                EndTag()
            }
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
}