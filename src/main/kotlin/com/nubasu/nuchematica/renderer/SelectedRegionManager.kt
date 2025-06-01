package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.common.SelectedRegion
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.schematic.Clipboard
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.server.ServerLifecycleHooks

public object SelectedRegionManager {
    private val mc = Minecraft.getInstance()
    private val regionRenderer = SelectedRegionRenderer()
    public var isRendering: Boolean = false
    public var selectedRegion: SelectedRegion = SelectedRegion(Vector3.ONE, Vector3.ONE)

    public fun renderLine(event: RenderLevelStageEvent) {
        if (isRendering) {
            regionRenderer.renderSelectedRegion(selectedRegion, event.poseStack, event.projectionMatrix)
        }
    }

    public fun setFirstPosition(position: Vec3): SelectedRegion {
        selectedRegion = SelectedRegion(position.toVector3(), selectedRegion.pos2)
        return selectedRegion
    }

    public fun setSecondPosition(position: Vec3): SelectedRegion {
        selectedRegion = SelectedRegion(selectedRegion.pos1, position.toVector3())
        return selectedRegion
    }

    public fun save() {
    }

    public fun getSelectedRegionBlocks(): List<BlockState> {
        val maximumPoint = selectedRegion.maximumPoint
        val minimumPoint = selectedRegion.minimumPoint

        val blocks: ArrayList<BlockState> = arrayListOf()

        for (x in minimumPoint.x until maximumPoint.x) {
            for (y in minimumPoint.y until maximumPoint.y) {
                for (z in minimumPoint.z until maximumPoint.z) {
                    blocks.add(getBlock(Vector3(x, y, z)))
                }
            }
        }
        return blocks
    }

    public fun place(clipboard: Clipboard) {
        for (i in 0 until clipboard.position.size) {
            val block = clipboard.block[i]
            val position = clipboard.position[i]
            ServerLifecycleHooks.getCurrentServer().getLevel(Level.OVERWORLD)!!.setBlockAndUpdate(position, block)
        }
    }

    private fun getBlock(pos: Vector3): BlockState {
        val blockPos = BlockPos(pos.x, pos.y, pos.z)
        return if (mc.level!!.isLoaded(blockPos)) {
            mc.level!!.getBlockState(blockPos)
        } else {
            Blocks.AIR.defaultBlockState()
        }
    }

    private fun Vec3.toVector3(): Vector3 {
        return Vector3(this.x, this.y, this.z)
    }
}