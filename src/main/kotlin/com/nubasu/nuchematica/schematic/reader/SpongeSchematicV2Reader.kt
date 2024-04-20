package com.nubasu.nuchematica.schematic.reader

import com.nubasu.nuchematica.utils.PropertyMapper
import com.nubasu.nuchematica.schematic.Clipboard
import com.nubasu.nuchematica.schematic.format.SpongeSchematicFormatV2
import com.nubasu.nuchematica.schematic.schemaobject.BlockEntityObject
import com.nubasu.nuchematica.schematic.schemaobject.EntityObject
import com.nubasu.nuchematica.schematic.schemaobject.MetadataObject
import com.nubasu.nuchematica.schematic.schemaobject.PaletteObject
import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.DoubleTag
import com.nubasu.nuchematica.tag.StringTag
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries
import java.io.IOException

public object SpongeSchematicV2Reader: SchematicReader {

    override fun read(tag: CompoundTag): Clipboard {
        if (!tag.value.containsKey("Schematic")) {
            throw IOException("does not exist Tag \"Schematic\"")
        }
        val root = tag.value["Schematic"] as CompoundTag

        val format = SpongeSchematicFormatV2(
            version = root.getInt("Version"),
            dataVersion = root.getInt("DataVersion"),
            metadata = getMetadata(root.value["Metadata"] as CompoundTag?),
            width = root.getShort("Width"),
            height = root.getShort("Height"),
            length = root.getShort("Length"),
            offset = root.getIntArray("Offset"),
            paletteMax = root.getInt("PaletteMax"),
            palette = getPalette(root),
            blockData = root.getByteArray("BlockData"),
            blockEntities = getBlockEntities(tag.getList("BlockEntities", CompoundTag::class.java)),
            entities = getEntities(tag.getList("Entities", CompoundTag::class.java)),
            biomePaletteMax = root.getInt("BiomePaletteMax"),
            biomePalette = getBiomePalette(root),
            biomeData = root.getByteArray("BimomeData"),
        )

        if (format.version != 2) {
            throw Exception("unsupported format V2")
        }

        val clipboard = Clipboard()

        for (x in 0 until format.width) {
            for (y in 0 until format.height) {
                for (z in 0 until format.length) {
                    val index = (y * format.length + z) * format.width + x
                    val palette = format.palette!!.palette

                    val blockMapperId = format.blockData[index].toInt() and 0xff
                    if (blockMapperId == 0) {
                        continue
                    }

                    var blockId = palette[blockMapperId] ?: continue

                    val args = arrayListOf<String>()
                    if (blockId.contains("[")) {
                        val argsString = blockId.split("[").last().split("]").first()
                        if (argsString.contains(",")) {
                            argsString.split(",").forEach {
                                args.add(it)
                            }
                        } else {
                            args.add(argsString)
                        }
                        blockId = blockId.split("[").first()
                    }
                    val blockLocation = ResourceLocation(blockId)

                    var blockState = ForgeRegistries.BLOCKS.getValue(blockLocation)!!.defaultBlockState()
                    if (blockState.isAir) {
                        continue
                    }

                    args.forEach {
                        if (it.contains("=")) {
                            val arg = it.split("=").first()
                            val value = it.split("=").last()
                            try {
                                blockState = PropertyMapper.mapping(blockState, arg, value)
                            } catch (e: Exception) {
                                println(e)
                            }
                        }
                    }
                    clipboard.block.add(blockState)
                    clipboard.position.add(BlockPos(x, y, z))
                }
            }
        }
        return clipboard
    }

    private fun getMetadata(tag: CompoundTag?): MetadataObject? {
        if (tag == null) {
            return null
        }
        return MetadataObject(
            name = tag.getString("Name"),
            author = tag.getString("Author"),
            date = tag.getLong("Date"),
            requiredMods = tag.getList("RequairedMods", StringTag::class.java).map { it.value },
        )
    }

    private fun getPalette(tag: CompoundTag): PaletteObject? {
        return tag.value["Palette"]?.let {
            PaletteObject(
                palette = (it as CompoundTag).value.map { tag -> tag.value.value as Int to tag.key }.toMap()
            )
        }
    }

    private fun getBiomePalette(tag: CompoundTag): PaletteObject? {
        return tag.value["BiomePalette"]?.let {
            PaletteObject(
                palette = (it as CompoundTag).value.map { tag -> tag.value.value as Int to tag.key }.toMap()
            )
        }
    }

    private fun getEntities(tag: List<CompoundTag>?): List<EntityObject>? {
        if (tag == null) {
            return null
        }
        return tag.map {
            EntityObject(
                pos = it.getList("Pos", DoubleTag::class.java).map { v -> v.value },
                id = it.getString("Id"),
                data = it.value["Extra"] as CompoundTag?
            )
        }
    }


    private fun getBlockEntities(tag: List<CompoundTag>?): List<BlockEntityObject>? {
        if (tag == null) {
            return null
        }
        return tag.map {
                BlockEntityObject(
                    pos = it.getIntArray("Pos"),
                    id = it.getString("Id"),
                    data = it.value["Extra"] as CompoundTag?
                )
        }
    }
}