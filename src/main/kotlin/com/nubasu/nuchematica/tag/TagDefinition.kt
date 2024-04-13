package com.nubasu.nuchematica.tag

import java.nio.charset.Charset

/**
 * ref: https://minecraft.fandom.com/wiki/NBT_format
 */
public enum class TagDefinition(public val id: Int, public val tagName: String, public val tagClass: Class<out Tag>) {
    TAG_END(0, "TAG_End", EndTag::class.java),
    TAG_BYTE(1, "TAG_Byte", ByteTag::class.java),
    TAG_SHORT(2, "TAG_Short", ShortTag::class.java),
    TAG_INT(3, "TAG_Int", IntTag::class.java),
    TAG_LONG(4, "TAG_Long", LongTag::class.java),
    TAG_FLOAT(5, "TAG_Float", FloatTag::class.java),
    TAG_DOUBLE(6, "TAG_Double", DoubleTag::class.java),
    TAG_BYTE_ARRAY(7, "TAG_Byte_Array", ByteArrayTag::class.java),
    TAG_STRING(8, "TAG_String", StringTag::class.java),
    TAG_LIST(9, "TAG_List", ListTag::class.java),
    TAG_COMPOUND(10, "TAG_Compound", CompoundTag::class.java),
    TAG_INT_ARRAY(11, "TAG_Int_Array", IntArrayTag::class.java),
    TAG_LONG_ARRAY(12, "TAG_Long_Array", LongArrayTag::class.java);

    public companion object {
        public val CHARSET: Charset = Charset.forName("UTF-8")

        public fun getTagClass(id: Int): Class<out Tag> {
            for (tag in enumValues<TagDefinition>()) {
                if (id == tag.id) {
                    return tag.tagClass
                }
            }
            throw IllegalArgumentException("Unknown tag type ID of $id")
        }

        public fun getTagId(tagClass: Class<out Tag>): Int {
            for (tag in enumValues<TagDefinition>()) {
                if (tagClass == tag.tagClass) {
                    return tag.id
                }
            }
            throw IllegalArgumentException("Unknown tag class of $tagClass")
        }

        public fun getTagNameFromClass(tagClass: Class<out Tag>): String {
            for (tag in enumValues<TagDefinition>()) {
                if (tagClass == tag.tagClass) {
                    return tag.tagName
                }
            }
            throw IllegalArgumentException("Unknown tag class of $tagClass")
        }
    }

}
