package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.io.SchematicFileLoader
import net.minecraftforge.client.event.RenderLevelStageEvent

public object SchematicRenderManager {
    public var isRendering: Boolean = true
    private val renderer = SchematicRenderer()

    public fun initialize() {
        renderer.initialize()
    }

    public fun render(event: RenderLevelStageEvent) {
        if (!isRendering) return
        renderer.render(event)
    }

    public fun loadRenderBlocks(schematicFile: String) {
        SchematicFileLoader.loadRenderBlocks(schematicFile)
    }
}