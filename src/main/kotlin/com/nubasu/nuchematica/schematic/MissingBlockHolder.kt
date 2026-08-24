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

/** Read-only combined view backed by [AbstractList]'s default mutators. */
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

/** Tracks mismatched schematic positions and their incremental revision log. */
object MissingBlockHolder {
    public val blockPos: MutableList<BlockPos> = OrderedPositionSet()
    public val airPos: MutableList<BlockPos> = OrderedPositionSet()
    private var revision: Long = 0L

    private val changeLog: ArrayDeque<MissingBlockChange> = ArrayDeque()

    private var initializing: Boolean = false
    private var pendingEntries: Iterator<Map.Entry<BlockPos, BlockState>>? = null
    private var workingAirPos: ArrayList<BlockPos> = arrayListOf()
    private var workingBlockPos: ArrayList<BlockPos> = arrayListOf()
    private var initializeTotal: Int = 0
    private var initializeProcessed: Int = 0
    private var initializeReportedPercent: Int = -1

    internal fun isInitializing(): Boolean = initializing

    internal fun hasMissing(): Boolean = airPos.isNotEmpty() || blockPos.isNotEmpty()

    internal fun missingCount(): Int = airPos.size + blockPos.size

    /**
     * Restarts classification and consumes its first per-tick budget immediately.
     *
     * Large schematics may remain in progress; callers must continue [pump] while
     * [isInitializing] is true.
     */
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

    /** Advances one classification budget and returns true when no work remains. */
    internal fun pump(onProgress: (Int) -> Unit = ::reportInitializeProgress): Boolean {
        val entries = pendingEntries ?: return true
        var processedThisCall = 0
        while (entries.hasNext() && processedThisCall < PrintWorldModel.CAPTURE_CELLS_PER_TICK) {
            val (pos, expectedState) = entries.next()
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

    /** Returns a revisioned view that freezes its positions on first content read. */
    internal fun missingSnapshot(): MissingSnapshot {
        return MissingSnapshot(
            revision = revision,
            missingLocal = MissingLocalView(airPos, blockPos),
        )
    }

    /**
     * Returns retained changes after [sinceRevision], or null when a rebuild is required.
     */
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
