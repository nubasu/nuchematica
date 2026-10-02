package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import java.util.Collections
import java.util.EnumMap

public data class MissingSnapshot(
    public val revision: Long,
    public val missingLocal: List<BlockPos>,
)

public class PrinterSessionKey(
    public val level: Any,
    public val content: Any,
    public val transformRevision: Long,
) {
    public fun matches(other: PrinterSessionKey): Boolean {
        return level === other.level &&
            content === other.content &&
            transformRevision == other.transformRevision
    }
}

public fun interface PlacementGateway {
    public fun submit(hit: BlockHitResult, requiredRotation: PlacementRotation?): Boolean
}

public fun interface ItemSupplier {
    public fun ensureHolding(state: BlockState): Boolean
}

public enum class PrinterSkipReason {
    OUT_OF_REACH,
    NO_SUPPORT_FACE,
    PREDICTION_MISMATCH,
    CATEGORY_EXCLUDED,
    RETRY_LIMIT,
}

public class PrinterSkipLog {
    private val positions: EnumMap<PrinterSkipReason, MutableSet<BlockPos>> =
        EnumMap(PrinterSkipReason::class.java)

    public fun record(reason: PrinterSkipReason, pos: BlockPos): Unit {
        positions.getOrPut(reason) { mutableSetOf() }.add(pos.immutable())
    }

    public fun count(reason: PrinterSkipReason): Int {
        return positions[reason]?.size ?: 0
    }

    public fun snapshot(): Map<PrinterSkipReason, Int> {
        val copy = EnumMap<PrinterSkipReason, Int>(PrinterSkipReason::class.java)
        PrinterSkipReason.values().forEach { reason ->
            copy[reason] = positions[reason]?.size ?: 0
        }
        return Collections.unmodifiableMap(copy)
    }

    public fun clear(): Unit {
        positions.values.forEach { it.clear() }
    }
}

public class PrinterRateLimiter(
    attemptsPerTick: Int = DEFAULT_ATTEMPTS_PER_TICK,
) {
    public var attemptsPerTick: Int = attemptsPerTick
        private set
    public var intervalTicks: Int = DEFAULT_INTERVAL_TICKS
        private set

    private var currentTick: Long = Long.MIN_VALUE
    private var lastAllowedTick: Long? = null
    private var acquired: Int = 0

    init {
        require(attemptsPerTick in 1..MAX_ATTEMPTS_PER_TICK)
    }

    public fun beginTick(tick: Long): Unit {
        if (currentTick == tick) return
        currentTick = tick
        acquired = 0
    }

    public fun updateAttemptsPerTick(value: Int): Unit {
        attemptsPerTick = value.coerceIn(1, MAX_ATTEMPTS_PER_TICK)
    }

    public fun updateIntervalTicks(value: Int): Unit {
        intervalTicks = value.coerceIn(1, MAX_INTERVAL_TICKS)
    }

    public fun tryAcquire(): Boolean {
        if (acquired >= attemptsPerTick) return false
        val previousAllowedTick = lastAllowedTick
        if (
            acquired == 0 &&
            previousAllowedTick != null &&
            currentTick >= previousAllowedTick &&
            currentTick - previousAllowedTick < intervalTicks
        ) {
            return false
        }
        if (acquired == 0) lastAllowedTick = currentTick
        acquired++
        return true
    }

    public companion object {
        public const val DEFAULT_ATTEMPTS_PER_TICK: Int = 1
        public const val MAX_ATTEMPTS_PER_TICK: Int = 8
        public const val DEFAULT_INTERVAL_TICKS: Int = 1
        public const val MAX_INTERVAL_TICKS: Int = 40
    }
}
