package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.gui.DisplayFlag
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.io.SchematicFileLoader
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.BlockToString.getBlockId
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import kotlin.math.floor

public object SchematicRenderManager {
    public var isRendering: Boolean = false
    private val schematicRenderer = SchematicRenderer()
    private val missingBlockRenderer = MissingBlockRender()
    public var initialPosition = Vec3.ZERO
    private var offset = Vec3.ZERO
    private var rotate = 0f
    private var rotationAxis = Vec3.ZERO
    public var initialDirection: Direction = Direction.NORTH
    private var settingsApplyPending = false
    private var ticksSinceSettingsChange = 0

    public fun getRenderBase(): Vec3 {
        return Vec3(
            initialPosition.x + offset.x,
            initialPosition.y + offset.y,
            initialPosition.z + offset.z
        )
    }

    // Missing-block state is NOT refreshed here; every caller path ends in rerender(),
    // which does it once. Refreshing here too would run the full world scan twice.
    public fun setOffset(vec3: Vec3) {
        offset = vec3
    }

    public fun rotate(pos: BlockPos): BlockPos {
        val size = SchematicHolder.schematicSize
        val p = when (initialDirection) {
            Direction.EAST -> BlockPos(0, 0, 0)
            Direction.SOUTH -> BlockPos((size.x - size.z), 0, 0)
            Direction.WEST -> BlockPos((size.x - size.z), 0, size.z - size.x)
            Direction.NORTH -> BlockPos(0, 0, size.z - size.x)
            else -> BlockPos(0, 0, 0)
        }

        return when(rotate.toInt()) {
            0   -> BlockPos( pos.x, pos.y, pos.z)
            90  -> BlockPos( pos.z + p.x, pos.y, size.x - pos.x + p.z)
            180 -> BlockPos(size.x - pos.x, pos.y, size.z - pos.z)
            270 -> BlockPos( size.z - pos.z + p.x, pos.y,  pos.x + p.z)
            else -> BlockPos(pos.x, pos.y, pos.z)
        }
    }

    public fun unrotate(pos: BlockPos): BlockPos {
        val size = SchematicHolder.schematicSize
        val p = when (initialDirection) {
            Direction.EAST -> BlockPos(0, 0, 0)
            Direction.SOUTH -> BlockPos((size.x - size.z), 0, 0)
            Direction.WEST -> BlockPos((size.x - size.z), 0, size.z - size.x)
            Direction.NORTH -> BlockPos(0, 0, size.z - size.x)
            else -> BlockPos(0, 0, 0)
        }

        return when (rotate.toInt()) {
            0   -> BlockPos(pos.x, pos.y, pos.z)
            90  -> BlockPos(size.x - pos.z + p.z, pos.y, pos.x - p.x)
            180 -> BlockPos(size.x - pos.x, pos.y, size.z - pos.z)
            270 -> BlockPos(pos.z - p.z, pos.y, size.z - pos.x + p.x)
            else -> BlockPos(pos.x, pos.y, pos.z)
        }
    }

    // See setOffset: rerender() is responsible for the missing-block refresh.
    public fun setRotation(rot: Float, axis: Vec3) {
        rotate = rot
        rotationAxis = axis
    }

    public fun initialize() {
        settingsApplyPending = false
        resetTransformToPlayer()
        isRendering = true
        applyFilterBlock()
        rerender()
    }

    public fun updateInitialPosition() {
        resetTransformToPlayer()
        rerender()
    }

    internal fun scheduleSettingsApply(): Unit {
        settingsApplyPending = true
        ticksSinceSettingsChange = 0
    }

    internal fun tickPendingSettings(): Unit {
        if (!settingsApplyPending) return
        if (++ticksSinceSettingsChange >= SETTINGS_APPLY_DELAY_TICKS) {
            flushPendingSettings()
        }
    }

    internal fun flushPendingSettings(): Unit {
        if (!settingsApplyPending) return
        settingsApplyPending = false

        val settings = RenderSettingHolder.renderSettings
        setOffset(Vec3(settings.offsetX.toDouble(), settings.offsetY.toDouble(), settings.offsetZ.toDouble()))
        applyFilterBlock()
        rerender()
    }

    // Resets offset/rotation and anchors the render base at the player's position,
    // shifted by the schematic size depending on the facing direction.
    private fun resetTransformToPlayer() {
        val player = Minecraft.getInstance().player ?: return
        val playerPos = player.position()
        val size = SchematicHolder.schematicSize
        rotationAxis = Vec3.ZERO
        offset = Vec3.ZERO
        rotate = 0f
        initialDirection = player.direction
        initialPosition = when(initialDirection) {
            Direction.EAST -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
            Direction.SOUTH -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z))
            Direction.WEST -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z - size.z))
            Direction.NORTH -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z - size.z))
            else -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
        }
    }

    public fun updateInitialPosition(direction: Direction, position: Vec3) {
        initialDirection = direction
        initialPosition = position
        rerender()
    }

    public fun rerender() {
        schematicRenderer.initialize()
        initMissingBlock()
    }

    public fun render(event: RenderLevelStageEvent) {
        if (!isRendering) return
        schematicRenderer.render(getRenderBase(), rotate, rotationAxis, event)
        missingBlockRenderer.render(getRenderBase(), rotate, rotationAxis, event)
    }

    public fun updatePlacedBlocks() {
        missingBlockRenderer.initialize()
    }

    public fun loadRenderBlocks(schematicFile: String): Boolean {
        return SchematicFileLoader.loadRenderBlocks(schematicFile)
    }

    private fun initMissingBlock() {
        MissingBlockHolder.initialize()
        missingBlockRenderer.initialize()
    }

    public fun applyFilterBlock() {
        val settings = RenderSettingHolder.renderSettings

        // heightLimit is in schematic-local Y (0 = bottom layer of the schematic).
        fun isVisible(pos: BlockPos, block: Block): Boolean {
            if (settings.hiddenBlocks.contains(getBlockId(block))) return false
            return when (settings.displayFlags) {
                DisplayFlag.ALL -> true
                DisplayFlag.UP_TO_HEIGHT -> pos.y <= settings.heightLimit
                DisplayFlag.ONLY_HEIGHT -> pos.y == settings.heightLimit
            }
        }

        val filteredBlocks = SchematicHolder.schematicCache.blocks.filter {
            isVisible(it.key, it.value.block)
        }
        val filteredEntity = SchematicHolder.schematicCache.blockEntities.filter {
            isVisible(it.key, it.value.blockState.block)
        }
        SchematicHolder.renderingBlocks = SchematicCache(filteredBlocks, filteredEntity)
    }

    private const val SETTINGS_APPLY_DELAY_TICKS = 4
}
