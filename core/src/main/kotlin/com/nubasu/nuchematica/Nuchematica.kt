package com.nubasu.nuchematica

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import com.nubasu.nuchematica.printer.PrinterSettingsIO
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import com.nubasu.nuchematica.renderer.LevelRenderContext
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** Loader-independent mod entry points; each loader binding forwards its events here. */
public object Nuchematica {
    public const val MODID: String = "nuchematica"

    public fun initClient(): Unit {
        PrinterSettingsHolder.printerSettings = PrinterSettingsIO.load()
    }

    public fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>): Unit {
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

        dispatcher.register(pos1Builder)
        dispatcher.register(pos2Builder)
        dispatcher.register(blocksBuilder)
    }

    public fun onResourceReload(): Unit {
        SchematicRenderManager.resourceReloaded()
    }

    public fun onWorldLoad(level: ClientLevel): Unit {
        SchematicRenderManager.worldLoaded(level)
    }

    public fun onWorldUnload(level: ClientLevel): Unit {
        SchematicMover.worldUnloaded()
        SchematicRenderManager.worldUnloaded(level)
    }

    public fun onRenderLevel(context: LevelRenderContext): Unit {
        SelectedRegionManager.renderLine(context)
        SchematicRenderManager.render(context)
    }

    public fun onClientTickEnd(): Unit {
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
