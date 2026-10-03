package com.nubasu.nuchematica.utils

public object BlockEntityMapper {
    public fun fromBlockId(blockId: String): String {
        return when (blockId) {
            in listOf(
                "minecraft:oak_sign", "minecraft:oak_wall_sign", "minecraft:spruce_sign", "minecraft:birch_sign", "minecraft:jungle_sign",
                "minecraft:acacia_sign", "minecraft:dark_oak_sign", "minecraft:crimson_sign", "minecraft:warped_sign",
                "minecraft:mangrove_sign", "minecraft:bamboo_sign", "minecraft:spruce_wall_sign", "minecraft:birch_wall_sign",
                "minecraft:jungle_wall_sign", "minecraft:acacia_wall_sign", "minecraft:dark_oak_wall_sign", "minecraft:crimson_wall_sign",
                "minecraft:warped_wall_sign", "minecraft:mangrove_wall_sign", "minecraft:bamboo_wall_sign"
            ) -> "minecraft:sign"
            in listOf(
                "minecraft:oak_hanging_sign", "minecraft:spruce_hanging_sign", "minecraft:birch_hanging_sign", "minecraft:jungle_hanging_sign",
                "minecraft:acacia_hanging_sign", "minecraft:dark_oak_hanging_sign", "minecraft:crimson_hanging_sign",
                "minecraft:warped_hanging_sign", "minecraft:mangrove_hanging_sign", "minecraft:bamboo_hanging_sign",
                "minecraft:oak_wall_hanging_sign", "minecraft:spruce_wall_hanging_sign", "minecraft:birch_wall_hanging_sign",
                "minecraft:jungle_wall_hanging_sign", "minecraft:acacia_wall_hanging_sign", "minecraft:dark_oak_wall_hanging_sign",
                "minecraft:crimson_wall_hanging_sign", "minecraft:warped_wall_hanging_sign", "minecraft:mangrove_wall_hanging_sign",
                "minecraft:bamboo_wall_hanging_sign"
            ) -> "minecraft:hanging_sign"
            in listOf("minecraft:player_head", "minecraft:player_wall_head", "minecraft:skeleton_skull", "minecraft:creeper_head", "minecraft:dragon_head",
                "minecraft:zombie_head", "minecraft:wither_skeleton_skull", "minecraft:piglin_head", "minecraft:skeleton_wall_skull",
                "minecraft:creeper_wall_head", "minecraft:dragon_wall_head", "minecraft:zombie_wall_head", "minecraft:wither_skeleton_wall_skull",
                "minecraft:piglin_wall_head") -> "minecraft:skull"
            "minecraft:spawner" -> "minecraft:mob_spawner"
            "minecraft:structure_block" -> "minecraft:structure_block"
            "minecraft:command_block" -> "minecraft:command_block"
            "minecraft:repeating_command_block" -> "minecraft:command_block"
            "minecraft:chain_command_block" -> "minecraft:command_block"
            else -> blockId
        }
    }
}