package com.nubasu.nuchematica.schematic.reader

import com.nubasu.nuchematica.utils.BlockIdMapper
import com.nubasu.nuchematica.utils.PropertyMapper
import com.nubasu.nuchematica.schematic.Clipboard
import com.nubasu.nuchematica.schematic.format.WorldEditSchematicFormat
import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.ListTag
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries
import java.io.IOException

public object WorldEditSchematicReader: SchematicReader {
    public override fun read(tag: CompoundTag): Clipboard {
        if (!tag.value.containsKey("Schematic")) {
            throw IOException("does not exist Tag \"Schematic\"")
        }
        val root = tag.value["Schematic"] as CompoundTag
        val materials = root.getString("Materials")
        if (materials != "Alpha") {
            throw Exception("unsupported format $materials")
        }

        val format = WorldEditSchematicFormat(
            name = "Schematic",
            materials = materials,
            width = root.getShort("Width"),
            height = root.getShort("Height"),
            length = root.getShort("Length"),
            weOriginX = root.getInt("WEOriginX"),       // unsupported in this mod
            weOriginY = root.getInt("WEOriginY"),       // unsupported in this mod
            weOriginZ = root.getInt("WEOriginZ"),       // unsupported in this mod
            weOffsetX = root.getInt("WEOffsetX"),       // unsupported in this mod
            weOffsetY = root.getInt("WEOffsetY"),       // unsupported in this mod
            weOffsetZ = root.getInt("WEOffsetZ"),       // unsupported in this mod
            blockIds = root.getByteArray("Blocks"),
            blockData = root.getByteArray("Data"),
            addBlocks = root.getByteArray("AddBlocks"), // unsupported in this mod
            tileEntities = (root.value["TileEntities"] as ListTag).value,
            entities = (root.value["TileEntities"] as ListTag).value
        )

        val clipboard = Clipboard()

        for (x in 0 until format.width) {
            for (y in 0 until format.height) {
                for (z in 0 until format.length) {
                    val index = (y * format.length + z) * format.width + x
                    val formatBlockId = format.blockIds[index].toInt() and 0xff
                    if (formatBlockId == 0) {
                        continue
                    }

                    var blockId = BlockIdMapper.getBlockIdFromLegacy(formatBlockId, format.blockData[index].toInt())

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
}