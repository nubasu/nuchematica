package com.nubasu.nuchematica.gui

import com.nubasu.nuchematica.common.Vector3
import kotlinx.serialization.Serializable
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Rotation

public enum class DisplayFlag {
    ALL,
    ONLY_HEIGHT,
    UP_TO_HEIGHT,
}

public enum class DirectionSetting {
    CLOCKWISE_0,
    CLOCKWISE_90,
    CLOCKWISE_180,
    CLOCKWISE_270;
    public companion object {
        public fun DirectionSetting.toMCRotation(): Rotation {
            return when (this) {
                CLOCKWISE_0 -> Rotation.NONE
                CLOCKWISE_90 -> Rotation.CLOCKWISE_90
                CLOCKWISE_180 -> Rotation.CLOCKWISE_180
                CLOCKWISE_270 -> Rotation.COUNTERCLOCKWISE_90
            }
        }
    }
}

@Serializable
public data class RenderSettings(
    var opacity: Float = 0.5f,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var offsetZ: Int = 0,
    var rotation: DirectionSetting = DirectionSetting.CLOCKWISE_0,
    var blockReplaceMap: MutableMap<String, String?> = mutableMapOf(),
    var heightLimit: Int = 0,
    var visibleBlocks: MutableSet<String> = mutableSetOf(),
    var hiddenBlocks: MutableSet<String> = mutableSetOf(),
    var displayFlags: DisplayFlag = DisplayFlag.ALL,
    var lastLoadedSchematicFile: String = "",
    var initialPosition: Vector3 = Vector3.ZERO,
    var initialRotation: Direction = Direction.EAST,
) {
    public fun applyFrom(other: RenderSettings) {
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
        this.lastLoadedSchematicFile = other.lastLoadedSchematicFile
        this.initialPosition = other.initialPosition
        this.initialRotation = other.initialRotation
    }
}