package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.printer.MissingSnapshot
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.AbstractList
import java.util.ArrayDeque

public data class MissingBlockChange(
    public val localPos: BlockPos,
    public val overlayChanged: Boolean,
    public val satisfiedChanged: Boolean,
    public val satisfied: Boolean,
)

// Constructing one of these is O(1) -- missingSnapshot() used to
// eagerly concatenate airPos+blockPos into a fresh ArrayList on EVERY call, even for
// callers that only read .revision (see missingSnapshot()'s doc) or just want a
// presence/count test (now hasMissing()/missingCount(), which never build one of these
// at all). At 0_all's 3.5M-entry missing set that unconditional copy alone was enough to
// freeze every printer/mover tick and the toggle's hasMissing check.
//
// Frozen-on-first-read, not a permanently live view: get()/size lazily concatenate
// air+block into a cached snapshot ON THE FIRST ACTUAL ACCESS, then keep returning that
// SAME cached list forever after, regardless of any later applyStatus/finishInitialize
// mutation of the (by-then-stale) air/block references. This is deliberate, not an
// oversight -- a caller that captures a MissingSnapshot once and reads its missingLocal
// more than once (possibly after this object's own later mutations elsewhere) must see
// the exact content as of that first read, unchanged, exactly like the OLD eager-copy
// implementation guaranteed (see MissingBlockHolderTest's frozen-snapshot assertions,
// unchanged by this fix). A caller that never actually reads the content (the .revision-
// only call sites) never pays the O(missing) cost at all -- that's the actual fix here,
// not making repeated reads of already-read content free (iterating N items is
// unavoidably O(N) once something really needs all of them).
//
// applyStatus below does `localPos in airPos`/
// `blockPos` plus `.add()`/`.remove()` on EVERY accepted placement or removal -- on a
// plain ArrayList that is an O(n) linear scan each time. At Fantasy's ~3k-entry missing
// set that is unnoticeable; at 0_all's ~3.5M entries it makes the whole print effectively
// O(missing^2) (thousands of placements, each an O(3.5M) scan). A bare LinkedHashSet would
// give O(1) contains/add/remove while preserving insertion order, but it is not a List --
// `assertEquals(listOf(x), MissingBlockHolder.airPos)` (MissingBlockHolderTest) and
// MissingLocalView's frozen-snapshot contract above both depend on airPos/blockPos
// behaving exactly like an ordered List to every external caller, and a List can never
// equal a Set even with identical elements (AbstractList.equals rejects non-List others).
// OrderedPositionSet is the "ArrayList+HashSet index" shape this design
// needs, realized as a single java.util.AbstractList facade (matching MissingLocalView's
// own pattern immediately below) over a LinkedHashSet: size/get/iteration order/equals/
// toString all read exactly like the List it replaces, while contains/add/remove/clear --
// the operations applyStatus and MissingBlockHolderTest's direct `+=`/`.clear()` calls
// actually use (verified: grep for MissingBlockHolder.(blockPos|airPos) across src/ and
// src/test/ turned up no indexed access anywhere) -- are routed to the backing
// LinkedHashSet's O(1) operations instead of AbstractList's default O(n) linear-scan ones.
// get(index) stays an O(n) ordinal walk since nothing ever calls it; duplicate adds are
// now structurally impossible (Set semantics) rather than caller-enforced (applyStatus's
// own `if (!wasAirMissing) add(...)` guard already prevented them either way).
private class OrderedPositionSet : AbstractList<BlockPos>() {
    private val backing: LinkedHashSet<BlockPos> = LinkedHashSet()

    override val size: Int get() = backing.size

    override fun get(index: Int): BlockPos {
        val iterator = backing.iterator()
        repeat(index) { iterator.next() }
        return iterator.next()
    }

    override fun contains(element: BlockPos): Boolean = backing.contains(element)

    override fun add(element: BlockPos): Boolean = backing.add(element)

    override fun add(index: Int, element: BlockPos): Unit {
        backing.add(element)
    }

    override fun remove(element: BlockPos): Boolean = backing.remove(element)

    override fun removeAt(index: Int): BlockPos {
        val element = get(index)
        backing.remove(element)
        return element
    }

    override fun clear(): Unit {
        backing.clear()
    }

    override fun iterator(): MutableIterator<BlockPos> = backing.iterator()
}

// AbstractList's default add/remove/set all throw UnsupportedOperationException (never
// overridden here), so callers cannot mutate this through the returned reference either.
private class MissingLocalView(
    private val air: List<BlockPos>,
    private val block: List<BlockPos>,
) : AbstractList<BlockPos>() {
    private var frozen: List<BlockPos>? = null

    private fun materialized(): List<BlockPos> {
        frozen?.let { return it }
        val combined = ArrayList<BlockPos>(air.size + block.size)
        combined.addAll(air)
        combined.addAll(block)
        frozen = combined
        return combined
    }

    override val size: Int get() = materialized().size

    override fun get(index: Int): BlockPos = materialized()[index]
}

object MissingBlockHolder {
    // OrderedPositionSet, not ArrayList -- see its doc above for why (O(1)
    // contains/add/remove at 0_all scale while staying List-equal to every existing
    // caller). Explicit MutableList<BlockPos> type: the concrete class is private, and
    // this project's explicit-API mode forbids a private type leaking into public API.
    public val blockPos: MutableList<BlockPos> = OrderedPositionSet()
    public val airPos: MutableList<BlockPos> = OrderedPositionSet()
    private var revision: Long = 0L

    // A bounded log of every overlay-changing event since the last
    // full initialize(), so LayerGateEligibleMissingCache/MoverMissingWorldCache can apply
    // exactly what changed instead of rescanning the whole missing set on every revision
    // bump. changesSince(revision) answers "what happened after that point"; a gap wider
    // than CHANGE_LOG_CAPACITY (a consumer idle for a long stretch) or a revision that
    // predates the last initialize() returns null, telling the caller to fall back to one
    // full rebuild from missingSnapshot() instead -- initialize() itself is a bulk
    // rescan, not a sequence of individual position events, so it cannot be replayed
    // incrementally and clears the log rather than trying to.
    private val changeLog: ArrayDeque<MissingBlockChange> = ArrayDeque()

    // Budgeted classification state. A pass is "in progress" from
    // beginInitialize() until finishInitialize() swaps its results into blockPos/airPos --
    // in between, blockPos/airPos keep showing whatever the PREVIOUS pass (or, on the very
    // first load, nothing) produced, never a half-built result, so a caller that ignores
    // isInitializing() still only ever sees a fully-consistent (if stale) missing set, not
    // a torn one. pendingEntries is a live iterator over the exact content map the pass
    // started against (see beginInitialize's doc for why holding just the iterator, not a
    // copy, is safe here).
    private var initializing: Boolean = false
    private var pendingEntries: Iterator<Map.Entry<BlockPos, BlockState>>? = null
    private var workingAirPos: ArrayList<BlockPos> = arrayListOf()
    private var workingBlockPos: ArrayList<BlockPos> = arrayListOf()
    private var initializeTotal: Int = 0
    private var initializeProcessed: Int = 0
    private var initializeReportedPercent: Int = -1

    // True from beginInitialize() until finishInitialize() swaps in
    // the pass's result. Printer/mover gate their tick on this exactly like they already
    // gate on PrintWorldModel.status() == CAPTURING (see SchematicPrinter/SchematicMover's
    // matching guard) -- the classification pass this flag covers only ever starts once
    // PrintWorldModel has already settled, so the two gates cover disjoint windows of the
    // same overall "world model not ready yet" period.
    internal fun isInitializing(): Boolean = initializing

    // O(1) accessors for the toggle-time and HUD checks that only
    // ever needed a count/presence test, not the full missing list a missingSnapshot()
    // call used to have to copy to answer them.
    internal fun hasMissing(): Boolean = airPos.isNotEmpty() || blockPos.isNotEmpty()

    internal fun missingCount(): Int = airPos.size + blockPos.size

    // Starts (or restarts) the budgeted classification pass and immediately pumps one
    // budget round -- a schematic small enough to finish within CAPTURE_CELLS_PER_TICK
    // positions still completes synchronously in this single call, exactly like before
    // this was budgeted (see MissingBlockHolderTest's initialize() tests, unchanged).
    // Callers that need to keep going across ticks for a larger schematic must check
    // isInitializing() after this returns and drive pump() themselves (see
    // SchematicRenderManager.initMissingBlock/pumpPrintWorldModelCapture).
    public fun initialize(): Unit {
        beginInitialize()
        pump()
    }

    private fun beginInitialize(): Unit {
        val dummy = SchematicHolder.renderingBlocks
        pendingEntries = dummy.blocks.entries.iterator()
        initializeTotal = dummy.blocks.size
        initializeProcessed = 0
        initializeReportedPercent = -1
        workingAirPos = ArrayList()
        workingBlockPos = ArrayList()
        initializing = true
    }

    // Advances the in-progress classification pass by at most
    // CAPTURE_CELLS_PER_TICK positions -- the same per-tick budget PrintWorldModel.pump
    // uses for its own capture, since this pass exists purely to consume that capture (no
    // separate tuning knob). No-op (returns true immediately) unless a pass is actually in
    // progress. onProgress fires at most once per 20%-multiple crossed, reusing the exact
    // "[nuchematica] scanning... N%" chat line PrintWorldModel's own capture already uses,
    // so the whole capture+classify sequence reads as one continuous "scanning" to the
    // user rather than two unrelated progress bars.
    internal fun pump(onProgress: (Int) -> Unit = ::reportInitializeProgress): Boolean {
        val entries = pendingEntries ?: return true
        var processedThisCall = 0
        while (entries.hasNext() && processedThisCall < PrintWorldModel.CAPTURE_CELLS_PER_TICK) {
            val (pos, expectedState) = entries.next()
            // Consumes the PrintWorldModel capture instead of a
            // second live-read pass -- stateAt only falls back to a live read when the
            // model itself is not yet READY for this position (should not happen here:
            // callers only invoke initialize() once PrintWorldModel has settled, see
            // SchematicRenderManager.initMissingBlock).
            val worldPos = SchematicRenderManager.localBlockToWorld(pos)
            val actualState = PrintWorldModel.stateAt(worldPos)
            if (!BlockStateEquivalence.matches(expectedState, actualState)) {
                if (actualState.isAir) workingAirPos.add(pos) else workingBlockPos.add(pos)
            }
            processedThisCall++
            initializeProcessed++
        }
        if (initializeTotal > 0) {
            val percent = (initializeProcessed * 100) / initializeTotal
            val bucket = (percent / PROGRESS_STEP_PERCENT) * PROGRESS_STEP_PERCENT
            if (bucket > initializeReportedPercent && bucket in 1..99) {
                initializeReportedPercent = bucket
                onProgress(bucket)
            }
        }
        if (entries.hasNext()) return false
        finishInitialize()
        return true
    }

    private fun finishInitialize(): Unit {
        val previousBlockPos = blockPos.toList()
        val previousAirPos = airPos.toList()
        blockPos.clear()
        blockPos.addAll(workingBlockPos)
        airPos.clear()
        airPos.addAll(workingAirPos)

        if (blockPos != previousBlockPos || airPos != previousAirPos) {
            revision++
        }
        changeLog.clear()
        initializing = false
        pendingEntries = null
        workingAirPos = ArrayList()
        workingBlockPos = ArrayList()
    }

    private fun reportInitializeProgress(percent: Int): Unit {
        ChatSender.send("[nuchematica] scanning... $percent%")
    }

    private const val PROGRESS_STEP_PERCENT: Int = 20

    public fun placed(pos: BlockPos, actualState: BlockState): MissingBlockChange? {
        val localPos = SchematicRenderManager.worldBlockToLocal(pos)
        val dummy = SchematicHolder.renderingBlocks
        val expectedState = dummy.blocks[localPos] ?: return null
        val satisfied = BlockStateEquivalence.matches(expectedState, actualState)
        return applyStatus(
            localPos = localPos,
            airMissing = !satisfied && actualState.isAir,
            blockMissing = !satisfied && !actualState.isAir,
        )
    }

    public fun removed(pos: BlockPos): MissingBlockChange? {
        val localPos = SchematicRenderManager.worldBlockToLocal(pos)
        val dummy = SchematicHolder.renderingBlocks
        val expectedState = dummy.blocks[localPos] ?: return null
        return applyStatus(
            localPos = localPos,
            airMissing = !expectedState.isAir,
            blockMissing = false,
        )
    }

    internal fun satisfiedPositions(): Set<BlockPos> {
        val airPositions = HashSet(airPos)
        val blockPositions = HashSet(blockPos)
        return SchematicHolder.renderingBlocks.blocks.keys.filterTo(LinkedHashSet()) { pos ->
            pos !in airPositions && pos !in blockPositions
        }
    }

    // missingLocal is a MissingLocalView instead of a fresh
    // O(missing) ArrayList copy built on every call -- at 0_all's 3.5M-entry missing set
    // that unconditional copy alone was expensive enough to freeze every printer/mover
    // tick, plus the toggle's hasMissing check (now routed to hasMissing() instead, see
    // SchematicMover.toggleRequested, which never touches missingLocal at all).
    //
    // Correctness (view vs. copy): MissingLocalView freezes its content on its OWN first
    // actual read (see its doc), so a MissingSnapshot returned here behaves exactly like
    // an eager-copy one from the CALLER's perspective -- once read, missingLocal
    // never changes under a caller holding the reference, even across later
    // applyStatus/finishInitialize calls. The only thing that changed is WHEN the copy
    // happens (lazily, on first .missingLocal access, instead of unconditionally inside
    // this function) and that a caller who never touches .missingLocal (several call
    // sites only read .revision, e.g. SchematicPrinter's isBlocked/deferMoverPositions)
    // now pays nothing for it. The revision field itself is unaffected by any of this: it
    // is still only ever bumped inside applyStatus/finishInitialize, so callers gating on
    // it (LayerGateEligibleMissingCache/MoverMissingWorldCache's structural-vs-incremental
    // branch) keep exactly their existing semantics.
    internal fun missingSnapshot(): MissingSnapshot {
        return MissingSnapshot(
            revision = revision,
            missingLocal = MissingLocalView(airPos, blockPos),
        )
    }

    // The changes strictly after `sinceRevision`, in the order they
    // happened, or null if that span is not (or no longer) fully retained -- either
    // `sinceRevision` predates the log entirely (a stale caller that has never synced, or
    // one that missed a recent initialize()'s reset) or the log has evicted entries past
    // CHANGE_LOG_CAPACITY. Either way the caller's only correct move is one full rebuild
    // from missingSnapshot(); this never happens on ordinary per-placement traffic (each
    // tick's caller is at most a few revisions behind), only after a real gap.
    internal fun changesSince(sinceRevision: Long): List<MissingBlockChange>? {
        if (sinceRevision == revision) return emptyList()
        val retainedBase = revision - changeLog.size
        if (sinceRevision < retainedBase || sinceRevision > revision) return null
        val skip = (sinceRevision - retainedBase).toInt()
        return changeLog.toList().subList(skip, changeLog.size)
    }

    private fun applyStatus(
        localPos: BlockPos,
        airMissing: Boolean,
        blockMissing: Boolean,
    ): MissingBlockChange {
        val wasAirMissing = localPos in airPos
        val wasBlockMissing = localPos in blockPos
        val wasSatisfied = !wasAirMissing && !wasBlockMissing

        if (airMissing) {
            if (!wasAirMissing) airPos.add(localPos)
        } else {
            airPos.remove(localPos)
        }
        if (blockMissing) {
            if (!wasBlockMissing) blockPos.add(localPos)
        } else {
            blockPos.remove(localPos)
        }

        val satisfied = !airMissing && !blockMissing
        val overlayChanged = wasAirMissing != airMissing || wasBlockMissing != blockMissing
        if (overlayChanged) {
            revision++
        }
        val change = MissingBlockChange(
            localPos = localPos,
            overlayChanged = overlayChanged,
            satisfiedChanged = wasSatisfied != satisfied,
            satisfied = satisfied,
        )
        if (overlayChanged) {
            changeLog.addLast(change)
            while (changeLog.size > CHANGE_LOG_CAPACITY) changeLog.removeFirst()
        }
        return change
    }

    private const val CHANGE_LOG_CAPACITY: Int = 4096
}
