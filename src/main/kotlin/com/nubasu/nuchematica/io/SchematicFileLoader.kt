package com.nubasu.nuchematica.io

import com.nubasu.nuchematica.common.PlacedBlockMap
import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.schematic.reader.WorldEditSchematicReader
import it.unimi.dsi.fastutil.io.FastBufferedInputStream
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.io.DataInputStream
import java.io.FileInputStream

public object SchematicFileLoader {
    private val shemDir = Minecraft.getInstance().gameDirectory.absolutePath + "/schematics"

    public fun loadRenderBlocks(schematicFile: String) {
        val inputStream = DataInputStream(FastBufferedInputStream(FileInputStream("$shemDir/$schematicFile")))
        val compoundTag = NbtReader(inputStream).readCompoundTag()
        val clipboard = WorldEditSchematicReader.read(compoundTag)
        inputStream.close()

        val blocks = mutableMapOf<BlockPos, BlockState>()
        val blockEntities = mutableMapOf<BlockPos, BlockEntity?>()

        for (i in 0 until clipboard.position.size) {
            val block = clipboard.block[i]
            val blockEntity = clipboard.tileEntity[i]

            if (!block.isAir) {
                val pos = clipboard.position[i]
                val relativeBlockPos = BlockPos(
                    pos.x,
                    pos.y,
                    pos.z
                )
                if (blockEntity != null) {
                    blockEntities[relativeBlockPos] = clipboard.tileEntity[i]
                } else {
                    blocks[relativeBlockPos] = clipboard.block[i]
                }
                if (PlacedBlockMap.blockList.containsKey(block.block)) {
                    PlacedBlockMap.blockList[block.block] = PlacedBlockMap.blockList[block.block]!! + 1
                } else {
                    PlacedBlockMap.blockList.put(block.block, 1)
                }
            }
        }

        val minX = blocks.keys.minOfOrNull { it.x } ?: 0
        val maxX = blocks.keys.maxOfOrNull { it.x } ?: 0
        val minY = blocks.keys.minOfOrNull { it.x } ?: 0
        val maxY = blocks.keys.maxOfOrNull { it.x } ?: 0
        val minZ = blocks.keys.minOfOrNull { it.z } ?: 0
        val maxZ = blocks.keys.maxOfOrNull { it.z } ?: 0

        SchematicHolder.schematicCache = SchematicCache(blocks, blockEntities)
        SchematicHolder.schematicSize = Vector3(
            maxX - minX, maxY - minY, maxZ - minZ
        )
    }
}