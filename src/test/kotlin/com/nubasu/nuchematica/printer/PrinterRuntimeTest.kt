package com.nubasu.nuchematica.printer

import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterRuntimeTest {
    @Test
    public fun everySessionKeyChangeCancelsInFlightAndSendsNothingThatTick(): Unit {
        val changedKeys: List<(Fixture) -> PrinterSessionKey> = listOf(
            { fixture ->
                PrinterSessionKey(
                    Any(),
                    fixture.contentIdentity,
                    fixture.transformRevision,
                    fixture.queueRevision,
                )
            },
            { fixture ->
                PrinterSessionKey(
                    fixture.levelIdentity,
                    Any(),
                    fixture.transformRevision,
                    fixture.queueRevision,
                )
            },
            { fixture ->
                PrinterSessionKey(
                    fixture.levelIdentity,
                    fixture.contentIdentity,
                    fixture.transformRevision + 1L,
                    fixture.queueRevision,
                )
            },
            { fixture ->
                PrinterSessionKey(
                    fixture.levelIdentity,
                    fixture.contentIdentity,
                    fixture.transformRevision,
                    fixture.queueRevision + 1L,
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
    public fun twoRejectedRetriesThenRecordRetryLimitSkip(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        for (tick in 2L..18L) {
            fixture.runtime.tick(fixture.context(tick))
        }

        assertEquals(3, fixture.gatewayCalls)
        assertEquals(1, fixture.runtime.skipLog.count(PrinterSkipReason.RETRY_LIMIT))
        assertTrue(fixture.runtime.attemptTracker.activeAttempts().isEmpty())
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
    public fun wrongStateDoesNotRetryWithinSession(): Unit {
        val fixture = Fixture()
        fixture.runtime.tick(fixture.context(tick = 0L))
        fixture.runtime.tick(fixture.context(tick = 1L))

        fixture.targetState = Blocks.DIRT.defaultBlockState()
        for (tick in 2L..6L) {
            fixture.runtime.tick(fixture.context(tick))
        }
        fixture.targetState = Blocks.AIR.defaultBlockState()
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

    private class Fixture(
        private val itemAvailable: Boolean = true,
    ) {
        val levelIdentity: Any = Any()
        val contentIdentity: Any = Any()
        val transformRevision: Long = 10L
        val queueRevision: Long = 20L
        val worldPos: BlockPos = BlockPos(0, 1, 0)
        val expectedState: BlockState = Blocks.STONE.defaultBlockState()
        val supportPos: BlockPos = worldPos.below()
        var targetState: BlockState = Blocks.AIR.defaultBlockState()
        var gatewayCalls: Int = 0

        private val skipLog = PrinterSkipLog()
        val runtime: PrinterRuntime = PrinterRuntime(
            skipLog = skipLog,
            candidateSelector = PrinterCandidateSelector(
                skipLog = skipLog,
                predictPlacement = { _, _ -> expectedState },
            ),
        )
        private val baseSessionKey = PrinterSessionKey(
            levelIdentity,
            contentIdentity,
            transformRevision,
            queueRevision,
        )

        fun context(
            tick: Long,
            sessionKey: PrinterSessionKey = baseSessionKey,
        ): PrinterTickContext {
            return PrinterTickContext(
                tick = tick,
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
                itemSupplier = ItemSupplier { itemAvailable },
                placementGateway = PlacementGateway {
                    gatewayCalls++
                    true
                },
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
