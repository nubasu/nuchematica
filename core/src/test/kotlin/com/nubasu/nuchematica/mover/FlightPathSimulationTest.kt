package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.PlacementSimulationHarness
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

internal class FlightPathSimulationTest {
    @Test
    internal fun doorwayGoesThroughNotOverAndDoorPremiseHolds(): Unit {
        for (closedDoor in listOf(false, true)) {
            val world = HashMap<BlockPos, BlockState>()
            val stone = Blocks.STONE.defaultBlockState()
            for (x in -3..3) {
                for (y in 0..4) {
                    if (x == 0 && (y == 0 || y == 1)) continue
                    world[BlockPos(x, y, 3)] = stone
                }
            }
            if (closedDoor) {
                world[BlockPos(0, 0, 3)] = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                world[BlockPos(0, 1, 3)] = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
            }
            val stateAt = worldStateAt(world)
            val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
            val start = BlockPos(0, 0, -3)
            val goal = BlockPos(0, 0, 9)

            val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

            val path = assertPathFoundAndCollisionFree(result, stateAt, "through the doorway (closedDoor=$closedDoor)")
            val straightLine = distance(start, goal)
            val actual = pathLength(start, path)
            assertTrue(
                actual <= straightLine * 1.2,
                "expected a near-straight path through the doorway, not a detour over the wall " +
                    "(closedDoor=$closedDoor): length=$actual straightLine=$straightLine",
            )
        }
    }

    @Test
    internal fun slabFloorSectionClearanceGatesTraversability(): Unit {
        for (threeHighCeiling in listOf(true, false)) {
            val world = HashMap<BlockPos, BlockState>()
            val stone = Blocks.STONE.defaultBlockState()
            val bottomSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
            val ceilingY = if (threeHighCeiling) 3 else 2
            for (x in -8..14) {
                world[BlockPos(x, -1, 0)] = stone
                world[BlockPos(x, ceilingY, 0)] = stone
                for (y in -6..8) {
                    world[BlockPos(x, y, -1)] = stone
                    world[BlockPos(x, y, 1)] = stone
                }
            }
            for (x in 3..5) {
                world[BlockPos(x, 0, 0)] = bottomSlab
            }
            val stateAt = worldStateAt(world)
            val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
            val start = BlockPos(-2, 0, 0)
            val goal = BlockPos(8, 0, 0)

            val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

            if (threeHighCeiling) {
                assertPathFoundAndCollisionFree(result, stateAt, "over the slab step with 2.5 free above it")
            } else {
                assertNull(result.path, "1.5 free above the raised slab step must not be traversable")
            }
        }
    }

    @Test
    internal fun slabStepMidStraightCorridorUsesCollisionFreeRaisedWaypoints(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        val bottomSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
        for (x in -4..10) {
            world[BlockPos(x, -1, 0)] = stone
            world[BlockPos(x, 3, 0)] = stone
            for (y in -6..8) {
                world[BlockPos(x, y, -1)] = stone
                world[BlockPos(x, y, 1)] = stone
            }
        }
        world[BlockPos(3, 0, 0)] = bottomSlab
        val stateAt = worldStateAt(world)
        val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val start = BlockPos(-2, 0, 0)
        val goal = BlockPos(8, 0, 0)

        val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

        val path = assertPathFoundAndCollisionFree(result, stateAt, "straight corridor with a mid-span slab step")
        val feetHeights = requireNotNull(result.feetHeights)
        assertTrue(feetHeights.any { feetY -> feetY >= 1.0 }) {
            "the only collision-free corridor route must rise above the slab before crossing"
        }
        var previous = Vec3(start.x + 0.5, requireNotNull(profile.feetHeightAt(start)), start.z + 0.5)
        for ((index, node) in path.withIndex()) {
            val next = Vec3(node.x + 0.5, feetHeights[index], node.z + 0.5)
            assertTrue(sweptVolumeCollisionFree(previous, next, stateAt)) {
                "unsafe simplified segment $previous -> $next"
            }
            previous = next
        }
    }

    @Test
    internal fun naiveFullCellModelWronglyAcceptsTheFenceOverhangRealCorrectlyRejectsIt(): Unit {
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -2..8) {
            world[BlockPos(x, 0, 0)] = fence
            world[BlockPos(x, 3, 0)] = stone
        }
        val stateAt = worldStateAt(world)
        val candidate = BlockPos(3, 1, 0)

        val naiveHeight = naiveProfile(stateAt).feetHeightAt(candidate)
        val realHeight = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt).feetHeightAt(candidate)

        assertNotNull(naiveHeight, "naive full-cell-solid model wrongly treats this cell as enterable")
        assertNull(realHeight, "real collision-aware predicate must reject insufficient clearance above the fence")
    }

    @Test
    internal fun naiveFullCellModelAgreesWithRealOnTheSlabSectionNotTraversableCase(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        val bottomSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
        for (x in -2..8) {
            world[BlockPos(x, -1, 0)] = stone
            world[BlockPos(x, 2, 0)] = stone
        }
        world[BlockPos(4, 0, 0)] = bottomSlab
        val stateAt = worldStateAt(world)
        val naive = naiveProfile(stateAt)
        val real = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val feetCandidate = BlockPos(4, 0, 0)
        val headCandidate = BlockPos(4, 1, 0)

        assertNull(naive.feetHeightAt(feetCandidate))
        assertNull(real.feetHeightAt(feetCandidate))
        assertNull(naive.feetHeightAt(headCandidate))
        assertNull(real.feetHeightAt(headCandidate))
    }

    @Test
    internal fun fenceLineNeedsFullHeadroomAboveTheOneAndHalfBlockShape(): Unit {
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        for (gapHeight in listOf(3, 4)) {
            val world = HashMap<BlockPos, BlockState>()
            val stone = Blocks.STONE.defaultBlockState()
            for (x in -2..8) {
                world[BlockPos(x, 0, 0)] = fence
                world[BlockPos(x, gapHeight, 0)] = stone
            }
            val stateAt = worldStateAt(world)
            val candidate = BlockPos(3, 1, 0)
            val height = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt).feetHeightAt(candidate)

            if (gapHeight == 3) {
                assertNull(height, "1.5 free above the fence (gap=3) must not be traversable")
            } else {
                assertEquals(1.55, height, "gap=4 leaves 2.5 free above the fence's own 1.5-tall shape")
            }
        }

        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -4..10) {
            world[BlockPos(x, 0, 0)] = fence
            world[BlockPos(x, 4, 0)] = stone
        }
        val stateAt = worldStateAt(world)
        val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val start = BlockPos(0, 1, -3)
        val goal = BlockPos(0, 1, 9)

        val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

        assertPathFoundAndCollisionFree(result, stateAt, "crossing the fence line with a 4-high gap")
    }

    @Test
    internal fun overhangReachedFromTheSideStaysUnderTheCanopy(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        val canopyY = 2
        for (x in -1..10) {
            for (z in -6..6) {
                world[BlockPos(x, canopyY, z)] = stone
            }
        }
        val stateAt = worldStateAt(world)
        val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val start = BlockPos(-3, 0, 0)
        val goal = BlockPos(5, 0, 0)

        val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

        val path = assertPathFoundAndCollisionFree(result, stateAt, "reaching a target under the canopy")
        assertTrue(
            (listOf(start) + path).all { node -> node.y < canopyY },
            "path must stay under the solid canopy, never pop up above it",
        )
    }

    @Test
    internal fun outOfRegionDetourHonorsExplicitBoundsBothWays(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -2..14) {
            for (y in -6..6) {
                world[BlockPos(x, y, 5)] = stone
            }
        }
        val stateAt = worldStateAt(world)
        val start = BlockPos(0, 0, 2)
        val goal = BlockPos(4, 0, 8)
        val exactBuildBox = AABB(0.0, 0.0, 0.0, 5.0, 3.0, 11.0)
        val inflatedBuildBox = AABB(-8.0, -8.0, -8.0, 13.0, 11.0, 19.0)

        val exactResult = findFlightPath(
            start,
            goal,
            isPassableCell = { false },
            profile = FlightPassabilityProfile.collisionAware(exactBuildBox, stateAt),
        )
        assertNull(exactResult.path, "the only route detours outside the exact build box -- must fail")

        val inflatedResult = findFlightPath(
            start,
            goal,
            isPassableCell = { false },
            profile = FlightPassabilityProfile.collisionAware(inflatedBuildBox, stateAt),
        )
        val path = assertPathFoundAndCollisionFree(inflatedResult, stateAt, "with bounds inflated by 8")
        assertTrue(
            path.any { node -> node.x < 0 },
            "expected the successful path to actually use the detour outside the exact box",
        )
    }

    @Test
    internal fun explicitBoundsReachADetourFarBeyondTheLegacyMarginCap(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -8..14) {
            for (y in -6..6) {
                if (x == -6) continue
                world[BlockPos(x, y, 5)] = stone
            }
        }
        val stateAt = worldStateAt(world)
        val start = BlockPos(0, 0, 2)
        val goal = BlockPos(4, 0, 8)
        val travelBounds = AABB(-10.0, -8.0, -8.0, 15.0, 8.0, 20.0)

        val result = findFlightPath(
            start,
            goal,
            isPassableCell = { false },
            profile = FlightPassabilityProfile.collisionAware(travelBounds, stateAt),
        )

        val path = assertPathFoundAndCollisionFree(result, stateAt, "detour 6 cells beyond the legacy margin cap")
        assertTrue(path.any { node -> node.x < -4 }, "expected the path to actually use the far detour opening")
    }

    @Test
    internal fun legacyProfileDeclaresNoFractionalHeightTrackingUnlikeCollisionAware(): Unit {
        val stateAt = worldStateAt(emptyMap())
        val legacy = FlightPassabilityProfile.legacy { true }
        val collisionAware = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)

        assertFalse(legacy.tracksFractionalHeight())
        assertTrue(collisionAware.tracksFractionalHeight())
    }

    @Test
    internal fun sealedPocketFailsCleanlyWithinBudget(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -1..5) {
            for (y in -1..5) {
                for (z in -1..5) {
                    val isShell = x == -1 || x == 5 || y == -1 || y == 5 || z == -1 || z == 5
                    if (isShell) world[BlockPos(x, y, z)] = stone
                }
            }
        }
        val stateAt = worldStateAt(world)
        val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val start = BlockPos(20, 0, 20)
        val goal = BlockPos(2, 0, 2)

        val startNanos = System.nanoTime()
        val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile, expansionBudget = 10)
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        assertNull(result.path)
        assertEquals(FlightPathFailureReason.BUDGET_EXHAUSTED, result.failureReason)
        assertTrue(elapsedMillis < 5000, "sealed pocket search must not hang: ${elapsedMillis}ms")
    }

    @Test
    internal fun identicalWorldAndEndpointsProduceIdenticalPath(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -3..3) {
            for (y in 0..4) {
                if (x == 0 && (y == 0 || y == 1)) continue
                world[BlockPos(x, y, 3)] = stone
            }
        }
        val stateAt = worldStateAt(world)
        val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val start = BlockPos(0, 0, -3)
        val goal = BlockPos(0, 0, 9)

        val first = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)
        val second = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

        assertEquals(first.path, second.path)
        assertEquals(first.feetHeights, second.feetHeights)
    }

    @Test
    internal fun fantasyBigHouseFullReachabilitySweep(): Unit {
        val expected = PlacementSimulationHarness.loadExpected() ?: return
        val startNanos = System.nanoTime()

        val minX = expected.keys.minOf { pos -> pos.x } - INFLATE
        val maxX = expected.keys.maxOf { pos -> pos.x } + INFLATE
        val minY = expected.keys.minOf { pos -> pos.y } - INFLATE
        val maxY = expected.keys.maxOf { pos -> pos.y } + INFLATE
        val minZ = expected.keys.minOf { pos -> pos.z } - INFLATE
        val maxZ = expected.keys.maxOf { pos -> pos.z } + INFLATE

        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> expected[pos] ?: air }
        val bounds = AABB(
            minX.toDouble(),
            minY.toDouble(),
            minZ.toDouble(),
            (maxX + 1).toDouble(),
            (maxY + 1).toDouble(),
            (maxZ + 1).toDouble(),
        )
        val profile = FlightPassabilityProfile.collisionAware(bounds, stateAt)

        val passable = HashMap<BlockPos, Double>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val pos = BlockPos(x, y, z)
                    val feetHeight = profile.feetHeightAt(pos) ?: continue
                    passable[pos] = feetHeight
                }
            }
        }
        for ((pos, feetHeight) in passable) {
            assertNodeCollisionFree(pos, feetHeight, stateAt)
        }

        val outdoorStart = BlockPos(minX, expected.keys.minOf { pos -> pos.y }, minZ)
        assertTrue(passable.containsKey(outdoorStart), "fixed outdoor start must itself be passable")

        val floodFill = floodFillReachable(outdoorStart, passable)
        val reachable = floodFill.reachable
        val sealed = passable.keys - reachable

        val sortedReachable = reachable.sortedWith(
            compareBy({ pos -> pos.x }, { pos -> pos.y }, { pos -> pos.z }),
        )
        val byLevel = LinkedHashMap<Int, BlockPos>()
        for (pos in sortedReachable) byLevel.putIfAbsent(pos.y, pos)
        val farthest = sortedReachable.maxByOrNull { pos -> squaredDistance(pos, outdoorStart) }

        val sample = LinkedHashSet<BlockPos>()
        sample.addAll(byLevel.values)
        if (farthest != null) sample.add(farthest)
        if (sortedReachable.size <= SAMPLE_TARGET) {
            sample.addAll(sortedReachable)
        } else {
            val step = sortedReachable.size.toDouble() / SAMPLE_TARGET
            var cursor = 0.0
            while (sample.size < SAMPLE_TARGET) {
                sample.add(sortedReachable[cursor.toInt().coerceAtMost(sortedReachable.lastIndex)])
                cursor += step
            }
        }

        for (target in sample) {
            val waypoints = chainWaypoints(target, outdoorStart, floodFill.parent)
            for (index in 0 until waypoints.lastIndex) {
                val legStart = waypoints[index]
                val legGoal = waypoints[index + 1]
                val result = findFlightPath(
                    legStart,
                    legGoal,
                    isPassableCell = { false },
                    expansionBudget = SWEEP_EXPANSION_BUDGET,
                    profile = profile,
                )
                assertPathFoundAndCollisionFree(
                    result,
                    stateAt,
                    "leg $legStart -> $legGoal (leg $index/${waypoints.lastIndex}) toward sampled cell $target",
                )
            }
        }

        if (farthest != null) {
            val singleShotResult = findFlightPath(
                outdoorStart,
                farthest,
                isPassableCell = { false },
                expansionBudget = SWEEP_EXPANSION_BUDGET,
                profile = profile,
            )
            assertPathFoundAndCollisionFree(
                singleShotResult,
                stateAt,
                "single-shot leg from $outdoorStart to the farthest reachable cell $farthest, no leg chaining",
            )
        }

        if (sealed.isNotEmpty()) {
            val sealedTarget = sealed.first()
            val sealedResult = findFlightPath(
                outdoorStart,
                sealedTarget,
                isPassableCell = { false },
                expansionBudget = SWEEP_EXPANSION_BUDGET,
                profile = profile,
            )
            assertNull(sealedResult.path, "sealed pocket at $sealedTarget must be unreachable from the outdoor start")
        }

        val elapsedSeconds = (System.nanoTime() - startNanos) / 1.0E9
        println(
            "Fantasy sweep: passable=${passable.size} reachable=${reachable.size} " +
                "sealed=${sealed.size} sampled=${sample.size} elapsedSeconds=$elapsedSeconds",
        )
        assertTrue(elapsedSeconds < 30.0, "sweep took ${elapsedSeconds}s, must stay under ~30s")
    }

    @Test
    internal fun composterHollowCavityIsNotFalselyBlockingUnderALowCeiling(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        world[BlockPos(0, 0, 0)] = Blocks.COMPOSTER.defaultBlockState()
        world[BlockPos(0, 2, 0)] = Blocks.STONE.defaultBlockState()
        val stateAt = worldStateAt(world)

        val height = collisionFittingFeetHeight(0, 0, 0, stateAt)

        assertEquals(
            0.175,
            height,
            "the composter's own thin floor (not its overall bounding envelope) is the real obstruction",
        )
    }

    @Test
    internal fun huggingPenaltyKeepsThePathOffAWallEvenOnARawDistanceTie(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -4..8) {
            world[BlockPos(x, 0, -1)] = stone
        }
        val stateAt = worldStateAt(world)
        val start = BlockPos(0, 0, 0)
        val goal = BlockPos(4, 0, 1)
        val bounds = AABB(-10.0, -10.0, -10.0, 10.0, 10.0, 10.0)

        val realProfile = FlightPassabilityProfile.collisionAware(bounds, stateAt)
        val unpenalizedProfile = object : FlightPassabilityProfile {
            override fun feetHeightAt(pos: BlockPos): Double? = realProfile.feetHeightAt(pos)
            override fun searchBounds(): AABB? = realProfile.searchBounds()
            override fun tracksFractionalHeight(): Boolean = realProfile.tracksFractionalHeight()
            override fun steppingCost(pos: BlockPos): Double = 0.0
        }

        val redResult = findFlightPath(start, goal, isPassableCell = { false }, profile = unpenalizedProfile)
        val redPath = assertPathFoundAndCollisionFree(redResult, stateAt, "raw-distance tie, unpenalized")
        assertTrue(
            redPath.any { node -> node.z <= 0 },
            "documents the RED: with no hugging cost, a same-length path along the wall is not disfavored",
        )

        val result = findFlightPath(start, goal, isPassableCell = { false }, profile = realProfile)
        val path = assertPathFoundAndCollisionFree(result, stateAt, "hugging-penalized")
        assertTrue(
            path.none { node -> node.z <= 0 },
            "expected the penalized path to keep at least 1 cell clear of the wall",
        )
    }

    @Test
    internal fun huggingPenaltyStillRoutesThroughAOneWideDoorway(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        val stone = Blocks.STONE.defaultBlockState()
        for (x in -3..3) {
            for (y in 0..4) {
                if (x == 0 && (y == 0 || y == 1)) continue
                world[BlockPos(x, y, 3)] = stone
            }
        }
        val stateAt = worldStateAt(world)
        val profile = FlightPassabilityProfile.collisionAware(UNBOUNDED, stateAt)
        val start = BlockPos(0, 0, -3)
        val goal = BlockPos(0, 0, 9)

        val result = findFlightPath(start, goal, isPassableCell = { false }, profile = profile)

        assertPathFoundAndCollisionFree(result, stateAt, "through a one-wide doorway despite the hugging penalty")
    }

    @Test
    internal fun sweptVolumeCheckKeepsLCornerWaypointADirectDiagonalWouldClip(): Unit {
        val world = HashMap<BlockPos, BlockState>()
        world[BlockPos(3, 0, 1)] = Blocks.STONE.defaultBlockState()
        val stateAt = worldStateAt(world)
        val start = Vec3(2.5, 0.0, 0.5)
        val corner = Vec3(2.5, 0.0, 2.5)
        val goal = Vec3(4.5, 0.0, 2.5)

        val naiveSmoothed = smoothFlightPath(
            start = start,
            waypoints = listOf(corner, goal),
            lineClear = { _, _ -> true },
        )
        assertEquals(
            listOf(goal),
            naiveSmoothed,
            "documents the RED: a volume-blind lineClear drops the corner waypoint",
        )
        assertFalse(
            sweptVolumeCollisionFree(start, goal, stateAt),
            "the straight diagonal must genuinely clip the pillar's own shape",
        )

        val smoothed = smoothFlightPath(
            start = start,
            waypoints = listOf(corner, goal),
            lineClear = { from, to -> sweptVolumeCollisionFree(from, to, stateAt) },
        )

        assertEquals(listOf(corner, goal), smoothed, "the corner waypoint must survive the diagonal that clips it")
    }

    private fun worldStateAt(world: Map<BlockPos, BlockState>): (BlockPos) -> BlockState {
        val air = Blocks.AIR.defaultBlockState()
        return { pos -> world[pos] ?: air }
    }

    private fun naiveProfile(stateAt: (BlockPos) -> BlockState): FlightPassabilityProfile {
        val getter = CollisionBlockGetterAdapter(stateAt)
        val isPassableCell: (BlockPos) -> Boolean = { pos -> stateAt(pos).getCollisionShape(getter, pos).isEmpty }
        return FlightPassabilityProfile.legacy(isPassableCell)
    }

    private fun assertPathFoundAndCollisionFree(
        result: FlightPathResult,
        stateAt: (BlockPos) -> BlockState,
        message: String,
    ): List<BlockPos> {
        val path = requireNotNull(result.path) { "expected a path $message, failureReason=${result.failureReason}" }
        val feetHeights = requireNotNull(result.feetHeights) { "expected feet heights $message" }
        assertEquals(path.size, feetHeights.size)
        for (index in path.indices) {
            assertNodeCollisionFree(path[index], feetHeights[index], stateAt)
        }
        return path
    }

    private fun assertNodeCollisionFree(node: BlockPos, feetHeight: Double, stateAt: (BlockPos) -> BlockState): Unit {
        val getter = CollisionBlockGetterAdapter(stateAt)
        val playerBox = AABB(
            node.x + 0.5 - PLAYER_HALF_WIDTH,
            feetHeight,
            node.z + 0.5 - PLAYER_HALF_WIDTH,
            node.x + 0.5 + PLAYER_HALF_WIDTH,
            feetHeight + PLAYER_HEIGHT,
            node.z + 0.5 + PLAYER_HALF_WIDTH,
        )
        for (yOffset in -1..2) {
            val checkPos = BlockPos(node.x, node.y + yOffset, node.z)
            val state = stateAt(checkPos)
            if (state.block is DoorBlock) continue
            val shape = state.getCollisionShape(getter, checkPos)
            if (shape.isEmpty) continue
            for (box in shape.toAabbs()) {
                val worldBox = box.move(checkPos.x.toDouble(), checkPos.y.toDouble(), checkPos.z.toDouble())
                assertFalse(
                    playerBox.intersects(worldBox),
                    "node $node at feet height $feetHeight intersects collision at $checkPos",
                )
            }
        }
    }

    private fun pathLength(start: BlockPos, path: List<BlockPos>): Double {
        var total = 0.0
        var anchor = start
        for (node in path) {
            total += distance(anchor, node)
            anchor = node
        }
        return total
    }

    private fun distance(a: BlockPos, b: BlockPos): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val dz = (b.z - a.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun squaredDistance(a: BlockPos, b: BlockPos): Long {
        val dx = (a.x - b.x).toLong()
        val dy = (a.y - b.y).toLong()
        val dz = (a.z - b.z).toLong()
        return dx * dx + dy * dy + dz * dz
    }

    private class FloodFillResult(val reachable: Set<BlockPos>, val parent: Map<BlockPos, BlockPos>)

    private fun floodFillReachable(start: BlockPos, passable: Map<BlockPos, Double>): FloodFillResult {
        if (!passable.containsKey(start)) return FloodFillResult(emptySet(), emptyMap())
        val visited = HashSet<BlockPos>()
        val parent = HashMap<BlockPos, BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue.addLast(start)
        visited.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in sixNeighbors(current)) {
                if (neighbor !in visited && passable.containsKey(neighbor)) {
                    visited.add(neighbor)
                    parent[neighbor] = current
                    queue.addLast(neighbor)
                }
            }
        }
        return FloodFillResult(visited, parent)
    }

    private fun chainWaypoints(target: BlockPos, start: BlockPos, parent: Map<BlockPos, BlockPos>): List<BlockPos> {
        val fullChain = ArrayList<BlockPos>()
        var current = target
        fullChain.add(current)
        while (current != start) {
            current = parent[current] ?: break
            fullChain.add(current)
        }
        fullChain.reverse()
        val waypoints = ArrayList<BlockPos>()
        var index = 0
        while (index < fullChain.lastIndex) {
            waypoints.add(fullChain[index])
            index += MAX_LOCAL_HOPS
        }
        waypoints.add(fullChain.last())
        return waypoints
    }

    private fun sixNeighbors(pos: BlockPos): List<BlockPos> {
        return listOf(
            BlockPos(pos.x + 1, pos.y, pos.z),
            BlockPos(pos.x - 1, pos.y, pos.z),
            BlockPos(pos.x, pos.y, pos.z + 1),
            BlockPos(pos.x, pos.y, pos.z - 1),
            BlockPos(pos.x, pos.y + 1, pos.z),
            BlockPos(pos.x, pos.y - 1, pos.z),
        )
    }

    private companion object {
        private val UNBOUNDED = AABB(-40.0, -40.0, -40.0, 40.0, 40.0, 40.0)
        private const val INFLATE: Int = 2
        private const val SAMPLE_TARGET: Int = 200

        private const val MAX_LOCAL_HOPS: Int = 8

        private const val SWEEP_EXPANSION_BUDGET: Int = 65536

        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
