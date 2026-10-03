package com.nubasu.nuchematica.platform

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.KeyMapping
import net.minecraft.client.renderer.ItemBlockRenderTypes
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

/** [PlatformHooks] backed only by vanilla Minecraft APIs. */
public object VanillaPlatformHooks : PlatformHooks {
    override fun keyMapping(name: String, keyCode: Int, category: String): KeyMapping =
        KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, category)

    // Vanilla assigns each block exactly one chunk layer; RenderType has no value equality, so
    // identity comparison against the layer singleton is the membership test.
    override fun canRenderInLayer(state: BlockState, renderType: RenderType): Boolean =
        ItemBlockRenderTypes.getChunkRenderType(state) == renderType

    override fun canRenderInLayer(state: FluidState, renderType: RenderType): Boolean =
        ItemBlockRenderTypes.getRenderLayer(state) == renderType

    // Vanilla block rendering is not filtered by a thread-local layer, so there is nothing to select.
    override fun <T> withRenderLayer(renderType: RenderType, action: () -> T): T = action()

    override fun renderBatched(
        dispatcher: BlockRenderDispatcher,
        state: BlockState,
        pos: BlockPos,
        view: BlockAndTintGetter,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        checkSides: Boolean,
        random: Random,
    ): Boolean = dispatcher.renderBatched(state, pos, view, poseStack, consumer, checkSides, random)

    override fun railShape(rail: BaseRailBlock, state: BlockState, level: BlockGetter, pos: BlockPos): RailShape =
        state.getValue(rail.shapeProperty)
}
