package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class ServerBreakAcknowledgementStoreTest {
    private val pos = BlockPos(4, 7, 9)
    private val slime = Blocks.SLIME_BLOCK.defaultBlockState()
    private val air = Blocks.AIR.defaultBlockState()

    @Test
    public fun optimisticClientAirIsHiddenUntilAnAcceptedServerAcknowledgementArrives(): Unit {
        val store = ServerBreakAcknowledgementStore()
        store.begin(pos, slime)

        assertEquals(slime, store.observedStateAt(pos) { air })

        store.acknowledge(pos, air, accepted = true)

        assertEquals(air, store.observedStateAt(pos) { slime })
    }

    @Test
    public fun rejectedAcknowledgementCannotTurnAReportedAirStateIntoSuccess(): Unit {
        val store = ServerBreakAcknowledgementStore()
        store.begin(pos, slime)

        store.acknowledge(pos, air, accepted = false)

        assertEquals(slime, store.observedStateAt(pos) { air })
    }

    @Test
    public fun acknowledgementForAnUntrackedManualBreakIsIgnored(): Unit {
        val store = ServerBreakAcknowledgementStore()

        store.acknowledge(pos, air, accepted = true)

        assertEquals(slime, store.observedStateAt(pos) { slime })
    }

    @Test
    public fun finishReleasesTheTrackedState(): Unit {
        val store = ServerBreakAcknowledgementStore()
        store.begin(pos, slime)
        store.acknowledge(pos, air, accepted = true)

        store.finish(pos)

        assertEquals(slime, store.observedStateAt(pos) { slime })
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
