package com.nubasu.nuchematica.io

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.common.PlacedBlockMap
import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.schematic.Clipboard
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.schematic.reader.DetectedSchematicFormat
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.schematic.reader.SpongeSchematicV1Reader
import com.nubasu.nuchematica.schematic.reader.SpongeSchematicV2Reader
import com.nubasu.nuchematica.schematic.reader.SpongeSchematicV3Reader
import com.nubasu.nuchematica.schematic.reader.WorldEditSchematicReader
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.io.File

public object SchematicFileLoader {
    public fun loadRenderBlocks(schematicFile: String): Boolean {
        val file = File(Minecraft.getInstance().gameDirectory, "schematics/$schematicFile")
        val clipboard = try {
            readClipboard(file)
        } catch (e: Exception) {
            LogUtils.getLogger().error("failed to load schematic: ${file.name}", e)
            ChatSender.send("[nuchematica] failed to load ${file.name}: ${e.message}")
            return false
        }

        val blocks = mutableMapOf<BlockPos, BlockState>()
        val blockEntities = mutableMapOf<BlockPos, BlockEntity>()
        PlacedBlockMap.blockList.clear()

        for (i in 0 until clipboard.position.size) {
            val block = clipboard.block[i]
            if (block.isAir) continue

            val pos = clipboard.position[i]
            blocks[pos] = block
            clipboard.tileEntity[i]?.let { blockEntities[pos] = it }
            PlacedBlockMap.blockList[block.block] = (PlacedBlockMap.blockList[block.block] ?: 0) + 1
        }

        val minX = blocks.keys.minOfOrNull { it.x } ?: 0
        val maxX = blocks.keys.maxOfOrNull { it.x } ?: 0
        val minY = blocks.keys.minOfOrNull { it.y } ?: 0
        val maxY = blocks.keys.maxOfOrNull { it.y } ?: 0
        val minZ = blocks.keys.minOfOrNull { it.z } ?: 0
        val maxZ = blocks.keys.maxOfOrNull { it.z } ?: 0

        SchematicHolder.schematicCache = SchematicCache(blocks, blockEntities)
        SchematicHolder.schematicSize = Vector3(
            maxX - minX, maxY - minY, maxZ - minZ
        )
        if (blocks.size > LARGE_SCHEMATIC_BLOCK_THRESHOLD) {
            ChatSender.send("[nuchematica] large schematic: experimental")
        }
        return true
    }

    private const val LARGE_SCHEMATIC_BLOCK_THRESHOLD: Int = 500_000

    private fun readClipboard(file: File): Clipboard {
        val root = SchematicFormatDetector.readRootTag(file)
        val format = SchematicFormatDetector.detect(root)
            ?: throw IllegalArgumentException("unsupported schematic format")
        val reader = when (format) {
            DetectedSchematicFormat.WORLD_EDIT -> WorldEditSchematicReader
            DetectedSchematicFormat.SPONGE_V1 -> SpongeSchematicV1Reader
            DetectedSchematicFormat.SPONGE_V2 -> SpongeSchematicV2Reader
            DetectedSchematicFormat.SPONGE_V3 -> SpongeSchematicV3Reader
        }
        return reader.read(root)
    }
}
