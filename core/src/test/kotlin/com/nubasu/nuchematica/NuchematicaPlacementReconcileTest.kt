package com.nubasu.nuchematica

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class NuchematicaPlacementReconcileTest {
    @Test
    public fun printerOwnedPendingPlacementIsSkippedWithoutReadingPredictedState(): Unit {
        var stateReads = 0

        val state = pendingPlacementState(
            pos = BlockPos.ZERO,
            isPrinterOwned = { true },
            stateAt = {
                stateReads++
                Blocks.STONE.defaultBlockState()
            },
        )

        assertNull(state)
        assertEquals(0, stateReads)
    }

    @Test
    public fun printerOwnedPendingBreakIsSkippedWithoutReadingOptimisticClientAir(): Unit {
        var stateReads = 0

        val state = pendingBreakState(
            pos = BlockPos.ZERO,
            isPrinterOwned = { true },
            stateAt = {
                stateReads++
                Blocks.AIR.defaultBlockState()
            },
        )

        assertNull(state)
        assertEquals(0, stateReads)
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
