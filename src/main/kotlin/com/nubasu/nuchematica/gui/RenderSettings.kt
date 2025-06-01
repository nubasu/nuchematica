package com.nubasu.nuchematica.gui

import net.minecraft.world.level.block.Block

public enum class DisplayFlag {
    ALL,
    ONLY_HEIGHT,
    UP_TO_HEIGHT,
}

public enum class DirectionSetting {
    NONE,
    NORTH,
    SOUTH,
    WEST,
    EAST,
}

data class RenderSettings(
    var opacity: Float = 0.5f,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var offsetZ: Int = 0,
    var rotation: DirectionSetting = DirectionSetting.NONE,
    var blockReplaceMap: MutableMap<Block, Block?> = mutableMapOf(),
    var heightLimit: Int = 0,
    var visibleBlocks: MutableSet<Block> = mutableSetOf(),
    var hiddenBlocks: MutableSet<Block> = mutableSetOf(),
    var displayFlags: DisplayFlag = DisplayFlag.ALL
) {
    fun applyFrom(other: RenderSettings) {
        this.opacity = other.opacity
        this.offsetX = other.offsetX
        this.offsetY = other.offsetY
        this.offsetZ = other.offsetZ
        this.rotation = other.rotation
        this.blockReplaceMap = other.blockReplaceMap.toMutableMap()
        this.heightLimit = other.heightLimit
        this.visibleBlocks = other.visibleBlocks.toMutableSet()
        this.hiddenBlocks = other.hiddenBlocks.toMutableSet()
        this.displayFlags = other.displayFlags
    }
}