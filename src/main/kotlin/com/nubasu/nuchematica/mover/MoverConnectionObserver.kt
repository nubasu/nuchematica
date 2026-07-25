package com.nubasu.nuchematica.mover

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.Connection
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket

internal object MoverConnectionObserver {
    @Volatile
    private var correctionReceived: Boolean = false

    @Volatile
    private var desiredChannel: Channel? = null

    @Volatile
    private var installedChannel: Channel? = null

    internal fun install(connection: Connection): Unit {
        val channel = connection.channel()
        val previousDesired = desiredChannel
        val previousInstalled = installedChannel
        if (previousDesired === channel && previousInstalled === channel) return

        desiredChannel = channel
        synchronized(this) {
            correctionReceived = false
        }
        if (previousDesired != null && previousDesired !== channel) {
            scheduleRemoval(previousDesired)
        }
        if (previousInstalled != null && previousInstalled !== channel) {
            scheduleRemoval(previousInstalled)
        }

        channel.eventLoop().execute {
            if (desiredChannel !== channel) return@execute
            val pipeline = channel.pipeline()
            if (
                pipeline.get(HANDLER_NAME) == null &&
                pipeline.get(PACKET_HANDLER_NAME) != null
            ) {
                pipeline.addBefore(
                    PACKET_HANDLER_NAME,
                    HANDLER_NAME,
                    CorrectionHandler(),
                )
            }
            if (pipeline.get(HANDLER_NAME) != null) {
                installedChannel = channel
            }
        }
    }

    internal fun remove(): Unit {
        val channels = listOfNotNull(desiredChannel, installedChannel).distinct()
        desiredChannel = null
        installedChannel = null
        synchronized(this) {
            correctionReceived = false
        }
        channels.forEach(::scheduleRemoval)
    }

    internal fun consumeCorrection(): Boolean {
        return synchronized(this) {
            val received = correctionReceived
            correctionReceived = false
            received
        }
    }

    private fun scheduleRemoval(channel: Channel): Unit {
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME)
            }
        }
    }

    private class CorrectionHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(context: ChannelHandlerContext, message: Any): Unit {
            try {
                if (message is ClientboundPlayerPositionPacket) {
                    synchronized(MoverConnectionObserver) {
                        correctionReceived = true
                    }
                }
            } finally {
                context.fireChannelRead(message)
            }
        }
    }

    private const val PACKET_HANDLER_NAME: String = "packet_handler"
    private const val HANDLER_NAME: String = "nuchematica_mover"
}
