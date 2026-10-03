package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos

/** Identifies one orientation-sensitive placement within an adapter session. */
internal data class PlacementRotationKey(internal val actionId: Long, internal val worldPos: BlockPos)

/**
 * Holds client and server rotation long enough for direction-sensitive placement.
 *
 * The original view is restored after the keyed submission finishes or is cancelled.
 */
internal class PlacementRotationSynchronizer(
    private val settleTicks: Long = SERVER_HEAD_ROTATION_SETTLE_TICKS,
) {
    private data class PendingRotation(
        val key: PlacementRotationKey,
        val required: PlacementRotation,
        val original: PlacementRotation,
        val startedTick: Long,
    )

    private var pending: PendingRotation? = null

    init {
        require(settleTicks >= 1L) { "settleTicks must be at least one full tick" }
    }

    internal val isPending: Boolean
        get() = pending != null

    /** Applies [requiredRotation] and returns true after the configured settle time. */
    internal fun prepare(
        key: PlacementRotationKey,
        tick: Long,
        currentRotation: () -> PlacementRotation,
        applyLocalRotation: (PlacementRotation) -> Unit,
        sendServerRotation: (PlacementRotation) -> Unit,
        requiredRotation: PlacementRotation,
    ): Boolean {
        val current = pending
        if (current == null || current.key != key || current.required != requiredRotation) {
            cancel(applyLocalRotation, sendServerRotation)
            pending = PendingRotation(
                key = key,
                required = requiredRotation,
                original = currentRotation(),
                startedTick = tick,
            )
        }
        maintain(applyLocalRotation, sendServerRotation)
        return tick - requireNotNull(pending).startedTick >= settleTicks
    }

    internal fun maintain(
        applyLocalRotation: (PlacementRotation) -> Unit,
        sendServerRotation: (PlacementRotation) -> Unit,
    ): Unit {
        val current = pending ?: return
        applyLocalRotation(current.required)
        sendServerRotation(current.required)
    }

    internal fun finish(
        key: PlacementRotationKey,
        applyLocalRotation: (PlacementRotation) -> Unit,
        sendServerRotation: (PlacementRotation) -> Unit,
    ): Unit {
        if (pending?.key != key) return
        cancel(applyLocalRotation, sendServerRotation)
    }

    internal fun cancel(
        applyLocalRotation: (PlacementRotation) -> Unit,
        sendServerRotation: (PlacementRotation) -> Unit,
    ): Unit {
        val current = pending ?: return
        pending = null
        applyLocalRotation(current.original)
        sendServerRotation(current.original)
    }

    /** Drops pending state without restoration when no player is available. */
    internal fun discard(): Unit {
        pending = null
    }

    internal companion object {
        internal const val SERVER_HEAD_ROTATION_SETTLE_TICKS: Long = 2L
    }
}
