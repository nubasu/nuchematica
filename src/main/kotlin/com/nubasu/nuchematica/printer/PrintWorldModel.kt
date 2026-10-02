package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * Main-thread frozen world region updated through explicit observed-write records.
 *
 * Reads outside a ready capture fall back to the current client world.
 */
internal object PrintWorldModel {
    internal enum class Status { IDLE, CAPTURING, READY, REFUSED }

    internal const val CAPTURE_CELLS_PER_TICK: Int = 50_000

    private const val MAX_REGION_CELLS: Long = 40_000_000L
    private const val REGION_MARGIN: Int = 2
    private const val PROGRESS_STEP_PERCENT: Int = 20

    private val AIR_STATE: BlockState = Blocks.AIR.defaultBlockState()

    private var status: Status = Status.IDLE
    private var levelIdentity: Any? = null
    private var contentIdentity: Any? = null
    private var transformRevision: Long = 0L

    private var minX: Int = 0
    private var minY: Int = 0
    private var minZ: Int = 0
    private var sizeX: Int = 0
    private var sizeY: Int = 0
    private var sizeZ: Int = 0
    private var cells: IntArray = IntArray(0)
    private val palette: ArrayList<BlockState> = ArrayList()
    private val paletteIndex: HashMap<BlockState, Int> = HashMap()

    private var captureCursor: Long = 0L
    private var reportedPercent: Int = -1

    private var writeRevisionCounter: Long = 0L

    private var planSnapshotActive: Boolean = false
    private var planSnapshotCursor: Int = 0
    private var planSnapshotStartRevision: Long = 0L
    private var planSnapshotBuffer: IntArray = IntArray(0)

    internal fun status(): Status = status

    internal fun writeRevision(): Long = writeRevisionCounter

    /** Starts or reuses an identity-matched capture and returns its lifecycle state. */
    internal fun ensureCapture(
        level: ClientLevel,
        contentIdentity: Any,
        transformRevision: Long,
        localPositions: Collection<BlockPos>,
        localToWorld: (BlockPos) -> BlockPos,
    ): Status {
        if (
            status != Status.IDLE &&
            levelIdentity === level &&
            this.contentIdentity === contentIdentity &&
            this.transformRevision == transformRevision
        ) {
            return status
        }

        reset()
        levelIdentity = level
        this.contentIdentity = contentIdentity
        this.transformRevision = transformRevision

        val bounds = schematicWorldBoundingBox(localPositions, localToWorld, REGION_MARGIN)
        // Empty schematics intentionally fall back to live reads.
        if (bounds == null) {
            status = Status.REFUSED
            return status
        }
        val (min, max) = bounds
        val sizeXLong = (max.x - min.x + 1).toLong()
        val sizeYLong = (max.y - min.y + 1).toLong()
        val sizeZLong = (max.z - min.z + 1).toLong()
        val volume = sizeXLong * sizeYLong * sizeZLong
        if (volume > MAX_REGION_CELLS) {
            ChatSender.send(
                "[nuchematica] schematic region too large for the world model " +
                    "($volume cells > $MAX_REGION_CELLS): frozen-world optimization disabled",
            )
            status = Status.REFUSED
            return status
        }

        minX = min.x
        minY = min.y
        minZ = min.z
        sizeX = sizeXLong.toInt()
        sizeY = sizeYLong.toInt()
        sizeZ = sizeZLong.toInt()
        cells = IntArray(volume.toInt())
        palette.add(AIR_STATE)
        paletteIndex[AIR_STATE] = 0
        captureCursor = 0L
        reportedPercent = -1
        status = Status.CAPTURING
        return status
    }

    internal fun pump(
        level: ClientLevel,
        onProgress: (Int) -> Unit = ::reportProgressToChat,
    ): Status {
        if (status != Status.CAPTURING) return status
        val volume = cells.size.toLong()
        val end = minOf(captureCursor + CAPTURE_CELLS_PER_TICK, volume)
        var index = captureCursor
        while (index < end) {
            val worldPos = worldPosForIndex(index)
            cells[index.toInt()] = paletteIdFor(level.getBlockState(worldPos))
            index++
        }
        captureCursor = index
        if (volume > 0L) {
            val percent = ((captureCursor * 100L) / volume).toInt()
            val bucket = (percent / PROGRESS_STEP_PERCENT) * PROGRESS_STEP_PERCENT
            if (bucket > reportedPercent && bucket in 1..99) {
                reportedPercent = bucket
                onProgress(bucket)
            }
        }
        if (captureCursor >= volume) status = Status.READY
        return status
    }

    internal fun cancel(): Unit {
        reset()
    }

    /** Reads the ready capture in-region and the current client world elsewhere. */
    internal fun stateAt(pos: BlockPos): BlockState {
        if (status == Status.READY && inRegion(pos)) {
            return palette[cells[indexFor(pos)]]
        }
        return Minecraft.getInstance().level?.getBlockState(pos) ?: AIR_STATE
    }

    /** Records an observed state only when its position lies in the ready capture. */
    internal fun recordWrite(pos: BlockPos, state: BlockState): Unit {
        if (status != Status.READY || !inRegion(pos)) return
        cells[indexFor(pos)] = paletteIdFor(state)
        writeRevisionCounter++
    }

    /** Starts a budgeted copy of the ready capture, replacing any pending copy. */
    internal fun beginPlanSnapshot(): Boolean {
        if (status != Status.READY) return false
        planSnapshotActive = true
        planSnapshotCursor = 0
        planSnapshotStartRevision = writeRevisionCounter
        planSnapshotBuffer = IntArray(cells.size)
        return true
    }

    /** Copies up to [maxCells] cells, restarting if the source revision changes. */
    internal fun pumpPlanSnapshot(maxCells: Int): PlanSnapshotPump {
        if (!planSnapshotActive || status != Status.READY) return PlanSnapshotPump.Unavailable
        var budget = maxCells
        while (true) {
            if (writeRevisionCounter != planSnapshotStartRevision) {
                planSnapshotCursor = 0
                planSnapshotStartRevision = writeRevisionCounter
            }
            if (planSnapshotCursor < cells.size && budget > 0) {
                val end = minOf(planSnapshotCursor + budget, cells.size)
                val copied = end - planSnapshotCursor
                System.arraycopy(cells, planSnapshotCursor, planSnapshotBuffer, planSnapshotCursor, copied)
                planSnapshotCursor = end
                budget -= copied
            }
            if (planSnapshotCursor < cells.size) return PlanSnapshotPump.InProgress
            val paletteSnapshot: List<BlockState> = ArrayList(palette)
            if (writeRevisionCounter != planSnapshotStartRevision) continue
            val completedRevision = writeRevisionCounter
            val snapshot = PlanWorldSnapshot(
                cells = planSnapshotBuffer,
                palette = paletteSnapshot,
                minX = minX,
                minY = minY,
                minZ = minZ,
                sizeX = sizeX,
                sizeY = sizeY,
                sizeZ = sizeZ,
            )
            planSnapshotActive = false
            return PlanSnapshotPump.Complete(snapshot, completedRevision)
        }
    }

    /** Discards only the pending plan copy while preserving the ready capture. */
    internal fun cancelPlanSnapshot(): Unit {
        planSnapshotActive = false
        planSnapshotCursor = 0
        planSnapshotBuffer = IntArray(0)
    }

    internal sealed interface PlanSnapshotPump {
        object InProgress : PlanSnapshotPump
        data class Complete(internal val snapshot: PlanWorldSnapshot, internal val writeRevision: Long) : PlanSnapshotPump
        object Unavailable : PlanSnapshotPump
    }

    /** Immutable in-region snapshot whose [stateAt] rejects out-of-bounds positions. */
    internal class PlanWorldSnapshot internal constructor(
        private val cells: IntArray,
        private val palette: List<BlockState>,
        private val minX: Int,
        private val minY: Int,
        private val minZ: Int,
        private val sizeX: Int,
        private val sizeY: Int,
        private val sizeZ: Int,
    ) {
        internal fun stateAt(pos: BlockPos): BlockState {
            val x = pos.x - minX
            val y = pos.y - minY
            val z = pos.z - minZ
            check(x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) {
                "position outside plan snapshot region: $pos"
            }
            return palette[cells[x + sizeX * (y + sizeY * z)]]
        }
    }

    private fun inRegion(pos: BlockPos): Boolean {
        if (cells.isEmpty()) return false
        val x = pos.x - minX
        val y = pos.y - minY
        val z = pos.z - minZ
        return x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ
    }

    private fun indexFor(pos: BlockPos): Int {
        val x = pos.x - minX
        val y = pos.y - minY
        val z = pos.z - minZ
        return x + sizeX * (y + sizeY * z)
    }

    private fun worldPosForIndex(index: Long): BlockPos {
        val x = (index % sizeX).toInt()
        val remaining = index / sizeX
        val y = (remaining % sizeY).toInt()
        val z = (remaining / sizeY).toInt()
        return BlockPos(minX + x, minY + y, minZ + z)
    }

    private fun paletteIdFor(state: BlockState): Int {
        return paletteIndex.getOrPut(state) {
            palette.add(state)
            palette.size - 1
        }
    }

    private fun reportProgressToChat(percent: Int): Unit {
        ChatSender.send("[nuchematica] scanning... $percent%")
    }

    private fun reset(): Unit {
        status = Status.IDLE
        levelIdentity = null
        contentIdentity = null
        transformRevision = 0L
        minX = 0
        minY = 0
        minZ = 0
        sizeX = 0
        sizeY = 0
        sizeZ = 0
        cells = IntArray(0)
        palette.clear()
        paletteIndex.clear()
        captureCursor = 0L
        reportedPercent = -1
        writeRevisionCounter++
        planSnapshotActive = false
        planSnapshotCursor = 0
        planSnapshotBuffer = IntArray(0)
    }
}
