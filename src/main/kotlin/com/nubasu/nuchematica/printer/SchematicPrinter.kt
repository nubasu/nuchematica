package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

public object SchematicPrinter {
    private val activation: PrinterActivation = PrinterActivation()
    private val runtime: PrinterRuntime = PrinterRuntime()
    private val statusTracker: PrinterStatusTracker = PrinterStatusTracker()

    internal val enabled: Boolean
        get() = activation.enabled

    internal var latestStatus: PrinterStatus? = null
        private set

    internal fun toggleRequested(isCreative: Boolean): PrinterActivationEvent {
        val event = activation.toggleRequested(isCreative)
        if (event == PrinterActivationEvent.ENABLED) {
            statusTracker.reset()
        } else {
            latestStatus = null
            runtime.cancelAll()
        }
        return event
    }

    internal fun tick(): Unit {
        if (!enabled) {
            latestStatus = null
            runtime.cancelAll()
            return
        }

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        val gameMode = minecraft.gameMode
        val isCreative = level != null && player != null && gameMode?.playerMode?.isCreative == true
        if (activation.tick(isCreative) == PrinterActivationEvent.AUTO_DISABLED) {
            ChatSender.send("[nuchematica] printer disabled (left creative mode)")
        }
        if (!enabled || level == null || player == null || gameMode == null) {
            latestStatus = null
            runtime.cancelAll()
            return
        }

        val settings = PrinterSettingsHolder.printerSettings
        runtime.rateLimiter.updateAttemptsPerTick(settings.attemptsPerTick)
        val content = SchematicHolder.renderingBlocks
        val missing = MissingBlockHolder.missingSnapshot()
        val transformRevision = SchematicRenderManager.currentTransformRevision()
        val sessionKey = PrinterSessionKey(
            level = level,
            content = content,
            transformRevision = transformRevision,
            queueRevision = missing.revision,
        )
        val gateway = RealPlacementGateway(gameMode, player, level)
        val completed = runtime.tick(
            PrinterTickContext(
                tick = level.gameTime,
                sessionKey = sessionKey,
                missingLocal = missing.missingLocal,
                expectedStateAt = content.blocks::get,
                localToWorld = SchematicRenderManager::localBlockToWorld,
                stateAt = level::getBlockState,
                placementContext = { expectedState, hit ->
                    BlockPlaceContext(
                        level,
                        player,
                        InteractionHand.MAIN_HAND,
                        ItemStack(expectedState.block.asItem()),
                        hit,
                    )
                },
                eyePosition = player.eyePosition,
                reach = minOf(settings.reach, gameMode.pickRange.toDouble()),
                itemSupplier = CreativeItemSupplier(player, gameMode),
                placementGateway = gateway,
            ),
        )
        latestStatus = statusTracker.update(
            levelIdentity = level,
            contentIdentity = content,
            transformRevision = transformRevision,
            remaining = missing.missingLocal.size,
            acceptedDelta = completed.count { it.outcome == PrinterAttemptOutcome.ACCEPTED },
            skips = runtime.skipLog.snapshot(),
        )
    }
}

internal class RealPlacementGateway(
    private val gameMode: MultiPlayerGameMode,
    private val player: LocalPlayer,
    private val level: ClientLevel,
) : PlacementGateway {
    override fun submit(hit: BlockHitResult): Boolean {
        return gameMode.useItemOn(player, level, InteractionHand.MAIN_HAND, hit).consumesAction()
    }
}
