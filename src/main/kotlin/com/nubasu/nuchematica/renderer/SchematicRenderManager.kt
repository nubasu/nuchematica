package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.io.SchematicFileLoader
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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

    public fun getRenderBase(): Vec3 {
        return Vec3(
            initialPosition.x + offset.x,
            initialPosition.y + offset.y,
            initialPosition.z + offset.z
        )
    }

    public fun setOffset(vec3: Vec3) {
        offset = vec3
        initMissingBlock()
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

    public fun setRotation(rot: Float, axis: Vec3) {
        rotate = rot
        rotationAxis = axis
        initMissingBlock()
    }

    public fun initialize() {
        val playerPos = Minecraft.getInstance().player!!.position()
        val size = SchematicHolder.schematicSize
        rotationAxis = Vec3.ZERO
        offset = Vec3.ZERO
        rotate = 0f
        initialDirection = Minecraft.getInstance().player!!.direction
        initialPosition = when(initialDirection) {
            Direction.EAST -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z)) // East
            Direction.SOUTH -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z)) // South
            Direction.WEST -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z - size.z)) // West
            Direction.NORTH -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z - size.z)) // North
            else -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
        }
        isRendering = true
        rerender()
    }

    public fun updateInitialPosition() {
        val playerPos = Minecraft.getInstance().player!!.position()
        val size = SchematicHolder.schematicSize
        rotationAxis = Vec3.ZERO
        offset = Vec3.ZERO
        rotate = 0f
        initialDirection = Minecraft.getInstance().player!!.direction
        initialPosition = when(initialDirection) {
            Direction.EAST -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z)) // East
            Direction.SOUTH -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z)) // South
            Direction.WEST -> Vec3(floor(playerPos.x - size.x), floor(playerPos.y), floor(playerPos.z - size.z)) // West
            Direction.NORTH -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z - size.z)) // North
            else -> Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
        }
        initMissingBlock()
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

    public fun loadRenderBlocks(schematicFile: String) {
        SchematicFileLoader.loadRenderBlocks(schematicFile)
    }

    private fun initMissingBlock() {
        MissingBlockHolder.initialize()
        missingBlockRenderer.initialize()
    }
}