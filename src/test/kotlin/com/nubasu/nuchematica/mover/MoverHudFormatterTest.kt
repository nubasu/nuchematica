package com.nubasu.nuchematica.mover

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class MoverHudFormatterTest {
    @Test
    public fun formatsCruiseStatusAndRoundedTarget(): Unit {
        val status = MoverStatus(
            state = MoverState.CRUISE,
            abortReason = null,
            target = Vec3(12.49, 64.5, -3.51),
            remainingMissing = 3,
        )

        assertEquals(
            listOf(
                "Auto-move: CRUISE",
                "Blocks left: 3",
                "Target: 12,65,-4",
            ),
            MoverHudFormatter.lines(status),
        )
    }

    @Test
    public fun formatsAbortedStatusWithReason(): Unit {
        val status = MoverStatus(
            state = MoverState.ABORTED,
            abortReason = MoverAbortReason.MANUAL_INPUT,
            target = null,
            remainingMissing = 2,
        )

        assertEquals(
            listOf(
                "Auto-move: ABORTED (MANUAL_INPUT)",
                "Blocks left: 2",
            ),
            MoverHudFormatter.lines(status),
        )
    }
}
