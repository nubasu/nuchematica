package com.nubasu.nuchematica.printer

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.protocol.game.ClientboundBlockBreakAckPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.level.block.state.BlockState

/**
 * Thread-safe store that hides optimistic removal until the server acknowledges the break.
 */
internal class ServerBreakAcknowledgementStore {
    private val pending: MutableMap<BlockPos, PendingBreak> = HashMap()

    @Synchronized
    internal fun begin(pos: BlockPos, baseline: BlockState): Unit {
        pending[pos.immutable()] = PendingBreak(baseline = baseline)
    }

    @Synchronized
    internal fun acknowledge(pos: BlockPos, state: BlockState, accepted: Boolean): Unit {
        val tracked = pending[pos] ?: return
        if (accepted) tracked.acknowledgedState = state
    }

    @Synchronized
    internal fun observedStateAt(pos: BlockPos, fallback: () -> BlockState): BlockState {
        val tracked = pending[pos] ?: return fallback()
        return tracked.acknowledgedState ?: tracked.baseline
    }

    @Synchronized
    internal fun finish(pos: BlockPos): Unit {
        pending.remove(pos)
    }

    @Synchronized
    internal fun clear(): Unit {
        pending.clear()
    }

    private data class PendingBreak(
        val baseline: BlockState,
        var acknowledgedState: BlockState? = null,
    )
}

internal object ScaffoldBreakConnectionObserver {
    private val store = ServerBreakAcknowledgementStore()

    @Volatile
    private var desiredChannel: Channel? = null

    @Volatile
    private var installedChannel: Channel? = null

    internal fun install(connection: Connection): Unit {
        val channel = connection.channel
        val previousDesired = desiredChannel
        val previousInstalled = installedChannel
        if (previousDesired === channel && previousInstalled === channel) return

        desiredChannel = channel
        store.clear()
        if (previousDesired != null && previousDesired !== channel) scheduleRemoval(previousDesired)
        if (previousInstalled != null && previousInstalled !== channel) scheduleRemoval(previousInstalled)

        channel.eventLoop().execute {
            if (desiredChannel !== channel) return@execute
            val pipeline = channel.pipeline()
            if (pipeline.get(HANDLER_NAME) == null && pipeline.get(PACKET_HANDLER_NAME) != null) {
                pipeline.addBefore(PACKET_HANDLER_NAME, HANDLER_NAME, BreakAckHandler())
            }
            if (pipeline.get(HANDLER_NAME) != null) installedChannel = channel
        }
    }

    internal fun begin(pos: BlockPos, baseline: BlockState): Unit = store.begin(pos, baseline)

    internal fun observedStateAt(pos: BlockPos, fallback: () -> BlockState): BlockState =
        store.observedStateAt(pos, fallback)

    internal fun finish(pos: BlockPos): Unit = store.finish(pos)

    internal fun remove(): Unit {
        val channels = listOfNotNull(desiredChannel, installedChannel).distinct()
        desiredChannel = null
        installedChannel = null
        store.clear()
        channels.forEach(::scheduleRemoval)
    }

    private fun scheduleRemoval(channel: Channel): Unit {
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            if (pipeline.get(HANDLER_NAME) != null) pipeline.remove(HANDLER_NAME)
        }
    }

    private class BreakAckHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(context: ChannelHandlerContext, message: Any): Unit {
            try {
                if (
                    message is ClientboundBlockBreakAckPacket &&
                    message.action() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                ) {
                    store.acknowledge(message.pos(), message.state(), message.allGood())
                }
            } finally {
                context.fireChannelRead(message)
            }
        }
    }

    private const val PACKET_HANDLER_NAME: String = "packet_handler"
    private const val HANDLER_NAME: String = "nuchematica_scaffold_break_ack"
}

/** Registers the baseline before issuing an optimistic creative-mode break request. */
internal fun requestCreativeBlockBreak(
    gameMode: MultiPlayerGameMode,
    level: ClientLevel,
    pos: BlockPos,
): Boolean {
    ScaffoldBreakConnectionObserver.begin(pos, level.getBlockState(pos))
    return gameMode.startDestroyBlock(pos, Direction.DOWN)
}
