package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.renderer.section.SingleSlotWorker
import com.nubasu.nuchematica.renderer.section.WorkerCompletion
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ExecutorService

// Main-thread, budgeted content-assembly seam PlanCoordinator drives every advance() call
// alongside PrintWorldModel's own snapshot budget: step is resumable across calls (true means
// complete), and result() is only ever read once step() has returned true. The returned list is
// handed to a worker thread with no defensive copy, so two rules are load-bearing for thread
// safety: reset() must abandon whatever storage a previous result() call returned (allocate
// fresh, never clear or reuse that same list), and a list once returned by result() must never
// be mutated after step() has returned true -- submission-time publication (SingleSlotWorker's
// own completion handoff) is the only memory barrier the worker thread gets.
internal interface PlanContentAssembler {
    fun reset(): Unit
    fun step(maxEntries: Int): Boolean
    fun result(): List<Pair<BlockPos, BlockState>>
}

// Production PlanContentAssembler: reproduces SchematicPrinter.tickPlanMode's synchronous
// missingLocal -> expectedState skip -> localToWorld assembly, budgeted across step() calls
// instead of built in one pass. reset() re-derives the source from missingLocal() and starts a
// brand new output list every time -- see PlanContentAssembler's own doc for why that matters
// to the worker thread reading result() with no defensive copy.
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

// A plan cycle's own identity -- independent of PrinterSessionKey (the v3 path's own identity
// type, left untouched). level/content are compared by reference (the same schematic content
// list/level instance means the same cycle); transformRevision/behavior by value, since either
// changing invalidates whatever the coordinator is mid-cycle on even if level/content did not.
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

// A completed, ready-to-execute plan cycle. writeRevision is the PrintWorldModel revision the
// underlying snapshot was taken under, carried forward so a later caller can notice a further
// divergence without re-deriving it from the coordinator that already discarded its own state.
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

// Immutable input to one worker-thread planning job -- content/snapshot/params must never
// change after submission; the worker thread reads them with no synchronization of its own,
// relying entirely on SingleSlotWorker's completion handoff as the happens-before publication.
// content in particular is exactly whatever PlanContentAssembler.result() returned -- see that
// interface's own doc for the aliasing rules that make handing it to a worker thread safe with
// no defensive copy. generation is carried through runPlanJob verbatim into PlanJobOutput,
// letting adopt() validate a completed job against the coordinator's own generation without
// depending on SingleSlotWorker's cancellation handling as its only line of defense.
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

// Runs entirely off the immutable job input -- no Minecraft/Level/ClientLevel/
// SchematicRenderManager/MissingBlockHolder or any other singleton access, so this stays safe
// to execute on the worker thread SingleSlotWorker owns. Constructing the cursor and adapter
// here (not back on the main thread after the job returns) is deliberate: both constructors
// walk every action in the plan, and for a multi-million-action plan that walk must not run on
// the main thread either.
private fun runPlanJob(input: PlanJobInput): PlanJobOutput {
    val plan = PrintPlanner.plan(input.content, input.snapshot::stateAt, input.params)
    val cursor = PlanExecutionCursor(plan)
    val adapter = PlanRuntimeAdapter(plan, cursor, input.params.bounds ?: { true })
    return PlanJobOutput(plan, cursor, adapter, input.generation)
}

// Off-thread plan coordinator: snapshots PrintWorldModel's frozen capture under a per-tick
// budget, classifies it via PrintPlanner on a worker thread, and hands back a validated,
// ready-to-execute PlanSession. Main-thread-only, same convention as every other per-tick
// printer/mover coordinator in this package -- advance()/cancel() must never be called from the
// worker thread this coordinator itself owns.
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

    // Bumped only by cancel() (explicit or identity-mismatch-triggered); carried through every
    // job's own input/output payload (see PlanJobInput/PlanJobOutput) so adopt() can tell
    // whether a cancel happened between this job's submission and its completion being polled
    // -- a second line of defense behind SingleSlotWorker's own cancellation handling, which
    // already discards a cancelled job's output before it ever reaches pollCompletion().
    private var generation: Long = 0L

    // The PrintWorldModel revision the in-flight cycle's snapshot completed under -- becomes
    // the adopted PlanSession's own writeRevision on success, or the failure latch's revision
    // half on a worker throw.
    private var snapshotWriteRevision: Long = -1L

    // Holds a completed plan snapshot until the assembler also finishes -- pumpPlanSnapshot's
    // own completion is one-shot (calling it again after Complete returns Unavailable, since the
    // model session it consumed is already gone), so once complete this must be kept until this
    // cycle actually submits or cancels, never re-fetched by calling pumpPlanSnapshot again.
    private var heldSnapshot: PrintWorldModel.PlanWorldSnapshot? = null

    // Holds a job input once submit() has been attempted at least once for it, so a submit that
    // returns false (a prior cycle's worker slot has not finished draining yet) retries with the
    // exact same input on the next advance() instead of re-deriving it or giving up.
    private var heldJobInput: PlanJobInput? = null

    // Memoizes a deterministic planning failure for one (identity, writeRevision) pair:
    // planning is a pure function of (content, snapshot, params), so retrying the exact same
    // pair would fail identically every time. Latched by identity+revision together -- a
    // different identity at the same revision proceeds normally, and toggling back to the
    // latched identity while the revision is still unchanged must return the latched Failed
    // again rather than resubmit a job already known to fail. This holds only under advance()'s
    // own caller-purity guarantee: identity+revision must be the only two things that can change
    // what content/bounds a cycle plans against.
    private var failureLatchIdentity: PlanIdentity? = null
    private var failureLatchRevision: Long = -1L

    // Call once per tick, main-thread only. identity/bounds are re-supplied every call --
    // never cached across calls beyond the in-progress cycle's own cycleIdentity -- so a caller
    // never needs a separate "did the target change" check of its own: an identity mismatch
    // against whatever cycle is already in progress is handled here, transparently, by
    // cancelling and restarting fresh within this same call.
    //
    // Caller purity: for a given identity, whatever assembler.result() produces and whatever
    // bounds accepts/rejects must be a pure function of that identity and the current
    // PrintWorldModel revision -- nothing else may vary while both stay unchanged. A caller
    // that derives either from some other input must fold that input into the identity object
    // itself, or the failure latch below will end up memoizing a stale answer.
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

        // The held snapshot may have gone stale while it waited on the assembler and/or a busy
        // worker slot to drain -- a recordWrite in that window must discard this cycle here
        // rather than pay for a whole worker planning pass only to discard it at adopt().
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
                // A throw against a snapshot the model has already moved past is not a real
                // planning failure -- it is stale input that a fresh cycle will simply redo
                // correctly, so it must not latch or notify.
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

    // The single adoption gate: every completed job passes through here, success or discard --
    // no other path ever returns Ready. All checks must hold together: (a) no cancel happened
    // since this job's own submission, (b) the model has not moved since the snapshot this job
    // classified against completed, (c) the identity this cycle was bound to at submit time
    // still matches the identity passed to this very call.
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
