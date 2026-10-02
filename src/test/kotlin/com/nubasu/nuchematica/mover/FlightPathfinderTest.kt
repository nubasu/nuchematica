package com.nubasu.nuchematica.mover

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
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

public class FlightPathfinderTest {
    @Test
    public fun openRoomStaircaseSmoothsToDiagonal(): Unit {
        val goal = Vec3(4.0, 3.0, 2.0)
        val staircase = listOf(
            Vec3(1.0, 0.0, 0.0),
            Vec3(1.0, 1.0, 0.0),
            Vec3(2.0, 1.0, 0.0),
            Vec3(2.0, 2.0, 1.0),
            goal,
        )

        val smoothed = smoothFlightPath(Vec3.ZERO, staircase, lineClear = { _, _ -> true })

        assertEquals(listOf(goal), smoothed)
    }

    @Test
    public fun blockedMidSegmentKeepsNecessaryCorner(): Unit {
        val start = Vec3.ZERO
        val firstStep = Vec3(1.0, 0.0, 0.0)
        val corner = Vec3(2.0, 0.0, 0.0)
        val afterCorner = Vec3(2.0, 0.0, 1.0)
        val goal = Vec3(4.0, 0.0, 2.0)
        val smoothed = smoothFlightPath(
            start = start,
            waypoints = listOf(firstStep, corner, afterCorner, goal),
            lineClear = { from, to ->
                when (from) {
                    start -> to == firstStep || to == corner
                    corner -> to == afterCorner || to == goal
                    else -> false
                }
            },
        )

        assertEquals(listOf(corner, goal), smoothed)
    }

    @Test
    public fun smoothingProbeBudgetIsRespected(): Unit {
        val waypoints = (1..70).map { x -> Vec3(x.toDouble(), 0.0, 0.0) }
        var probes = 0

        val smoothed = smoothFlightPath(
            start = Vec3.ZERO,
            waypoints = waypoints,
            lineClear = { _, _ ->
                probes++
                false
            },
            probeBudget = 64,
        )

        assertEquals(64, probes)
        assertEquals(waypoints, smoothed)
    }

    @Test
    public fun straightCorridorCollapsesToGoalWaypoint(): Unit {
        val goal = BlockPos(5, 0, 0)

        val result = findFlightPath(BlockPos.ZERO, goal, isPassableCell = { true })

        assertEquals(listOf(goal), result.path)
    }

    @Test
    public fun forcedCornersAreTheOnlyIntermediateWaypoints(): Unit {
        val feetCells = setOf(
            BlockPos(0, 0, 0),
            BlockPos(1, 0, 0),
            BlockPos(1, 0, 1),
            BlockPos(1, 0, 2),
            BlockPos(2, 0, 2),
            BlockPos(3, 0, 2),
        )

        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = BlockPos(3, 0, 2),
            isPassableCell = corridorPassability(feetCells),
        )

        assertEquals(
            listOf(
                BlockPos(1, 0, 0),
                BlockPos(1, 0, 2),
                BlockPos(3, 0, 2),
            ),
            result.path,
        )
    }

    @Test
    public fun fullHeightWallUsesALateralRoute(): Unit {
        val goal = BlockPos(4, 0, 0)
        val wall: (BlockPos) -> Boolean = { position ->
            position.x == 2 && position.z == 0
        }

        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = goal,
            isPassableCell = { position -> !wall(position) },
        )

        val waypoints = requireNotNull(result.path)
        assertEquals(goal, waypoints.last())
        assertTrue(waypoints.any { waypoint -> waypoint.z != 0 })
    }

    @Test
    public fun sealedPlaneReturnsNull(): Unit {
        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = BlockPos(4, 0, 0),
            isPassableCell = { position -> position.x != 2 },
        )

        assertNull(result.path)
        assertEquals(FlightPathFailureReason.NO_PATH, result.failureReason)
    }

    @Test
    public fun configuredExpansionBudgetIsNeverExceeded(): Unit {
        var passabilityCalls = 0
        assertEquals(4096, MIN_EXPANSION_BUDGET)

        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = BlockPos(4, 0, 0),
            isPassableCell = {
                passabilityCalls++
                true
            },
            expansionBudget = 1,
        )

        assertNull(result.path)
        assertEquals(FlightPathFailureReason.BUDGET_EXHAUSTED, result.failureReason)
        assertTrue(passabilityCalls <= 16)
    }

    @Test
    public fun blockedHeadCellMakesFeetCellUnenterable(): Unit {
        val goal = BlockPos(2, 0, 0)
        val feetCells = setOf(BlockPos.ZERO, BlockPos(1, 0, 0), goal)
        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = goal,
            isPassableCell = { position ->
                (position in feetCells || position.below() in feetCells) &&
                    position != BlockPos(1, 1, 0)
            },
        )

        assertNull(result.path)
        assertEquals(FlightPathFailureReason.NO_PATH, result.failureReason)
    }

    @Test
    public fun oversizedAxisFailsBeforeInspectingCells(): Unit {
        var passabilityCalls = 0

        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = BlockPos(130, 0, 0),
            isPassableCell = {
                passabilityCalls++
                true
            },
        )

        assertNull(result.path)
        assertEquals(FlightPathFailureReason.VOLUME_CLAMPED, result.failureReason)
        assertEquals(0, passabilityCalls)
    }

    @Test
    public fun oversizedTotalVolumeFailsEvenWithinPerAxisLimit(): Unit {
        var passabilityCalls = 0

        val result = findFlightPath(
            start = BlockPos.ZERO,
            goal = BlockPos(119, 119, 31),
            isPassableCell = {
                passabilityCalls++
                true
            },
        )

        assertNull(result.path)
        assertEquals(FlightPathFailureReason.VOLUME_CLAMPED, result.failureReason)
        assertEquals(0, passabilityCalls)
    }

    @Test
    public fun hundredCellLongLegSucceeds(): Unit {
        val goal = BlockPos(100, 0, 0)

        val result = findFlightPath(BlockPos.ZERO, goal, isPassableCell = { true })

        assertEquals(listOf(goal), result.path)
    }

    private fun corridorPassability(
        feetCells: Set<BlockPos>,
    ): (BlockPos) -> Boolean {
        return { position ->
            position in feetCells || position.below() in feetCells
        }
    }

    @Test
    public fun resolveEnterableCellRescuesXBoundaryPressedAgainstWall(): Unit {
        val position = Vec3(-9.9, 1.0, 0.5)
        val wallColumn = BlockPos(-10, 1, 0)
        val enterable = enterableFromPassable { cell -> cell != wallColumn }

        val resolved = resolveEnterableCell(position, PLAYER_HALF_WIDTH, enterable)

        assertEquals(BlockPos(-11, 1, 0), resolved)
    }

    @Test
    public fun resolveEnterableCellRescuesFractionalHoverIntoTheCellAbove(): Unit {
        val position = Vec3(0.5, 5.5, 0.5)
        val solidFloor = BlockPos(0, 5, 0)
        val enterable = enterableFromPassable { cell -> cell != solidFloor }

        val resolved = resolveEnterableCell(position, PLAYER_HALF_WIDTH, enterable)

        assertEquals(BlockPos(0, 6, 0), resolved)
    }

    @Test
    public fun resolveEnterableCellRequiresBothAxesForACornerRescue(): Unit {
        val position = Vec3(-9.9, 1.0, -9.9)
        val blockedFeet = setOf(
            BlockPos(-10, 1, -10),
            BlockPos(-11, 1, -10),
            BlockPos(-10, 1, -11),
        )
        val enterable = enterableFromPassable { cell -> cell !in blockedFeet }

        val resolved = resolveEnterableCell(position, PLAYER_HALF_WIDTH, enterable)

        assertEquals(BlockPos(-11, 1, -11), resolved)
    }

    @Test
    public fun resolveEnterableCellReturnsNullWhenFullyEnclosed(): Unit {
        val position = Vec3(-9.9, 5.5, -9.9)

        val resolved = resolveEnterableCell(position, PLAYER_HALF_WIDTH) { _ -> false }

        assertNull(resolved)
    }

    @Test
    public fun resolveEnterableCellProbesCandidatesNearestFirstInDeterministicOrder(): Unit {
        val position = Vec3(-9.9, 1.0, -9.8)
        val onlyOpenCell = BlockPos(-11, 1, -11)
        val probed = mutableListOf<BlockPos>()
        val enterable: (BlockPos) -> Boolean = { cell ->
            probed.add(cell)
            cell == onlyOpenCell
        }

        val resolved = resolveEnterableCell(position, PLAYER_HALF_WIDTH, enterable)

        assertEquals(onlyOpenCell, resolved)
        assertEquals(
            listOf(
                BlockPos(-10, 1, -10),
                BlockPos(-11, 1, -10),
                BlockPos(-10, 1, -11),
                BlockPos(-11, 1, -11),
            ),
            probed,
        )
    }

    private fun enterableFromPassable(passable: (BlockPos) -> Boolean): (BlockPos) -> Boolean {
        return { cell -> passable(cell) && passable(cell.above()) }
    }

    @Test
    public fun marginWidenedLineClearKeepsCornerWaypointAroundTheWall(): Unit {
        val stateAt = wallStateAt()
        val start = Vec3(0.5, 0.0, 2.0)
        val corner = Vec3(3.5, 0.0, 2.0)
        val goal = Vec3(3.5, 0.0, 1.4)

        val smoothed = smoothFlightPath(
            start = start,
            waypoints = listOf(corner, goal),
            lineClear = { from, to ->
                sweptVolumeCollisionFree(from, to, stateAt, halfWidth = PLAYER_HALF_WIDTH + SMOOTHING_CLEARANCE_MARGIN)
            },
        )

        assertEquals(listOf(corner, goal), smoothed)
    }

    @Test
    public fun marginWidenedLineClearStillCollapsesWithNoBlocksNearby(): Unit {
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { _ -> air }
        val start = Vec3(0.5, 0.0, 2.0)
        val corner = Vec3(3.5, 0.0, 2.0)
        val goal = Vec3(3.5, 0.0, 1.4)

        val smoothed = smoothFlightPath(
            start = start,
            waypoints = listOf(corner, goal),
            lineClear = { from, to ->
                sweptVolumeCollisionFree(from, to, stateAt, halfWidth = PLAYER_HALF_WIDTH + SMOOTHING_CLEARANCE_MARGIN)
            },
        )

        assertEquals(listOf(goal), smoothed)
    }

    @Test
    public fun marginWidenedFootprintDetectsWallThatTheExactFootprintClears(): Unit {
        val stateAt = wallStateAt()
        val from = Vec3(0.5, 0.0, 1.4)
        val to = Vec3(3.5, 0.0, 1.4)

        assertTrue(sweptVolumeCollisionFree(from, to, stateAt))
        assertFalse(
            sweptVolumeCollisionFree(from, to, stateAt, halfWidth = PLAYER_HALF_WIDTH + SMOOTHING_CLEARANCE_MARGIN),
        )
    }

    @Test
    public fun marginWidenedRequiredHeightDetectsCeilingThatDefaultHeightClears(): Unit {
        val stateAt = ceilingStateAt()
        val from = Vec3(0.5, 0.0, 0.5)
        val to = Vec3(3.5, 0.0, 0.5)

        assertTrue(sweptVolumeCollisionFree(from, to, stateAt))
        assertFalse(
            sweptVolumeCollisionFree(from, to, stateAt, requiredHeight = PLAYER_HEIGHT + SMOOTHING_CLEARANCE_MARGIN),
        )
    }

    @Test
    public fun footprintDetectsFenceShapeOwnedByCellBelowPlayerFeet(): Unit {
        val fencePos = BlockPos.ZERO
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == fencePos) fence else air }

        assertFalse(isFootprintCollisionFree(0.5, 1.2, 0.5, stateAt))
        assertTrue(isFootprintCollisionFree(0.5, 1.55, 0.5, stateAt))
    }

    @Test
    public fun sweptVolumeDetectsNarrowDiagonalCornerBetweenSamples(): Unit {
        val obstacle = BlockPos(9, 16, 8)
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == obstacle) stone else air }
        val from = Vec3(10.5002, 16.33, 7.8107)
        val to = Vec3(9.5, 16.05, 7.5)

        assertFalse(sweptVolumeCollisionFree(from, to, stateAt))
    }

    private fun wallStateAt(): (BlockPos) -> BlockState {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val wall = HashMap<BlockPos, BlockState>()
        for (x in 0..3) {
            for (y in 0..2) {
                wall[BlockPos(x, y, 0)] = stone
            }
        }
        return { pos -> wall[pos] ?: air }
    }

    private fun ceilingStateAt(): (BlockPos) -> BlockState {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val ceiling = HashMap<BlockPos, BlockState>()
        for (x in 0..3) {
            ceiling[BlockPos(x, 2, 0)] = stone
        }
        return { pos -> ceiling[pos] ?: air }
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
