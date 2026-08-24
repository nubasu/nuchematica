package com.nubasu.nuchematica.printer

import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class InlineExecutorService : AbstractExecutorService() {
    var executeCount: Int = 0
        private set

    override fun execute(command: Runnable): Unit {
        executeCount++
        command.run()
    }

    override fun shutdown(): Unit {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown(): Boolean = false
    override fun isTerminated(): Boolean = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

private class DeferredExecutorService : AbstractExecutorService() {
    private val pending: ArrayDeque<Runnable> = ArrayDeque()
    var executeCount: Int = 0
        private set

    override fun execute(command: Runnable): Unit {
        executeCount++
        pending.addLast(command)
    }

    fun runNext(): Boolean {
        val task = pending.removeFirstOrNull() ?: return false
        task.run()
        return true
    }

    override fun shutdown(): Unit {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown(): Boolean = false
    override fun isTerminated(): Boolean = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

private class ThreadPerTaskExecutorService : AbstractExecutorService() {
    val threads: MutableList<Thread> = mutableListOf()

    override fun execute(command: Runnable): Unit {
        val thread = Thread(command).apply { isDaemon = true }
        threads.add(thread)
        thread.start()
    }

    override fun shutdown(): Unit {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown(): Boolean = false
    override fun isTerminated(): Boolean = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

private class LatchBlockingContent(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
    private val delegate: List<Pair<BlockPos, BlockState>>,
) : AbstractList<Pair<BlockPos, BlockState>>() {
    override val size: Int get() = delegate.size

    override fun get(index: Int): Pair<BlockPos, BlockState> {
        if (index == 0) {
            entered.countDown()
            var released = false
            while (!released) {
                try {
                    release.await()
                    released = true
                } catch (interrupted: InterruptedException) {
                }
            }
        }
        return delegate[index]
    }
}

private class ScriptedAssembler(
    initialContent: List<Pair<BlockPos, BlockState>>,
    private val stepsUntilDone: Int = 1,
) : PlanContentAssembler {
    var content: List<Pair<BlockPos, BlockState>> = initialContent
    var resetCount: Int = 0
        private set
    var stepCount: Int = 0
        private set

    override fun reset(): Unit {
        resetCount++
        stepCount = 0
    }

    override fun step(maxEntries: Int): Boolean {
        stepCount++
        return stepCount >= stepsUntilDone
    }

    override fun result(): List<Pair<BlockPos, BlockState>> = content
}

private fun fakePlacementContext(hit: BlockHitResult): BlockPlaceContext {
    val context: BlockPlaceContext = mockk(relaxed = true)
    every { context.clickedPos } returns hit.blockPos.relative(hit.direction)
    return context
}

private class DrivingHarness(private val liveWorld: MutableMap<BlockPos, BlockState>) {
    private val frozenModel: MutableMap<BlockPos, BlockState> = HashMap(liveWorld)
    var tick: Long = 0L
    private var heldState: BlockState? = null

    fun stateAt(pos: BlockPos): BlockState = liveWorld[pos] ?: Blocks.AIR.defaultBlockState()

    fun context(): PlanRuntimeTickContext {
        return PlanRuntimeTickContext(
            tick = tick,
            stateAt = { pos -> frozenModel[pos] ?: Blocks.AIR.defaultBlockState() },
            liveStateAt = ::stateAt,
            recordWrite = { pos, state -> frozenModel[pos] = state },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
            playerFeetPos = null,
            settings = PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false),
            placementContext = { _, hit -> fakePlacementContext(hit) },
            predictPlacement = { item, _ -> item.block.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
            itemSupplier = ItemSupplier { state -> heldState = state; true },
            placementGateway = PlacementGateway { hit, _ ->
                heldState?.let { state -> liveWorld[hit.blockPos.relative(hit.direction)] = state }
                true
            },
            destroy = { pos -> liveWorld[pos] = Blocks.AIR.defaultBlockState(); true },
            placementIntervalTicks = 1,
        )
    }
}

internal class PlanCoordinatorTest {
    private lateinit var coordinator: PlanCoordinator

    @AfterEach
    internal fun tearDown(): Unit {
        if (::coordinator.isInitialized) coordinator.cancel()
        PrintWorldModel.cancel()
    }

    @Test
    internal fun adoptionDiscardsOnRevisionBumpBetweenSubmitAndPollThenAFreshCycleReflectsTheNewWorldState(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val assembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        assertEquals(
            PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.SNAPSHOTTING),
            coordinator.advance(identity, assembler, bounds),
        )
        assertEquals(
            PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING),
            coordinator.advance(identity, assembler, bounds),
        )

        PrintWorldModel.recordWrite(BlockPos(2, 2, 2), Blocks.STONE.defaultBlockState())
        assertEquals(
            PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE),
            coordinator.advance(identity, assembler, bounds),
        )

        PrintWorldModel.recordWrite(target, Blocks.STONE.defaultBlockState())
        val ready = runToReady(coordinator, identity, assembler, bounds)
        assertEquals(1, ready.plan.report.alreadyPlacedCount)
        assertEquals(0, ready.plan.report.directCount)
    }

    @Test
    internal fun adoptionDiscardsOnIdentityChangeAndTheFreshCycleReflectsTheNewIdentity(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val behaviorA = PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)
        val behaviorB = PlacementBehaviorSettings(substituteLookalikes = false, placeWaterloggedDry = false)
        val identityA = PlanIdentity(Any(), Any(), 0L, behaviorA)
        val identityB = PlanIdentity(identityA.level, identityA.content, identityA.transformRevision, behaviorB)
        val assembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identityA, assembler, bounds)
        val submitted = coordinator.advance(identityA, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), submitted)

        val afterMismatch = coordinator.advance(identityB, assembler, bounds)
        assertTrue(afterMismatch !is PlanCoordinatorStep.Ready)

        val ready = runToReady(coordinator, identityB, assembler, bounds)
        assertEquals(behaviorB, ready.identity.behavior)
    }

    @Test
    internal fun cancelDuringPlanningPreventsTheStaleCompletionFromEverSurfacingAsReadyAndAFreshCycleSucceeds(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = DeferredExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val assembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identity, assembler, bounds)
        val submitted = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), submitted)
        assertEquals(1, executor.executeCount)

        coordinator.cancel()
        assertTrue(executor.runNext())

        val afterCancel = coordinator.advance(identity, assembler, bounds)
        assertTrue(afterCancel !is PlanCoordinatorStep.Ready)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.SNAPSHOTTING), afterCancel)

        val freshSubmit = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), freshSubmit)
        assertEquals(2, executor.executeCount)
        assertTrue(executor.runNext())

        val ready = runToReady(coordinator, identity, assembler, bounds)
        assertEquals(1, ready.plan.report.directCount)
    }

    @Test
    internal fun snapshotCompletingBeforeAssemblyFinishesIsHeldRatherThanDiscarded(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val assembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()), stepsUntilDone = 3)
        val bounds: (BlockPos) -> Boolean = { true }

        val ready = runToReady(coordinator, identity, assembler, bounds, maxAdvances = 10)
        assertEquals(1, ready.plan.report.directCount)
    }

    @Test
    internal fun aWriteDuringTheHeldSnapshotWindowCancelsBeforeSubmitInsteadOfWastingAWorkerPass(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val assembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()), stepsUntilDone = 3)
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identity, assembler, bounds)
        coordinator.advance(identity, assembler, bounds)

        PrintWorldModel.recordWrite(BlockPos(2, 2, 2), Blocks.STONE.defaultBlockState())

        coordinator.advance(identity, assembler, bounds)
        val onceAssemblyFinishes = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE), onceAssemblyFinishes)
        assertEquals(0, executor.executeCount)

        val ready = runToReady(coordinator, identity, assembler, bounds, maxAdvances = 10)
        assertEquals(1, executor.executeCount)
        assertEquals(1, ready.plan.report.directCount)
    }

    @Test
    internal fun submitReturningFalseWhileTheSlotStillDrainsWaitsInsteadOfThrowing(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = ThreadPerTaskExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val bounds: (BlockPos) -> Boolean = { true }

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingContent = LatchBlockingContent(entered, release, listOf(target to Blocks.STONE.defaultBlockState()))
        val blockingAssembler = ScriptedAssembler(blockingContent)

        coordinator.advance(identity, blockingAssembler, bounds)
        val submitted = coordinator.advance(identity, blockingAssembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), submitted)
        assertEquals(1, executor.threads.size)

        entered.await()
        coordinator.cancel()

        val freshAssembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()))
        coordinator.advance(identity, freshAssembler, bounds)
        val whileSlotStillOccupied = coordinator.advance(identity, freshAssembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.SNAPSHOTTING), whileSlotStillOccupied)

        release.countDown()
        executor.threads[0].join()

        val afterSlotFrees = coordinator.advance(identity, freshAssembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), afterSlotFrees)
        assertEquals(2, executor.threads.size)
        executor.threads[1].join()

        val ready = runToReady(coordinator, identity, freshAssembler, bounds)
        assertEquals(1, ready.plan.report.directCount)
    }

    @Test
    internal fun workerThrowLatchesFailedUntilARecordWriteClearsItThenAFreshCycleSucceeds(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
        captureReadyRegion(level, BlockPos(0, 0, 0), BlockPos(0, 0, 0))

        val executor = InlineExecutorService()
        var failureCount = 0
        coordinator = PlanCoordinator(onPlanningFailure = { failureCount++ }, workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val outOfRegionTarget = BlockPos(100, 100, 100)
        val assembler = ScriptedAssembler(listOf(outOfRegionTarget to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identity, assembler, bounds)
        val submitted = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), submitted)
        assertEquals(1, executor.executeCount)

        assertEquals(PlanCoordinatorStep.Failed, coordinator.advance(identity, assembler, bounds))
        assertEquals(1, failureCount)

        assertEquals(PlanCoordinatorStep.Failed, coordinator.advance(identity, assembler, bounds))
        assertEquals(PlanCoordinatorStep.Failed, coordinator.advance(identity, assembler, bounds))
        assertEquals(1, executor.executeCount)
        assertEquals(1, failureCount)

        val target = BlockPos(0, 1, 0)
        PrintWorldModel.recordWrite(BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState())
        assembler.content = listOf(target to Blocks.STONE.defaultBlockState())

        val ready = runToReady(coordinator, identity, assembler, bounds)
        assertEquals(1, ready.plan.report.directCount)
        assertEquals(2, executor.executeCount)
    }

    @Test
    internal fun aWorkerFailureAtAStaleRevisionIsASilentRestartNotALatchedFailure(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
        captureReadyRegion(level, BlockPos(0, 0, 0), BlockPos(0, 0, 0))

        val executor = DeferredExecutorService()
        var failureCount = 0
        coordinator = PlanCoordinator(onPlanningFailure = { failureCount++ }, workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val outOfRegionTarget = BlockPos(100, 100, 100)
        val assembler = ScriptedAssembler(listOf(outOfRegionTarget to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identity, assembler, bounds)
        val submitted = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), submitted)
        assertEquals(1, executor.executeCount)

        val target = BlockPos(0, 1, 0)
        PrintWorldModel.recordWrite(BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState())
        assertTrue(executor.runNext())

        val afterThrow = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE), afterThrow)
        assertEquals(0, failureCount)

        assembler.content = listOf(target to Blocks.STONE.defaultBlockState())
        coordinator.advance(identity, assembler, bounds)
        val freshSubmitted = coordinator.advance(identity, assembler, bounds)
        assertEquals(PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING), freshSubmitted)
        assertTrue(executor.runNext())

        val ready = runToReady(coordinator, identity, assembler, bounds)
        assertEquals(1, ready.plan.report.directCount)
    }

    @Test
    internal fun aDifferentIdentityProceedsToReadyWhileAnotherIdentitysFailureLatchHolds(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
        captureReadyRegion(level, BlockPos(0, 0, 0), BlockPos(0, 0, 0))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identityA = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val identityB = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val outOfRegionTarget = BlockPos(100, 100, 100)
        val assembler = ScriptedAssembler(listOf(outOfRegionTarget to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identityA, assembler, bounds)
        coordinator.advance(identityA, assembler, bounds)
        assertEquals(PlanCoordinatorStep.Failed, coordinator.advance(identityA, assembler, bounds))

        assembler.content = listOf(BlockPos(0, 1, 0) to Blocks.AIR.defaultBlockState())
        val ready = runToReady(coordinator, identityB, assembler, bounds)
        assertEquals(1, ready.plan.report.alreadyPlacedCount)
    }

    @Test
    internal fun theLatchReappliesToItsOwnIdentityAfterADifferentIdentitysCycleAtTheSameRevision(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
        captureReadyRegion(level, BlockPos(0, 0, 0), BlockPos(0, 0, 0))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identityA = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val identityB = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val outOfRegionTarget = BlockPos(100, 100, 100)
        val assembler = ScriptedAssembler(listOf(outOfRegionTarget to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        coordinator.advance(identityA, assembler, bounds)
        coordinator.advance(identityA, assembler, bounds)
        assertEquals(PlanCoordinatorStep.Failed, coordinator.advance(identityA, assembler, bounds))

        assembler.content = listOf(BlockPos(0, 1, 0) to Blocks.AIR.defaultBlockState())
        runToReady(coordinator, identityB, assembler, bounds)
        val executeCountAfterB = executor.executeCount

        assembler.content = listOf(outOfRegionTarget to Blocks.STONE.defaultBlockState())
        assertEquals(PlanCoordinatorStep.Failed, coordinator.advance(identityA, assembler, bounds))
        assertEquals(executeCountAfterB, executor.executeCount)
    }

    @Test
    internal fun readyIsReturnedExactlyOnceAndTheSessionDrivesAScriptedPlanEndToEnd(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 2, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val executor = InlineExecutorService()
        coordinator = PlanCoordinator(workerExecutor = executor)
        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val assembler = ScriptedAssembler(listOf(target to Blocks.STONE.defaultBlockState()))
        val bounds: (BlockPos) -> Boolean = { true }

        val ready = runToReady(coordinator, identity, assembler, bounds)
        assertEquals(1, ready.plan.report.directCount)

        val afterReady = coordinator.advance(identity, assembler, bounds)
        assertTrue(afterReady !is PlanCoordinatorStep.Ready)

        val states = mutableMapOf(target.below() to Blocks.STONE.defaultBlockState())
        val harness = DrivingHarness(states)
        var ticks = 0
        while (!ready.cursor.status().isComplete && ticks < 40) {
            ready.adapter.tick(harness.context())
            harness.tick++
            ticks++
        }
        val status = ready.cursor.status()
        assertEquals(1, status.doneCount)
        assertEquals(0, status.failedCount)
        assertEquals(Blocks.STONE.defaultBlockState(), harness.stateAt(target))
    }

    @Test
    internal fun twoIdenticalCoordinatorRunsProduceIdenticalStepSequencesAndPlans(): Unit {
        val level = mockClientLevel()
        val target = BlockPos(0, 1, 0)
        every { level.getBlockState(any()) } answers {
            val pos = firstArg<BlockPos>()
            if (pos == target) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
        }
        captureReadyRegion(level, BlockPos(-2, -2, -2), BlockPos(2, 2, 2))

        val identity = PlanIdentity(Any(), Any(), 0L, defaultBehavior())
        val bounds: (BlockPos) -> Boolean = { true }
        val content = listOf(target to Blocks.STONE.defaultBlockState())

        fun runOnce(): Pair<List<PlanCoordinatorPhase>, PrintPlan> {
            val executor = InlineExecutorService()
            val runCoordinator = PlanCoordinator(workerExecutor = executor)
            val assembler = ScriptedAssembler(content)
            val steps = mutableListOf<PlanCoordinatorPhase>()
            var result: PlanSession? = null
            var iterations = 0
            while (result == null && iterations < 10) {
                when (val step = runCoordinator.advance(identity, assembler, bounds)) {
                    is PlanCoordinatorStep.InProgress -> steps.add(step.phase)
                    is PlanCoordinatorStep.Ready -> result = step.session
                    PlanCoordinatorStep.Failed -> error("unexpected Failed")
                }
                iterations++
            }
            runCoordinator.cancel()
            return steps to checkNotNull(result).plan
        }

        val (steps1, plan1) = runOnce()
        val (steps2, plan2) = runOnce()

        assertEquals(steps1, steps2)
        assertEquals(plan1.units, plan2.units)
        assertEquals(plan1.reservations, plan2.reservations)
    }

    @Test
    internal fun budgetedAssemblerSplitsWorkAcrossMultipleStepCallsWhenTheSourceExceedsTheBudget(): Unit {
        val positions = listOf(BlockPos(0, 0, 0), BlockPos(1, 0, 0), BlockPos(2, 0, 0))
        val assembler = BudgetedPlanContentAssembler(
            missingLocal = { positions },
            expectedState = { Blocks.STONE.defaultBlockState() },
            localToWorld = { it },
        )

        assembler.reset()
        assertFalse(assembler.step(2))
        assertTrue(assembler.step(2))
        assertEquals(positions.map { it to Blocks.STONE.defaultBlockState() }, assembler.result())
    }

    @Test
    internal fun budgetedAssemblerSkipsPositionsWithNoExpectedStateExactlyLikeTheSynchronousMapNotNull(): Unit {
        val kept = BlockPos(0, 0, 0)
        val skipped = BlockPos(1, 0, 0)
        val assembler = BudgetedPlanContentAssembler(
            missingLocal = { listOf(kept, skipped) },
            expectedState = { pos -> if (pos == skipped) null else Blocks.STONE.defaultBlockState() },
            localToWorld = { it },
        )

        assembler.reset()
        assertTrue(assembler.step(10))
        assertEquals(listOf(kept to Blocks.STONE.defaultBlockState()), assembler.result())
    }

    @Test
    internal fun resetAbandonsThePreviousCyclesOwnResultListInsteadOfClearingOrReusingIt(): Unit {
        val assembler = BudgetedPlanContentAssembler(
            missingLocal = { listOf(BlockPos(0, 0, 0)) },
            expectedState = { Blocks.STONE.defaultBlockState() },
            localToWorld = { it },
        )

        assembler.reset()
        assertTrue(assembler.step(10))
        val firstResult = assembler.result()

        assembler.reset()
        assertTrue(assembler.step(10))
        val secondResult = assembler.result()

        assertTrue(firstResult !== secondResult)
        assertEquals(listOf(BlockPos(0, 0, 0) to Blocks.STONE.defaultBlockState()), firstResult)
        assertEquals(listOf(BlockPos(0, 0, 0) to Blocks.STONE.defaultBlockState()), secondResult)
    }

    @Test
    internal fun budgetedAssemblerPreservesTheSourceCollectionsOwnIterationOrder(): Unit {
        val positions = listOf(BlockPos(3, 0, 0), BlockPos(1, 0, 0), BlockPos(2, 0, 0))
        val assembler = BudgetedPlanContentAssembler(
            missingLocal = { positions },
            expectedState = { Blocks.STONE.defaultBlockState() },
            localToWorld = { it },
        )

        assembler.reset()
        assertTrue(assembler.step(10))
        assertEquals(positions, assembler.result().map { it.first })
    }

    @Test
    internal fun budgetedAssemblerWithAnEmptySourceCompletesOnTheFirstStepWithAnEmptyResult(): Unit {
        val assembler = BudgetedPlanContentAssembler(
            missingLocal = { emptyList() },
            expectedState = { Blocks.STONE.defaultBlockState() },
            localToWorld = { it },
        )

        assembler.reset()
        assertTrue(assembler.step(10))
        assertTrue(assembler.result().isEmpty())
    }

    private fun captureReadyRegion(level: ClientLevel, min: BlockPos, max: BlockPos): Unit {
        PrintWorldModel.ensureCapture(level, Any(), 0L, listOf(min, max)) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        check(status == PrintWorldModel.Status.READY) { "test setup expected READY, got $status" }
    }

    private fun runToReady(
        planCoordinator: PlanCoordinator,
        identity: PlanIdentity,
        assembler: PlanContentAssembler,
        bounds: (BlockPos) -> Boolean,
        maxAdvances: Int = 20,
    ): PlanSession {
        repeat(maxAdvances) {
            val step = planCoordinator.advance(identity, assembler, bounds)
            if (step is PlanCoordinatorStep.Ready) return step.session
        }
        throw AssertionError("coordinator did not reach Ready within $maxAdvances advance() calls")
    }

    private fun defaultBehavior(): PlacementBehaviorSettings =
        PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)

    private fun mockClientLevel(): ClientLevel = mockk()

    internal companion object {
        @BeforeAll
        @JvmStatic
        internal fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
