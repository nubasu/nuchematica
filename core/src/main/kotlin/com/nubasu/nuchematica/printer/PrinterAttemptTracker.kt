package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

public data class PrinterAttempt(
    public val worldPos: BlockPos,
    public val expectedState: BlockState,
    public val baselineState: BlockState,
    public val sentTick: Long,
    public val retryCount: Int,
)

public enum class PrinterAttemptOutcome {
    ACCEPTED,
    REJECTED,
    WRONG_STATE,
    TIMEOUT,
}

public data class PrinterAttemptResult(
    public val attempt: PrinterAttempt,
    public val outcome: PrinterAttemptOutcome,
    public val observedState: BlockState,
    public val completedTick: Long,
)

public class PrinterAttemptTracker {
    private val active: LinkedHashMap<BlockPos, ActiveAttempt> = LinkedHashMap()

    public fun attempt(
        worldPos: BlockPos,
        expectedState: BlockState,
        baselineState: BlockState,
        sentTick: Long,
        retryCount: Int,
    ): Boolean {
        require(retryCount >= 0)
        val immutablePos = worldPos.immutable()
        if (immutablePos in active) return false
        active[immutablePos] = ActiveAttempt(
            PrinterAttempt(
                worldPos = immutablePos,
                expectedState = expectedState,
                baselineState = baselineState,
                sentTick = sentTick,
                retryCount = retryCount,
            ),
        )
        return true
    }

    public fun observe(
        tick: Long,
        stateAt: (BlockPos) -> BlockState,
        matches: (BlockState, BlockState) -> Boolean = BlockStateEquivalence::matches,
    ): List<PrinterAttemptResult> {
        val completed = mutableListOf<PrinterAttemptResult>()
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next().value
            val attempt = tracked.attempt
            val elapsed = tick - attempt.sentTick
            if (elapsed <= 0L || tracked.lastObservedTick == tick) continue

            val observed = stateAt(attempt.worldPos)
            tracked.record(tick, observed)
            val stableOutcome = if (tracked.stableTicks >= SETTLE_TICKS) {
                classifyStable(attempt, observed, elapsed, matches)
            } else {
                null
            }
            val outcome = stableOutcome ?: if (elapsed >= DEADLINE_TICKS) {
                PrinterAttemptOutcome.TIMEOUT
            } else {
                null
            }
            if (outcome != null) {
                completed.add(
                    PrinterAttemptResult(
                        attempt = attempt,
                        outcome = outcome,
                        observedState = observed,
                        completedTick = tick,
                    ),
                )
                iterator.remove()
            }
        }
        return completed
    }

    public fun isInFlight(worldPos: BlockPos): Boolean {
        return worldPos in active
    }

    public fun activeAttempts(): List<PrinterAttempt> {
        return active.values.map(ActiveAttempt::attempt)
    }

    public fun inFlightCount(): Int {
        return active.size
    }

    public fun cancelAll(): Unit {
        active.clear()
    }

    private fun classifyStable(
        attempt: PrinterAttempt,
        observed: BlockState,
        elapsed: Long,
        matches: (BlockState, BlockState) -> Boolean,
    ): PrinterAttemptOutcome? {
        return when {
            matches(attempt.expectedState, observed) -> PrinterAttemptOutcome.ACCEPTED
            observed == attempt.baselineState && elapsed < DEADLINE_TICKS -> null
            observed == attempt.baselineState -> PrinterAttemptOutcome.REJECTED
            else -> PrinterAttemptOutcome.WRONG_STATE
        }
    }

    private class ActiveAttempt(
        val attempt: PrinterAttempt,
    ) {
        var lastState: BlockState? = null
        var stableTicks: Int = 0
        var lastObservedTick: Long = Long.MIN_VALUE

        fun record(tick: Long, state: BlockState): Unit {
            if (lastState == state) {
                stableTicks++
            } else {
                lastState = state
                stableTicks = 1
            }
            lastObservedTick = tick
        }
    }

    public companion object {
        public const val DEADLINE_TICKS: Long = 20L
        public const val SETTLE_TICKS: Int = 5
    }
}
