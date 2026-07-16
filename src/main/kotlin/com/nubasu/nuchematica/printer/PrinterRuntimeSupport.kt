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
    public val queueRevision: Long,
) {
    public fun matches(other: PrinterSessionKey): Boolean {
        return level === other.level &&
            content === other.content &&
            transformRevision == other.transformRevision &&
            queueRevision == other.queueRevision
    }
}

public fun interface PlacementGateway {
    public fun submit(hit: BlockHitResult): Boolean
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

    // Set-of-positions instead of an event counter: a single unsupported block
    // ticks skip logic every game tick, and an event counter would blow up to
    // hundreds of "skips" per second for one block. Tracking distinct positions
    // keeps this readable as "N blocks are skipped for reason X".
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

    private var currentTick: Long = Long.MIN_VALUE
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

    public fun tryAcquire(): Boolean {
        if (acquired >= attemptsPerTick) return false
        acquired++
        return true
    }

    public companion object {
        public const val DEFAULT_ATTEMPTS_PER_TICK: Int = 1
        public const val MAX_ATTEMPTS_PER_TICK: Int = 8
    }
}
