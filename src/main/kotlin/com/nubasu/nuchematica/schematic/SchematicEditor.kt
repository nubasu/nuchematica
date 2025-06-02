package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

public object SchematicEditor {
    public fun translate(offset: Vector3) {
        SchematicRenderManager.setOffset(Vec3(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
    }

    public fun rotate(rotation: DirectionSetting) {
        val cache = SchematicHolder.schematicCache

        val minX = cache.blocks.keys.minOfOrNull { it.x } ?: 0
        val maxX = cache.blocks.keys.maxOfOrNull { it.x } ?: 0
        val minZ = cache.blocks.keys.minOfOrNull { it.z } ?: 0
        val maxZ = cache.blocks.keys.maxOfOrNull { it.z } ?: 0
        val size = BlockPos(maxX - minX + 1, 0, maxZ - minZ + 1)

        SchematicRenderManager.rotate = when(rotation) {
            DirectionSetting.CLOCKWISE_0 -> 0f
            DirectionSetting.CLOCKWISE_90 -> 90f
            DirectionSetting.CLOCKWISE_180 -> 180f
            DirectionSetting.CLOCKWISE_270 -> 270f
        }
        SchematicRenderManager.rotationAxis =  when(rotation) {
            DirectionSetting.CLOCKWISE_0 -> Vec3(0.0, 0.0, 0.0)
            DirectionSetting.CLOCKWISE_90 -> Vec3( -size.x.toDouble(), 0.0, 0.0)
            DirectionSetting.CLOCKWISE_180 ->  Vec3(-size.x.toDouble(), 0.0, -size.z.toDouble())
            DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -size.z.toDouble())
        }
    }
}