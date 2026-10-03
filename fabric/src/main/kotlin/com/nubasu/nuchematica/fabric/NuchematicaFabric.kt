package com.nubasu.nuchematica.fabric

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.nubasu.nuchematica.Nuchematica
import com.nubasu.nuchematica.gui.MainGui
import com.nubasu.nuchematica.gui.PrinterHudOverlay
import com.nubasu.nuchematica.keysetting.KeyManager
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import com.nubasu.nuchematica.renderer.LevelRenderContext
import com.nubasu.nuchematica.renderer.NuchematicaShaders
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.InteractionResult

/** Fabric entry point: adapts Fabric API events to [Nuchematica]. */
public class NuchematicaFabric : ClientModInitializer {
    override fun onInitializeClient(): Unit {
        Nuchematica.initClient()

        val keyManager = KeyManager()
        val mainGui = MainGui()
        val printerHudOverlay = PrinterHudOverlay()
        val clientBlockInteractHandler = ClientBlockInteractHandler()

        keyManager.keyMappings.forEach { keyMapping -> KeyBindingHelper.registerKeyBinding(keyMapping) }

        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                keyManager.handleKeyInputs()
                Nuchematica.onClientTickEnd()
            },
        )

        // AFTER_TRANSLUCENT runs after particles and before clouds, the point the ghost blocks are drawn at.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(
            WorldRenderEvents.AfterTranslucent { context ->
                Nuchematica.onRenderLevel(
                    LevelRenderContext(
                        context.matrixStack(),
                        context.projectionMatrix(),
                        context.camera(),
                        checkNotNull(context.frustum()),
                    ),
                )
            },
        )

        HudRenderCallback.EVENT.register(
            HudRenderCallback { matrices, _ ->
                mainGui.renderOverlay(matrices)
                printerHudOverlay.renderOverlay(matrices)
            },
        )

        CommandRegistrationCallback.EVENT.register(
            CommandRegistrationCallback { dispatcher, _ -> Nuchematica.registerCommands(dispatcher) },
        )

        CoreShaderRegistrationCallback.EVENT.register(
            CoreShaderRegistrationCallback { context ->
                context.register(NuchematicaShaders.END_PORTAL_OPACITY, DefaultVertexFormat.POSITION) { shader ->
                    NuchematicaShaders.endPortalOpacityLoaded(shader)
                }
                context.register(NuchematicaShaders.END_GATEWAY_OPACITY, DefaultVertexFormat.POSITION) { shader ->
                    NuchematicaShaders.endGatewayOpacityLoaded(shader)
                }
            },
        )

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = ResourceLocation(Nuchematica.MODID, "resource_reload")

                override fun onResourceManagerReload(resourceManager: ResourceManager): Unit {
                    Nuchematica.onResourceReload()
                }
            },
        )

        AttackBlockCallback.EVENT.register(
            AttackBlockCallback { _, world, _, pos, _ ->
                clientBlockInteractHandler.onLeftClickBlock(world, pos)
                InteractionResult.PASS
            },
        )

        UseBlockCallback.EVENT.register(
            UseBlockCallback { player, world, hand, hitResult ->
                clientBlockInteractHandler.onRightClickBlock(world, player, hand, hitResult.blockPos, hitResult.direction)
                InteractionResult.PASS
            },
        )
    }
}
