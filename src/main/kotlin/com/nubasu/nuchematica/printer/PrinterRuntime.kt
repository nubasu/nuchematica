package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

public class PrinterTickContext(
    public val tick: Long,
    public val sessionKey: PrinterSessionKey,
    public val missingLocal: List<BlockPos>,
    public val expectedStateAt: (BlockPos) -> BlockState?,
    public val localToWorld: (BlockPos) -> BlockPos,
    public val stateAt: (BlockPos) -> BlockState,
    public val placementContext: (BlockState, BlockHitResult) -> BlockPlaceContext,
    public val eyePosition: Vec3,
    public val reach: Double,
    public val itemSupplier: ItemSupplier,
    public val placementGateway: PlacementGateway,
)

public class PrinterRuntime(
    public val skipLog: PrinterSkipLog = PrinterSkipLog(),
    public val attemptTracker: PrinterAttemptTracker = PrinterAttemptTracker(),
    public val rateLimiter: PrinterRateLimiter = PrinterRateLimiter(),
    public val candidateSelector: PrinterCandidateSelector = PrinterCandidateSelector(skipLog = skipLog),
) {
    private var sessionKey: PrinterSessionKey? = null
    private val retryCounts: MutableMap<BlockPos, Int> = mutableMapOf()
    private val retryReadyTick: MutableMap<BlockPos, Long> = mutableMapOf()
    private val blockedPositions: MutableSet<BlockPos> = mutableSetOf()

    public fun tick(context: PrinterTickContext): List<PrinterAttemptResult> {
        if (!synchronizeSession(context.sessionKey)) return emptyList()

        rateLimiter.beginTick(context.tick)
        val completed = attemptTracker.observe(context.tick, context.stateAt)
        completed.forEach { result -> processCompletion(result, context.tick) }

        val selectableMissing = context.missingLocal.filter { localPos ->
            val worldPos = context.localToWorld(localPos)
            worldPos !in blockedPositions &&
                !attemptTracker.isInFlight(worldPos) &&
                context.tick >= (retryReadyTick[worldPos] ?: Long.MIN_VALUE)
        }
        val candidates = candidateSelector.select(
            missingLocal = selectableMissing,
            expectedStateAt = context.expectedStateAt,
            localToWorld = context.localToWorld,
            stateAt = context.stateAt,
            placementContext = context.placementContext,
            eyePosition = context.eyePosition,
            reach = context.reach,
        )

        for (candidate in candidates) {
            if (!rateLimiter.tryAcquire()) break
            if (!context.itemSupplier.ensureHolding(candidate.expectedState)) continue

            val baselineState = context.stateAt(candidate.worldPos)
            if (!context.placementGateway.submit(candidate.hit)) continue

            attemptTracker.attempt(
                worldPos = candidate.worldPos,
                expectedState = candidate.expectedState,
                baselineState = baselineState,
                sentTick = context.tick,
                retryCount = retryCounts[candidate.worldPos] ?: 0,
            )
        }
        return completed
    }

    public fun sessionMatches(key: PrinterSessionKey): Boolean {
        return sessionKey?.matches(key) == true
    }

    public fun cancelAll(): Unit {
        sessionKey = null
        clearSessionWork()
    }

    private fun synchronizeSession(next: PrinterSessionKey): Boolean {
        if (sessionKey?.matches(next) == true) return true
        clearSessionWork()
        sessionKey = next
        return false
    }

    private fun clearSessionWork(): Unit {
        attemptTracker.cancelAll()
        retryCounts.clear()
        retryReadyTick.clear()
        blockedPositions.clear()
        skipLog.clear()
    }

    private fun processCompletion(result: PrinterAttemptResult, tick: Long): Unit {
        val worldPos = result.attempt.worldPos
        when (result.outcome) {
            PrinterAttemptOutcome.ACCEPTED -> {
                retryCounts.remove(worldPos)
                retryReadyTick.remove(worldPos)
                blockedPositions.add(worldPos)
            }
            PrinterAttemptOutcome.WRONG_STATE -> {
                retryCounts.remove(worldPos)
                retryReadyTick.remove(worldPos)
                blockedPositions.add(worldPos)
            }
            PrinterAttemptOutcome.REJECTED,
            PrinterAttemptOutcome.TIMEOUT,
            -> {
                if (result.attempt.retryCount >= MAX_RETRIES) {
                    retryCounts.remove(worldPos)
                    retryReadyTick.remove(worldPos)
                    blockedPositions.add(worldPos)
                    skipLog.record(PrinterSkipReason.RETRY_LIMIT, worldPos)
                } else {
                    retryCounts[worldPos] = result.attempt.retryCount + 1
                    retryReadyTick[worldPos] = tick + 1L
                }
            }
        }
    }

    public companion object {
        public const val MAX_RETRIES: Int = 2
    }
}
