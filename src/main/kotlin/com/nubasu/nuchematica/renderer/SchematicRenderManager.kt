package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.io.SchematicFileLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import kotlin.math.floor

public object SchematicRenderManager {
    public var isRendering: Boolean = true
    private val renderer = SchematicRenderer()
    private var initialPosition = Vec3.ZERO
    private var offset = Vec3.ZERO
    public var rotate = 0f
    public var rotationAxis = Vec3.ZERO

    public fun getRenderBase(): Vec3 {
        return Vec3(
            initialPosition.x + offset.x,
            initialPosition.y + offset.y,
            initialPosition.z + offset.z
        )
    }

    public fun setOffset(vec3: Vec3) {
        offset = vec3
    }

    public fun initialize() {
        val playerPos = Minecraft.getInstance().player!!.position()
        initialPosition = Vec3(floor(playerPos.x), floor(playerPos.y), floor(playerPos.z))
        renderer.initialize()
    }

    public fun rerender() {
        renderer.initialize()
    }

    public fun render(event: RenderLevelStageEvent) {
        if (!isRendering) return
        renderer.render(getRenderBase(), rotate, rotationAxis, event)
    }

    public fun loadRenderBlocks(schematicFile: String) {
        SchematicFileLoader.loadRenderBlocks(schematicFile)
    }
}