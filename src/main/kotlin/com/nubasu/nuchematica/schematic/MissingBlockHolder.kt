package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.printer.MissingSnapshot
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.AbstractList
import java.util.ArrayDeque

public data class MissingBlockChange(
    public val localPos: BlockPos,
    public val overlayChanged: Boolean,
    public val satisfiedChanged: Boolean,
    public val satisfied: Boolean,
    public val extraChanged: Boolean = false,
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
    public val extraPos: MutableList<BlockPos> = OrderedPositionSet()
    private var revision: Long = 0L

    private val changeLog: ArrayDeque<MissingBlockChange> = ArrayDeque()

    private var initializing: Boolean = false
    private var pendingEntries: Iterator<Map.Entry<BlockPos, BlockState>>? = null
    private var workingAirPos: ArrayList<BlockPos> = arrayListOf()
    private var workingBlockPos: ArrayList<BlockPos> = arrayListOf()
    private var workingExtraPos: ArrayList<BlockPos> = arrayListOf()
    private var scanRegion: ExpectedAirRegion? = null
    private var regionCursor: Long = 0L
    private var initializeTotal: Long = 0L
    private var initializeProcessed: Long = 0L
    private var initializeReportedPercent: Int = -1

    // Chunks the scan could not read (unloaded, so the client reports VOID_AIR everywhere in them).
    // Positions inside an unknown chunk are classified as neither air-missing, block-missing, nor extra.
    private val unknownChunks: LinkedHashMap<Long, BlockPos> = LinkedHashMap()
    private val unknownProbeOrder: ArrayDeque<Long> = ArrayDeque()
    private val rescanQueue: ArrayDeque<Long> = ArrayDeque()
    private var rescanCurrentChunk: Long? = null
    private var rescanCellCursor: Int = 0
    private var worldMinY: Int = 0
    private var worldMaxY: Int = -1

    internal fun isInitializing(): Boolean = initializing

    internal fun hasMissing(): Boolean = airPos.isNotEmpty() || blockPos.isNotEmpty()

    internal fun missingCount(): Int = airPos.size + blockPos.size

    internal fun unknownChunkCount(): Int = unknownChunks.size

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
        val region = SchematicHolder.expectedAirRegion
        scanRegion = region
        regionCursor = 0L
        initializeTotal = dummy.blocks.size.toLong() + (region?.volume ?: 0L)
        initializeProcessed = 0L
        initializeReportedPercent = -1
        workingAirPos = ArrayList()
        workingBlockPos = ArrayList()
        workingExtraPos = ArrayList()
        unknownChunks.clear()
        unknownProbeOrder.clear()
        rescanQueue.clear()
        rescanCurrentChunk = null
        rescanCellCursor = 0
        val yRange = localYRange(dummy, region)
        worldMinY = yRange?.let { SchematicRenderManager.localBlockToWorld(BlockPos(0, it.first, 0)).y } ?: 0
        worldMaxY = yRange?.let { SchematicRenderManager.localBlockToWorld(BlockPos(0, it.second, 0)).y } ?: -1
        initializing = true
    }

    /** Local Y span covering both the rendered blocks and the expected-air region, if any. */
    private fun localYRange(content: SchematicCache, region: ExpectedAirRegion?): Pair<Int, Int>? {
        var minY: Int? = null
        var maxY: Int? = null
        for (pos in content.blocks.keys) {
            val y = pos.y
            if (minY == null || y < minY) minY = y
            if (maxY == null || y > maxY) maxY = y
        }
        if (region != null) {
            minY = if (minY == null) region.minY else minOf(minY, region.minY)
            maxY = if (maxY == null) region.maxY else maxOf(maxY, region.maxY)
        }
        val safeMinY = minY ?: return null
        val safeMaxY = maxY ?: return null
        return safeMinY to safeMaxY
    }

    /**
     * Records a scan position that could not be classified because its chunk is unloaded.
     *
     * The probe is taken at the middle of the scanned world Y span so that a position outside the
     * level's build height (which also reads as void air) does not keep the chunk unknown forever.
     */
    private fun recordUnknownChunk(worldPos: BlockPos): Unit {
        val chunkKey = ChunkPos.asLong(worldPos)
        val probe = BlockPos(worldPos.x, worldMinY + (worldMaxY - worldMinY) / 2, worldPos.z)
        if (unknownChunks.putIfAbsent(chunkKey, probe) == null) {
            unknownProbeOrder.addLast(chunkKey)
        }
    }

    /** Advances one classification budget and returns true when no work remains. */
    internal fun pump(onProgress: (Int) -> Unit = ::reportInitializeProgress): Boolean {
        val entries = pendingEntries ?: return true
        var budgetRemaining = PrintWorldModel.CAPTURE_CELLS_PER_TICK
        while (entries.hasNext() && budgetRemaining > 0) {
            val (pos, expectedState) = entries.next()
            val worldPos = SchematicRenderManager.localBlockToWorld(pos)
            val actualState = PrintWorldModel.stateAt(worldPos)
            if (actualState.`is`(Blocks.VOID_AIR)) {
                recordUnknownChunk(worldPos)
            } else if (!BlockStateEquivalence.matches(expectedState, actualState)) {
                if (actualState.isAir) workingAirPos.add(pos) else workingBlockPos.add(pos)
            }
            budgetRemaining--
            initializeProcessed++
        }
        if (!entries.hasNext()) {
            val region = scanRegion
            if (region != null) {
                val schematicBlocks = SchematicHolder.schematicCache.blocks
                while (regionCursor < region.volume && budgetRemaining > 0) {
                    val cell = region.positionAt(regionCursor)
                    if (cell !in schematicBlocks) {
                        val worldPos = SchematicRenderManager.localBlockToWorld(cell)
                        val actualState = PrintWorldModel.stateAt(worldPos)
                        if (actualState.`is`(Blocks.VOID_AIR)) {
                            recordUnknownChunk(worldPos)
                        } else if (!actualState.isAir) {
                            workingExtraPos.add(cell)
                        }
                    }
                    regionCursor++
                    budgetRemaining--
                    initializeProcessed++
                }
            }
        }
        if (initializeTotal > 0L) {
            val percent = ((initializeProcessed * 100L) / initializeTotal).toInt()
            val bucket = (percent / PROGRESS_STEP_PERCENT) * PROGRESS_STEP_PERCENT
            if (bucket > initializeReportedPercent && bucket in 1..99) {
                initializeReportedPercent = bucket
                onProgress(bucket)
            }
        }
        if (entries.hasNext()) return false
        val region = scanRegion
        if (region != null && regionCursor < region.volume) return false
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
        extraPos.clear()
        extraPos.addAll(workingExtraPos)

        if (blockPos != previousBlockPos || airPos != previousAirPos) {
            revision++
        }
        changeLog.clear()
        initializing = false
        pendingEntries = null
        scanRegion = null
        regionCursor = 0L
        workingAirPos = ArrayList()
        workingBlockPos = ArrayList()
        workingExtraPos = ArrayList()
    }

    private fun reportInitializeProgress(percent: Int): Unit {
        ChatSender.send("[nuchematica] scanning... $percent%")
    }

    private const val PROGRESS_STEP_PERCENT: Int = 20

    public fun placed(pos: BlockPos, actualState: BlockState): MissingBlockChange? {
        val localPos = SchematicRenderManager.worldBlockToLocal(pos)
        val dummy = SchematicHolder.renderingBlocks
        val expectedState = dummy.blocks[localPos] ?: return applyExtraStatus(localPos, extra = !actualState.isAir)
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
        val expectedState = dummy.blocks[localPos] ?: return applyExtraStatus(localPos, extra = false)
        return applyStatus(
            localPos = localPos,
            airMissing = !expectedState.isAir,
            blockMissing = false,
        )
    }

    /** Tracks a non-schematic position inside the expected-air region whose world block is non-air. */
    private fun applyExtraStatus(localPos: BlockPos, extra: Boolean): MissingBlockChange? {
        val region = SchematicHolder.expectedAirRegion ?: return null
        if (!region.contains(localPos)) return null
        if (localPos in SchematicHolder.schematicCache.blocks) return null
        val wasExtra = localPos in extraPos
        if (extra) {
            if (!wasExtra) extraPos.add(localPos)
        } else {
            extraPos.remove(localPos)
        }
        return MissingBlockChange(
            localPos = localPos,
            overlayChanged = false,
            satisfiedChanged = false,
            satisfied = true,
            extraChanged = wasExtra != extra,
        )
    }

    internal fun satisfiedPositions(): Set<BlockPos> {
        val airPositions = HashSet(airPos)
        val blockPositions = HashSet(blockPos)
        val hasUnknownChunks = unknownChunks.isNotEmpty()
        return SchematicHolder.renderingBlocks.blocks.keys.filterTo(LinkedHashSet()) { pos ->
            pos !in airPositions && pos !in blockPositions &&
                (!hasUnknownChunks || ChunkPos.asLong(SchematicRenderManager.localBlockToWorld(pos)) !in unknownChunks)
        }
    }

    /**
     * Probes chunks recorded as unknown by the last scan and reclassifies the ones that loaded.
     *
     * No-op while a scan is in progress. Each call first re-probes a bounded number of unknown
     * chunks, moving the ones that loaded into a rescan queue, then reclassifies that queue's
     * positions under a separate cell budget, carrying both cursors across calls so neither a long
     * chunk list nor a single large chunk needs to finish within one call.
     */
    internal fun pumpUnknownChunks(): List<MissingBlockChange> {
        if (initializing) return emptyList()
        val changes = ArrayList<MissingBlockChange>()

        var probeBudget = UNKNOWN_CHUNK_POLL_PER_CALL
        while (probeBudget > 0) {
            val chunkKey = unknownProbeOrder.poll() ?: break
            probeBudget--
            val probePos = unknownChunks[chunkKey] ?: continue
            if (PrintWorldModel.stateAt(probePos).`is`(Blocks.VOID_AIR)) {
                unknownProbeOrder.addLast(chunkKey)
            } else {
                unknownChunks.remove(chunkKey)
                rescanQueue.addLast(chunkKey)
            }
        }

        var cellBudget = PrintWorldModel.CAPTURE_CELLS_PER_TICK
        while (cellBudget > 0) {
            val chunkKey = rescanCurrentChunk ?: rescanQueue.poll()?.also {
                rescanCurrentChunk = it
                rescanCellCursor = 0
            } ?: break

            val chunkPos = ChunkPos(chunkKey)
            val baseX = chunkPos.minBlockX
            val baseZ = chunkPos.minBlockZ
            val ySize = worldMaxY - worldMinY + 1
            val totalCells = 16 * 16 * ySize
            // A chunk that unloaded again since it was probed goes back to the unknown set; a void
            // cell inside a loaded chunk only means the cell is outside the build height, so it is
            // skipped rather than making the whole chunk unknown.
            val probe = BlockPos(baseX, worldMinY + (worldMaxY - worldMinY) / 2, baseZ)
            if (rescanCellCursor == 0 && PrintWorldModel.stateAt(probe).`is`(Blocks.VOID_AIR)) {
                unknownChunks[chunkKey] = probe
                unknownProbeOrder.addLast(chunkKey)
                rescanCurrentChunk = null
                continue
            }
            while (rescanCellCursor < totalCells && cellBudget > 0) {
                val index = rescanCellCursor
                val worldPos = BlockPos(
                    baseX + index % 16,
                    worldMinY + index / 256,
                    baseZ + (index / 16) % 16,
                )
                val state = PrintWorldModel.stateAt(worldPos)
                cellBudget--
                rescanCellCursor++
                if (state.`is`(Blocks.VOID_AIR)) continue
                val localPos = SchematicRenderManager.worldBlockToLocal(worldPos)
                val expectedState = SchematicHolder.renderingBlocks.blocks[localPos]
                if (expectedState != null) {
                    val satisfied = BlockStateEquivalence.matches(expectedState, state)
                    val change = applyStatus(
                        localPos = localPos,
                        airMissing = !satisfied && state.isAir,
                        blockMissing = !satisfied && !state.isAir,
                        previouslyKnown = false,
                    )
                    if (change.overlayChanged || change.satisfiedChanged) changes.add(change)
                } else {
                    val region = SchematicHolder.expectedAirRegion
                    if (region != null && region.contains(localPos) && localPos !in SchematicHolder.schematicCache.blocks) {
                        val change = applyExtraStatus(localPos, extra = !state.isAir)
                        if (change != null && change.extraChanged) changes.add(change)
                    }
                }
            }
            if (rescanCellCursor >= totalCells) {
                rescanCurrentChunk = null
            }
        }

        return changes
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
        previouslyKnown: Boolean = true,
    ): MissingBlockChange {
        val wasAirMissing = localPos in airPos
        val wasBlockMissing = localPos in blockPos
        // A position rescanned out of an unknown chunk was in neither list, but that reflects
        // "unknown", not "satisfied" -- it must not be reported as freshly satisfied unless it is.
        val wasSatisfied = previouslyKnown && !wasAirMissing && !wasBlockMissing

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
    private const val UNKNOWN_CHUNK_POLL_PER_CALL: Int = 256
}
