package com.nubasu.nuchematica.utils

import net.minecraft.core.Direction
import net.minecraft.core.Direction.Axis
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DropperBlock
import net.minecraft.world.level.block.PoweredRailBlock
import net.minecraft.world.level.block.RailBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.*

public object PropertyMapper {

    public fun mapping(blockState: BlockState, property: String, value: String): BlockState {
        val id = blockState.block.name.toString()

        val newState = when (property) {
            "level" -> {
                when (id) {
                    Blocks.WATER.name.toString() -> blockState.setValue(BlockStateProperties.LEVEL, value.toInt())
                    Blocks.CAULDRON.name.toString() -> blockState //blockState.setValue(BlockStateProperties.LEVEL_CAULDRON, value.toInt())
                    Blocks.COMPOSTER.name.toString() -> blockState.setValue(BlockStateProperties.LEVEL_COMPOSTER, value.toInt())
                    else -> blockState.setValue(BlockStateProperties.LEVEL, value.toInt())
                }
            }
            "north" -> {
                when {
                    isWall(id) -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.NORTH_WALL, WallSide.NONE)
                            "low" -> blockState.setValue(BlockStateProperties.NORTH_WALL, WallSide.LOW)
                            "tail" -> blockState.setValue(BlockStateProperties.NORTH_WALL, WallSide.TALL)
                            else -> blockState
                        }
                    }
                    id == Blocks.REDSTONE_WIRE.name.toString() -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.NONE)
                            "side" -> blockState.setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.SIDE)
                            "up" ->  blockState.setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.UP)
                            else -> blockState
                        }
                    }
                    else -> blockState.setValue(BlockStateProperties.NORTH, toBoolean(value))
                }
            }
            "west" -> {
                when {
                    isWall(id) -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.WEST_WALL, WallSide.NONE)
                            "low" -> blockState.setValue(BlockStateProperties.WEST_WALL, WallSide.LOW)
                            "tail" -> blockState.setValue(BlockStateProperties.WEST_WALL, WallSide.TALL)
                            else -> blockState
                        }
                    }
                    id == Blocks.REDSTONE_WIRE.name.toString() -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.NONE)
                            "side" -> blockState.setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.SIDE)
                            "up" -> blockState.setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.UP)
                            else -> blockState
                        }
                    }
                    else -> blockState.setValue(BlockStateProperties.WEST, toBoolean(value))
                }            }
            "east" -> {
                when {
                    isWall(id) -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.EAST_WALL, WallSide.NONE)
                            "low" -> blockState.setValue(BlockStateProperties.EAST_WALL, WallSide.LOW)
                            "tail" -> blockState.setValue(BlockStateProperties.EAST_WALL, WallSide.TALL)
                            else -> blockState
                        }
                    }
                    id == Blocks.REDSTONE_WIRE.name.toString() -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.NONE)
                            "side" -> blockState.setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.SIDE)
                            "up" -> blockState.setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.UP)
                            else -> blockState
                        }
                    }
                    else -> blockState.setValue(BlockStateProperties.EAST, toBoolean(value))
                }            }
            "south" -> {
                when {
                    isWall(id) -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.SOUTH_WALL, WallSide.NONE)
                            "low" -> blockState.setValue(BlockStateProperties.SOUTH_WALL, WallSide.LOW)
                            "tail" -> blockState.setValue(BlockStateProperties.SOUTH_WALL, WallSide.TALL)
                            else -> blockState
                        }
                    }
                    id == Blocks.REDSTONE_WIRE.name.toString() -> {
                        when (value) {
                            "none" -> blockState.setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.NONE)
                            "side" -> blockState.setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.SIDE)
                            "up" -> blockState.setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.UP)
                            else -> blockState
                        }
                    }
                    else -> blockState.setValue(BlockStateProperties.SOUTH, toBoolean(value))
                }            }
            "up" ->     blockState.setValue(BlockStateProperties.UP, toBoolean(value))
            "down" ->   blockState.setValue(BlockStateProperties.DOWN, toBoolean(value))
            "disarmed" -> blockState.setValue(BlockStateProperties.DISARMED, toBoolean(value))
            "powered" -> blockState.setValue(BlockStateProperties.POWERED, toBoolean(value))
            "axis" -> {
                when (value) {
                    "x" -> blockState.setValue(BlockStateProperties.AXIS, Axis.X)
                    "y" -> blockState.setValue(BlockStateProperties.AXIS, Axis.Y)
                    "z" -> blockState.setValue(BlockStateProperties.AXIS, Axis.Z)
                    else -> blockState
                }
            }
            "short" -> blockState.setValue(BlockStateProperties.SHORT, toBoolean(value))
            "conditional" -> blockState.setValue(BlockStateProperties.CONDITIONAL, toBoolean(value))
            "moisture" -> blockState.setValue(BlockStateProperties.MOISTURE, value.toInt())
            "face" -> {
                when(value) {
                    "ceiling" -> blockState.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING)
                    "wall" -> blockState.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                    "floor" -> blockState.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    else -> blockState
                }
            }
            "hinge" -> {
                when (value) {
                    "right" -> blockState.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                    "left" -> blockState.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                    else -> blockState
                }
            }
            "locked" -> blockState.setValue(BlockStateProperties.LOCKED, toBoolean(value))
            "triggered" -> blockState.setValue(BlockStateProperties.TRIGGERED, toBoolean(value))
            "bites" -> blockState.setValue(BlockStateProperties.BITES, value.toInt())
            "mode" -> {
                when(value) {
                    "save" -> blockState.setValue(BlockStateProperties.STRUCTUREBLOCK_MODE, StructureMode.SAVE)
                    "load" -> blockState.setValue(BlockStateProperties.STRUCTUREBLOCK_MODE,  StructureMode.LOAD)
                    else -> blockState
                }
            }
            "persistent" -> blockState.setValue(BlockStateProperties.PERSISTENT, toBoolean(value))
            "in_wall" -> blockState.setValue(BlockStateProperties.IN_WALL, toBoolean(value))
            "distance" -> blockState.setValue(BlockStateProperties.DISTANCE, value.toInt())
            "occupied" -> blockState.setValue(BlockStateProperties.OCCUPIED, toBoolean(value))
            "stage" ->blockState.setValue(BlockStateProperties.STAGE, value.toInt())
            "enabled" -> blockState.setValue(BlockStateProperties.ENABLED, toBoolean(value))
            "part" -> {
                when (value) {
                    "foot" -> blockState.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                    "head" -> blockState.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                    else -> blockState
                }
            }
            "has_record" -> blockState.setValue(BlockStateProperties.HAS_RECORD, toBoolean(value))
            "extended" ->blockState.setValue(BlockStateProperties.EXTENDED, toBoolean(value))
            "open" -> blockState.setValue(BlockStateProperties.OPEN, toBoolean(value))
            "snowy" -> blockState.setValue(BlockStateProperties.SNOWY, toBoolean(value))
            "power" -> blockState.setValue(BlockStateProperties.POWER, value.toInt())
            "has_bottle_0" -> blockState.setValue(BlockStateProperties.HAS_BOTTLE_0, toBoolean(value))
            "lit" -> blockState.setValue(BlockStateProperties.LIT, toBoolean(value))
            "half" -> {
                when (value) {
                    "top" -> blockState.setValue(BlockStateProperties.HALF, Half.TOP)
                    "bottom" -> blockState.setValue(BlockStateProperties.HALF, Half.BOTTOM)
                    else -> blockState
                }
            }
            "type" -> {
                when (value) {
                    "single" -> blockState.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE)
                    "left" -> blockState.setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)
                    "right" -> blockState.setValue(BlockStateProperties.CHEST_TYPE, ChestType.RIGHT)
                    "double" -> blockState.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
                    "top" -> blockState.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                    "bottom" -> blockState.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
                    "normal" -> blockState.setValue(BlockStateProperties.PISTON_TYPE, PistonType.DEFAULT)
                    "sticky" -> blockState.setValue(BlockStateProperties.PISTON_TYPE, PistonType.STICKY)
                    else -> blockState
                }
            }
            "delay" -> blockState.setValue(BlockStateProperties.DELAY, value.toInt())
            "inverted" -> blockState.setValue(BlockStateProperties.INVERTED, toBoolean(value))
            "eye" -> blockState.setValue(BlockStateProperties.EYE, toBoolean(value))
            "rotation" ->blockState.setValue(BlockStateProperties.ROTATION_16, value.toInt())
            "age" -> {
                when (id) {
                    in age1List -> blockState.setValue(BlockStateProperties.AGE_1, value.toInt())
                    in age2List -> blockState.setValue(BlockStateProperties.AGE_2, value.toInt())
                    in age3List -> blockState.setValue(BlockStateProperties.AGE_3, value.toInt())
                    in age4List -> blockState.setValue(BlockStateProperties.AGE_4, value.toInt())
                    in age7List -> blockState.setValue(BlockStateProperties.AGE_7, value.toInt())
                    in age15List -> blockState.setValue(BlockStateProperties.AGE_15, value.toInt())
                    in age25List -> blockState.setValue(BlockStateProperties.AGE_25, value.toInt())
                    else -> blockState
                }
            }
            "shape" -> {
                if (id !in railList) { // TODO: fix here
                    when (value) {
                        "north_south" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_SOUTH)
                        "east_west" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST)
                        "north_east" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_EAST)
                        "north_west" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_WEST)
                        "south_east" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.SOUTH_EAST)
                        "south_west" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.SOUTH_WEST)
                        "ascending_east" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.ASCENDING_EAST)
                        "ascending_west" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.ASCENDING_WEST)
                        "ascending_south" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.ASCENDING_SOUTH)
                        "ascending_north" -> blockState.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.ASCENDING_NORTH)
                        "straight" -> blockState.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
                        "outer_right" -> blockState.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_RIGHT)
                        "outer_left" -> blockState.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_LEFT)
                        "inner_left" -> blockState.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_RIGHT)
                        "inner_right" -> blockState.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT)
                        else -> blockState
                    }
                } else {
                    blockState
                }
            }
            "attached" -> blockState.setValue(BlockStateProperties.ATTACHED, toBoolean(value))
            "has_bottle_1" -> blockState.setValue(BlockStateProperties.HAS_BOTTLE_1, toBoolean(value))
            "facing" -> {
                when (id) {
                    in pistonList, in dispenserList-> {
                        when (value) {
                            "north" -> blockState.setValue(BlockStateProperties.FACING, Direction.NORTH)
                            "south" -> blockState.setValue(BlockStateProperties.FACING, Direction.SOUTH)
                            "west" -> blockState.setValue(BlockStateProperties.FACING, Direction.WEST)
                            "east" -> blockState.setValue(BlockStateProperties.FACING, Direction.EAST)
                            "up" -> blockState.setValue(BlockStateProperties.FACING, Direction.UP)
                            "down" -> blockState.setValue(BlockStateProperties.FACING, Direction.DOWN)
                            else -> blockState
                        }
                    }
                    else -> {
                        if (id == Blocks.HOPPER.name.toString()) {
                            when (value) {
                                "north" -> blockState.setValue(BlockStateProperties.FACING_HOPPER, Direction.NORTH)
                                "south" -> blockState.setValue(BlockStateProperties.FACING_HOPPER, Direction.SOUTH)
                                "west" -> blockState.setValue(BlockStateProperties.FACING_HOPPER, Direction.WEST)
                                "east" -> blockState.setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST)
                                "up" -> blockState.setValue(BlockStateProperties.FACING_HOPPER, Direction.UP)
                                "down" -> blockState.setValue(BlockStateProperties.FACING_HOPPER, Direction.DOWN)
                                else -> blockState
                            }
                        } else {
                            when (value) {
                                "north" -> blockState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                                "south" -> blockState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                                "west" -> blockState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                                "east" -> blockState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                                else -> blockState
                            }
                        }
                    }
                }
            }
            "layers" -> blockState.setValue(BlockStateProperties.LAYERS, value.toInt())
            "unstable" -> blockState.setValue(BlockStateProperties.UNSTABLE, toBoolean(value))
            "has_bottle_2" -> blockState.setValue(BlockStateProperties.HAS_BOTTLE_2, toBoolean(value))
            "waterlogged" -> blockState.setValue(BlockStateProperties.WATERLOGGED, toBoolean(value))
            else -> blockState
        }

        return newState
    }

    private fun isWall(blockName: String): Boolean {
        return blockName in wallList
    }

    private val pistonList = listOf(
        Blocks.PISTON.name.toString(),
        Blocks.PISTON_HEAD.name.toString(),
        Blocks.STICKY_PISTON.name.toString(),
        Blocks.MOVING_PISTON.name.toString()
    )

    private val age1List = listOf(
        Blocks.BAMBOO.name.toString()
    )

    private val age2List = listOf(
        Blocks.COCOA.name.toString()
    )

    private val age3List = listOf(
        Blocks.MANGROVE_ROOTS.name.toString(),
        Blocks.FROSTED_ICE.name.toString()
    )

    private val age4List = listOf(
        Blocks.BEETROOTS.name.toString(),
        Blocks.SWEET_BERRY_BUSH.name.toString(),
        Blocks.NETHER_WART.name.toString(),

    )

    private val age5List = listOf(
        Blocks.CHORUS_FLOWER.name.toString()
    )

    private val age7List = listOf(
        Blocks.WHEAT.name.toString(),
        Blocks.PUMPKIN_STEM.name.toString(),
        Blocks.MELON_STEM.name.toString(),
        Blocks.CARROTS.name.toString(),
        Blocks.POTATOES.name.toString(),

    )

    private val age15List = listOf(
        Blocks.FIRE.name.toString(),
        Blocks.SOUL_FIRE.name.toString(),
        Blocks.CACTUS.name.toString(),
        Blocks.SUGAR_CANE.name.toString(),
    )

    private val age25List = listOf(
        Blocks.KELP.name.toString(),
        Blocks.WEEPING_VINES.name.toString(),
        Blocks.WEEPING_VINES.name.toString(),
        Blocks.CAVE_VINES.name.toString(),
    )

    private val wallList = listOf(
        Blocks.ANDESITE_WALL.name.toString(),
        Blocks.BLACKSTONE_WALL.name.toString(),
        Blocks.BRICK_WALL.name.toString(),
        Blocks.COBBLESTONE_WALL.name.toString(),
        Blocks.COBBLED_DEEPSLATE_WALL.name.toString(),
        Blocks.DEEPSLATE_BRICK_WALL.name.toString(),
        Blocks.DEEPSLATE_TILE_WALL.name.toString(),
        Blocks.DIORITE_WALL.name.toString(),
        Blocks.END_STONE_BRICK_WALL.name.toString(),
        Blocks.GRANITE_WALL.name.toString(),
        Blocks.MOSSY_COBBLESTONE_WALL.name.toString(),
        Blocks.MOSSY_STONE_BRICK_WALL.name.toString(),
        Blocks.MUD_BRICK_WALL.name.toString(),
        Blocks.NETHER_BRICK_WALL.name.toString(),
        Blocks.POLISHED_BLACKSTONE_BRICK_WALL.name.toString(),
        Blocks.POLISHED_BLACKSTONE_WALL.name.toString(),
        Blocks.POLISHED_DEEPSLATE_WALL.name.toString(),
        Blocks.PRISMARINE_WALL.name.toString(),
        Blocks.RED_NETHER_BRICK_WALL.name.toString(),
        Blocks.RED_SANDSTONE_WALL.name.toString(),
        Blocks.SANDSTONE_WALL.name.toString(),
        Blocks.STONE_BRICK_WALL.name.toString(),
    )

    private val railList = listOf(
//        Blocks.RAIL.name.toString(),
        Blocks.POWERED_RAIL.name.toString(),
        Blocks.ACTIVATOR_RAIL.name.toString(),
        Blocks.DETECTOR_RAIL.name.toString(),
    )

    private val dispenserList = listOf(
        Blocks.DISPENSER.name.toString(),
        Blocks.DROPPER.name.toString()
    )

    private fun toBoolean(value: String): Boolean {
        return value == "true"
    }
}