package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

public data class MissingBlockChange(
    public val localPos: BlockPos,
    public val overlayChanged: Boolean,
    public val satisfiedChanged: Boolean,
    public val satisfied: Boolean,
)

object MissingBlockHolder {
    val blockPos = arrayListOf<BlockPos>()
    val airPos = arrayListOf<BlockPos>()

    public fun initialize(): Unit {
        blockPos.clear()
        airPos.clear()
        val mc = Minecraft.getInstance()
        val dummy = SchematicHolder.renderingBlocks
        val world = mc.level!!

        for ((pos, expectedState) in dummy.blocks) {
            val worldPos = SchematicRenderManager.localBlockToWorld(pos)
            val actualState = world.getBlockState(worldPos)
            if (expectedState == actualState) continue

            if (actualState.isAir) {
                airPos.add(pos)
            } else {
                blockPos.add(pos)
            }
        }
    }

    public fun placed(pos: BlockPos, actualState: BlockState): MissingBlockChange? {
        val localPos = SchematicRenderManager.worldBlockToLocal(pos)
        val dummy = SchematicHolder.renderingBlocks
        val expectedState = dummy.blocks[localPos] ?: return null
        val satisfied = expectedState == actualState
        return applyStatus(
            localPos = localPos,
            airMissing = !satisfied && actualState.isAir,
            blockMissing = !satisfied && !actualState.isAir,
        )
    }

    public fun removed(pos: BlockPos): MissingBlockChange? {
        val localPos = SchematicRenderManager.worldBlockToLocal(pos)
        val dummy = SchematicHolder.renderingBlocks
        val expectedState = dummy.blocks[localPos] ?: return null
        return applyStatus(
            localPos = localPos,
            airMissing = !expectedState.isAir,
            blockMissing = false,
        )
    }

    internal fun satisfiedPositions(): Set<BlockPos> {
        val airPositions = HashSet(airPos)
        val blockPositions = HashSet(blockPos)
        return SchematicHolder.renderingBlocks.blocks.keys.filterTo(LinkedHashSet()) { pos ->
            pos !in airPositions && pos !in blockPositions
        }
    }

    private fun applyStatus(
        localPos: BlockPos,
        airMissing: Boolean,
        blockMissing: Boolean,
    ): MissingBlockChange {
        val wasAirMissing = localPos in airPos
        val wasBlockMissing = localPos in blockPos
        val wasSatisfied = !wasAirMissing && !wasBlockMissing

        if (airMissing) {
            if (!wasAirMissing) airPos.add(localPos)
        } else {
            airPos.remove(localPos)
        }
        if (blockMissing) {
            if (!wasBlockMissing) blockPos.add(localPos)
        } else {
            blockPos.remove(localPos)
        }

        val satisfied = !airMissing && !blockMissing
        return MissingBlockChange(
            localPos = localPos,
            overlayChanged = wasAirMissing != airMissing || wasBlockMissing != blockMissing,
            satisfiedChanged = wasSatisfied != satisfied,
            satisfied = satisfied,
        )
    }
}
