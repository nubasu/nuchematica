package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.renderer.section.SingleSlotWorker
import com.nubasu.nuchematica.renderer.section.WorkerCompletion
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ExecutorService

/**
 * Incrementally assembles immutable planner input on the client thread.
 *
 * After [step] completes, [result] must remain unchanged. [reset] must replace its
 * storage because the result is handed to a worker without a defensive copy.
 */
internal interface PlanContentAssembler {
    fun reset(): Unit
    fun step(maxEntries: Int): Boolean
    fun result(): List<Pair<BlockPos, BlockState>>
}

internal class BudgetedPlanContentAssembler(
    private val missingLocal: () -> Collection<BlockPos>,
    private val expectedState: (BlockPos) -> BlockState?,
    private val localToWorld: (BlockPos) -> BlockPos,
) : PlanContentAssembler {
    private var source: Iterator<BlockPos> = emptyList<BlockPos>().iterator()
    private var output: ArrayList<Pair<BlockPos, BlockState>> = ArrayList()

    override fun reset(): Unit {
        source = missingLocal().iterator()
        output = ArrayList()
    }

    override fun step(maxEntries: Int): Boolean {
        var consumed = 0
        while (consumed < maxEntries && source.hasNext()) {
            val local = source.next()
            consumed++
            val expected = expectedState(local) ?: continue
            output.add(localToWorld(local) to expected)
        }
        return !source.hasNext()
    }

    override fun result(): List<Pair<BlockPos, BlockState>> = output
}

/** Identity key using reference equality for level/content and value equality otherwise. */
internal class PlanIdentity(
    internal val level: Any,
    internal val content: Any,
    internal val transformRevision: Long,
    internal val behavior: PlacementBehaviorSettings,
) {
    internal fun matches(other: PlanIdentity): Boolean {
        return level === other.level &&
            content === other.content &&
            transformRevision == other.transformRevision &&
            behavior == other.behavior
    }
}

/** Ready plan bound to the world-model revision from which it was produced. */
internal class PlanSession(
    internal val plan: PrintPlan,
    internal val cursor: PlanExecutionCursor,
    internal val adapter: PlanRuntimeAdapter,
    internal val identity: PlanIdentity,
    internal val writeRevision: Long,
)

internal enum class PlanCoordinatorPhase { IDLE, SNAPSHOTTING, PLANNING, READY }

internal sealed interface PlanCoordinatorStep {
    data class InProgress(internal val phase: PlanCoordinatorPhase) : PlanCoordinatorStep
    data class Ready(internal val session: PlanSession) : PlanCoordinatorStep
    object Failed : PlanCoordinatorStep
}

private class PlanJobInput(
    internal val content: List<Pair<BlockPos, BlockState>>,
    internal val snapshot: PrintWorldModel.PlanWorldSnapshot,
    internal val params: PrintPlanParams,
    internal val generation: Long,
)

private class PlanJobOutput(
    internal val plan: PrintPlan,
    internal val cursor: PlanExecutionCursor,
    internal val adapter: PlanRuntimeAdapter,
    internal val generation: Long,
)

private fun runPlanJob(input: PlanJobInput): PlanJobOutput {
    val plan = PrintPlanner.plan(input.content, input.snapshot::stateAt, input.params)
    val cursor = PlanExecutionCursor(plan)
    val adapter = PlanRuntimeAdapter(plan, cursor, input.params.bounds ?: { true })
    return PlanJobOutput(plan, cursor, adapter, input.generation)
}

/**
 * Produces revision-consistent plans through budgeted copying and off-thread planning.
 */
internal class PlanCoordinator(
    private val onPlanningFailure: (Throwable) -> Unit = {},
    workerExecutor: ExecutorService? = null,
    private val snapshotCellsPerAdvance: Int = SNAPSHOT_CELLS_PER_ADVANCE,
    private val assemblyEntriesPerAdvance: Int = ASSEMBLY_ENTRIES_PER_ADVANCE,
) {
    private val workerExecutorOverride: ExecutorService? = workerExecutor
    private var worker: SingleSlotWorker<PlanJobInput, PlanJobOutput>? = null

    private var phase: PlanCoordinatorPhase = PlanCoordinatorPhase.IDLE
    private var cycleIdentity: PlanIdentity? = null
    private var assemblerDone: Boolean = false

    private var generation: Long = 0L

    private var snapshotWriteRevision: Long = -1L

    private var heldSnapshot: PrintWorldModel.PlanWorldSnapshot? = null

    private var heldJobInput: PlanJobInput? = null

    private var failureLatchIdentity: PlanIdentity? = null
    private var failureLatchRevision: Long = -1L

    /**
     * Advances one client-tick budget and restarts when [identity] changes.
     *
     * For a fixed identity and world revision, [assembler] output and [bounds] must be stable.
     */
    internal fun advance(
        identity: PlanIdentity,
        assembler: PlanContentAssembler,
        bounds: (BlockPos) -> Boolean,
    ): PlanCoordinatorStep {
        if (phase != PlanCoordinatorPhase.IDLE && cycleIdentity?.matches(identity) != true) {
            cancelCycle()
        }
        return when (phase) {
            PlanCoordinatorPhase.IDLE -> advanceIdle(identity, assembler)
            PlanCoordinatorPhase.SNAPSHOTTING -> advanceSnapshotting(identity, assembler, bounds)
            PlanCoordinatorPhase.PLANNING -> advancePlanning(identity)
            PlanCoordinatorPhase.READY -> error("PlanCoordinator must never rest at READY between advance() calls")
        }
    }

    internal fun cancel(): Unit {
        cancelCycle()
    }

    private fun advanceIdle(identity: PlanIdentity, assembler: PlanContentAssembler): PlanCoordinatorStep {
        if (failureLatchIdentity?.matches(identity) == true && failureLatchRevision == PrintWorldModel.writeRevision()) {
            return PlanCoordinatorStep.Failed
        }
        if (!PrintWorldModel.beginPlanSnapshot()) {
            return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE)
        }
        assembler.reset()
        cycleIdentity = identity
        assemblerDone = false
        phase = PlanCoordinatorPhase.SNAPSHOTTING
        return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.SNAPSHOTTING)
    }

    private fun advanceSnapshotting(
        identity: PlanIdentity,
        assembler: PlanContentAssembler,
        bounds: (BlockPos) -> Boolean,
    ): PlanCoordinatorStep {
        if (heldSnapshot == null) {
            val pump = PrintWorldModel.pumpPlanSnapshot(snapshotCellsPerAdvance)
            if (pump is PrintWorldModel.PlanSnapshotPump.Unavailable) {
                cancelCycle()
                return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE)
            }
            if (pump is PrintWorldModel.PlanSnapshotPump.Complete) {
                heldSnapshot = pump.snapshot
                snapshotWriteRevision = pump.writeRevision
            }
        }
        if (!assemblerDone) {
            assemblerDone = assembler.step(assemblyEntriesPerAdvance)
        }
        val snapshot = heldSnapshot
        if (snapshot == null || !assemblerDone) {
            return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.SNAPSHOTTING)
        }

        if (PrintWorldModel.writeRevision() != snapshotWriteRevision) {
            cancelCycle()
            return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE)
        }

        val input = heldJobInput ?: PlanJobInput(
            content = assembler.result(),
            snapshot = snapshot,
            params = PrintPlanParams(bounds = bounds, behavior = identity.behavior),
            generation = generation,
        ).also { heldJobInput = it }
        if (!obtainWorker().submit(input)) {
            return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.SNAPSHOTTING)
        }
        heldJobInput = null
        heldSnapshot = null
        phase = PlanCoordinatorPhase.PLANNING
        return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING)
    }

    private fun advancePlanning(identity: PlanIdentity): PlanCoordinatorStep {
        val completion = obtainWorker().pollCompletion()
            ?: return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.PLANNING)
        return when (completion) {
            is WorkerCompletion.Success -> adopt(completion.value, identity)
            is WorkerCompletion.Failure -> {
                if (PrintWorldModel.writeRevision() != snapshotWriteRevision) {
                    phase = PlanCoordinatorPhase.IDLE
                    PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE)
                } else {
                    failureLatchIdentity = cycleIdentity
                    failureLatchRevision = snapshotWriteRevision
                    phase = PlanCoordinatorPhase.IDLE
                    onPlanningFailure(completion.throwable)
                    PlanCoordinatorStep.Failed
                }
            }
        }
    }

    private fun adopt(output: PlanJobOutput, identity: PlanIdentity): PlanCoordinatorStep {
        val boundIdentity = cycleIdentity
        val currentRevision = PrintWorldModel.writeRevision()
        val valid = output.generation == generation &&
            currentRevision == snapshotWriteRevision &&
            boundIdentity != null &&
            boundIdentity.matches(identity)
        phase = PlanCoordinatorPhase.IDLE
        if (!valid) {
            return PlanCoordinatorStep.InProgress(PlanCoordinatorPhase.IDLE)
        }
        return PlanCoordinatorStep.Ready(
            PlanSession(
                plan = output.plan,
                cursor = output.cursor,
                adapter = output.adapter,
                identity = identity,
                writeRevision = currentRevision,
            ),
        )
    }

    private fun cancelCycle(): Unit {
        generation++
        worker?.cancelCurrent()
        PrintWorldModel.cancelPlanSnapshot()
        cycleIdentity = null
        assemblerDone = false
        heldSnapshot = null
        heldJobInput = null
        phase = PlanCoordinatorPhase.IDLE
    }

    private fun obtainWorker(): SingleSlotWorker<PlanJobInput, PlanJobOutput> {
        val existing = worker
        if (existing != null) return existing
        val executorOverride = workerExecutorOverride
        val created = if (executorOverride != null) {
            SingleSlotWorker(
                threadName = "nuchematica-plan-worker",
                operation = ::runPlanJob,
                discard = {},
                executor = executorOverride,
            )
        } else {
            SingleSlotWorker(
                threadName = "nuchematica-plan-worker",
                operation = ::runPlanJob,
                discard = {},
            )
        }
        worker = created
        return created
    }

    internal companion object {
        internal const val SNAPSHOT_CELLS_PER_ADVANCE: Int = 4_000_000
        internal const val ASSEMBLY_ENTRIES_PER_ADVANCE: Int = 200_000
    }
}
