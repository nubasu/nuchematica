package com.nubasu.nuchematica.mover

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class StartupOrphanCleanupTest {
    @Test
    internal fun cleanupOffersOnlyTopOfEachVerticalScaffoldChainNearestFirst(): Unit {
        val nearBottom = BlockPos(0, 0, 0)
        val nearMiddle = BlockPos(0, 1, 0)
        val nearTop = BlockPos(0, 2, 0)
        val farTop = BlockPos(8, 4, 0)

        val ordered = exposedStartupOrphansNearestFirst(
            positions = listOf(nearBottom, nearMiddle, farTop, nearTop),
            playerPosition = Vec3(0.5, 5.0, 0.5),
        )

        assertEquals(listOf(nearTop, farTop), ordered)
    }

    @Test
    internal fun cleanupExposesNextScaffoldAfterItsTopIsGone(): Unit {
        val bottom = BlockPos(0, 0, 0)
        val middle = BlockPos(0, 1, 0)

        assertEquals(
            listOf(middle),
            exposedStartupOrphansNearestFirst(
                positions = listOf(bottom, middle),
                playerPosition = Vec3(0.5, 4.0, 0.5),
            ),
        )
        assertEquals(
            listOf(bottom),
            exposedStartupOrphansNearestFirst(
                positions = listOf(bottom),
                playerPosition = Vec3(0.5, 4.0, 0.5),
            ),
        )
    }

    @Test
    internal fun breakTargetUsesItsOwnCenterWithoutAPlacementSupportFace(): Unit {
        val target = BlockPos(3, 7, 11)

        assertEquals(
            listOf(Vec3.atCenterOf(target)),
            planReachHitPoints(
                pos = target,
                expected = Blocks.SLIME_BLOCK.defaultBlockState(),
                stateAt = { Blocks.AIR.defaultBlockState() },
                breakTargets = setOf(target),
            ),
        )
    }

    @Test
    internal fun standBelowBreakTargetDoesNotViolatePlacementLayerSafety(): Unit {
        val target = BlockPos(0, 5, 0)

        assertTrue(
            planStandMeetsLayerSafety(
                standFeetY = 3,
                covered = listOf(target),
                breakTargets = setOf(target),
            ),
        )
    }
}
