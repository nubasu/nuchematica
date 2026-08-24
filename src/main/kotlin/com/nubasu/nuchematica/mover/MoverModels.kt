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
    TRAPPED,
}

public data class MoverCommand(
    public val jump: Boolean,
    public val enableFlight: Boolean,
    public val horizontalX: Double,
    public val horizontalZ: Double,
    public val vertical: Int,
    public val stopMovement: Boolean,
    public val resetHorizontalVelocity: Boolean = false,
)

public data class PathProbeResult(
    public val clear: Boolean,
    public val chunkLoaded: Boolean,
)

public enum class MoverDeferralCause {
    MOVER_UNREACHABLE,
    NO_PROGRESS,
}

public data class MoverPlanFrontierTarget(
    public val pos: BlockPos,
    public val expected: BlockState,
)

/** Movement-facing plan state with collision reservations and full remaining counts. */
public data class MoverPlanFrontier(
    public val waitingForReach: List<MoverPlanFrontierTarget>,
    public val columnBlocked: List<BlockPos>,
    public val inFlight: List<BlockPos>,
    /** Empty values make [MoverCore] reserve conservative full cubes. */
    public val inFlightCollisionStates: List<MoverPlanFrontierTarget> = emptyList(),
    public val totalWaitingForReach: Int = waitingForReach.size,
    public val totalColumnBlocked: Int = columnBlocked.size,
    public val totalRemainingActions: Int = totalWaitingForReach + totalColumnBlocked,
)

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

/**
 * Immutable inputs and callbacks for one mover tick.
 *
 * [onWorkPositionAbandoned] reports a failed target but does not itself defer blocks;
 * [onPositionsUncoverable] owns that decision. [canComplete] is checked only after a
 * fresh empty route. In plan mode, [planFrontier] replaces layer work and
 * [planSessionFinal] becomes the completion gate. [planStateAt] supplies collision state,
 * while [planSupportStateAt] independently enables support-aware reach checks.
 */
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
    public val gatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT,
    public val missingWorld: Collection<BlockPos>,
    public val isPlaceable: (BlockPos) -> Boolean,
    public val expectedStateAt: (BlockPos) -> BlockState? = { null },
    public val isPassableCell: (BlockPos) -> Boolean,
    public val reach: Double,
    public val pathProbe: (from: Vec3, to: Vec3) -> PathProbeResult,
    public val onWorkPositionAbandoned: (Set<BlockPos>) -> Unit,
    public val onWorkPositionUnproductive: (
        covered: Set<BlockPos>,
        deferPositions: Set<BlockPos>,
    ) -> Unit,
    public val onPositionsUncoverable: (
        positions: Set<BlockPos>,
        cause: MoverDeferralCause,
    ) -> Unit,
    internal val onPlanPositionUnreachable: (BlockPos) -> Unit = {},
    public val canComplete: () -> Boolean,
    public val onFinalSweepBackoffBypass: () -> Unit = {},
    internal val onHoldHardCap: () -> Unit = {},
    internal val planMode: Boolean = false,
    internal val planFrontier: MoverPlanFrontier? = null,
    internal val planSessionFinal: Boolean = false,
    internal val planStateAt: ((BlockPos) -> BlockState)? = null,
    internal val planSupportStateAt: ((BlockPos) -> BlockState)? = null,
    internal val planBreakTargets: Set<BlockPos> = emptySet(),
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
    internal val aStarResolveStartRescues: Int,
    internal val aStarResolveGoalRescues: Int,
    internal val smoothingWaypointsIn: Int,
    internal val smoothingWaypointsOut: Int,
    internal val routeBuilds: Int,
)

internal data class MoverLaneTelemetry(
    internal val passes: Int,
    internal val segments: Int,
    internal val laneTicks: Int,
)
