package com.nubasu.nuchematica.tag

import java.util.HashMap

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public class CompoundTagBuilder(private val entries: MutableMap<String, Tag> = HashMap()) {

    private fun put(key: String, value: Tag): CompoundTagBuilder {
        entries[key] = value
        return this
    }

    public fun putByteArray(key: String, value: ByteArray): CompoundTagBuilder {
        return put(key, ByteArrayTag(value))
    }

    public fun putByte(key: String, value: Byte): CompoundTagBuilder {
        return put(key, ByteTag(value))
    }

    public fun putDouble(key: String, value: Double): CompoundTagBuilder {
        return put(key, DoubleTag(value))
    }

    public fun putFloat(key: String, value: Float): CompoundTagBuilder {
        return put(key, FloatTag(value))
    }

    public fun putIntArray(key: String, value: IntArray): CompoundTagBuilder {
        return put(key, IntArrayTag(value))
    }

    public fun putInt(key: String, value: Int): CompoundTagBuilder {
        return put(key, IntTag(value))
    }

    public fun putLongArray(key: String, value: LongArray): CompoundTagBuilder {
        return put(key, LongArrayTag(value))
    }

    public fun putLong(key: String, value: Long): CompoundTagBuilder {
        return put(key, LongTag(value))
    }

    public fun putShort(key: String, value: Short): CompoundTagBuilder {
        return put(key, ShortTag(value))
    }

    public fun putString(key: String, value: String): CompoundTagBuilder {
        return put(key, StringTag(value))
    }

    public fun remove(key: String): CompoundTagBuilder {
        entries.remove(key)
        return this
    }

    public fun putAll(map: Map<String, Tag>): CompoundTagBuilder {
        for ((k, v) in map) {
            put(k, v)
        }
        return this
    }

    public fun build(): CompoundTag {
        return CompoundTag(HashMap(entries))
    }

    public companion object {
        public fun create(): CompoundTagBuilder {
            return CompoundTagBuilder()
        }
    }
}
