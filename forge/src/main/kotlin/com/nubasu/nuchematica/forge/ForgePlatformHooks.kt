package com.nubasu.nuchematica.forge

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.nubasu.nuchematica.platform.PlatformHooks
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
import net.minecraftforge.client.ForgeHooksClient
import net.minecraftforge.client.model.data.EmptyModelData
import net.minecraftforge.client.settings.KeyConflictContext
import java.util.Random

/** [PlatformHooks] backed by Forge's patched vanilla members and Forge-only APIs. */
public object ForgePlatformHooks : PlatformHooks {
    override fun keyMapping(name: String, keyCode: Int, category: String): KeyMapping =
        KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, keyCode, category)

    override fun canRenderInLayer(state: BlockState, renderType: RenderType): Boolean =
        ItemBlockRenderTypes.canRenderInLayer(state, renderType)

    override fun canRenderInLayer(state: FluidState, renderType: RenderType): Boolean =
        ItemBlockRenderTypes.canRenderInLayer(state, renderType)

    override fun <T> withRenderLayer(renderType: RenderType, action: () -> T): T {
        try {
            ForgeHooksClient.setRenderType(renderType)
            return action()
        } finally {
            ForgeHooksClient.setRenderType(null)
        }
    }

    override fun renderBatched(
        dispatcher: BlockRenderDispatcher,
        state: BlockState,
        pos: BlockPos,
        view: BlockAndTintGetter,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        checkSides: Boolean,
        random: Random,
    ): Boolean = dispatcher.renderBatched(
        state,
        pos,
        view,
        poseStack,
        consumer,
        checkSides,
        random,
        EmptyModelData.INSTANCE,
    )

    override fun railShape(rail: BaseRailBlock, state: BlockState, level: BlockGetter, pos: BlockPos): RailShape =
        rail.getRailDirection(state, level, pos, null)
}
