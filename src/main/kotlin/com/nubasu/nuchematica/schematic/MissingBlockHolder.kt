package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.floor

object MissingBlockHolder {
    val blockPos = arrayListOf<BlockPos>()
    val airPos = arrayListOf<BlockPos>()

    fun initialize() {
        blockPos.clear()
        airPos.clear()
        val mc = Minecraft.getInstance()
        val dummy = SchematicHolder.renderingBlocks
        val world = mc.level!!
        val offset = SchematicRenderManager.getRenderBase()

        for ((pos, expectedState) in dummy.blocks) {
            val rotated = SchematicRenderManager.rotate(pos)
            val translatedPos = BlockPos(
                floor(rotated.x + offset.x).toInt(),
                floor(rotated.y + offset.y).toInt(),
                floor(rotated.z + offset.z).toInt()
            )

            val actualState = world.getBlockState(translatedPos)
            if (expectedState == actualState) continue

            if (actualState.isAir) {
                airPos.add(pos)
            } else {
                blockPos.add(pos)
            }
        }
    }

    fun placed(pos: BlockPos, actualState: BlockState): Boolean {
        var needUpdateInitialize = false
        val dummy = SchematicHolder.renderingBlocks
        val offset = SchematicRenderManager.getRenderBase()

        val schemaPos = BlockPos(
            pos.x - offset.x.toInt(),
            pos.y - offset.y.toInt(),
            pos.z - offset.z.toInt()
        )
        val rotated = SchematicRenderManager.unrotate(schemaPos)
        if (airPos.remove(rotated)) {
            needUpdateInitialize = true
        }

        val expectedState = dummy.blocks[rotated] ?: return needUpdateInitialize
        if (expectedState != actualState) {
            blockPos.add(rotated)
            needUpdateInitialize = true
        }
        return needUpdateInitialize
    }

    fun removed(pos: BlockPos): Boolean {
        var needUpdateInitialize = false

        val offset = SchematicRenderManager.getRenderBase()
        val dummy = SchematicHolder.renderingBlocks

        val schemaPos = BlockPos(
            pos.x - offset.x.toInt(),
            pos.y - offset.y.toInt(),
            pos.z - offset.z.toInt()
        )
        val rotated = SchematicRenderManager.unrotate(schemaPos)
        if (blockPos.remove(rotated)) {
            needUpdateInitialize = true
        }
        if (dummy.blocks[rotated] != null) {
            airPos.add(rotated)
            needUpdateInitialize = true
        }
        return needUpdateInitialize
    }
}