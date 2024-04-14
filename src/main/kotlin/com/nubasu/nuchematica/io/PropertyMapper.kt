package com.nubasu.nuchematica.io

import net.minecraft.core.Direction
import net.minecraft.core.Direction.Axis
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.*

public object PropertyMapper {

    public fun <T : Comparable<T>?> mapping(blockState: BlockState, property: String, value: T): BlockState {
        when (property) {
            "level" ->  blockState.fluidState.setValue(BlockStateProperties.LEVEL, value as Int)
            "north" ->  blockState.setValue(BlockStateProperties.NORTH, value as Boolean)
            "west" ->   blockState.setValue(BlockStateProperties.WEST, value as Boolean)
            "east" ->   blockState.setValue(BlockStateProperties.EAST, value as Boolean)
            "south" ->  blockState.setValue(BlockStateProperties.SOUTH, value as Boolean)
            "up" ->     blockState.setValue(BlockStateProperties.UP, value as Boolean)
            "down" ->   blockState.setValue(BlockStateProperties.DOWN, value as Boolean)
            "disarmed" -> blockState.setValue(BlockStateProperties.DISARMED, value as Boolean)
            "powered" -> blockState.setValue(BlockStateProperties.POWERED, value as Boolean)
            "axis" -> blockState.setValue(BlockStateProperties.AXIS, value as Axis)
            "short" -> blockState.setValue(BlockStateProperties.SHORT, value as Boolean)
            "conditional" -> blockState.setValue(BlockStateProperties.CONDITIONAL, value as Boolean)
            "moisture" -> blockState.setValue(BlockStateProperties.MOISTURE, value as Int)
            "face" -> blockState.setValue(BlockStateProperties.ATTACH_FACE, value as AttachFace)
            "hinge" -> blockState.setValue(BlockStateProperties.DOOR_HINGE, value as DoorHingeSide)
            "locked" -> blockState.setValue(BlockStateProperties.LOCKED, value as Boolean)
            "triggered" -> blockState.setValue(BlockStateProperties.TRIGGERED, value as Boolean)
            "bites" -> blockState.setValue(BlockStateProperties.BITES, value as Int)
            "mode" -> blockState.setValue(BlockStateProperties.STRUCTUREBLOCK_MODE, value as StructureMode)
            "persistent" -> blockState.setValue(BlockStateProperties.PERSISTENT, value as Boolean)
            "in_wall" -> blockState.setValue(BlockStateProperties.IN_WALL, value as Boolean)
            "distance" -> blockState.setValue(BlockStateProperties.DISTANCE, value as Int)
            "occupied" -> blockState.setValue(BlockStateProperties.OCCUPIED, value as Boolean)
            "stage" ->blockState.setValue(BlockStateProperties.STAGE, value as Int)
            "enabled" -> blockState.setValue(BlockStateProperties.ENABLED, value as Boolean)
            "part" -> blockState.setValue(BlockStateProperties.BED_PART, value as BedPart)
            "has_record" -> blockState.setValue(BlockStateProperties.HAS_RECORD, value as Boolean)
            "extended" ->blockState.setValue(BlockStateProperties.EXTENDED, value as Boolean)
            "open" -> blockState.setValue(BlockStateProperties.OPEN, value as Boolean)
            "snowy" -> blockState.setValue(BlockStateProperties.SNOWY, value as Boolean)
            "power" -> blockState.setValue(BlockStateProperties.POWER, value as Int)
            "has_bottle_0" -> blockState.setValue(BlockStateProperties.HAS_BOTTLE_0, value as Boolean)
            "lit" -> blockState.setValue(BlockStateProperties.LIT, value as Boolean)
            "half" -> blockState.setValue(BlockStateProperties.HALF, value as Half)
            "type" -> blockState.setValue(BlockStateProperties.CHEST_TYPE, value as ChestType)
            "delay" -> blockState.setValue(BlockStateProperties.DELAY, value as Int)
            "inverted" -> blockState.setValue(BlockStateProperties.INVERTED, value as Boolean)
            "eye" -> blockState.setValue(BlockStateProperties.EYE, value as Boolean)
            "rotation" ->blockState.setValue(BlockStateProperties.ROTATION_16, value as Int)
            "age" -> blockState.setValue(IntegerProperty.create("age", 0, value as Int), value as Int)
            "shape" -> blockState.setValue(BlockStateProperties.STAIRS_SHAPE, value as StairsShape)
            "attached" -> blockState.setValue(BlockStateProperties.ATTACHED, value as Boolean)
            "has_bottle_1" -> blockState.setValue(BlockStateProperties.HAS_BOTTLE_1, value as Boolean)
            "facing" -> blockState.setValue(BlockStateProperties.FACING, value as Direction)
            "layers" -> blockState.setValue(BlockStateProperties.LAYERS, value as Int)
            "unstable" -> blockState.setValue(BlockStateProperties.UNSTABLE, value as Boolean)
            "has_bottle_2" -> blockState.setValue(BlockStateProperties.HAS_BOTTLE_2, value as Boolean)
        }

        return blockState
    }
}