package com.nubasu.nuchematica.platform

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.KeyMapping
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.BlockRenderDispatcher
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.RailShape
import net.minecraft.world.level.material.FluidState
import java.util.Random

/**
 * Loader-specific behavior that the loader-independent code needs but vanilla Minecraft does not
 * expose uniformly. A loader entry point installs its implementation into [Platform.hooks] before
 * any other mod code runs.
 */
public interface PlatformHooks {
    /** Creates a keyboard key mapping for [keyCode] in [category]. */
    public fun keyMapping(name: String, keyCode: Int, category: String): KeyMapping

    /** Whether [state] is drawn when chunk layer [renderType] is rendered. */
    public fun canRenderInLayer(state: BlockState, renderType: RenderType): Boolean

    /** Whether [state] is drawn when chunk layer [renderType] is rendered. */
    public fun canRenderInLayer(state: FluidState, renderType: RenderType): Boolean

    /** Runs [action] with [renderType] selected as the layer that model rendering is filtered by. */
    public fun <T> withRenderLayer(renderType: RenderType, action: () -> T): T

    /** Renders the block model of [state] into [consumer]. */
    public fun renderBatched(
        dispatcher: BlockRenderDispatcher,
        state: BlockState,
        pos: BlockPos,
        view: BlockAndTintGetter,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        checkSides: Boolean,
        random: Random,
    ): Boolean

    /** The shape of [rail] in [state]. */
    public fun railShape(rail: BaseRailBlock, state: BlockState, level: BlockGetter, pos: BlockPos): RailShape
}

/** Holder of the active [PlatformHooks]; defaults to the pure-vanilla implementation. */
public object Platform {
    @Volatile
    public var hooks: PlatformHooks = VanillaPlatformHooks
}
