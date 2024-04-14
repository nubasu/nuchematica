package com.nubasu.nuchematica.io

import com.nubasu.nuchematica.tag.*
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException

public class NbtWriter(private val output: DataOutputStream): Closeable {

    public fun writeNbt(name: String, tag: Tag) {
        writeTagName(name, tag)
        writeTagPayload(tag)
    }

    private fun writeTagName(name: String, tag: Tag) {
        val tagId = TagDefinition.getTagId(tag::class.java)
        val nameByte = name.toByteArray(TagDefinition.CHARSET)
        val length = nameByte.size
        output.writeByte(tagId)
        output.writeShort(length)
        output.write(nameByte)
        writeTagPayload(tag)
    }

    public fun writeTagPayload(tag: Tag) {
        when (val id: Int = TagDefinition.getTagId(tag.javaClass)) {
            TagDefinition.TAG_END.id -> writeEndTagPayload(tag as EndTag)
            TagDefinition.TAG_BYTE.id -> writeByteTagPayload(tag as ByteTag)
            TagDefinition.TAG_SHORT.id -> writeShortTagPayload(tag as ShortTag)
            TagDefinition.TAG_INT.id -> writeIntTagPayload(tag as IntTag)
            TagDefinition.TAG_LONG.id -> writeLongTagPayload(tag as LongTag)
            TagDefinition.TAG_FLOAT.id -> writeFloatTagPayload(tag as FloatTag)
            TagDefinition.TAG_DOUBLE.id -> writeDoubleTagPayload(tag as DoubleTag)
            TagDefinition.TAG_BYTE_ARRAY.id -> writeByteArrayTagPayload(tag as ByteArrayTag)
            TagDefinition.TAG_STRING.id -> writeStringTagPayload(tag as StringTag)
            TagDefinition.TAG_LIST.id -> writeListTagPayload(tag as ListTag)
            TagDefinition.TAG_COMPOUND.id -> writeCompoundTagPayload(tag as CompoundTag)
            TagDefinition.TAG_INT_ARRAY.id -> writeIntArrayTagPayload(tag as IntArrayTag)
            TagDefinition.TAG_LONG_ARRAY.id -> writeLongArrayTagPayload(tag as LongArrayTag)
            else -> throw IOException("Invalid tag id: $id.")
        }
    }

    private fun writeEndTagPayload(tag: EndTag) {
        output.writeByte(0)
    }

    private fun writeByteTagPayload(tag: ByteTag) {
        output.writeByte(tag.value.toInt())
    }

    private fun writeShortTagPayload(tag: ShortTag) {
        output.writeShort(tag.value.toInt())
    }

    private fun writeIntTagPayload(tag: IntTag) {
        output.writeInt(tag.value)
    }

    private fun writeFloatTagPayload(tag: FloatTag) {
        output.writeFloat(tag.value)
    }

    private fun writeLongTagPayload(tag: LongTag) {
        output.writeLong(tag.value)
    }

    private fun writeDoubleTagPayload(tag: DoubleTag) {
        output.writeDouble(tag.value)
    }

    private fun writeByteArrayTagPayload(tag: ByteArrayTag) {
        val bytes: ByteArray = tag.value
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun writeStringTagPayload(tag: StringTag) {
        val bytes: ByteArray = tag.value.toByteArray(TagDefinition.CHARSET)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun writeListTagPayload(tag: ListTag) {
        val tags: List<Tag> = tag.value
        val size = tags.size
        output.writeByte(TagDefinition.getTagId(tag.type))
        output.writeInt(size)
        for (tag1 in tags) {
            writeTagPayload(tag1)
        }
    }

    private fun writeCompoundTagPayload(tag: CompoundTag) {
        for ((key, value) in tag.value.entries) {
            writeTagName(key, value)
        }
        writeTagPayload(EndTag())
    }

    private fun writeIntArrayTagPayload(tag: IntArrayTag) {
        val data: IntArray = tag.value
        output.writeInt(data.size)
        for (aData in data) {
            output.writeInt(aData)
        }
    }

    private fun writeLongArrayTagPayload(tag: LongArrayTag) {
        val data: LongArray = tag.value
        output.writeInt(data.size)
        for (aData in data) {
            output.writeLong(aData)
        }
    }

    override fun close() {
        output.close()
    }
}