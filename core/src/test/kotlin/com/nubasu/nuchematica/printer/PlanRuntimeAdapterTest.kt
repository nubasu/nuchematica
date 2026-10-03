package com.nubasu.nuchematica.printer

import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

private fun fakePlacementContext(hit: BlockHitResult, clickedPosOverride: BlockPos? = null): BlockPlaceContext {
    val context: BlockPlaceContext = mockk(relaxed = true)
    every { context.clickedPos } returns (clickedPosOverride ?: hit.blockPos.relative(hit.direction))
    return context
}

internal class PlanRuntimeAdapterTest {
    private val stone: BlockState = Blocks.STONE.defaultBlockState()
    private val air: BlockState = Blocks.AIR.defaultBlockState()
    private val defaultSettings: PlacementBehaviorSettings =
        PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)

    private fun emptyReport(): BuildabilityReport {
        return BuildabilityReport(
            directCount = 0,
            scaffoldCount = 0,
            reservedCount = 0,
            excludedCategoryCount = 0,
            excludedOccupiedCount = 0,
            excludedFallingCount = 0,
            unreachableCount = 0,
            alreadyPlacedCount = 0,
            excludedPositions = emptyList(),
            unreachablePositions = emptyList(),
            scaffoldMaterialPositions = emptyList(),
        )
    }

    private fun planOf(actions: List<PlanAction>): PrintPlan {
        val unit = PlanUnit(band = 0, tileX = 0, tileZ = 0, actions = actions)
        return PrintPlan(units = listOf(unit), reservations = emptyMap(), report = emptyReport())
    }

    private fun PlanExecutionCursor.submitEarliestFrontier(): CursorAction {
        return submitSpecific(orderedFrontier().first())!!
    }

    private class Harness(private val liveWorld: MutableMap<BlockPos, BlockState>) {
        private val frozenModel: MutableMap<BlockPos, BlockState> = HashMap(liveWorld)
        var tick: Long = 0L
        var eyePosition: Vec3 = Vec3(0.5, 1.5, 0.5)
        var reach: Double = 10.0
        var playerFeetPos: Vec3? = null
        var itemAvailable: Boolean = true
        var submitSucceeds: Boolean = true
        var destroySucceeds: Boolean = true
        var placementIntervalTicks: Int = 1
        var attemptsPerTick: Int = 1
        private var heldState: BlockState? = null
        var destroyCallCount: Int = 0
            private set
        var submitCallCount: Int = 0
            private set

        val clearedPendingPlace: MutableList<BlockPos> = mutableListOf()
        val clearedPendingBreak: MutableList<BlockPos> = mutableListOf()

        fun stateAt(pos: BlockPos): BlockState = liveWorld[pos] ?: Blocks.AIR.defaultBlockState()

        fun frozenStateAt(pos: BlockPos): BlockState = frozenModel[pos] ?: Blocks.AIR.defaultBlockState()

        fun recordFrozenWrite(pos: BlockPos, state: BlockState): Unit {
            frozenModel[pos] = state
        }

        fun setState(pos: BlockPos, state: BlockState): Unit {
            liveWorld[pos] = state
        }

        fun destroy(pos: BlockPos): Boolean {
            destroyCallCount++
            if (destroySucceeds) liveWorld[pos] = Blocks.AIR.defaultBlockState()
            return destroySucceeds
        }

        fun context(
            settings: PlacementBehaviorSettings = defaultSettingsFor(),
            clickedPosOverride: BlockPos? = null,
            onFrozenLiveDivergence: () -> Unit = {},
            orientedPrediction: (BlockItem, BlockPlaceContext, BlockState) -> PlacementRotation? = { _, _, _ -> null },
            preparePlacementRotation: (Long, BlockPos, PlacementRotation) -> Boolean = { _, _, _ -> true },
            finishPlacementRotation: (Long, BlockPos) -> Unit = { _, _ -> },
            cancelPlacementRotation: (Long, BlockPos) -> Unit = { _, _ -> },
        ): PlanRuntimeTickContext {
            return PlanRuntimeTickContext(
                tick = tick,
                stateAt = ::frozenStateAt,
                liveStateAt = ::stateAt,
                recordWrite = ::recordFrozenWrite,
                eyePosition = eyePosition,
                reach = reach,
                playerFeetPos = playerFeetPos,
                settings = settings,
                placementContext = { _, hit -> fakePlacementContext(hit, clickedPosOverride) },
                predictPlacement = { item, _ -> item.block.defaultBlockState() },
                orientedPrediction = orientedPrediction,
                itemSupplier = ItemSupplier { state ->
                    if (itemAvailable) {
                        heldState = state
                        true
                    } else {
                        false
                    }
                },
                placementGateway = PlacementGateway { hit, _ ->
                    submitCallCount++
                    if (submitSucceeds) {
                        heldState?.let { state -> liveWorld[hit.blockPos.relative(hit.direction)] = state }
                    }
                    submitSucceeds
                },
                preparePlacementRotation = preparePlacementRotation,
                finishPlacementRotation = finishPlacementRotation,
                cancelPlacementRotation = cancelPlacementRotation,
                destroy = ::destroy,
                placementIntervalTicks = placementIntervalTicks,
                attemptsPerTick = attemptsPerTick,
                onFrozenLiveDivergence = onFrozenLiveDivergence,
                clearPendingPlace = clearedPendingPlace::add,
                clearPendingBreak = clearedPendingBreak::add,
            )
        }

        private fun defaultSettingsFor(): PlacementBehaviorSettings =
            PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)
    }

    private fun runUntilComplete(
        adapter: PlanRuntimeAdapter,
        harness: Harness,
        maxTicks: Int = 80,
    ): Unit {
        var ticks = 0
        while (!adapter.cursor.status().isComplete && ticks < maxTicks) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        assertTrue(adapter.cursor.status().isComplete) {
            "plan did not complete within $maxTicks ticks, status=${adapter.cursor.status()}"
        }
    }

    @Test
    internal fun directPlacementUnitCompletesEndToEnd(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(1, status.doneCount)
        assertEquals(0, status.failedCount)
        assertEquals(stone, harness.stateAt(target))
    }

    @Test
    internal fun orientationSensitivePlacementWaitsForRotationPreparationBeforeSubmitting(): Unit {
        val target = BlockPos(0, 2, 0)
        val expected = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        val required = PlacementRotation(yaw = 90f, pitch = 0f)
        val harness = Harness(mutableMapOf(target.below() to stone))
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, expected, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))
        var ready = false
        val prepared = mutableListOf<Triple<Long, BlockPos, PlacementRotation>>()
        val finished = mutableListOf<Pair<Long, BlockPos>>()
        fun context(): PlanRuntimeTickContext = harness.context(
            orientedPrediction = { _, _, _ -> required },
            preparePlacementRotation = { actionId, pos, rotation ->
                prepared += Triple(actionId, pos, rotation)
                ready
            },
            finishPlacementRotation = { actionId, pos -> finished += actionId to pos },
        )

        adapter.tick(context())
        harness.tick++

        val waiting = adapter.cursor.stateOf(0)
        assertTrue(waiting is ActionState.Waiting)
        assertEquals(WaitingReason.WAITING_FOR_ROTATION, (waiting as ActionState.Waiting).reason)
        assertEquals(0, harness.submitCallCount) { "must not click in the packet's first tick" }
        assertEquals(listOf(Triple(0L, target, required)), prepared)
        assertTrue(finished.isEmpty())

        ready = true
        adapter.tick(context())
        harness.tick++

        assertEquals(1, harness.submitCallCount)
        assertEquals(listOf(0L to target), finished)
        assertTrue(adapter.cursor.stateOf(0) is ActionState.InFlight)
    }

    @Test
    internal fun reservationExecutesAfterItsNaturalTriggerAndCountsAsAPlacedTarget(): Unit {
        val support = BlockPos(0, 1, 0)
        val reservedTarget = support.above()
        val states = mutableMapOf(support.below() to stone)
        val harness = Harness(states)
        val naturalUnit = PlanUnit(
            band = 0,
            tileX = 0,
            tileZ = 0,
            actions = listOf(PlanAction.PlaceTarget(support, stone, groupId = 1)),
        )
        val plan = PrintPlan(
            units = listOf(naturalUnit),
            reservations = mapOf(
                0 to listOf(
                    PlanAction.PlaceTarget(
                        reservedTarget,
                        stone,
                        dependsOn = listOf(support),
                        groupId = 2,
                    ),
                ),
            ),
            report = emptyReport(),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        runUntilComplete(adapter, harness)

        assertEquals(stone, harness.stateAt(support))
        assertEquals(stone, harness.stateAt(reservedTarget))
        assertEquals(2, adapter.cursor.status().doneCount)
        assertEquals(PlanTargetCounts(remaining = 0, placed = 2, failedOrSkipped = 0), adapter.targetActionCounts())
    }

    @Test
    internal fun scaffoldTransactionCompletesEndToEndIncludingBreak(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(3, status.doneCount)
        assertEquals(0, status.failedCount)
        assertEquals(stone, harness.stateAt(target))
        assertEquals(air, harness.stateAt(scaffoldPos))
        assertTrue(harness.destroyCallCount >= 1)
    }

    private fun runGroupUntilScaffoldDoneAndTargetInFlight(
        scaffoldPos: BlockPos,
        target: BlockPos,
    ): Pair<PlanRuntimeAdapter, Harness> {
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        var ticks = 0
        while (adapter.cursor.status().doneCount < 1 && ticks < 40) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        assertEquals(1, adapter.cursor.status().doneCount)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.InFlight)
        return adapter to harness
    }

    @Test
    internal fun removeNoOpWhenCellAlreadyAir(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val (adapter, harness) = runGroupUntilScaffoldDoneAndTargetInFlight(scaffoldPos, target)

        harness.setState(scaffoldPos, air)
        val destroyCallsBefore = harness.destroyCallCount
        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(3, status.doneCount)
        assertEquals(0, status.failedCount)
        assertEquals(destroyCallsBefore, harness.destroyCallCount)
    }

    @Test
    internal fun removeFailsWithoutBreakingWhenForeignBlockOccupiesCell(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val (adapter, harness) = runGroupUntilScaffoldDoneAndTargetInFlight(scaffoldPos, target)

        harness.setState(scaffoldPos, Blocks.COBBLESTONE.defaultBlockState())
        val destroyCallsBefore = harness.destroyCallCount
        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(2, status.doneCount)
        assertEquals(1, status.failedCount)
        assertEquals(destroyCallsBefore, harness.destroyCallCount)
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), harness.stateAt(scaffoldPos))
    }

    @Test
    internal fun outstandingScaffoldCellsReportsAPlacedButNotYetRemovedCell(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val (adapter, _) = runGroupUntilScaffoldDoneAndTargetInFlight(scaffoldPos, target)

        assertEquals(listOf(scaffoldPos), adapter.outstandingScaffoldCells())
    }

    @Test
    internal fun scaffoldTimeoutStillSubmitsMandatoryRemove(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        val deadline = PrinterAttemptTracker.DEADLINE_TICKS
        val exhaustionTick = (PrinterRuntime.MAX_RETRIES + 1) * deadline
        fun toggledContext(): PlanRuntimeTickContext {
            return PlanRuntimeTickContext(
                tick = harness.tick,
                stateAt = harness::frozenStateAt,
                liveStateAt = { pos ->
                    if (pos == scaffoldPos) {
                        when {
                            harness.tick == exhaustionTick -> SCAFFOLD_BLOCK_STATE
                            harness.tick % deadline == 0L -> air
                            harness.tick % 2L == 0L -> air
                            else -> Blocks.COBBLESTONE.defaultBlockState()
                        }
                    } else {
                        harness.stateAt(pos)
                    }
                },
                recordWrite = harness::recordFrozenWrite,
                eyePosition = harness.eyePosition,
                reach = harness.reach,
                playerFeetPos = null,
                settings = defaultSettings,
                placementContext = { _, hit -> fakePlacementContext(hit) },
                predictPlacement = { item, _ -> item.block.defaultBlockState() },
                orientedPrediction = { _, _, _ -> null },
                itemSupplier = ItemSupplier { true },
                placementGateway = PlacementGateway { _, _ -> true },
                destroy = harness::destroy,
                placementIntervalTicks = 1,
                attemptsPerTick = 1,
            )
        }

        var ticks = 0
        while (adapter.cursor.status().failedCount == 0 && ticks < 200) {
            adapter.tick(toggledContext())
            harness.tick++
            ticks++
        }
        assertEquals(1, adapter.cursor.status().failedCount)

        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
        assertEquals(1, status.doneCount)
        assertEquals(1, status.skippedCount)
        assertTrue(harness.destroyCallCount >= 1)
    }

    @Test
    internal fun postCleanupMismatchDowngradesADoneTargetAfterGroupCleanupSettles(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        var ticks = 0
        while (adapter.cursor.status().doneCount < 2 && ticks < 40) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        assertEquals(2, adapter.cursor.status().doneCount)
        harness.setState(target, Blocks.DIRT.defaultBlockState())

        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(1, status.failedCount)
        assertEquals(2, status.doneCount)
    }

    @Test
    internal fun outOfReachTargetResolvesOnceEyeMovesCloser(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.eyePosition = Vec3(500.0, 500.0, 500.0)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        val statusWhileFar = adapter.cursor.status()
        assertEquals(1, statusWhileFar.waitingCount)
        assertFalse(statusWhileFar.isComplete)

        harness.eyePosition = Vec3(0.5, 1.5, 0.5)
        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(1, status.doneCount)
    }

    @Test
    internal fun itemUnavailableReportsEventWithoutSubmitting(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val status = adapter.cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
        assertEquals(air, harness.stateAt(target))
    }

    @Test
    internal fun gatewaySubmitFailureReportsSubmitFailed(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.submitSucceeds = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val status = adapter.cursor.status()
        assertTrue(status.isComplete)
        assertEquals(1, status.failedCount)
    }

    @Test
    internal fun submitFailureClearsPendingPlaceForThatWorldPos(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.submitSucceeds = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        assertTrue(harness.clearedPendingPlace.isEmpty())

        adapter.tick(harness.context())

        assertEquals(1, adapter.cursor.status().failedCount)
        assertEquals(listOf(target), harness.clearedPendingPlace)
    }

    @Test
    internal fun rejectedPlacementRetriesWithFreshAttemptIdThenAccepts(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        fun rejectingContext(): PlanRuntimeTickContext {
            return PlanRuntimeTickContext(
                tick = harness.tick,
                stateAt = harness::frozenStateAt,
                liveStateAt = harness::stateAt,
                recordWrite = harness::recordFrozenWrite,
                eyePosition = harness.eyePosition,
                reach = harness.reach,
                playerFeetPos = null,
                settings = defaultSettings,
                placementContext = { _, hit -> fakePlacementContext(hit) },
                predictPlacement = { item, _ -> item.block.defaultBlockState() },
                orientedPrediction = { _, _, _ -> null },
                itemSupplier = ItemSupplier { true },
                placementGateway = PlacementGateway { _, _ -> true },
                destroy = { true },
                placementIntervalTicks = 1,
                attemptsPerTick = 1,
            )
        }
        repeat(PrinterAttemptTracker.SETTLE_TICKS + 1) {
            adapter.tick(rejectingContext())
            harness.tick++
        }
        assertFalse(adapter.cursor.status().isComplete)
        assertEquals(0, adapter.cursor.status().failedCount)

        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(1, status.doneCount)
        assertEquals(0, status.failedCount)
        assertEquals(stone, harness.stateAt(target))
    }

    @Test
    internal fun staleCorrelatedEventIsIgnoredAndCounted(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        val ignoredBefore = adapter.cursor.status().ignoredEventCount

        adapter.cursor.onEvent(CursorEvent.Accepted(actionId = 0L, attemptId = 999L))

        assertEquals(ignoredBefore + 1, adapter.cursor.status().ignoredEventCount)
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
    }

    @Test
    internal fun placementIntervalTicksIsReflectedEveryTick(): Unit {
        val posA = BlockPos(0, 2, 0)
        val posB = BlockPos(1, 2, 0)
        val states = mutableMapOf(posA.below() to stone, posB.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        harness.placementIntervalTicks = 5
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(posA, stone, groupId = 1),
                PlanAction.PlaceTarget(posB, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertEquals(1, adapter.cursor.status().failedCount)

        repeat(4) {
            adapter.tick(harness.context())
            harness.tick++
            assertEquals(1, adapter.cursor.status().failedCount)
        }

        adapter.tick(harness.context())
        harness.tick++
        assertEquals(2, adapter.cursor.status().failedCount)
    }

    @Test
    internal fun attemptsPerTickAllowsMultipleGroupsToResolveWithinOneTick(): Unit {
        val posA = BlockPos(0, 2, 0)
        val posB = BlockPos(1, 2, 0)
        val states = mutableMapOf(posA.below() to stone, posB.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        harness.attemptsPerTick = 2
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(posA, stone, groupId = 1),
                PlanAction.PlaceTarget(posB, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        assertEquals(2, adapter.cursor.status().failedCount) {
            "expected both groups' own actions submitted within the same tick"
        }
    }

    @Test
    internal fun isPlacementInFlightTracksASubmittedButNotYetObservedTarget(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        assertFalse(adapter.isPlacementInFlight(target))

        adapter.tick(harness.context())
        harness.tick++

        assertTrue(adapter.cursor.stateOf(0) is ActionState.InFlight)
        assertTrue(adapter.isPlacementInFlight(target))

        runUntilComplete(adapter, harness)

        assertEquals(1, adapter.cursor.status().doneCount)
        assertFalse(adapter.isPlacementInFlight(target))
    }

    @Test
    internal fun placementAckClearsPendingPlaceForThatWorldPos(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        assertTrue(harness.clearedPendingPlace.isEmpty())

        runUntilComplete(adapter, harness)

        assertEquals(1, adapter.cursor.status().doneCount)
        assertEquals(listOf(target), harness.clearedPendingPlace)
    }

    @Test
    internal fun isPlacementInFlightTracksAnInFlightRemoveAction(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val (adapter, harness) = runGroupUntilScaffoldDoneAndTargetInFlight(scaffoldPos, target)

        var ticks = 0
        while (adapter.cursor.stateOf(2) !is ActionState.InFlight && ticks < 40) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        assertTrue(adapter.cursor.stateOf(2) is ActionState.InFlight)
        assertTrue(adapter.isPlacementInFlight(scaffoldPos))
        assertTrue(adapter.isBreakInFlight(scaffoldPos))
    }

    @Test
    internal fun removeAckClearsPendingBreakBeforeGenericManualReconciliationRuns(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        runUntilComplete(adapter, harness)

        assertEquals(listOf(scaffoldPos), harness.clearedPendingBreak)
        assertFalse(adapter.isBreakInFlight(scaffoldPos))
    }

    @Test
    internal fun retargetMismatchBlocksSubmitAndFlagsSessionDirty(): Unit {
        val target = BlockPos(0, 2, 0)
        val diverged = target.offset(5, 0, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))
        var divergenceNotified = false

        adapter.tick(
            harness.context(
                clickedPosOverride = diverged,
                onFrozenLiveDivergence = { divergenceNotified = true },
            ),
        )

        assertEquals(air, harness.stateAt(target))
        val state = adapter.cursor.stateOf(0)
        assertTrue(state is ActionState.Waiting)
        assertEquals(WaitingReason.WAITING_FOR_REACH, (state as ActionState.Waiting).reason)
        assertTrue(divergenceNotified)
    }

    @Test
    internal fun retargetDivergenceRepairsFrozenModelForSupportAndWorldPosBeforeReclassifying(): Unit {
        val target = BlockPos(0, 2, 0)
        val support = target.below()
        val diverged = target.offset(5, 0, 0)
        val states = mutableMapOf(support to stone)
        val harness = Harness(states)

        harness.setState(support, air)
        assertEquals(stone, harness.frozenStateAt(support))
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context(clickedPosOverride = diverged))

        assertEquals(air, harness.frozenStateAt(support))
        assertEquals(air, harness.frozenStateAt(target))

        val repairedResolution = resolvePlacement(
            worldPos = target,
            expectedState = stone,
            stateAt = harness::frozenStateAt,
            eyePosition = harness.eyePosition,
            reach = harness.reach,
            settings = defaultSettings,
            placementContext = { _, hit -> fakePlacementContext(hit) },
            predictPlacement = { item, _ -> item.block.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )
        assertEquals(PlacementResolution.NoSupportFace, repairedResolution)
    }

    @Test
    internal fun outstandingScaffoldCellsIncludesInFlightPlacement(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        assertTrue(adapter.cursor.stateOf(0) is ActionState.InFlight)
        assertEquals(listOf(scaffoldPos), adapter.outstandingScaffoldCells())
    }

    @Test
    internal fun outstandingScaffoldCellsIncludesTimedOutPlacement(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val cursor = PlanExecutionCursor(plan)
        val adapter = PlanRuntimeAdapter(plan, cursor)

        var current = cursor.submitEarliestFrontier()
        repeat(PrinterRuntime.MAX_RETRIES) {
            cursor.onEvent(CursorEvent.Timeout(current.actionId, current.attemptId))
            current = cursor.submitEarliestFrontier()
        }
        cursor.onEvent(CursorEvent.Timeout(current.actionId, current.attemptId))

        val state = cursor.stateOf(0)
        assertTrue(state is ActionState.Failed)
        assertEquals(ActionFailureReason.TIMEOUT, (state as ActionState.Failed).reason)
        assertEquals(listOf(scaffoldPos), adapter.outstandingScaffoldCells())
    }

    @Test
    internal fun sweepOutstandingScaffoldsDestroysLiveCellsAndRecordsWriteOnConfirmedAir(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))
        adapter.cursor.submitEarliestFrontier()
        harness.setState(scaffoldPos, SCAFFOLD_BLOCK_STATE)

        assertEquals(air, harness.frozenStateAt(scaffoldPos))

        adapter.sweepOutstandingScaffolds(harness::stateAt, harness::destroy, harness::recordFrozenWrite)

        assertEquals(1, harness.destroyCallCount)
        assertEquals(air, harness.stateAt(scaffoldPos))
        assertEquals(air, harness.frozenStateAt(scaffoldPos))
    }

    @Test
    internal fun sweepOutstandingScaffoldsLeavesNonScaffoldCellsUntouched(): Unit {
        val scaffoldPos = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(scaffoldPos.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffoldPos, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffoldPos, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))
        adapter.cursor.submitEarliestFrontier()
        harness.setState(scaffoldPos, Blocks.COBBLESTONE.defaultBlockState())

        adapter.sweepOutstandingScaffolds(harness::stateAt, harness::destroy, harness::recordFrozenWrite)

        assertEquals(0, harness.destroyCallCount)
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), harness.stateAt(scaffoldPos))
    }

    @Test
    internal fun isWithinBoundsReflectsConstructorPredicate(): Unit {
        val target = BlockPos(0, 2, 0)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(
            plan,
            PlanExecutionCursor(plan),
            bounds = { pos -> pos.x in -5..5 && pos.y in -5..5 && pos.z in -5..5 },
        )

        assertTrue(adapter.isWithinBounds(BlockPos(3, 0, -2)))
        assertFalse(adapter.isWithinBounds(BlockPos(50, 0, 0)))
    }

    @Test
    internal fun isWithinBoundsDefaultsToAlwaysTrueWithoutAnExplicitPredicate(): Unit {
        val target = BlockPos(0, 2, 0)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        assertTrue(adapter.isWithinBounds(BlockPos(9999, 9999, 9999)))
    }

    @Test
    internal fun targetActionCountsReportsFailedOrSkippedForATerminallyFailedTarget(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val counts = adapter.targetActionCounts()
        assertEquals(0, counts.remaining)
        assertEquals(0, counts.placed)
        assertEquals(1, counts.failedOrSkipped)
    }

    @Test
    internal fun targetExpectationsExcludeScaffoldActionsAndPreservePlanOrder(): Unit {
        val scaffold = BlockPos(0, 1, 0)
        val firstTarget = BlockPos(0, 2, 0)
        val secondTarget = BlockPos(1, 2, 0)
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffold, groupId = 1),
                PlanAction.PlaceTarget(firstTarget, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffold, groupId = 1),
                PlanAction.PlaceTarget(secondTarget, Blocks.OAK_PLANKS.defaultBlockState(), groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        assertEquals(
            listOf(
                PlanTargetExpectation(firstTarget, stone),
                PlanTargetExpectation(secondTarget, Blocks.OAK_PLANKS.defaultBlockState()),
            ),
            adapter.targetExpectations(),
        )
    }

    @Test
    internal fun frontierSnapshotReportsOutOfReachTargetInWaitingForReach(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.eyePosition = Vec3(500.0, 500.0, 500.0)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val snapshot = adapter.frontierSnapshot()
        assertEquals(1, snapshot.waitingForReach.size)
        assertEquals(target, snapshot.waitingForReach.single().pos)
        assertEquals(stone, snapshot.waitingForReach.single().expected)
        assertTrue(snapshot.columnBlocked.isEmpty())
    }

    @Test
    internal fun frontierSnapshotReportsPlayerColumnBlockedTarget(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.playerFeetPos = Vec3(0.5, 0.5, 0.5)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val snapshot = adapter.frontierSnapshot()
        assertEquals(listOf(target), snapshot.columnBlocked)
        assertEquals(target, snapshot.waitingForReach.single().pos)
    }

    @Test
    internal fun frontierSnapshotReportsFenceShapeBelowPlayerAsColumnBlocked(): Unit {
        val target = BlockPos.ZERO
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        val harness = Harness(mutableMapOf(target.below() to stone))
        harness.playerFeetPos = Vec3(0.5, 1.2, 0.5)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, fence, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val snapshot = adapter.frontierSnapshot()
        assertEquals(listOf(target), snapshot.columnBlocked)
        assertEquals(air, harness.stateAt(target))
    }

    @Test
    internal fun placementThatWouldSealPlayersLastExitWaitsForEvacuation(): Unit {
        val target = BlockPos(12, 9, 2)
        val trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.OPEN, true)
            .setValue(BlockStateProperties.POWERED, true)
        val states = mutableMapOf<BlockPos, BlockState>()
        for (x in 5..20) {
            for (y in 4..14) {
                for (z in -3..7) {
                    if (x in 10..20 && y in 9..10 && z == 2) continue
                    states[BlockPos(x, y, z)] = stone
                }
            }
        }
        val harness = Harness(states)
        harness.playerFeetPos = Vec3(10.5, 9.05, 2.5)
        harness.eyePosition = Vec3(10.5, 10.67, 2.5)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, trapdoor, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        assertEquals(listOf(target), adapter.frontierSnapshot().columnBlocked)
        assertEquals(air, harness.stateAt(target)) {
            "the gateway must not submit a placement that would seal the player's last exit"
        }
    }

    @Test
    internal fun frontierSnapshotDropsEntryOnceTargetIsDispatchedAndCompletes(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.eyePosition = Vec3(500.0, 500.0, 500.0)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertEquals(1, adapter.frontierSnapshot().waitingForReach.size)

        harness.eyePosition = Vec3(0.5, 1.5, 0.5)
        runUntilComplete(adapter, harness)

        val snapshot = adapter.frontierSnapshot()
        assertTrue(snapshot.waitingForReach.isEmpty())
        assertTrue(snapshot.inFlight.isEmpty())
    }

    @Test
    internal fun frontierSnapshotCarriesResolvedCollisionStateForInFlightPlacement(): Unit {
        val target = BlockPos(0, 2, 0)
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        val harness = Harness(mutableMapOf(target.below() to stone))
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, fence, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val snapshot = adapter.frontierSnapshot()
        assertEquals(listOf(target), snapshot.inFlight)
        assertEquals(listOf(target), snapshot.inFlightCollisionStates.map { collision -> collision.pos })
        val collisionState = snapshot.inFlightCollisionStates.single().expected
        assertEquals(fence.block, collisionState.block)
        assertTrue(collisionState.getValue(BlockStateProperties.NORTH))
        assertTrue(collisionState.getValue(BlockStateProperties.EAST))
        assertTrue(collisionState.getValue(BlockStateProperties.SOUTH))
        assertTrue(collisionState.getValue(BlockStateProperties.WEST))
    }

    @Test
    internal fun frontierSnapshotPrunesAWaitingForSupportEntryCascadeSkippedWithoutDispatch(): Unit {
        val support = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(support, stone, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 2),
            ),
        )
        val cursor = PlanExecutionCursor(plan)
        val adapter = PlanRuntimeAdapter(plan, cursor)
        val harness = Harness(mutableMapOf(support.below() to stone))
        harness.attemptsPerTick = 2

        adapter.tick(harness.context())
        assertTrue(cursor.stateOf(0) is ActionState.InFlight)
        assertTrue(cursor.stateOf(1) is ActionState.Waiting)
        assertEquals(1, adapter.frontierSnapshot().waitingForSupport.size)

        var current = cursor.submitSpecific(1)!!
        repeat(PrinterRuntime.MAX_RETRIES) {
            cursor.onEvent(CursorEvent.Timeout(current.actionId, current.attemptId))
            current = cursor.submitSpecific(1)!!
        }
        cursor.onEvent(CursorEvent.Timeout(current.actionId, current.attemptId))

        assertTrue(cursor.stateOf(1) is ActionState.Failed)

        val snapshot = adapter.frontierSnapshot()
        assertTrue(snapshot.waitingForSupport.isEmpty())
    }

    @Test
    internal fun predictionMismatchWaitsWhileASameUnitPlannedNeighborIsInFlight(): Unit {
        val target = BlockPos(0, 2, 0)
        val plannedNeighbor = target.east()
        val expected = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        val harness = Harness(mutableMapOf(target.below() to stone, plannedNeighbor.below() to stone))
        harness.attemptsPerTick = 2
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(plannedNeighbor, stone, groupId = 1),
                PlanAction.PlaceTarget(target, expected, groupId = 2),
            ),
        )
        val cursor = PlanExecutionCursor(plan)
        val adapter = PlanRuntimeAdapter(plan, cursor)

        adapter.tick(harness.context())

        assertTrue(cursor.stateOf(0) is ActionState.InFlight)
        val state = cursor.stateOf(1)
        assertTrue(state is ActionState.Waiting) { "expected pending-neighbor deferral, was $state" }
        assertEquals(WaitingReason.WAITING_FOR_SUPPORT, (state as ActionState.Waiting).reason)
        assertEquals(listOf(target), adapter.frontierSnapshot().waitingForSupport.map { it.pos })
    }

    @Test
    internal fun strictOrderNeverPlacesALaterTargetAheadOfAnEarlierUnresolvedOne(): Unit {
        val far = BlockPos(1000, 2, 0)
        val near = BlockPos(0, 2, 0)
        val states = mutableMapOf(near.below() to stone, far.below() to stone)
        val harness = Harness(states)
        harness.eyePosition = Vec3(2.0, 3.5, 0.5)
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(far, stone, groupId = 1),
                PlanAction.PlaceTarget(near, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        repeat(10) {
            adapter.tick(harness.context())
            harness.tick++
        }

        assertFalse(stone == harness.stateAt(near)) {
            "near must not be placed while the earlier far target is still Waiting"
        }
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Waiting)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Pending)
    }

    @Test
    internal fun inFlightGroupDoesNotBlockASiblingGroupsOwnDispatch(): Unit {
        val posA = BlockPos(0, 2, 0)
        val posB = BlockPos(1, 2, 0)
        val states = mutableMapOf(posA.below() to stone, posB.below() to stone)
        val harness = Harness(states)
        harness.attemptsPerTick = 2
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(posA, stone, groupId = 1),
                PlanAction.PlaceTarget(posB, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++

        assertTrue(adapter.cursor.stateOf(0) is ActionState.InFlight)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.InFlight) {
            "posB's own group must not wait for posA's own ack -- both may be in flight at once"
        }

        runUntilComplete(adapter, harness)
        assertEquals(2, adapter.cursor.status().doneCount)
    }

    @Test
    internal fun pendingLaterSupportNeighborFailsImmediatelyInsteadOfWaitingForStallEscape(): Unit {
        val lower = BlockPos(0, 2, 0)
        val upper = lower.above()
        val states = mutableMapOf(lower.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(upper, stone, groupId = 2),
                PlanAction.PlaceTarget(lower, stone, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        val upperState = adapter.cursor.stateOf(0)
        assertTrue(upperState is ActionState.Failed) { "expected immediate resolve failure, was $upperState" }
        assertEquals(ActionFailureReason.RESOLVE_FAILED, (upperState as ActionState.Failed).reason)
        assertEquals(0, adapter.stallEscapeCount())
    }

    @Test
    internal fun pendingEarlierSupportTargetBehindItsInFlightScaffoldDefers(): Unit {
        val target = BlockPos(0, 2, 0)
        val support = target.east()
        val scaffold = support.below()
        val harness = Harness(mutableMapOf(scaffold.below() to stone))
        harness.attemptsPerTick = 2
        val plan = planOf(
            listOf(
                PlanAction.PlaceScaffold(scaffold, groupId = 1),
                PlanAction.PlaceTarget(support, stone, groupId = 1),
                PlanAction.RemoveScaffold(scaffold, groupId = 1),
                PlanAction.PlaceTarget(target, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())

        assertTrue(adapter.cursor.stateOf(0) is ActionState.InFlight)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Pending)
        val targetState = adapter.cursor.stateOf(3)
        assertTrue(targetState is ActionState.Waiting) { "expected earlier-group support deferral, was $targetState" }
        assertEquals(WaitingReason.WAITING_FOR_SUPPORT, (targetState as ActionState.Waiting).reason)
        assertEquals(listOf(target), adapter.frontierSnapshot().waitingForSupport.map { it.pos })
    }

    @Test
    internal fun moverUnreachableRequestFailsTheStillMatchingStrictHeadOnTheNextAdapterTick(): Unit {
        val target = BlockPos(0, 2, 0)
        val harness = Harness(mutableMapOf(target.below() to stone))
        harness.eyePosition = Vec3(500.0, 500.0, 500.0)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Waiting)

        adapter.requestFrontierUnreachable(target)
        harness.tick++
        adapter.tick(harness.context())

        val state = adapter.cursor.stateOf(0)
        assertTrue(state is ActionState.Failed) { "expected mover reach failure, was $state" }
        assertEquals(ActionFailureReason.RESOLVE_FAILED, (state as ActionState.Failed).reason)
        assertEquals(1, adapter.moverUnreachableCount())
        assertEquals(0, adapter.stallEscapeCount())
    }

    @Test
    internal fun outOfOrderSupportDependencyStallsThenForcesResolveFailedAfterThreshold(): Unit {
        val lower = BlockPos(0, 2, 0)
        val upper = lower.above()
        val states = mutableMapOf(lower.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(upper, stone, groupId = 2),
                PlanAction.PlaceTarget(lower, stone, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        var ticks = 0
        val maxTicks = (STRICT_FRONTIER_STALL_TICKS + 100L).toInt()
        while (!adapter.cursor.status().isComplete && ticks < maxTicks) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }

        val status = adapter.cursor.status()
        assertTrue(status.isComplete) { "plan did not complete within $ticks ticks, status=$status" }
        assertEquals(1, status.failedCount) {
            "upper's own out-of-order support dependency must be forced to ResolveFailed by the stall escape"
        }
        assertEquals(1, status.doneCount)
        assertEquals(stone, harness.stateAt(lower))
    }

    @Test
    internal fun stallEscapeStreakSurvivesTicksWhereTheRateLimiterNeverReachedTheHead(): Unit {
        val lower = BlockPos(0, 2, 0)
        val upper = lower.above()
        val states = mutableMapOf(lower.below() to stone)
        val harness = Harness(states)
        harness.placementIntervalTicks = 2
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(upper, stone, groupId = 2),
                PlanAction.PlaceTarget(lower, stone, groupId = 1),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        var ticks = 0
        val maxTicks = (STRICT_FRONTIER_STALL_TICKS + 200L).toInt()
        while (!adapter.cursor.status().isComplete && ticks < maxTicks) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }

        val status = adapter.cursor.status()
        assertTrue(status.isComplete) {
            "plan did not complete within $ticks ticks (interval-limited dispatch must not " +
                "reset the stall streak on an unobserved tick), status=$status"
        }
        assertEquals(1, status.failedCount) {
            "upper's own out-of-order support dependency must still be forced to " +
                "ResolveFailed once real elapsed ticks exceed the stall bound, even though " +
                "only every other tick actually observed it as the blocker"
        }
        assertEquals(1, status.doneCount)
        assertEquals(stone, harness.stateAt(lower))
    }

    @Test
    internal fun playerColumnBlockedNeverForcedToResolveFailedByStallEscape(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.playerFeetPos = Vec3(0.5, 0.5, 0.5)
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        repeat((STRICT_FRONTIER_STALL_TICKS + 50L).toInt()) {
            adapter.tick(harness.context())
            harness.tick++
        }

        val status = adapter.cursor.status()
        assertFalse(status.isComplete)
        assertEquals(0, status.failedCount)
        assertEquals(1, status.waitingCount)
    }

    @Test
    internal fun supportDeferralOnlyWaitsOnSameUnitPlannedNeighbor(): Unit {
        val support = BlockPos(0, 1, 0)
        val target = BlockPos(0, 2, 0)
        val laterUnitNeighbor = BlockPos(0, 3, 0)
        val harness = Harness(mutableMapOf())
        val unit0 = PlanUnit(
            band = 0,
            tileX = 0,
            tileZ = 0,
            actions = listOf(
                PlanAction.PlaceTarget(support, stone, groupId = 2),
                PlanAction.PlaceTarget(target, stone, groupId = 1),
            ),
        )
        val unit1 = PlanUnit(
            band = 1,
            tileX = 0,
            tileZ = 0,
            actions = listOf(PlanAction.PlaceTarget(laterUnitNeighbor, stone, groupId = 3)),
        )
        val plan = PrintPlan(units = listOf(unit0, unit1), reservations = emptyMap(), report = emptyReport())
        val cursor = PlanExecutionCursor(plan)
        val adapter = PlanRuntimeAdapter(plan, cursor)

        var current = cursor.submitEarliestFrontier()
        repeat(PrinterRuntime.MAX_RETRIES) {
            cursor.onEvent(CursorEvent.Timeout(current.actionId, current.attemptId))
            current = cursor.submitEarliestFrontier()
        }
        cursor.onEvent(CursorEvent.Timeout(current.actionId, current.attemptId))
        assertTrue(cursor.stateOf(0) is ActionState.Failed)

        var ticks = 0
        while (cursor.currentUnitIndex() == 0 && ticks < 50) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }

        assertTrue(cursor.currentUnitIndex() > 0) {
            "unit 0 did not complete within $ticks ticks, target state=${cursor.stateOf(1)}"
        }
        val targetState = cursor.stateOf(1)
        assertTrue(targetState is ActionState.Failed) { "expected target RESOLVE_FAILED, was $targetState" }
        assertEquals(ActionFailureReason.RESOLVE_FAILED, (targetState as ActionState.Failed).reason)
    }

    @Test
    internal fun evaluateNoProgressBackoffArmsOnlyOnceThresholdReachedByConsecutiveNoProgressDiscards(): Unit {
        val threshold = 3

        val first = evaluateNoProgressBackoff(
            placedAtDiscard = 0,
            currentNoProgressCount = 0,
            threshold = threshold,
        )
        assertEquals(1, first.noProgressCount)
        assertFalse(first.armBackoff)

        val second = evaluateNoProgressBackoff(
            placedAtDiscard = 0,
            currentNoProgressCount = first.noProgressCount,
            threshold = threshold,
        )
        assertEquals(2, second.noProgressCount)
        assertFalse(second.armBackoff)

        val third = evaluateNoProgressBackoff(
            placedAtDiscard = 0,
            currentNoProgressCount = second.noProgressCount,
            threshold = threshold,
        )
        assertEquals(3, third.noProgressCount)
        assertTrue(third.armBackoff)

        val fourth = evaluateNoProgressBackoff(
            placedAtDiscard = 2,
            currentNoProgressCount = third.noProgressCount,
            threshold = threshold,
        )
        assertEquals(0, fourth.noProgressCount)
        assertFalse(fourth.armBackoff)
    }

    @Test
    internal fun evaluateNoProgressBackoffNeverArmsWhileEveryDiscardPlacedSomething(): Unit {
        val threshold = 3

        var count = 0
        for (placed in listOf(10, 1, 1, 1)) {
            val decision = evaluateNoProgressBackoff(
                placedAtDiscard = placed,
                currentNoProgressCount = count,
                threshold = threshold,
            )
            assertEquals(0, decision.noProgressCount)
            assertFalse(decision.armBackoff)
            count = decision.noProgressCount
        }
    }

    @Test
    internal fun frontierSnapshotReturnsTheWholeSameLayerSegmentLargerThanTheOldWindow(): Unit {
        val sameLayerCount = 40
        val sameLayerActions = (0 until sameLayerCount).map { index ->
            PlanAction.PlaceTarget(BlockPos(index, 0, 0), stone, groupId = index + 1)
        }
        val nextLayerAction = PlanAction.PlaceTarget(BlockPos(0, 1, 0), stone, groupId = sameLayerCount + 1)
        val plan = planOf(sameLayerActions + nextLayerAction)
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        val waiting = adapter.frontierSnapshot().waitingForReach

        assertEquals(sameLayerCount, waiting.size)
        assertTrue(waiting.all { target -> target.pos.y == 0 })
    }

    @Test
    internal fun frontierSnapshotStopsBeforeTheNextLayerEvenWithinTheOldWindow(): Unit {
        val sameLayerCount = 10
        val nextLayerCount = 25
        val sameLayerActions = (0 until sameLayerCount).map { index ->
            PlanAction.PlaceTarget(BlockPos(index, 0, 0), stone, groupId = index + 1)
        }
        val nextLayerActions = (0 until nextLayerCount).map { index ->
            PlanAction.PlaceTarget(BlockPos(index, 1, 0), stone, groupId = sameLayerCount + index + 1)
        }
        val plan = planOf(sameLayerActions + nextLayerActions)
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        val waiting = adapter.frontierSnapshot().waitingForReach

        assertEquals(sameLayerCount, waiting.size)
        assertTrue(waiting.all { target -> target.pos.y == 0 })
    }

    @Test
    internal fun sweepSubmitsAFailedTargetExactlyOnceBeforeTheNextLayerDispatches(): Unit {
        val target1 = BlockPos(0, 2, 0)
        val target2 = BlockPos(5, 3, 0)
        val states = mutableMapOf(target1.below() to stone, target2.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target1, stone, groupId = 1),
                PlanAction.PlaceTarget(target2, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Pending)
        assertEquals(air, harness.stateAt(target1))

        harness.itemAvailable = true

        adapter.tick(harness.context())
        harness.tick++
        assertEquals(stone, harness.stateAt(target1)) {
            "the sweep must submit to the failed target the tick right after its own layer finished"
        }
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Pending) {
            "the next layer must not dispatch while the sweep is still running"
        }

        runUntilComplete(adapter, harness)
        assertEquals(stone, harness.stateAt(target1))
        assertEquals(stone, harness.stateAt(target2))
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed) { "cursor state stays frozen" }
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Done)
    }

    @Test
    internal fun sweepDefersAHeadWithNoSupportBehindItsQueuedSupportNeighbor(): Unit {
        val target = BlockPos(0, 2, 0)
        val support = target.east()
        val states = mutableMapOf(support.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target, stone, groupId = 1),
                PlanAction.PlaceTarget(support, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        repeat(2) {
            adapter.tick(harness.context())
            harness.tick++
        }
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Failed)

        harness.itemAvailable = true
        var ticks = 0
        while (adapter.isSweepPending() && ticks < 40) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }

        assertFalse(adapter.isSweepPending())
        assertEquals(stone, harness.stateAt(support))
        assertEquals(stone, harness.stateAt(target)) {
            "the sweep must place its queued support before retrying the dependent"
        }
    }

    @Test
    internal fun sweepDoesNotDeferAVerticalLogBehindAnUnusableSideNeighbor(): Unit {
        val target = BlockPos(0, 2, 0)
        val sideNeighbor = target.east()
        val verticalLog = Blocks.OAK_LOG.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val states = mutableMapOf(sideNeighbor.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target, verticalLog, groupId = 1),
                PlanAction.PlaceTarget(sideNeighbor, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        repeat(2) {
            adapter.tick(harness.context())
            harness.tick++
        }
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Failed)

        harness.itemAvailable = true
        adapter.tick(harness.context())
        harness.tick++
        assertTrue(adapter.isSweepPending()) { "the real side-neighbor target is still queued" }

        adapter.tick(harness.context())
        harness.tick++

        assertFalse(adapter.isSweepPending()) {
            "the unusable side face must not cause a second vertical-log revisit"
        }
        assertEquals(stone, harness.stateAt(sideNeighbor))
        assertEquals(air, harness.stateAt(target))
    }

    @Test
    internal fun sweepAcceptedRecordsAFrozenWriteButLeavesCursorStateFailed(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        assertEquals(air, harness.frozenStateAt(target))

        harness.itemAvailable = true
        var ticks = 0
        while (harness.frozenStateAt(target) != stone && ticks < 40) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }

        assertEquals(stone, harness.frozenStateAt(target)) {
            "the sweep's own ACCEPTED outcome must record the placement into the frozen model"
        }
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed) {
            "cursor state must stay frozen at Failed even though the sweep's own attempt was accepted"
        }
        assertEquals(stone, harness.stateAt(target))
    }

    @Test
    internal fun sweepReattemptThatFailsAgainIsNeverSweptTwiceAndNextLayerResumes(): Unit {
        val target1 = BlockPos(0, 2, 0)
        val target2 = BlockPos(5, 3, 0)
        val states = mutableMapOf(target1.below() to stone, target2.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target1, stone, groupId = 1),
                PlanAction.PlaceTarget(target2, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        var target1SubmitCount = 0
        fun context(): PlanRuntimeTickContext = PlanRuntimeTickContext(
            tick = harness.tick,
            stateAt = harness::frozenStateAt,
            liveStateAt = harness::stateAt,
            recordWrite = harness::recordFrozenWrite,
            eyePosition = harness.eyePosition,
            reach = harness.reach,
            playerFeetPos = harness.playerFeetPos,
            settings = defaultSettings,
            placementContext = { _, hit -> fakePlacementContext(hit) },
            predictPlacement = { item, _ -> item.block.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
            itemSupplier = ItemSupplier { harness.itemAvailable },
            placementGateway = PlacementGateway { hit, _ ->
                val pos = hit.blockPos.relative(hit.direction)
                if (pos == target1) {
                    target1SubmitCount++
                } else {
                    harness.setState(pos, stone)
                }
                true
            },
            destroy = harness::destroy,
            placementIntervalTicks = 1,
            attemptsPerTick = 1,
            clearPendingPlace = harness.clearedPendingPlace::add,
        )

        adapter.tick(context())
        harness.tick++
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        assertEquals(ActionFailureReason.ITEM_UNAVAILABLE, (adapter.cursor.stateOf(0) as ActionState.Failed).reason)

        harness.itemAvailable = true
        var ticks = 0
        while (adapter.cursor.stateOf(1) !is ActionState.Done && ticks < 60) {
            adapter.tick(context())
            harness.tick++
            ticks++
        }

        assertTrue(adapter.cursor.stateOf(1) is ActionState.Done) {
            "the next layer must resume and complete after the sweep's own re-attempt settles"
        }
        assertEquals(1, target1SubmitCount) {
            "the sweep must submit to target1 exactly once (never swept at all would leave this at 0)"
        }
        assertEquals(air, harness.stateAt(target1)) {
            "the sweep's own single re-attempt must have settled REJECTED, never actually placing it"
        }
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)
        assertEquals(ActionFailureReason.ITEM_UNAVAILABLE, (adapter.cursor.stateOf(0) as ActionState.Failed).reason) {
            "cursor state must stay at its original failure reason -- sweep never emits a cursor event"
        }

        repeat(30) {
            adapter.tick(context())
            harness.tick++
        }
        assertEquals(1, target1SubmitCount) { "target1 must never be swept a second time" }
        assertEquals(air, harness.stateAt(target1))
    }

    @Test
    internal fun sweepLeavesNoTraceWhenALayersSweepCandidateSetIsEmpty(): Unit {
        val target1 = BlockPos(0, 2, 0)
        val target2 = BlockPos(5, 3, 0)
        val states = mutableMapOf(target1.below() to stone, target2.below() to stone)
        val harness = Harness(states)
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target1, stone, groupId = 1),
                PlanAction.PlaceTarget(target2, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        runUntilComplete(adapter, harness)

        val status = adapter.cursor.status()
        assertEquals(2, status.doneCount)
        assertEquals(0, status.failedCount)
        assertFalse(adapter.isSweepPending())
        assertEquals(stone, harness.stateAt(target1))
        assertEquals(stone, harness.stateAt(target2))
    }

    @Test
    internal fun sweepOutOfReachTargetIsNotConsumedUntilStallBoundThenConsumed(): Unit {
        val target1 = BlockPos(0, 2, 0)
        val target2 = BlockPos(5, 3, 0)
        val states = mutableMapOf(target1.below() to stone, target2.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target1, stone, groupId = 1),
                PlanAction.PlaceTarget(target2, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)

        harness.itemAvailable = true
        harness.eyePosition = Vec3(500.0, 500.0, 500.0)

        repeat((STRICT_FRONTIER_STALL_TICKS - 10L).toInt()) {
            adapter.tick(harness.context())
            harness.tick++
        }
        assertEquals(1, adapter.frontierSnapshot().waitingForReach.size) {
            "an out-of-reach sweep target must not be consumed before the stall bound"
        }
        assertTrue(adapter.cursor.stateOf(1) is ActionState.Pending) {
            "the next layer must not dispatch while the sweep is still waiting on reach"
        }
        assertEquals(air, harness.stateAt(target1))

        repeat(20) {
            adapter.tick(harness.context())
            harness.tick++
        }
        assertTrue(adapter.frontierSnapshot().waitingForReach.none { target -> target.pos == target1 }) {
            "the stall bound must consume the out-of-reach sweep target and move on"
        }
        assertFalse(adapter.cursor.stateOf(1) is ActionState.Pending) {
            "the next layer's own dispatch must resume once the stalled sweep target is consumed"
        }
    }

    @Test
    internal fun isSweepPendingStaysTrueThroughTheFinalLayersSweepEvenAfterCursorReportsComplete(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++

        assertTrue(adapter.cursor.status().isComplete) {
            "sanity: the only action failed terminally, so the cursor itself is already complete"
        }
        assertTrue(adapter.isSweepPending()) {
            "the final layer's own sweep judgment has not run yet -- completion must stay pending"
        }

        harness.itemAvailable = true
        var ticks = 0
        while (adapter.isSweepPending() && ticks < 40) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }

        assertFalse(adapter.isSweepPending()) {
            "the final layer's own sweep pass must eventually conclude"
        }
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed) {
            "cursor state stays frozen even though the world now holds the placed block"
        }
        assertEquals(stone, harness.stateAt(target))
    }

    @Test
    internal fun isPlacementInFlightReportsTrueForASweepSubmittedPosition(): Unit {
        val target = BlockPos(0, 2, 0)
        val states = mutableMapOf(target.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(listOf(PlanAction.PlaceTarget(target, stone, groupId = 1)))
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertFalse(adapter.isPlacementInFlight(target))

        harness.itemAvailable = true
        adapter.tick(harness.context())
        harness.tick++

        assertTrue(adapter.isPlacementInFlight(target)) {
            "a sweep-submitted attempt must be recognized as printer-owned while outstanding"
        }

        var ticks = 0
        while (adapter.isPlacementInFlight(target) && ticks < 20) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        assertFalse(adapter.isPlacementInFlight(target)) {
            "must release once the sweep's own attempt settles, exactly like an ordinary dispatch does"
        }
    }

    @Test
    internal fun frontierSnapshotReportsPlayerColumnBlockedSweepTarget(): Unit {
        val target1 = BlockPos(0, 2, 0)
        val target2 = BlockPos(5, 3, 0)
        val states = mutableMapOf(target1.below() to stone, target2.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target1, stone, groupId = 1),
                PlanAction.PlaceTarget(target2, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)

        harness.itemAvailable = true
        harness.playerFeetPos = Vec3(0.5, 0.5, 0.5)
        adapter.tick(harness.context())
        harness.tick++

        val snapshot = adapter.frontierSnapshot()
        assertEquals(listOf(target1), snapshot.columnBlocked)
        assertEquals(target1, snapshot.waitingForReach.single().pos)
        assertEquals(air, harness.stateAt(target1)) { "never actually submitted while column-blocked" }
    }

    @Test
    internal fun sweepRetargetMismatchNeverSubmitsAndConsumesTheAttempt(): Unit {
        val target1 = BlockPos(0, 2, 0)
        val target2 = BlockPos(5, 3, 0)
        val diverged = target1.offset(5, 0, 0)
        val states = mutableMapOf(target1.below() to stone, target2.below() to stone)
        val harness = Harness(states)
        harness.itemAvailable = false
        val plan = planOf(
            listOf(
                PlanAction.PlaceTarget(target1, stone, groupId = 1),
                PlanAction.PlaceTarget(target2, stone, groupId = 2),
            ),
        )
        val adapter = PlanRuntimeAdapter(plan, PlanExecutionCursor(plan))

        adapter.tick(harness.context())
        harness.tick++
        assertTrue(adapter.cursor.stateOf(0) is ActionState.Failed)

        harness.itemAvailable = true
        adapter.tick(harness.context(clickedPosOverride = diverged))
        harness.tick++

        assertEquals(air, harness.stateAt(target1)) {
            "a retarget mismatch must never submit -- gateway.submit must not have run"
        }
        assertTrue(adapter.frontierSnapshot().inFlight.isEmpty()) {
            "the mismatched attempt must be consumed, not left tracked as outstanding"
        }

        var ticks = 0
        while (adapter.cursor.stateOf(1) is ActionState.Pending && ticks < 20) {
            adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        assertFalse(adapter.cursor.stateOf(1) is ActionState.Pending) {
            "the next layer's own dispatch must resume once the mismatched sweep attempt is consumed"
        }
        assertEquals(air, harness.stateAt(target1)) { "target1 must stay unplaced" }
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
