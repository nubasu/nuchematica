package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType

/**
 * Custom [RenderType]s that centralize the hand-written RenderSystem state the renderers used to
 * toggle by hand. Declared as an object that inherits [RenderType] so the protected static shard
 * constants (RENDERTYPE_TRANSLUCENT_SHADER, TRANSLUCENT_TRANSPARENCY, ...) and the protected static
 * create() factory are in scope.
 *
 * The dummy super-constructor arguments are never used: this object is only a holder and its own
 * setup/clear runnables are no-ops. Only the four public vals below are meant to be drawn with.
 * The public 7-arg create() overload is used (the 5-arg one is package-private); affectsCrumbling
 * and sortOnUpload are both false (translucency is pre-sorted at build time via setQuadSortOrigin).
 */
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
    // Ghost blocks use a smaller polygon offset (0.5, 5) than the vanilla layering shard so the
    // translucent preview sits just in front of the real world without z-fighting.
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

    // A5 adopted pair matches the output target already active during AFTER_PARTICLES.
    private val schematicOutput: RenderStateShard.OutputStateShard = PARTICLES_TARGET

    // Solid ghost geometry: block atlas (mipped) + blending. Uses the no-crumbling translucent
    // shader so ghosts stay full-bright and unfogged (that shader ignores the bound lightmap and
    // applies no fog), matching the pre-refactor look.
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

    // Translucent ghost geometry: identical state to GHOST_BLOCKS. Quads are pre-sorted at build
    // time via setQuadSortOrigin, so no upload-time sort is needed.
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

    // Missing / wrong-block overlay: flat position+color quads, no culling, pushed toward the
    // camera with the vanilla polygon offset so the overlay wins the depth fight.
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

    // Selected-region outline: position+color debug lines with a normal depth test.
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
