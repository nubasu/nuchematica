package com.nubasu.nuchematica.printer

import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterRuntimeTest {
    @Test
    public fun feedSnapshotMatchesSingleSelectorPassAndCompletions(): Unit {
        val positions = listOf(
            BlockPos(0, 1, 0),
            BlockPos(1, 1, 0),
            BlockPos(2, 1, 0),
        )
        val supports = positions.mapTo(HashSet()) { position -> position.below() }
        val placed = mutableSetOf<BlockPos>()
        val sessionKey = PrinterSessionKey(Any(), Any(), 1L)
        val runtime = PrinterRuntime(
            rateLimiter = PrinterRateLimiter(attemptsPerTick = 2),
            candidateSelector = PrinterCandidateSelector(
                predictPlacement = { _, _ -> Blocks.STONE.defaultBlockState() },
            ),
        )
        fun context(
            tick: Long,
            queueRevision: Long,
            missingLocal: List<BlockPos>,
        ): PrinterTickContext {
            return PrinterTickContext(
                tick = tick,
                queueRevision = queueRevision,
                sessionKey = sessionKey,
                missingLocal = missingLocal,
                expectedStateAt = { Blocks.STONE.defaultBlockState() },
                localToWorld = { it },
                stateAt = { position ->
                    when {
                        position in placed -> Blocks.STONE.defaultBlockState()
                        position in supports ->
                            Blocks.STONE.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                },
                placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
                eyePosition = Vec3(0.5, 2.0, 0.5),
                reach = 10.0,
                itemSupplier = ItemSupplier { true },
                placementGateway = PlacementGateway { _, _ -> true },
            )
        }

        runtime.tick(context(tick = 0L, queueRevision = 7L, missingLocal = positions))
        assertNull(runtime.feedSnapshot())

        runtime.tick(context(tick = 1L, queueRevision = 7L, missingLocal = positions))

        assertEquals(
            FeedSnapshot(
                tick = 1L,
                queueRevision = 7L,
                candidateCount = 3,
                submittedCount = 2,
                rateLimitedRemainder = 1,
                inFlightCount = 2,
                acceptedThisTick = 0,
            ),
            runtime.feedSnapshot(),
        )

        placed.addAll(runtime.attemptTracker.activeAttempts().map { attempt -> attempt.worldPos })
        for (tick in 2L..6L) {
            runtime.tick(context(tick, queueRevision = 8L, missingLocal = emptyList()))
        }
        assertEquals(
            FeedSnapshot(
                tick = 6L,
                queueRevision = 8L,
                candidateCount = 0,
                submittedCount = 0,
                rateLimitedRemainder = 0,
                inFlightCount = 0,
                acceptedThisTick = 2,
                ticksSinceLastAccept = 0L,
            ),
            runtime.feedSnapshot(),
        )
    }

    @Test
    public fun ticksSinceLastAcceptTracksElapsedTicksAndResetsOnSessionChange(): Unit {
        val positions = listOf(BlockPos(0, 1, 0))
        val supports = positions.mapTo(HashSet()) { position -> position.below() }
        val placed = mutableSetOf<BlockPos>()
        val sessionKeyA = PrinterSessionKey(Any(), Any(), 1L)
        val runtime = PrinterRuntime(
            candidateSelector = PrinterCandidateSelector(
                predictPlacement = { _, _ -> Blocks.STONE.defaultBlockState() },
            ),
        )
        fun context(
            sessionKey: PrinterSessionKey,
            tick: Long,
            missingLocal: List<BlockPos>,
        ): PrinterTickContext {
            return PrinterTickContext(
                tick = tick,
                queueRevision = 1L,
                sessionKey = sessionKey,
                missingLocal = missingLocal,
                expectedStateAt = { Blocks.STONE.defaultBlockState() },
                localToWorld = { it },
                stateAt = { position ->
                    when {
                        position in placed -> Blocks.STONE.defaultBlockState()
                        position in supports -> Blocks.STONE.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                },
                placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
                eyePosition = Vec3(0.5, 2.0, 0.5),
                reach = 10.0,
                itemSupplier = ItemSupplier { true },
                placementGateway = PlacementGateway { _, _ -> true },
            )
        }

        runtime.tick(context(sessionKeyA, tick = 0L, missingLocal = positions))
        assertNull(runtime.feedSnapshot())

        runtime.tick(context(sessionKeyA, tick = 1L, missingLocal = positions))
        assertNull(runtime.feedSnapshot()?.ticksSinceLastAccept)

        placed.addAll(runtime.attemptTracker.activeAttempts().map { attempt -> attempt.worldPos })
        for (tick in 2L..6L) {
            runtime.tick(context(sessionKeyA, tick = tick, missingLocal = emptyList()))
        }
        assertEquals(0L, runtime.feedSnapshot()?.ticksSinceLastAccept)

        runtime.tick(context(sessionKeyA, tick = 9L, missingLocal = emptyList()))
        assertEquals(3L, runtime.feedSnapshot()?.ticksSinceLastAccept)

        val sessionKeyB = PrinterSessionKey(Any(), Any(), 2L)
        runtime.tick(context(sessionKeyB, tick = 10L, missingLocal = emptyList()))
        assertNull(runtime.feedSnapshot())

        runtime.tick(context(sessionKeyB, tick = 11L, missingLocal = emptyList()))
        assertNull(runtime.feedSnapshot()?.ticksSinceLastAccept)
    }

    @Test
    public fun levelContentAndTransformSessionChangesCancelInFlightAndSendNothingThatTick(): Unit {
        val changedKeys: List<(Fixture) -> PrinterSessionKey> = listOf(
            { fixture ->
                PrinterSessionKey(
                    Any(),
                    fixture.contentIdentity,
                    fixture.transformRevision,
                )
            },
            { fixture ->
                PrinterSessionKey(
                    fixture.levelIdentity,
                    Any(),
                    fixture.transformRevision,
                )
            },
            { fixture ->
                PrinterSessionKey(
                    fixture.levelIdentity,
                    fixture.contentIdentity,
                    fixture.transformRevision + 1L,
                )
            },
        )

        changedKeys.forEach { changedKey ->
            val fixture = Fixture()
            fixture.runtime.tick(fixture.context(tick = 0L))
            fixture.runtime.tick(fixture.context(tick = 1L))
            assertEquals(1, fixture.gatewayCalls)
            assertTrue(fixture.runtime.attemptTracker.isInFlight(fixture.worldPos))

            val callsBeforeCancellation = fixture.gatewayCalls
            fixture.runtime.tick(fixture.context(tick = 2L, sessionKey = changedKey(fixture)))

            assertEquals(callsBeforeCancellation, fixture.gatewayCalls)
            assertFalse(fixture.runtime.attemptTracker.isInFlight(fixture.worldPos))
        }
    }

    @Test
    public fun queueRevisionChangeDoesNotCancelInFlightAttempt(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))
        assertEquals(1, fixture.gatewayCalls)
        assertTrue(fixture.runtime.attemptTracker.isInFlight(fixture.worldPos))

        fixture.queueRevision++
        fixture.runtime.tick(fixture.context(tick = 2L))

        assertEquals(1, fixture.gatewayCalls)
        assertTrue(fixture.runtime.attemptTracker.isInFlight(fixture.worldPos))
    }

    @Test
    public fun predictedPlacementSurvivesQueueRevisionTicksAndCompletesAccepted(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))
        fixture.targetState = fixture.expectedState

        val completed = mutableListOf<PrinterAttemptResult>()
        for (tick in 2L..6L) {
            fixture.queueRevision++
            completed += fixture.runtime.tick(fixture.context(tick))
        }

        assertEquals(listOf(PrinterAttemptOutcome.ACCEPTED), completed.map { it.outcome })
        assertEquals(1, fixture.gatewayCalls)
        assertTrue(fixture.runtime.attemptTracker.activeAttempts().isEmpty())
    }

    @Test
    public fun rejectedRetriesAreFilteredOnlyWhileInjectedPredicateBlocks(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        val completed = mutableListOf<PrinterAttemptResult>()
        val exhaustionTick = 1L +
            (PrinterRuntime.MAX_RETRIES + 1L) * PrinterAttemptTracker.DEADLINE_TICKS +
            PrinterRuntime.MAX_RETRIES
        for (tick in 2L..exhaustionTick) {
            completed += fixture.runtime.tick(fixture.context(tick))
        }

        assertEquals(
            listOf(
                PrinterAttemptOutcome.REJECTED,
                PrinterAttemptOutcome.REJECTED,
                PrinterAttemptOutcome.REJECTED,
            ),
            completed.map { it.outcome },
        )
        assertEquals(3, fixture.gatewayCalls)
        assertEquals(1, fixture.runtime.skipLog.count(PrinterSkipReason.RETRY_LIMIT))
        assertTrue(fixture.runtime.attemptTracker.activeAttempts().isEmpty())

        fixture.runtime.tick(fixture.context(tick = exhaustionTick + 1L))
        assertEquals(3, fixture.gatewayCalls)

        fixture.blocked = false
        fixture.runtime.tick(fixture.context(tick = exhaustionTick + 2L))
        assertEquals(4, fixture.gatewayCalls)
        assertEquals(0, fixture.runtime.attemptTracker.activeAttempts().single().retryCount)
    }

    @Test
    public fun retryLimitBlockingNotifiesOnRetryLimitBlocked(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        val exhaustionTick = 1L +
            (PrinterRuntime.MAX_RETRIES + 1L) * PrinterAttemptTracker.DEADLINE_TICKS +
            PrinterRuntime.MAX_RETRIES
        for (tick in 2L..exhaustionTick) {
            fixture.runtime.tick(fixture.context(tick))
        }

        assertEquals(1, fixture.runtime.skipLog.count(PrinterSkipReason.RETRY_LIMIT))
        assertEquals(listOf(fixture.worldPos), fixture.retryLimitBlocked)
    }

    @Test
    public fun timeoutBecomesEligibleForRetryOnFollowingTick(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        for (tick in 2L..21L) {
            fixture.targetState = if (tick % 2L == 0L) {
                Blocks.DIRT.defaultBlockState()
            } else {
                Blocks.COBBLESTONE.defaultBlockState()
            }
            fixture.runtime.tick(fixture.context(tick))
        }
        assertEquals(1, fixture.gatewayCalls)

        fixture.targetState = Blocks.AIR.defaultBlockState()
        fixture.runtime.tick(fixture.context(tick = 22L))

        assertEquals(2, fixture.gatewayCalls)
        assertEquals(1, fixture.runtime.attemptTracker.activeAttempts().single().retryCount)
    }

    @Test
    public fun wrongStateDoesNotRetryWhileTargetRemainsOccupied(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        fixture.targetState = Blocks.DIRT.defaultBlockState()
        for (tick in 2L..6L) {
            fixture.runtime.tick(fixture.context(tick))
        }
        fixture.runtime.tick(fixture.context(tick = 7L))

        assertEquals(1, fixture.gatewayCalls)
        assertTrue(fixture.runtime.attemptTracker.activeAttempts().isEmpty())
    }

    @Test
    public fun unavailableItemSupplierNeverCallsGateway(): Unit {
        val fixture = Fixture(itemAvailable = false)

        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        assertEquals(0, fixture.gatewayCalls)
        assertTrue(fixture.runtime.attemptTracker.activeAttempts().isEmpty())
    }

    @Test
    public fun requiredRotationFromCandidatePropagatesToGateway(): Unit {
        val rotation = PlacementRotation(yaw = 90f, pitch = 0f)
        val fixture = Fixture(orientedRotation = rotation)

        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        assertEquals(1, fixture.gatewayCalls)
        assertEquals(listOf(rotation), fixture.submittedRotations)
    }

    @Test
    public fun itemSupplierReceivesSubstituteStateWhileAttemptTracksSchematicState(): Unit {
        val doubleOakSlab = Blocks.OAK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
        val oakPlanks = Blocks.OAK_PLANKS.defaultBlockState()
        val fixture = Fixture(
            expectedState = doubleOakSlab,
            predictedState = oakPlanks,
        )

        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        assertEquals(listOf(oakPlanks), fixture.suppliedStates)
        assertEquals(
            doubleOakSlab,
            fixture.runtime.attemptTracker.activeAttempts().single().expectedState,
        )
    }

    private class Fixture(
        private val itemAvailable: Boolean = true,
        private val orientedRotation: PlacementRotation? = null,
        val expectedState: BlockState = Blocks.STONE.defaultBlockState(),
        private val predictedState: BlockState = expectedState,
    ) {
        val levelIdentity: Any = Any()
        val contentIdentity: Any = Any()
        val transformRevision: Long = 10L
        var queueRevision: Long = 20L
        val worldPos: BlockPos = BlockPos(0, 1, 0)
        val supportPos: BlockPos = worldPos.below()
        var targetState: BlockState = Blocks.AIR.defaultBlockState()
        var gatewayCalls: Int = 0
        var blocked: Boolean = false
        val submittedRotations: MutableList<PlacementRotation?> = mutableListOf()
        val suppliedStates: MutableList<BlockState> = mutableListOf()
        val retryLimitBlocked: MutableList<BlockPos> = mutableListOf()

        private val skipLog = PrinterSkipLog()
        val runtime: PrinterRuntime = PrinterRuntime(
            skipLog = skipLog,
            isBlocked = { blockedPos -> blocked && blockedPos == worldPos },
            onRetryLimitBlocked = { blockedPos ->
                blocked = true
                retryLimitBlocked += blockedPos
            },
            candidateSelector = PrinterCandidateSelector(
                skipLog = skipLog,
                predictPlacement = { _, _ ->
                    if (orientedRotation != null) Blocks.DIRT.defaultBlockState() else predictedState
                },
                orientedPrediction = { _, _, _ -> orientedRotation },
            ),
        )
        fun context(
            tick: Long,
            sessionKey: PrinterSessionKey = currentSessionKey(),
        ): PrinterTickContext {
            return PrinterTickContext(
                tick = tick,
                queueRevision = queueRevision,
                sessionKey = sessionKey,
                missingLocal = listOf(worldPos),
                expectedStateAt = { expectedState },
                localToWorld = { it },
                stateAt = { pos ->
                    when (pos) {
                        worldPos -> targetState
                        supportPos -> Blocks.STONE.defaultBlockState()
                        else -> Blocks.AIR.defaultBlockState()
                    }
                },
                placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
                eyePosition = Vec3(0.5, 2.0, 0.5),
                reach = 4.5,
                itemSupplier = ItemSupplier { suppliedState ->
                    suppliedStates += suppliedState
                    itemAvailable
                },
                placementGateway = PlacementGateway { _, requiredRotation ->
                    gatewayCalls++
                    submittedRotations += requiredRotation
                    true
                },
            )
        }

        private fun currentSessionKey(): PrinterSessionKey {
            return PrinterSessionKey(
                levelIdentity,
                contentIdentity,
                transformRevision,
            )
        }
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
