package com.nubasu.nuchematica.io

import com.nubasu.nuchematica.schematic.Clipboard
import com.nubasu.nuchematica.schematic.container.BiomeContainer
import com.nubasu.nuchematica.schematic.container.BlockContainer
import com.nubasu.nuchematica.schematic.format.SpongeSchematicFormatV3
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

public object SpongeSchematicV3Reader: SchematicReader {
    override fun read(tag: CompoundTag): Clipboard {
        val head = tag.value[""] as CompoundTag

        if (!head.value.containsKey("Schematic")) {
            throw IOException("does not exist Tag \"Schematic\"")
        }
        val root = head.value["Schematic"] as CompoundTag

        val format = SpongeSchematicFormatV3(
            version = root.getInt("Version"),
            dataVersion = root.getInt("DataVersion"),
            metadata = getMetadata(root.value["Metadata"] as CompoundTag),
            width = root.getShort("Width"),
            height = root.getShort("Height"),
            length = root.getShort("Length"),
            offset = root.getIntArray("Offset"),
            blocks = getBlocks(root.value["Blocks"] as CompoundTag?),
            biomes = getBiomes(root.value["Biomes"] as CompoundTag?),
            entities = getEntities(root.value["Entities"] as CompoundTag?),
        )

        if (format.version != 3) {
            throw Exception("unsupported format V3")
        }

        val clipboard = Clipboard()

        for (x in 0 until format.width) {
            for (y in 0 until format.height) {
                for (z in 0 until format.length) {
                    val index = (y * format.length + z) * format.width + x
                    val palette = format.blocks!!.palette.palette

                    val blockMapperId = format.blocks.data[index].toInt() and 0xff
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

    private fun getMetadata(tag: CompoundTag): MetadataObject {
        return MetadataObject(
            name = tag.getString("Name"),
            author = tag.getString("Author"),
            date = tag.getLong("Date"),
            requiredMods = tag.getList("RequairedMods", StringTag::class.java).map { it.value },
        )
    }

    private fun getBlocks(tag: CompoundTag?): BlockContainer? {
        if (tag == null) {
            return null
        }

        return getPalette(tag)?.let {
            BlockContainer(
                palette = it,
                data = tag.getByteArray("Data"),
                blockEntities = (tag.getList("BlockEntities", CompoundTag::class.java)).map { getBlockEntities(it) }
            )
        }
    }

    private fun getBiomes(tag: CompoundTag?): BiomeContainer? {
        if (tag == null) {
            return null
        }
        return getPalette(tag)?.let {
            BiomeContainer(
                palette = it,
                data = tag.getByteArray("Data")
            )
        }
    }

    private fun getPalette(tag: CompoundTag): PaletteObject? {
        return tag.value["Palette"]?.let {
            PaletteObject(
                palette = (it as CompoundTag).value.map { tag -> tag.value.value as Int to tag.key }.toMap()
            )
        }
    }

    private fun getEntities(tag: CompoundTag?): EntityObject? {
        if (tag == null) {
            return null
        }
        return EntityObject(
            pos = tag.getList("Pos", DoubleTag::class.java).map { it.value },
            id = tag.getString("Id"),
            data = tag.value["Data"] as CompoundTag?
        )
    }


    private fun getBlockEntities(tag: CompoundTag): BlockEntityObject {
        return BlockEntityObject(
            pos = tag.getIntArray("Pos"),
            id = tag.getString("Id"),
            data = tag.value["Data"] as CompoundTag?
        )
    }

}