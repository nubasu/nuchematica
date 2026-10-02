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

@Mod(Nuchematica.MODID)
public class Nuchematica {
    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        val keyManager = KeyManager()
        PrinterSettingsHolder.printerSettings = PrinterSettingsIO.load()

        // FMLClientSetupEvent listeners must use the mod event bus.
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
        // RenderLevelStageEvent fires once per stage; draw only after particles.
        if (event.stage == Stage.AFTER_PARTICLES) {
            SelectedRegionManager.renderLine(event)
            SchematicRenderManager.render(event)
        }
    }

    @SubscribeEvent
    public fun onClientTick(event: TickEvent.ClientTickEvent ) {
        if (event.phase != TickEvent.Phase.END) return
        val world = Minecraft.getInstance().level
        if (world == null) {
            SchematicPrinter.tick()
            SchematicMover.tick()
            return
        }

        SchematicRenderManager.tickPendingSettings()
        SchematicPrinter.tick()
        SchematicMover.tick()

        val breakIter = ClientBlockInteractHandler.pendingBreakPositions.iterator()
        while (breakIter.hasNext()) {
            val pos = breakIter.next()
            val currentState = pendingBreakState(
                pos = pos,
                isPrinterOwned = SchematicPrinter::ownsPendingBreak,
                stateAt = world::getBlockState,
            )
            if (currentState != null) {
                MissingBlockHolder.removed(pos)?.let(SchematicRenderManager::onMissingBlockChange)
                PrintWorldModel.recordWrite(pos, currentState)
                SchematicPrinter.invalidatePlanSession(pos)
                breakIter.remove()
            }
        }

        val placeIter = ClientBlockInteractHandler.pendingPlacePositions.iterator()
        while (placeIter.hasNext()) {
            val pos = placeIter.next()
            val currentState = pendingPlacementState(
                pos = pos,
                isPrinterOwned = SchematicPrinter::ownsPendingPlacement,
                stateAt = world::getBlockState,
            )
            if (currentState != null) {
                MissingBlockHolder.placed(pos, currentState)
                    ?.let(SchematicRenderManager::onMissingBlockChange)
                PrintWorldModel.recordWrite(pos, currentState)
                SchematicPrinter.invalidatePlanSession(pos)
                placeIter.remove()
            }
        }
    }

    public companion object {
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

internal fun pendingBreakState(
    pos: BlockPos,
    isPrinterOwned: (BlockPos) -> Boolean,
    stateAt: (BlockPos) -> BlockState,
): BlockState? {
    if (isPrinterOwned(pos)) return null
    return stateAt(pos).takeIf(BlockState::isAir)
}
