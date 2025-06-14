package com.nubasu.nuchematica.schematic

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

public object SchematicEditor {
    public fun applyJson(settings: RenderSettings) {
        if (settings.lastLoadedSchematicFile == "") return
        LogUtils.getLogger().info("clicked: ${settings.lastLoadedSchematicFile}")
        SchematicRenderManager.loadRenderBlocks(settings.lastLoadedSchematicFile)

        SchematicRenderManager.initialize()
        translate(Vector3(
            settings.offsetX,
            settings.offsetY,
            settings.offsetZ
        ))
        rotate(settings.rotation)
        SchematicRenderManager.updateInitialPosition(
            settings.initialRotation,
            Vec3(
                settings.initialPosition.x.toDouble(),
                settings.initialPosition.y.toDouble(),
                settings.initialPosition.z.toDouble(),
            )
        )
    }

    public fun translate(offset: Vector3) {
        SchematicRenderManager.setOffset(Vec3(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
    }

    public fun rotate(rotation: DirectionSetting) {
        val schematicSize = SchematicHolder.schematicSize
        val size = BlockPos(schematicSize.x + 1, 0, schematicSize.z + 1)

        val rot = when(rotation) {
            DirectionSetting.CLOCKWISE_0 -> 0f
            DirectionSetting.CLOCKWISE_90 -> 90f
            DirectionSetting.CLOCKWISE_180 -> 180f
            DirectionSetting.CLOCKWISE_270 -> 270f
        }

        val axis = when(SchematicRenderManager.initialDirection) {
            Direction.EAST -> {
                when(rotation) {
                    DirectionSetting.CLOCKWISE_0 -> Vec3(0.0, 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_90 -> Vec3( -size.x.toDouble(), 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_180 ->  Vec3(-size.x.toDouble(), 0.0, -size.z.toDouble())
                    DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -size.z.toDouble())
                }
            }
            Direction.SOUTH -> {
                when(rotation) {
                    DirectionSetting.CLOCKWISE_0 -> Vec3(0.0, 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_90 -> Vec3( -size.x.toDouble(), 0.0, size.x.toDouble()-size.z.toDouble())
                    DirectionSetting.CLOCKWISE_180 ->  Vec3(-size.x.toDouble(), 0.0, -size.z.toDouble())
                    DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -size.z.toDouble()-(size.x.toDouble()-size.z.toDouble()))
                }
            }
            Direction.WEST -> {
                when(rotation) {
                    DirectionSetting.CLOCKWISE_0 -> Vec3(0.0, 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_90 -> Vec3( -size.z.toDouble(), 0.0, size.x.toDouble()-size.z.toDouble())
                    DirectionSetting.CLOCKWISE_180 ->  Vec3(-size.x.toDouble(), 0.0, -size.z.toDouble())
                    DirectionSetting.CLOCKWISE_270 -> Vec3(size.z.toDouble()-size.x.toDouble(), 0.0, -size.z.toDouble()-(size.x.toDouble()-size.z.toDouble()))
                }
            }
            Direction.NORTH -> {
                when(rotation) {
                    DirectionSetting.CLOCKWISE_0 -> Vec3(0.0, 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_90 -> Vec3( -size.z.toDouble(), 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_180 ->  Vec3(-size.x.toDouble(), 0.0, -size.z.toDouble())
                    DirectionSetting.CLOCKWISE_270 -> Vec3(size.z.toDouble()-size.x.toDouble(), 0.0, -size.z.toDouble())
                }
            }
            else -> {
                when(rotation) {
                    DirectionSetting.CLOCKWISE_0 -> Vec3(0.0, 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_90 -> Vec3( -size.x.toDouble(), 0.0, 0.0)
                    DirectionSetting.CLOCKWISE_180 ->  Vec3(-size.x.toDouble(), 0.0, -size.z.toDouble())
                    DirectionSetting.CLOCKWISE_270 -> Vec3(0.0, 0.0, -size.z.toDouble())
                }
            }
        }

        SchematicRenderManager.setRotation(rot, axis)
    }
}