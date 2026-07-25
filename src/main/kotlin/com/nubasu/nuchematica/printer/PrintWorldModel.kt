package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

// The frozen-world assumption for v3 (within the print range, only automode
// itself changes the world during a run). Holds a
// snapshot of the schematic's world-space AABB (+ margin) as a flat array indexed by
// (x,y,z) offset -- explicitly NOT a HashMap<BlockPos, BlockState> for the bulk storage,
// which at 0_all's 27.9M-cell volume would mean 500MB+ of boxed entries and GC pressure
// this design already ruled out. Positions outside the region fall
// back to a live world read (rare: support/scaffold neighbors just beyond the margin).
//
// Building the snapshot is budgeted across client ticks (CAPTURE_CELLS_PER_TICK per call)
// instead of one blocking pass, since a live getBlockState per cell over the whole region
// is exactly the freeze MissingBlockHolder.initialize used to cause. While CAPTURING,
// SchematicPrinter/SchematicMover stay idle (see their tick() entry guards) -- there is
// nothing to place or route yet, since the classification pass that derives "missing"
// itself waits for this capture to finish (see MissingBlockHolder.initialize).
//
// Writes are the other half of the frozen-world discipline: this model is updated ONLY
// from server-confirmed outcomes (a placement's ACCEPTED/WRONG_STATE ack, a confirmed
// scaffold break, the generic manual-placement click reconcile) -- never speculatively.
// See SchematicPrinter.tick's completed.forEach, ScaffoldLedger's onBroken callback, and
// Nuchematica.onClientTick's pending break/place reconcile for the write call sites.
//
// Main-thread-only: this object holds no synchronization and must not be touched off the
// client thread (matches every other piece of per-tick printer/mover state in this repo).
internal object PrintWorldModel {
    internal enum class Status { IDLE, CAPTURING, READY, REFUSED }

    // CAPTURE_CELLS_PER_TICK: the per-tick capture budget.
    internal const val CAPTURE_CELLS_PER_TICK: Int = 50_000

    // Region volume cap. Above this, the model refuses to activate at
    // all (falls back to an always-live-read behavior) rather than allocate an
    // unbounded flat array; 0_all's 27.9M-cell region sits comfortably under this.
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

    // Monotonic write counter, bumped by every recordWrite that actually stores a cell
    // (including a same-value store) and by every reset() -- cancel(), a failed ensureCapture,
    // and each new-identity capture restart all route through reset(), so a stale plan
    // snapshot can always tell it no longer matches the model by comparing against a value it
    // captured earlier. Never reset back to 0 itself.
    private var writeRevisionCounter: Long = 0L

    private var planSnapshotActive: Boolean = false
    private var planSnapshotCursor: Int = 0
    private var planSnapshotStartRevision: Long = 0L
    private var planSnapshotBuffer: IntArray = IntArray(0)

    internal fun status(): Status = status

    internal fun writeRevision(): Long = writeRevisionCounter

    // Starts a fresh capture whenever the (level, contentIdentity, transformRevision)
    // identity differs from the one this model was last built/building for -- mirrors the
    // identity-keyed invalidation every other printer/mover cache in this package already
    // uses (LayerGateMissingCache, PrinterDeferralLedger, ...). A matching identity is a
    // no-op: an in-progress capture keeps its cursor, a READY/REFUSED model stays as-is.
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
        if (bounds == null) {
            // Nothing to capture (empty schematic): every stateAt query is out-of-region by
            // construction, so REFUSED (always-live-fallback) is exactly correct here too.
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

    // Advances the in-progress capture by at most CAPTURE_CELLS_PER_TICK cells, reading
    // the live world once per cell (this IS the one-time cost the budgeting spreads across
    // ticks instead of paying in one freeze). No-op (returns the current status
    // immediately) unless a capture is actually in progress. onProgress fires at most once
    // per 20%-multiple crossed (never at 0% or 100%: the caller's own CAPTURING->READY
    // transition already marks completion).
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

    // Cancels an in-progress (or completed) capture cleanly, discarding the array/palette
    // and returning to IDLE. Called on toggles/aborts that must not keep stale capture
    // state around (world unload; a fresh ensureCapture call for a new identity performs
    // the same reset internally before rebuilding).
    internal fun cancel(): Unit {
        reset()
    }

    // Inside a READY region: array lookup, no world I/O. Outside the region, or before the
    // model is READY (CAPTURING/IDLE/REFUSED): a live read, exactly like every call site
    // would without this model -- deliberately NOT a stored level reference, so this
    // always reflects whatever ClientLevel is actually current (and so tests that stub
    // Minecraft.getInstance().level directly keep working against an IDLE/REFUSED model
    // with zero PrintWorldModel setup).
    internal fun stateAt(pos: BlockPos): BlockState {
        if (status == Status.READY && inRegion(pos)) {
            return palette[cells[indexFor(pos)]]
        }
        return Minecraft.getInstance().level?.getBlockState(pos) ?: AIR_STATE
    }

    // The only mutation path -- callers own the "was this write
    // actually server-confirmed" decision (see the class doc's write-site list). A no-op
    // outside the READY region (nothing to update) or before READY (no array to write
    // into yet -- MissingBlockHolder.initialize hasn't consumed the capture, so nothing
    // downstream can have observed a stale value yet either).
    internal fun recordWrite(pos: BlockPos, state: BlockState): Unit {
        if (status != Status.READY || !inRegion(pos)) return
        cells[indexFor(pos)] = paletteIdFor(state)
        writeRevisionCounter++
    }

    // Begins a budgeted copy session of the current READY capture, for a plan classification
    // pass to run against off the main thread (see PlanCoordinator) -- false when the model is
    // not READY, since there is nothing consistent to copy yet. Starting a session always
    // discards whatever the previous session's own buffer held (a fresh allocation), matching
    // every other identity-keyed restart in this object.
    internal fun beginPlanSnapshot(): Boolean {
        if (status != Status.READY) return false
        planSnapshotActive = true
        planSnapshotCursor = 0
        planSnapshotStartRevision = writeRevisionCounter
        planSnapshotBuffer = IntArray(cells.size)
        return true
    }

    // Advances the in-progress plan snapshot copy by at most maxCells cells via arraycopy
    // chunks. If writeRevision changed since this copy pass began -- including partway through
    // this very call -- the copy silently restarts from cell 0 under the same call's remaining
    // budget rather than publish a mix of pre- and post-write cells. The palette is only ever
    // copied once every cell has, immediately before one final revision check, so a write
    // racing in during that last window still forces a restart instead of shipping a snapshot
    // whose palette and cells disagree on which revision they belong to.
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

    // Drops an in-progress plan snapshot copy early, freeing its buffer immediately instead of
    // leaving it to linger until the next beginPlanSnapshot() call overwrites it -- called by
    // PlanCoordinator's own cancel(); never touches this model's own READY capture.
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

    // Immutable, isolated copy of a READY capture's cells/palette/geometry, taken by
    // pumpPlanSnapshot for a worker thread to classify a plan against. A worker thread must
    // never fall back to a live world read: planner bounds predicates reject out-of-region
    // positions before reading, so any out-of-region read reaching this snapshot is a bug to
    // surface loudly, not to paper over -- hence the throw below rather than a live fallback.
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

    // x fastest, then y, then z slowest -- the exact inverse of worldPosForIndex below.
    // Boundary correctness (the classic off-by-one risk on AABB edges) is exercised by
    // PrintWorldModelTest against every face/corner of the captured region.
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
