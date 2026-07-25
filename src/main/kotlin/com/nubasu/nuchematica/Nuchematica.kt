package com.nubasu.nuchematica

import com.mojang.brigadier.Command
import com.nubasu.nuchematica.gui.MainGui
import com.nubasu.nuchematica.gui.PrinterHudOverlay
import com.nubasu.nuchematica.keysetting.KeyManager
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import com.nubasu.nuchematica.printer.PrinterSettingsIO
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import com.nubasu.nuchematica.renderer.NuchematicaShaders
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.commands.Commands
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Nuchematica.MODID)
public class Nuchematica {
    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        val keyManager = KeyManager()
        PrinterSettingsHolder.printerSettings = PrinterSettingsIO.load()

        // Key bindings must be registered on the MOD event bus (FMLClientSetupEvent is a
        // mod-bus event and never fires for listeners registered on the Forge bus).
        modEventBus.addListener(keyManager::keyRegister)
        modEventBus.addListener(NuchematicaShaders::registerShaders)
        modEventBus.addListener(this::registerClientReloadListeners)

        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.register(keyManager)
        MinecraftForge.EVENT_BUS.register(MainGui())
        MinecraftForge.EVENT_BUS.register(PrinterHudOverlay())
        MinecraftForge.EVENT_BUS.register(SchematicMover)
        MinecraftForge.EVENT_BUS.register(ClientBlockInteractHandler())
    }

    @SubscribeEvent
    public fun onRegisterCommand(event: RegisterCommandsEvent) {
        val pos1Builder = Commands.literal("pos1")
            .executes {
                SelectedRegionManager.setFirstPosition(it.source.position)
                Command.SINGLE_SUCCESS
            }

        val pos2Builder = Commands.literal("pos2")
            .executes {
                SelectedRegionManager.setSecondPosition(it.source.position)
                Command.SINGLE_SUCCESS
            }

        val blocksBuilder = Commands.literal("blocks")
            .executes {
                val blocks = SelectedRegionManager.getSelectedRegionBlocks()
                blocks.forEach {
                    ChatSender.send(it.block.name.toString())

                    it.tags.forEach {
                        ChatSender.send(it.toString())
                    }
                }
                Command.SINGLE_SUCCESS
            }

        event.dispatcher.register(pos1Builder)
        event.dispatcher.register(pos2Builder)
        event.dispatcher.register(blocksBuilder)
    }

    public fun registerClientReloadListeners(event: RegisterClientReloadListenersEvent): Unit {
        event.registerReloadListener(
            ResourceManagerReloadListener {
                SchematicRenderManager.resourceReloaded()
            },
        )
    }

    @SubscribeEvent
    public fun onWorldLoad(event: WorldEvent.Load): Unit {
        (event.world as? ClientLevel)?.let(SchematicRenderManager::worldLoaded)
    }

    @SubscribeEvent
    public fun onWorldUnload(event: WorldEvent.Unload): Unit {
        (event.world as? ClientLevel)?.let { level ->
            SchematicMover.worldUnloaded()
            SchematicRenderManager.worldUnloaded(level)
        }
    }

    @SubscribeEvent
    public fun onWorldRenderLast(event: RenderLevelStageEvent) {
        // A5 adopted pair: draw after particles and target that stage's active output.
        // RenderLevelStageEvent fires once per stage (~10x per frame); draw only once.
        if (event.stage == Stage.AFTER_PARTICLES) {
            SelectedRegionManager.renderLine(event)
            SchematicRenderManager.render(event)
        }
    }

    @SubscribeEvent
    public fun onClientTick(event: TickEvent.ClientTickEvent ) {
        if (event.phase != TickEvent.Phase.END) return  // run at end of tick
        val world = Minecraft.getInstance().level
        if (world == null) {
            SchematicPrinter.tick()
            SchematicMover.tick()
            return
        }

        SchematicRenderManager.tickPendingSettings()
        SchematicPrinter.tick()
        SchematicMover.tick()

        // Check pending breaks
        val breakIter = ClientBlockInteractHandler.pendingBreakPositions.iterator()
        while (breakIter.hasNext()) {
            val pos = breakIter.next()
            val currentState = world.getBlockState(pos)
            if (currentState.isAir) {
                // The block was broken by the player
                MissingBlockHolder.removed(pos)?.let(SchematicRenderManager::onMissingBlockChange)
                // The generic click reconcile path is one of the write-on-ack sites --
                // a manual player break is just as much a server-confirmed outcome as
                // a printer-submitted one.
                PrintWorldModel.recordWrite(pos, currentState)
                SchematicPrinter.invalidatePlanSession(pos)
                breakIter.remove()
            }
            // (Optional: remove after a timeout to avoid stuck entries if not broken)
        }

        // Check pending placements
        val placeIter = ClientBlockInteractHandler.pendingPlacePositions.iterator()
        while (placeIter.hasNext()) {
            val pos = placeIter.next()
            val currentState = pendingPlacementState(
                pos = pos,
                isPrinterOwned = SchematicPrinter::ownsPendingPlacement,
                stateAt = world::getBlockState,
            )
            if (currentState != null) {
                // A block was placed by the player
                MissingBlockHolder.placed(pos, currentState)
                    ?.let(SchematicRenderManager::onMissingBlockChange)
                // Write-on-ack for the manual-placement side of the generic click
                // reconcile path.
                PrintWorldModel.recordWrite(pos, currentState)
                SchematicPrinter.invalidatePlanSession(pos)
                placeIter.remove()
            }
        }
    }


    public companion object {
        // Define mod id in a common place for everything to reference
        public const val MODID: String = "nuchematica"
    }
}

internal fun pendingPlacementState(
    pos: BlockPos,
    isPrinterOwned: (BlockPos) -> Boolean,
    stateAt: (BlockPos) -> BlockState,
): BlockState? {
    if (isPrinterOwned(pos)) return null
    val currentState = stateAt(pos)
    return currentState.takeUnless(BlockState::isAir)
}
