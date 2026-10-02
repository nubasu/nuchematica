package com.nubasu.nuchematica.forge

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.nubasu.nuchematica.Nuchematica
import com.nubasu.nuchematica.gui.MainGui
import com.nubasu.nuchematica.gui.PrinterHudOverlay
import com.nubasu.nuchematica.keysetting.KeyManager
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.platform.Platform
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import com.nubasu.nuchematica.renderer.LevelRenderContext
import com.nubasu.nuchematica.renderer.NuchematicaShaders
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.client.ClientRegistry
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.MovementInputUpdateEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

/** Forge entry point: installs the Forge platform hooks and adapts Forge events to [Nuchematica]. */
@Mod(Nuchematica.MODID)
public class NuchematicaForge {
    private val keyManager: KeyManager
    private val mainGui: MainGui
    private val printerHudOverlay: PrinterHudOverlay
    private val clientBlockInteractHandler: ClientBlockInteractHandler

    init {
        Platform.hooks = ForgePlatformHooks
        Nuchematica.initClient()

        keyManager = KeyManager()
        mainGui = MainGui()
        printerHudOverlay = PrinterHudOverlay()
        clientBlockInteractHandler = ClientBlockInteractHandler()

        // FMLClientSetupEvent and the registration events are mod-bus events.
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        modEventBus.addListener(this::onClientSetup)
        modEventBus.addListener(this::onRegisterShaders)
        modEventBus.addListener(this::onRegisterClientReloadListeners)

        MinecraftForge.EVENT_BUS.register(this)
    }

    public fun onClientSetup(event: FMLClientSetupEvent): Unit {
        keyManager.keyMappings.forEach(ClientRegistry::registerKeyBinding)
    }

    public fun onRegisterShaders(event: RegisterShadersEvent): Unit {
        event.registerShader(
            ShaderInstance(
                event.resourceManager,
                NuchematicaShaders.END_PORTAL_OPACITY,
                DefaultVertexFormat.POSITION,
            ),
        ) { shader -> NuchematicaShaders.endPortalOpacityLoaded(shader) }
        event.registerShader(
            ShaderInstance(
                event.resourceManager,
                NuchematicaShaders.END_GATEWAY_OPACITY,
                DefaultVertexFormat.POSITION,
            ),
        ) { shader -> NuchematicaShaders.endGatewayOpacityLoaded(shader) }
    }

    public fun onRegisterClientReloadListeners(event: RegisterClientReloadListenersEvent): Unit {
        event.registerReloadListener(
            ResourceManagerReloadListener {
                Nuchematica.onResourceReload()
            },
        )
    }

    @SubscribeEvent
    public fun onRegisterCommands(event: RegisterCommandsEvent): Unit {
        Nuchematica.registerCommands(event.dispatcher)
    }

    @SubscribeEvent
    public fun onWorldLoad(event: WorldEvent.Load): Unit {
        (event.world as? ClientLevel)?.let { level -> Nuchematica.onWorldLoad(level) }
    }

    @SubscribeEvent
    public fun onWorldUnload(event: WorldEvent.Unload): Unit {
        (event.world as? ClientLevel)?.let { level -> Nuchematica.onWorldUnload(level) }
    }

    @SubscribeEvent
    public fun onRenderLevelStage(event: RenderLevelStageEvent): Unit {
        // RenderLevelStageEvent fires once per stage; draw only after particles.
        if (event.stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            Nuchematica.onRenderLevel(
                LevelRenderContext(event.poseStack, event.projectionMatrix, event.camera, event.frustum),
            )
        }
    }

    @SubscribeEvent
    public fun onClientTick(event: TickEvent.ClientTickEvent): Unit {
        if (event.phase != TickEvent.Phase.END) return
        Nuchematica.onClientTickEnd()
    }

    @SubscribeEvent
    public fun onKeyInput(event: InputEvent.KeyInputEvent): Unit {
        keyManager.handleKeyInputs()
    }

    @SubscribeEvent
    public fun onRenderGameOverlayPost(event: RenderGameOverlayEvent.Post): Unit {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return
        mainGui.renderOverlay(event.matrixStack)
        printerHudOverlay.renderOverlay(event.matrixStack)
    }

    @SubscribeEvent
    public fun onMovementInput(event: MovementInputUpdateEvent): Unit {
        SchematicMover.onMovementInput(event.player, event.input)
    }

    @SubscribeEvent
    public fun onLoggedOut(event: ClientPlayerNetworkEvent.LoggedOutEvent): Unit {
        SchematicMover.onLoggedOut(event.player)
    }

    @SubscribeEvent
    public fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock): Unit {
        clientBlockInteractHandler.onLeftClickBlock(event.world, event.pos)
    }

    @SubscribeEvent
    public fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock): Unit {
        clientBlockInteractHandler.onRightClickBlock(event.world, event.player, event.hand, event.pos, event.face)
    }
}
