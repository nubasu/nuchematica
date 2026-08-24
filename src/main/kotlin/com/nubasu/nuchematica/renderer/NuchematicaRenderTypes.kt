package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType

public object NuchematicaRenderTypes : RenderType(
    "nuchematica_dummy",
    DefaultVertexFormat.BLOCK,
    VertexFormat.Mode.QUADS,
    256,
    false,
    false,
    Runnable {},
    Runnable {},
) {
    // Offset ghost geometry toward the camera to avoid z-fighting.
    private val ghostLayering: RenderStateShard.LayeringStateShard = RenderStateShard.LayeringStateShard(
        "nuchematica_ghost_layering",
        Runnable {
            RenderSystem.polygonOffset(0.5f, 5f)
            RenderSystem.enablePolygonOffset()
        },
        Runnable {
            RenderSystem.polygonOffset(0f, 0f)
            RenderSystem.disablePolygonOffset()
        },
    )

    internal fun setupGhostLayering(): Unit = ghostLayering.setupRenderState()

    internal fun clearGhostLayering(): Unit = ghostLayering.clearRenderState()

    // AFTER_PARTICLES already targets the particle framebuffer.
    private val schematicOutput: RenderStateShard.OutputStateShard = PARTICLES_TARGET

    public val GHOST_BLOCKS: RenderType = create(
        "nuchematica_ghost_blocks",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RENDERTYPE_TRANSLUCENT_NO_CRUMBLING_SHADER)
            .setTextureState(BLOCK_SHEET_MIPPED)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(LIGHTMAP)
            .setLayeringState(ghostLayering)
            .setOutputState(schematicOutput)
            .createCompositeState(false),
    )

    public val GHOST_TRANSLUCENT: RenderType = create(
        "nuchematica_ghost_translucent",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RENDERTYPE_TRANSLUCENT_NO_CRUMBLING_SHADER)
            .setTextureState(BLOCK_SHEET_MIPPED)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(LIGHTMAP)
            .setLayeringState(ghostLayering)
            .setOutputState(schematicOutput)
            .createCompositeState(false),
    )

    public val MISSING_OVERLAY: RenderType = create(
        "nuchematica_missing_overlay",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(POSITION_COLOR_SHADER)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setLayeringState(POLYGON_OFFSET_LAYERING)
            .setOutputState(schematicOutput)
            .createCompositeState(false),
    )

    public val REGION_LINES: RenderType = create(
        "nuchematica_region_lines",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.DEBUG_LINES,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(POSITION_COLOR_SHADER)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setOutputState(schematicOutput)
            .createCompositeState(false),
    )
}
