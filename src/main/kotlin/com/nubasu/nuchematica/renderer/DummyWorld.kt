package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

public object DummyWorld : BlockAndTintGetter {
    private val blocks = SchematicHolder.renderingBlocks.blocks
    private val blockEntities = SchematicHolder.renderingBlocks.blockEntities

    private val chunkBlockMap: Map<ChunkPos, Map<BlockPos, BlockState>> =
        blocks.entries.groupBy({ ChunkPos(it.key) }, { it.key to it.value })
            .mapValues { it.value.toMap() }

    private val chunkEntityMap: Map<ChunkPos, Map<BlockPos, BlockEntity?>> =
        blockEntities.entries.groupBy({ ChunkPos(it.key) }, { it.key to it.value })
            .mapValues { it.value.toMap() }

    override fun getBlockState(pos: BlockPos): BlockState {
        return SchematicHolder.renderingBlocks.blocks[pos] ?: Blocks.AIR.defaultBlockState()
    }

    override fun getFluidState(pos: BlockPos): FluidState {
        return SchematicHolder.renderingBlocks.blocks[pos]?.fluidState ?: Fluids.EMPTY.defaultFluidState()
    }

    override fun getShade(direction: Direction, shade: Boolean): Float {
        return 1.0f
    }

    override fun getLightEngine(): LevelLightEngine {
        return Minecraft.getInstance().level!!.lightEngine
    }

    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int {
        return resolver.getColor(Minecraft.getInstance().level!!.getBiome(pos).value(), pos.x.toDouble(),
            pos.z.toDouble()
        )
    }

    override fun getBlockEntity(pos: BlockPos): BlockEntity? {
        return SchematicHolder.renderingBlocks.blockEntities[pos]
    }

    override fun getBrightness(lightLayer: LightLayer, pos: BlockPos): Int {
        return 15
    }

    override fun getMinBuildHeight(): Int {
        return Int.MIN_VALUE
    }

    override fun getHeight(): Int {
        return Int.MAX_VALUE
    }

    override fun getRawBrightness(pos: BlockPos, amount: Int): Int {
        return 15
    }

    override fun isOutsideBuildHeight(pos: BlockPos): Boolean {
        return false
    }
}