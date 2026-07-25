package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.FeedSnapshot
import com.nubasu.nuchematica.printer.PlanFrontierTarget
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

public class MoverSessionKey(
    private val levelIdentity: Any,
    private val contentIdentity: Any,
    private val transformRevision: Long,
) {
    public fun matches(other: MoverSessionKey): Boolean {
        return levelIdentity === other.levelIdentity &&
            contentIdentity === other.contentIdentity &&
            transformRevision == other.transformRevision
    }
}

public enum class MoverState {
    IDLE,
    TAKEOFF,
    CRUISE,
    HOLD,
    COMPLETE,
    ABORTED,
}

public enum class MoverAbortReason {
    MANUAL_INPUT,
    GUI_OPEN,
    DAMAGED,
    SERVER_CORRECTION,
    GAMEMODE_LOST,
    SESSION_CHANGED,
    TOGGLED_OFF,
    MAYFLY_REQUIRED,
    TAKEOFF_TIMEOUT,
    // Batch/discrete-mode safety net for genuine entombment -- see
    // MoverCore's trappedThisTick doc for the exact trigger.
    TRAPPED,
}

public data class MoverCommand(
    public val jump: Boolean,
    public val enableFlight: Boolean,
    public val horizontalX: Double,
    public val horizontalZ: Double,
    public val vertical: Int,
    public val stopMovement: Boolean,
)

public data class PathProbeResult(
    public val clear: Boolean,
    public val chunkLoaded: Boolean,
)

public enum class MoverDeferralCause {
    MOVER_UNREACHABLE,
    NO_PROGRESS,
}

// Mover-side mirror of the printer's own (internal) PlanFrontierSnapshot/
// PlanFrontierTarget (see SchematicPrinter.planFrontierSnapshot's doc for what
// waitingForReach/columnBlocked/inFlight mean) -- a distinct public type rather than a
// direct reference, since the printer package's originals are internal-visibility
// classes and MoverTickContext's own public data-class copy()/constructor cannot expose
// an internal parameter type. SchematicMover.tick does the one-shot conversion.
public data class MoverPlanFrontierTarget(
    public val pos: BlockPos,
    public val expected: BlockState,
)

public data class MoverPlanFrontier(
    public val waitingForReach: List<MoverPlanFrontierTarget>,
    public val columnBlocked: List<BlockPos>,
    public val inFlight: List<BlockPos>,
    // The printer's own FULL waitingForReach/columnBlocked counts, mirrored straight through
    // from PlanFrontierSnapshot (see its own doc) -- what the HUD's remaining-work count
    // reads. Defaulted to the list's own size so every fixture predating this field keeps
    // reporting exactly what it always did.
    public val totalWaitingForReach: Int = waitingForReach.size,
    public val totalColumnBlocked: Int = columnBlocked.size,
    // The printer cursor's own pending+waiting+inFlight action count -- what MoverCore's own
    // plan-mode remainingMissing (the HUD's "Blocks left" line) reads instead of a frontier
    // list's own size, since a frontier list's size tracks how far dispatch has marched rather
    // than how much work is actually left, and can grow as dispatch advances even while real
    // remaining work shrinks. Defaulted to totalWaitingForReach + totalColumnBlocked so a
    // fixture that predates this field keeps reporting exactly what it always did.
    public val totalRemainingActions: Int = totalWaitingForReach + totalColumnBlocked,
)

// SchematicMover.tick's own conversion caps the printer's frontier down to the nearest
// maxCount targets before handing them to MoverCore for routing -- unbounded, a
// multi-thousand-target frontier means re-scoring every one of them through
// LayerRoutePlanner on every empty rebuild (see MoverCore.buildPlanRoute's own rebuild-
// pacing doc for the other half of that fix). Deterministic: ties broken by BlockPos
// comparison (x, then y, then z) rather than left to sort stability.
internal fun nearestPlanFrontierTargets(
    targets: List<MoverPlanFrontierTarget>,
    playerPosition: Vec3,
    maxCount: Int,
): List<MoverPlanFrontierTarget> {
    return targets.sortedWith(
        compareBy(
            { target -> blockCenterDistanceSquared(target.pos, playerPosition) },
            { target -> target.pos.x },
            { target -> target.pos.y },
            { target -> target.pos.z },
        ),
    ).take(maxCount)
}

private fun blockCenterDistanceSquared(pos: BlockPos, playerPosition: Vec3): Double {
    val center = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    return center.distanceToSqr(playerPosition)
}

// Snapshot-side counterpart of nearestPlanFrontierTargets's own cap: selects the nearest
// maxCount entries directly on the printer's own PlanFrontierTarget list (the pre-conversion
// type), by the same block-center distance/tie-break rule, and converts ONLY the selected
// ones into MoverPlanFrontierTarget wrappers. SchematicMover.tick's own per-tick conversion
// uses this instead of nearestPlanFrontierTargets so the printer's FULL waitingForReach list
// is never wrapped before it gets capped down to the bounded working set.
internal fun nearestPlanFrontierSnapshotTargets(
    targets: List<PlanFrontierTarget>,
    playerPosition: Vec3,
    maxCount: Int,
): List<MoverPlanFrontierTarget> {
    return targets.sortedWith(
        compareBy(
            { target -> blockCenterDistanceSquared(target.pos, playerPosition) },
            { target -> target.pos.x },
            { target -> target.pos.y },
            { target -> target.pos.z },
        ),
    ).take(maxCount).map { target -> MoverPlanFrontierTarget(target.pos, target.expected) }
}

public data class MoverTickContext(
    public val sessionKey: MoverSessionKey,
    public val playerPos: Vec3,
    public val onGround: Boolean,
    public val flying: Boolean,
    public val mayfly: Boolean,
    public val isCreative: Boolean,
    public val guiOpen: Boolean,
    public val hurt: Boolean,
    public val manualInput: Boolean,
    public val correctionReceived: Boolean,
    public val queueRevision: Long,
    internal val feedSnapshot: FeedSnapshot? = null,
    public val gateY: Int?,
    // ASCENT vs RECOVERY (see PrinterLayerGatePhase's doc).
    // Defaults to ASCENT so fixtures that predate the two-phase build keep their
    // existing (single-phase) behavior unless a test drives RECOVERY explicitly.
    public val gatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT,
    // Collection, not List -- MoverMissingWorldCache hands out a
    // live LinkedHashMap.values view (no per-tick copy). Every consumer only ever calls
    // size/filter{}, both Collection-safe.
    public val missingWorld: Collection<BlockPos>,
    public val isPlaceable: (BlockPos) -> Boolean,
    // Expected state per missing world position, threaded to the
    // batch planner (LayerRoutePlanner.planRoute) so its coverage decisions can use the
    // selector-aligned hit envelope instead of block-center distance. Defaults to null
    // for every position (planRoute falls back to a plain block's envelope) so older
    // fixtures keep working unmodified.
    public val expectedStateAt: (BlockPos) -> BlockState? = { null },
    public val isPassableCell: (BlockPos) -> Boolean,
    public val reach: Double,
    public val pathProbe: (from: Vec3, to: Vec3) -> PathProbeResult,
    // Fired with the covered set of the CURRENT work position by every MoverCore path
    // that abandons it without route completion (A* failure / chunk-wait timeout /
    // flight-loss limit). Candidate-level retry means this event does not itself defer
    // the covered blocks; a later onPositionsUncoverable event owns that decision.
    public val onWorkPositionAbandoned: (Set<BlockPos>) -> Unit,
    // Fired before rebuilding when an arrival HOLD reaches a fallback without any
    // queue-revision change since arrival. The second set contains only positions that
    // have now been unproductive at two distinct targets and must receive NO_PROGRESS.
    public val onWorkPositionUnproductive: (
        covered: Set<BlockPos>,
        deferPositions: Set<BlockPos>,
    ) -> Unit,
    // Fired for planner anchors for which every candidate is banned or covers nothing.
    public val onPositionsUncoverable: (
        positions: Set<BlockPos>,
        cause: MoverDeferralCause,
    ) -> Unit,
    // Checked only after the two-phase terminal protocol rebuilt an empty route from a
    // fresh tick context. Production rejects COMPLETE if printer classification still
    // has actionable work.
    public val canComplete: () -> Boolean,
    // Called only after the fresh empty-route confirmation passes canComplete. The
    // printer removes active progress backoffs without resetting their failure history,
    // then MoverCore performs one final route rebuild guarded by queue revision.
    public val onFinalSweepBackoffBypass: () -> Unit = {},
    internal val onHoldHardCap: () -> Unit = {},
    // Plan mode (v4, see MoverCore.buildRoute's context.planMode branch): when true,
    // targets come from planFrontier instead of missingWorld/gateY, and completion is
    // gated on planSessionFinal instead of canComplete(). Defaults to false (v3) so
    // every existing construction site is unaffected.
    internal val planMode: Boolean = false,
    // The plan-mode session's current frontier -- null whenever planMode is false, or
    // plan mode has no adopted session yet. See SchematicPrinter.planFrontierSnapshot's
    // own doc for what waitingForReach/columnBlocked/inFlight mean.
    internal val planFrontier: MoverPlanFrontier? = null,
    // See SchematicPrinter.isPlanSessionFinal's own doc -- the only signal plan mode's
    // empty-route terminal confirmation consults; canComplete() is never read for it.
    internal val planSessionFinal: Boolean = false,
    // Live world state reader for the plan-mode collision-aware A* profile
    // (FlightPassabilityProfile.collisionAware) -- null whenever plan mode has no world to
    // read against (planMode false, or an older fixture that predates this field). A plan
    // leg built with a null value falls back to the over-the-top shape instead of crashing.
    internal val planStateAt: ((BlockPos) -> BlockState)? = null,
    // Search-space bounds (min, max) for the same collision-aware profile -- the schematic's
    // own world-space AABB inflated for region-exit travel. Null alongside planStateAt for
    // the same reasons (see that field's own doc).
    internal val planTravelBounds: Pair<BlockPos, BlockPos>? = null,
)

public data class MoverStatus(
    public val state: MoverState,
    public val abortReason: MoverAbortReason?,
    public val target: Vec3?,
    public val remainingMissing: Int,
)

internal data class MoverPathTelemetry(
    internal val aStarInvocations: Int,
    internal val aStarSuccesses: Int,
    internal val aStarFailVolumeClamped: Int,
    internal val aStarFailBudgetExhausted: Int,
    internal val aStarFailNoPath: Int,
    internal val aStarFailStartOrGoalBlocked: Int,
    // Endpoints resolveEnterableCell rescued from a naive-floor cell the
    // player's AABB merely touched onto an actually-enterable neighbor.
    internal val aStarResolveStartRescues: Int,
    internal val aStarResolveGoalRescues: Int,
    internal val smoothingWaypointsIn: Int,
    internal val smoothingWaypointsOut: Int,
)

// Passes/segments planned and ticks spent following lane segments,
// surfaced on the C3MOV terminal summary line.
internal data class MoverLaneTelemetry(
    internal val passes: Int,
    internal val segments: Int,
    internal val laneTicks: Int,
)
