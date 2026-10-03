package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

internal class PlanModeFantasySimulationTest {
    private val behavior: PlacementBehaviorSettings =
        PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)

    @BeforeEach
    internal fun resetPrinterSettings() {
        PrinterSettingsHolder.printerSettings = PrinterSettings()
    }

    private class SimWorld(private val minY: Int, private val ground: BlockState, private val air: BlockState) {
        val live: HashMap<BlockPos, BlockState> = HashMap()
        val frozen: HashMap<BlockPos, BlockState> = HashMap()

        fun liveStateAt(pos: BlockPos): BlockState = live[pos] ?: if (pos.y < minY) ground else air
        fun frozenStateAt(pos: BlockPos): BlockState = frozen[pos] ?: if (pos.y < minY) ground else air
        fun recordWrite(pos: BlockPos, state: BlockState): Unit {
            frozen[pos] = state
        }

        fun resyncFrozenFromLive(): Unit {
            frozen.clear()
            frozen.putAll(live)
        }
    }

    private fun fakePlacementContext(hit: BlockHitResult): BlockPlaceContext {
        val context: BlockPlaceContext = mockk(relaxed = true)
        every { context.clickedPos } returns hit.blockPos.relative(hit.direction)
        return context
    }

    private fun centerOf(pos: BlockPos): Vec3 = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    private fun stepToward(from: Vec3, to: Vec3, maxStep: Double): Vec3 {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance <= maxStep) return to
        val scale = maxStep / distance
        return Vec3(from.x + dx * scale, from.y + dy * scale, from.z + dz * scale)
    }

    private fun fakeContext(world: SimWorld, tick: Long, eyePosition: Vec3): PlanRuntimeTickContext {
        var heldState: BlockState? = null
        return PlanRuntimeTickContext(
            tick = tick,
            stateAt = world::frozenStateAt,
            liveStateAt = world::liveStateAt,
            recordWrite = world::recordWrite,
            eyePosition = eyePosition,
            reach = REACH,
            playerFeetPos = null,
            settings = behavior,
            placementContext = { _, hit -> fakePlacementContext(hit) },
            predictPlacement = { _, _ -> null },
            orientedPrediction = { _, _, _ -> PlacementRotation(0f, 0f) },
            itemSupplier = ItemSupplier { state -> heldState = state; true },
            placementGateway = PlacementGateway { hit, _ ->
                heldState?.let { state -> world.live[hit.blockPos.relative(hit.direction).immutable()] = state }
                true
            },
            destroy = { pos -> world.live[pos.immutable()] = Blocks.AIR.defaultBlockState(); true },
            placementIntervalTicks = 1,
        )
    }

    private data class PassRun(val ticksUsed: Long, val nextTick: Long, val eye: Vec3)

    private fun runPass(
        adapter: PlanRuntimeAdapter,
        world: SimWorld,
        startTick: Long,
        startEye: Vec3,
        playerSpeed: Double,
        budget: Long,
    ): PassRun {
        var tick = startTick
        var eye = startEye
        var ticksUsed = 0L
        while (!adapter.cursor.status().isComplete) {
            check(ticksUsed < budget) {
                "plan-mode simulation did not reach completion within $budget ticks: status=${adapter.cursor.status()}"
            }
            val waiting = adapter.frontierSnapshot().waitingForReach
            if (waiting.isNotEmpty()) {
                val strictFrontier = waiting.first()
                eye = stepToward(eye, centerOf(strictFrontier.pos), playerSpeed)
            }
            adapter.tick(fakeContext(world, tick, eye))
            tick++
            ticksUsed++
        }
        return PassRun(ticksUsed, tick, eye)
    }

    private fun resolveFailedTargets(plan: PrintPlan, cursor: PlanExecutionCursor): List<BlockPos> {
        val failed = mutableListOf<BlockPos>()
        var actionId = 0L
        for (unit in executablePlanUnits(plan)) {
            for (action in unit.actions) {
                if (action is PlanAction.PlaceTarget) {
                    val state = cursor.stateOf(actionId)
                    if (state is ActionState.Failed && state.reason == ActionFailureReason.RESOLVE_FAILED) {
                        failed += action.pos
                    }
                }
                actionId++
            }
        }
        return failed
    }

    @Test
    internal fun planModeReachesCompletionWithZeroResolveFailedAcrossBothPasses(): Unit {
        val expected = PlacementSimulationHarness.loadExpected() ?: return
        val content = expected.entries.map { (pos, state) -> pos to state }
        val minY = expected.keys.minOf { it.y }
        val minX = expected.keys.minOf { it.x }
        val minZ = expected.keys.minOf { it.z }
        val ground = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val world = SimWorld(minY, ground, air)
        var playerSpeed = PLAYER_SPEED_BLOCKS_PER_TICK

        val startWall = System.nanoTime()

        val startEye = Vec3(minX - 6.0, minY + 1.5, minZ - 6.0)

        val plan1 = PrintPlanner.plan(content, world::frozenStateAt)
        val cursor1 = PlanExecutionCursor(plan1)
        val adapter1 = PlanRuntimeAdapter(plan1, cursor1)
        val run1 = runPass(adapter1, world, startTick = 0L, startEye = startEye, playerSpeed = playerSpeed, budget = TICK_BUDGET)
        val counts1 = adapter1.targetActionCounts()
        var resolveFailedAll = resolveFailedTargets(plan1, cursor1)

        val status1 = cursor1.status()
        val willRetry = shouldAutoRetryPlanSession(status1, plan1.report.reservedCount > 0, retryUsed = false)

        var finalPlan = plan1
        var placedTotal = counts1.placed
        var pass2Ticks = 0L
        if (willRetry) {
            world.resyncFrozenFromLive()
            val plan2 = PrintPlanner.plan(content, world::frozenStateAt)
            val cursor2 = PlanExecutionCursor(plan2)
            val adapter2 = PlanRuntimeAdapter(plan2, cursor2)
            val run2 = runPass(
                adapter2,
                world,
                startTick = run1.nextTick,
                startEye = run1.eye,
                playerSpeed = playerSpeed,
                budget = TICK_BUDGET,
            )
            pass2Ticks = run2.ticksUsed
            assertTrue(cursor2.status().isComplete) { "pass 2 did not reach completion: ${cursor2.status()}" }
            val counts2 = adapter2.targetActionCounts()
            placedTotal += counts2.placed
            resolveFailedAll = resolveFailedAll + resolveFailedTargets(plan2, cursor2)
            finalPlan = plan2
        }

        val wallMillis = (System.nanoTime() - startWall) / 1_000_000
        val stillReservedAfterRetry = finalPlan.report.reservedCount
        val excludedFinal = finalPlan.report.excludedCategoryCount + finalPlan.report.excludedOccupiedCount +
            finalPlan.report.excludedFallingCount + finalPlan.report.unreachableCount
        println(
            "[PlanModeFantasySimulationTest] pass1Ticks=${run1.ticksUsed} pass2Ticks=$pass2Ticks " +
                "placedTotal=$placedTotal stillReservedAfterRetry=$stillReservedAfterRetry " +
                "excluded=$excludedFinal wallMillis=$wallMillis",
        )

        assertTrue(resolveFailedAll.isEmpty()) {
            "RESOLVE_FAILED target actions found: ${resolveFailedAll.take(10)} (total ${resolveFailedAll.size})"
        }

        for ((pos, expectedState) in expected) {
            val placedState = world.live[pos] ?: continue
            assertTrue(BlockStateEquivalence.matches(expectedState, placedState, behavior)) {
                "placed state at $pos ($placedState) does not match schematic expectation ($expectedState)"
            }
        }

        assertEquals(expected.size, placedTotal + stillReservedAfterRetry + excludedFinal)
        assertTrue(placedTotal >= SURVIVABLE_V3_PLACED_COUNT) {
            "plan-mode placed $placedTotal, below the survivable v3 baseline of $SURVIVABLE_V3_PLACED_COUNT"
        }

        assertTrue(cursor1.status().isComplete)
    }

    internal companion object {
        private const val REACH: Double = 4.0
        private const val PLAYER_SPEED_BLOCKS_PER_TICK: Double = 0.5
        private const val TICK_BUDGET: Long = 500_000L
        private const val SURVIVABLE_V3_PLACED_COUNT: Int = 3072
        private var tags: BoundVanillaTags? = null

        @JvmStatic
        @BeforeAll
        internal fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            tags = BoundVanillaTags.bind()
        }

        @JvmStatic
        @AfterAll
        internal fun unbindTags() {
            tags?.close()
            tags = null
        }
    }
}
