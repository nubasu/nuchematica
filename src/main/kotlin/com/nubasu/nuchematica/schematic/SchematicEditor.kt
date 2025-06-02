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
        val schematicSize = SchematicHolder.schematicSize
        val size = BlockPos(schematicSize.x + 1, 0, schematicSize.z + 1)

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