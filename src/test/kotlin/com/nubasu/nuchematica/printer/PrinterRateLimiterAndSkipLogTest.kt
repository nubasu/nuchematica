package com.nubasu.nuchematica.printer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class PrinterRateLimiterAndSkipLogTest {
    @Test
    public fun oneAttemptPerTickResetsOnlyWhenTickChanges(): Unit {
        val limiter = PrinterRateLimiter()
        limiter.updateIntervalTicks(1)

        limiter.beginTick(10L)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        limiter.beginTick(10L)
        assertFalse(limiter.tryAcquire())
        limiter.beginTick(11L)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    public fun intervalFiveAllowsAgainOnlyOnFifthTick(): Unit {
        val limiter = PrinterRateLimiter()
        limiter.updateIntervalTicks(0)
        assertEquals(1, limiter.intervalTicks)
        limiter.updateIntervalTicks(99)
        assertEquals(PrinterRateLimiter.MAX_INTERVAL_TICKS, limiter.intervalTicks)
        limiter.updateIntervalTicks(5)

        limiter.beginTick(10L)
        assertTrue(limiter.tryAcquire())
        for (tick in 11L..14L) {
            limiter.beginTick(tick)
            assertFalse(limiter.tryAcquire())
        }
        limiter.beginTick(15L)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    public fun intervalAndAttemptsPerTickAllowFullBatchTogether(): Unit {
        val limiter = PrinterRateLimiter(3)
        limiter.updateIntervalTicks(5)

        limiter.beginTick(20L)
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        for (tick in 21L..24L) {
            limiter.beginTick(tick)
            assertFalse(limiter.tryAcquire())
        }
        limiter.beginTick(25L)
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    public fun eightAttemptsPerTickHonorsMaximumBoundary(): Unit {
        val limiter = PrinterRateLimiter(PrinterRateLimiter.MAX_ATTEMPTS_PER_TICK)

        limiter.beginTick(1L)

        repeat(PrinterRateLimiter.MAX_ATTEMPTS_PER_TICK) {
            assertTrue(limiter.tryAcquire())
        }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    public fun updatedAttemptsPerTickClampsAndAppliesImmediately(): Unit {
        val limiter = PrinterRateLimiter()
        limiter.updateAttemptsPerTick(0)
        assertEquals(1, limiter.attemptsPerTick)
        limiter.updateAttemptsPerTick(99)
        assertEquals(PrinterRateLimiter.MAX_ATTEMPTS_PER_TICK, limiter.attemptsPerTick)

        limiter.updateAttemptsPerTick(2)
        limiter.beginTick(1L)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    public fun facePlacementSetsCameraTargetBeforeFirstSubmitOnly(): Unit {
        val events = mutableListOf<String>()
        val targets = mutableListOf<Pair<Float, Float>>()
        val delegate = PlacementGateway { _, _ ->
            events += "submit"
            true
        }
        val gateway = FacePlacementGateway(
            delegate = delegate,
            facePlacement = true,
            eyePosition = Vec3.ZERO,
            setCameraTarget = { yaw, pitch ->
                events += "target"
                targets += yaw to pitch
            },
        )

        assertTrue(gateway.submit(hitAt(Vec3(0.0, 0.0, 1.0)), null))
        assertTrue(gateway.submit(hitAt(Vec3(1.0, 1.0, 0.0)), null))

        assertEquals(listOf("target", "submit", "submit"), events)
        assertEquals(1, targets.size)
        assertEquals(0.0f, targets.single().first, 0.0000001f)
        assertEquals(0.0f, targets.single().second, 0.0000001f)
    }

    @Test
    public fun disabledFacePlacementNeverSetsCameraTarget(): Unit {
        var targetCalls = 0
        var submitCalls = 0
        val gateway = FacePlacementGateway(
            delegate = PlacementGateway { _, _ ->
                submitCalls++
                true
            },
            facePlacement = false,
            eyePosition = Vec3.ZERO,
            setCameraTarget = { _, _ -> targetCalls++ },
        )

        gateway.submit(hitAt(Vec3(0.0, 0.0, 1.0)), null)
        gateway.submit(hitAt(Vec3(1.0, 0.0, 0.0)), null)

        assertEquals(2, submitCalls)
        assertEquals(0, targetCalls)
    }

    @Test
    public fun requiredRotationRestoresServerRotationAfterUseWithEitherFaceSetting(): Unit {
        val required = PlacementRotation(yaw = 90f, pitch = 25f)
        val original = PlacementRotation(yaw = 10f, pitch = -5f)

        listOf(false, true).forEach { facePlacement ->
            val events = mutableListOf<String>()
            val gateway = FacePlacementGateway(
                delegate = PlacementGateway { _, rotation ->
                    submitOrientedPlacement(
                        requiredRotation = requireNotNull(rotation),
                        originalRotation = original,
                        setLocalRotation = { current ->
                            events += if (current == required) "local-required" else "local-original"
                        },
                        sendServerRotation = { current ->
                            events += if (current == required) "send-required" else "send-original"
                        },
                        useItemOn = {
                            events += "use"
                            true
                        },
                    )
                },
                facePlacement = facePlacement,
                eyePosition = Vec3.ZERO,
                setCameraTarget = { _, _ -> Unit },
            )

            assertTrue(gateway.submit(hitAt(Vec3(0.0, 0.0, 1.0)), required))
            assertEquals(
                listOf("local-required", "send-required", "use", "local-original", "send-original"),
                events,
            )
        }
    }

    @Test
    public fun placementRotationSynchronizerWaitsTwoTicksAndRestoresOriginalRotation(): Unit {
        val synchronizer = PlacementRotationSynchronizer(settleTicks = 2L)
        val key = PlacementRotationKey(actionId = 7L, worldPos = BlockPos(1, 2, 3))
        val original = PlacementRotation(yaw = 10f, pitch = -5f)
        val required = PlacementRotation(yaw = 90f, pitch = 25f)
        var local = original
        val sent = mutableListOf<PlacementRotation>()
        val apply: (PlacementRotation) -> Unit = { rotation -> local = rotation }
        val send: (PlacementRotation) -> Unit = { rotation -> sent += rotation }

        assertFalse(
            synchronizer.prepare(key, 20L, { local }, apply, send, required),
            "the first packet cannot prove the server has ticked its head rotation yet",
        )
        assertEquals(required, local)
        synchronizer.maintain(apply, send)
        assertFalse(synchronizer.prepare(key, 21L, { local }, apply, send, required))
        assertTrue(synchronizer.prepare(key, 22L, { local }, apply, send, required))
        assertTrue(synchronizer.isPending)

        synchronizer.finish(key, apply, send)

        assertFalse(synchronizer.isPending)
        assertEquals(original, local)
        assertEquals(original, sent.last())
        assertTrue(sent.dropLast(1).all { rotation -> rotation == required })
    }

    @Test
    public fun cameraEaseStepMovesMonotonicallyTowardTarget(): Unit {
        var current = 0f
        val steps = mutableListOf<Float>()
        repeat(7) {
            current = CameraEase.step(current, 100f, 15f)
            steps += current
        }

        assertEquals(listOf(15f, 30f, 45f, 60f, 75f, 90f, 100f), steps)
    }

    @Test
    public fun cameraEaseStepTakesShortestDirectionAcrossYawBoundary(): Unit {
        val next = CameraEase.step(170f, -170f, 15f)

        assertEquals(185f, next, 0.0000001f)
    }

    @Test
    public fun cameraEaseStepStaysAtTargetOnceReached(): Unit {
        val next = CameraEase.step(100f, 100f, 15f)

        assertEquals(100f, next, 0.0000001f)
    }

    @Test
    public fun placementRotationMatchesYawAndPitchFormula(): Unit {
        val rotation = placementRotation(
            eyePosition = Vec3(1.0, 2.0, 3.0),
            hitLocation = Vec3(0.0, 3.0, 4.0),
        )

        assertEquals(45.0, rotation.yaw, 0.0000001)
        assertEquals(-35.264389682754654, rotation.pitch, 0.0000001)
    }

    @Test
    public fun skipLogAggregatesEachReasonAndReturnsSnapshot(): Unit {
        val log = PrinterSkipLog()
        log.record(PrinterSkipReason.NO_SUPPORT_FACE, BlockPos(0, 0, 0))
        log.record(PrinterSkipReason.NO_SUPPORT_FACE, BlockPos(1, 0, 0))
        log.record(PrinterSkipReason.RETRY_LIMIT, BlockPos(2, 0, 0))

        val snapshot = log.snapshot()
        log.clear()

        assertEquals(2, snapshot[PrinterSkipReason.NO_SUPPORT_FACE])
        assertEquals(1, snapshot[PrinterSkipReason.RETRY_LIMIT])
        assertEquals(0, snapshot[PrinterSkipReason.OUT_OF_REACH])
        assertEquals(0, log.count(PrinterSkipReason.NO_SUPPORT_FACE))
    }

    @Test
    public fun recordingSamePositionTwiceStaysIdempotentPerReason(): Unit {
        val log = PrinterSkipLog()
        val pos = BlockPos(3, 4, 5)

        log.record(PrinterSkipReason.NO_SUPPORT_FACE, pos)
        log.record(PrinterSkipReason.NO_SUPPORT_FACE, pos)
        assertEquals(1, log.count(PrinterSkipReason.NO_SUPPORT_FACE))

        log.record(PrinterSkipReason.NO_SUPPORT_FACE, BlockPos(6, 7, 8))
        assertEquals(2, log.count(PrinterSkipReason.NO_SUPPORT_FACE))
    }

    private fun hitAt(location: Vec3): BlockHitResult {
        return BlockHitResult(location, Direction.UP, BlockPos.ZERO, false)
    }
}
