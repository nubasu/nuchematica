package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class ScaffoldChainPlannerTest {
    private val target = BlockPos(0, 5, 0)
    private val plainExpected = Blocks.STONE.defaultBlockState()

    @Test
    public fun shortestPathIsFoundAcrossMultipleGoalDirections(): Unit {
        val solid = setOf(BlockPos(0, 2, 0), BlockPos(0, 5, -6))
        val stateAt = stateReader(solid)
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            pos == target || pos == target.above() || pos == target.south() ||
                pos == target.west() || pos == target.east()
        }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = isSchematicPosition,
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(listOf(BlockPos(0, 3, 0), BlockPos(0, 4, 0)), plan?.cells)
        assertEquals(target, plan?.targetPos)
    }

    @Test
    public fun resultIsDeterministicAcrossRepeatedCalls(): Unit {
        val solid = setOf(BlockPos(0, 2, 0), BlockPos(0, 5, -6))
        val stateAt = stateReader(solid)
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            pos == target || pos == target.above() || pos == target.south() ||
                pos == target.west() || pos == target.east()
        }

        val first = ScaffoldChainPlanner.plan(target, plainExpected, isSchematicPosition, stateAt, null)
        val second = ScaffoldChainPlanner.plan(target, plainExpected, isSchematicPosition, stateAt, null)

        assertEquals(first, second)
    }

    @Test
    public fun chainAtExactlyTheLengthCapStillSucceeds(): Unit {
        val tallTarget = BlockPos(0, 10, 0)
        val groundY = 1
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.y <= groundY) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = tallTarget,
            expectedState = plainExpected,
            isSchematicPosition = { it == tallTarget },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(SCAFFOLD_CHAIN_LIMIT, plan?.cells?.size)
        assertEquals(tallTarget, plan?.targetPos)
        assertTrue(plan != null && plan.cells.last() in Direction.values().map { tallTarget.relative(it) })
    }

    @Test
    public fun chainOneCellBeyondTheLengthCapReturnsNull(): Unit {
        val tallTarget = BlockPos(0, 10, 0)
        val groundY = 0
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.y <= groundY) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = tallTarget,
            expectedState = plainExpected,
            isSchematicPosition = { it == tallTarget },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertNull(plan)
    }

    @Test
    public fun noAnchorReachableReturnsNullWhenNoGoalCellQualifies(): Unit {
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            pos == target || Direction.values().any { direction -> pos == target.relative(direction) }
        }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = isSchematicPosition,
            stateAt = { Blocks.STONE.defaultBlockState() },
            playerFeetPos = null,
        )

        assertNull(plan)
    }

    @Test
    public fun noAnchorReachableReturnsNullInAnUnboundedVoid(): Unit {
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = { it == target },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertNull(plan)
    }

    @Test
    public fun playerColumnCellIsExcludedMidChainForcingAnAlternateRoute(): Unit {
        val tallTarget = BlockPos(0, 10, 0)
        val groundY = 3
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.y <= groundY) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val blockedCells = setOf(BlockPos(0, 5, 0), BlockPos(0, 6, 0), BlockPos(0, 7, 0))
        val isSchematicPosition: (BlockPos) -> Boolean = { pos ->
            pos == tallTarget || pos == tallTarget.above() || pos == tallTarget.north() ||
                pos == tallTarget.south() || pos == tallTarget.west() || pos == tallTarget.east()
        }
        val playerFeetPos = Vec3(0.5, 5.0, 0.5)

        val plan = ScaffoldChainPlanner.plan(
            targetPos = tallTarget,
            expectedState = plainExpected,
            isSchematicPosition = isSchematicPosition,
            stateAt = stateAt,
            playerFeetPos = playerFeetPos,
        )

        val resolved = requireNotNull(plan) { "a valid detour around the blocked span must still be found" }
        assertTrue(blockedCells.none { it in resolved.cells }) {
            "no player-column cell may ever be used: ${resolved.cells}"
        }
        assertEquals(tallTarget, resolved.targetPos)
        assertEquals(tallTarget.below(), resolved.cells.last()) { "the only available goal cell is DOWN" }
        assertTrue(resolved.cells.size <= SCAFFOLD_CHAIN_LIMIT)
    }

    @Test
    public fun chainIsRejectedWhenTheOnlyRouteAndAnchorLieOutsideBounds(): Unit {
        val solidPos = BlockPos(0, 2, 0)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == solidPos) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val isInBounds: (BlockPos) -> Boolean = { pos -> pos.y >= 5 }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = { it == target },
            stateAt = stateAt,
            playerFeetPos = null,
            isInBounds = isInBounds,
        )

        assertNull(plan)
    }

    @Test
    public fun directPlanNeverReadsOutOfBoundsCandidateBeforeRejection(): Unit {
        val isSchematicPosition: (BlockPos) -> Boolean = { pos -> pos != target.below() && pos != target.east() }
        val isInBounds: (BlockPos) -> Boolean = { pos -> pos != target.below() }
        val stateAt: (BlockPos) -> BlockState = { pos ->
            when (pos) {
                target.below() -> throw AssertionError("read out-of-bounds position $pos")
                target.east().north() -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
        }

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = isSchematicPosition,
            stateAt = stateAt,
            playerFeetPos = null,
            isInBounds = isInBounds,
        )

        assertEquals(listOf(target.east()), plan?.cells)
        assertEquals(target, plan?.targetPos)
    }

    @Test
    public fun directPlanNeverReadsPastBoundsWhileScanningCandidatesOwnSupportNeighbors(): Unit {
        val candidate = target.below()
        val poison = candidate.below()
        val isSchematicPosition: (BlockPos) -> Boolean = { pos -> pos != candidate }
        val isInBounds: (BlockPos) -> Boolean = { pos -> pos != poison }
        val stateAt: (BlockPos) -> BlockState = { pos ->
            when (pos) {
                poison -> throw AssertionError("read out-of-bounds position $pos")
                candidate.north() -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
        }

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = isSchematicPosition,
            stateAt = stateAt,
            playerFeetPos = null,
            isInBounds = isInBounds,
        )

        assertEquals(listOf(candidate), plan?.cells)
    }

    @Test
    public fun chainFailsWhenTheOnlyReachableAnchorsSupportLiesOutsideBounds(): Unit {
        val corridor = setOf(BlockPos(0, 4, 0), BlockPos(0, 3, 0), BlockPos(0, 2, 0))
        val poison = BlockPos(0, 1, 0)
        val isSchematicPosition: (BlockPos) -> Boolean = { pos -> pos !in corridor }
        val isInBounds: (BlockPos) -> Boolean = { pos -> pos != poison }
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == poison) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        val plan = ScaffoldChainPlanner.plan(
            targetPos = target,
            expectedState = plainExpected,
            isSchematicPosition = isSchematicPosition,
            stateAt = stateAt,
            playerFeetPos = null,
            isInBounds = isInBounds,
        )

        assertNull(plan)
    }

    private fun stateReader(solid: Set<BlockPos>): (BlockPos) -> BlockState {
        return { pos -> if (pos in solid) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState() }
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
