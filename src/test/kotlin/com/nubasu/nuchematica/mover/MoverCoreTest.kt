package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.FeedSnapshot
import com.nubasu.nuchematica.printer.PlanFrontierTarget
import com.nubasu.nuchematica.printer.PrinterAttemptTracker
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.PrinterRateLimiter
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.floor

public class MoverCoreTest {
    @Test
    public fun fullStateMachineReachesCompleteAndRemainsStopped(): Unit {
        val fixture = Fixture()
        val core = fixture.core

        assertEquals(MoverState.IDLE, core.status().state)
        assertEquals(MoverState.TAKEOFF, core.toggleRequested(true, true, true))

        val jump = core.tick(fixture.context(onGround = true))
        assertTrue(jump.jump)
        assertFalse(jump.enableFlight)

        val enableFlight = core.tick(fixture.context(onGround = false))
        assertFalse(enableFlight.jump)
        assertTrue(enableFlight.enableFlight)

        core.tick(fixture.context(onGround = false, flying = true))
        assertEquals(MoverState.CRUISE, core.status().state)
        val target = requireNotNull(core.status().target)

        val arrival = target.add(0.3, 0.2, 0.3)
        val hold = core.tick(fixture.context(playerPos = arrival, onGround = false, flying = true))
        assertEquals(MoverState.HOLD, core.status().state)
        assertStopped(hold)

        core.tick(
            fixture.context(
                playerPos = arrival,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                missingWorld = emptyList(),
                feedSnapshot = emptyFeed(queueRevision = 1L),
            ),
        )
        assertEquals(MoverState.HOLD, core.status().state)

        core.tick(
            fixture.context(
                playerPos = arrival,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                missingWorld = emptyList(),
                feedSnapshot = emptyFeed(queueRevision = 1L),
            ),
        )
        assertEquals(MoverState.HOLD, core.status().state)

        core.tick(
            fixture.context(
                playerPos = arrival,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                missingWorld = emptyList(),
                feedSnapshot = emptyFeed(queueRevision = 1L),
            ),
        )
        assertEquals(MoverState.COMPLETE, core.status().state)
        assertEquals(0, core.status().remainingMissing)

        repeat(3) {
            assertStopped(core.tick(fixture.context(manualInput = true)))
            assertEquals(MoverState.COMPLETE, core.status().state)
        }
    }

    @Test
    public fun manualInputAborts(): Unit {
        val fixture = activeFixture()

        assertAbort(
            fixture,
            fixture.context(manualInput = true),
            MoverAbortReason.MANUAL_INPUT,
        )
    }

    @Test
    public fun openGuiAborts(): Unit {
        val fixture = activeFixture()

        assertAbort(
            fixture,
            fixture.context(guiOpen = true),
            MoverAbortReason.GUI_OPEN,
        )
    }

    @Test
    public fun damageAborts(): Unit {
        val fixture = activeFixture()

        assertAbort(
            fixture,
            fixture.context(hurt = true),
            MoverAbortReason.DAMAGED,
        )
    }

    @Test
    public fun serverCorrectionAborts(): Unit {
        val fixture = activeFixture()

        assertAbort(
            fixture,
            fixture.context(correctionReceived = true),
            MoverAbortReason.SERVER_CORRECTION,
        )
    }

    @Test
    public fun planModeIsolatedCorrectionResyncsWithoutAborting(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )

        assertNull(fixture.core.status().abortReason)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeMoreThanThreeCorrectionsWithinRollingWindowAbortsAsGenuineDesync(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)

        repeat(3) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    correctionReceived = true,
                ),
            )
            assertNull(fixture.core.status().abortReason)
        }

        val fourthCommand = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )

        assertAbortStatus(fixture.core, MoverAbortReason.SERVER_CORRECTION)
        assertStopped(fourthCommand)
    }

    @Test
    public fun planModeCorrectionsOutsideRollingWindowDoNotAccumulateTowardAbort(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)

        repeat(3) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    correctionReceived = true,
                ),
            )
        }
        assertNull(fixture.core.status().abortReason)

        repeat(101) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        assertNull(fixture.core.status().abortReason)

        repeat(3) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    correctionReceived = true,
                ),
            )
        }
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeSecondCorrectionOnSameWorkPositionSwitchesToAlternativeStand(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )
        assertNull(fixture.core.status().abortReason)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        repeat(40) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }

        val secondCorrectionCommand = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )

        assertNull(fixture.core.status().abortReason)
        assertStopped(secondCorrectionCommand)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
            ),
        )

        assertNull(fixture.core.status().abortReason)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planCorrectionEscapeOnLastStandDefersRebuildInsteadOfCollapsingTwoPhaseCompletion(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val emptyFrontier = MoverPlanFrontier(emptyList(), emptyList(), emptyList())
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        repeat(40) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }

        val secondCorrectionCommand = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
                correctionReceived = true,
            ),
        )

        assertNull(fixture.core.status().abortReason)
        assertNotEquals(MoverState.COMPLETE, fixture.core.status().state)
        assertStopped(secondCorrectionCommand)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun creativeGamemodeLossAborts(): Unit {
        val fixture = activeFixture()

        assertAbort(
            fixture,
            fixture.context(isCreative = false),
            MoverAbortReason.GAMEMODE_LOST,
        )
    }

    @Test
    public fun sessionIdentityChangeAborts(): Unit {
        val fixture = activeFixture()
        val changedSession = MoverSessionKey(Any(), fixture.contentIdentity, fixture.transformRevision)

        assertAbort(
            fixture,
            fixture.context(sessionKey = changedSession),
            MoverAbortReason.SESSION_CHANGED,
        )
    }

    @Test
    public fun activeToggleAborts(): Unit {
        val fixture = activeFixture()

        assertEquals(MoverState.ABORTED, fixture.core.toggleRequested(true, true, true))
        assertEquals(MoverAbortReason.TOGGLED_OFF, fixture.core.status().abortReason)
        assertStopped(fixture.core.tick(fixture.context()))
    }

    @Test
    public fun takeoffTimeoutAbortsOnTickOneHundred(): Unit {
        val fixture = Fixture()
        fixture.core.toggleRequested(true, true, true)

        repeat(99) {
            val command = fixture.core.tick(fixture.context(onGround = true))
            assertTrue(command.jump)
            assertEquals(MoverState.TAKEOFF, fixture.core.status().state)
        }

        val timeout = fixture.core.tick(fixture.context(onGround = true))
        assertAbortStatus(fixture.core, MoverAbortReason.TAKEOFF_TIMEOUT)
        assertStopped(timeout)
    }

    @Test
    public fun mayflyAndOtherInvalidTogglesRemainIdle(): Unit {
        val fixture = Fixture()

        assertEquals(MoverState.IDLE, fixture.core.toggleRequested(false, true, true))
        assertEquals(MoverAbortReason.MAYFLY_REQUIRED, fixture.core.status().abortReason)

        assertEquals(MoverState.IDLE, fixture.core.toggleRequested(true, false, true))
        assertEquals(MoverAbortReason.GAMEMODE_LOST, fixture.core.status().abortReason)

        assertEquals(MoverState.IDLE, fixture.core.toggleRequested(true, true, false))
        assertNull(fixture.core.status().abortReason)
        assertFalse(fixture.core.tick(fixture.context()).stopMovement)
    }

    @Test
    public fun simultaneousAbortConditionsUseFrozenPriority(): Unit {
        val fixture = activeFixture()
        val changedSession = MoverSessionKey(Any(), Any(), fixture.transformRevision + 1L)

        val command = fixture.core.tick(
            fixture.context(
                sessionKey = changedSession,
                manualInput = true,
                guiOpen = true,
                hurt = true,
                correctionReceived = true,
                isCreative = false,
            ),
        )

        assertAbortStatus(fixture.core, MoverAbortReason.MANUAL_INPUT)
        assertStopped(command)
    }

    @Test
    public fun abortedCoreAlwaysReturnsStoppedCommand(): Unit {
        val fixture = activeFixture()
        fixture.core.tick(fixture.context(manualInput = true))

        repeat(3) {
            val command = fixture.core.tick(
                fixture.context(
                    onGround = false,
                    flying = true,
                    queueRevision = it.toLong() + 1L,
                ),
            )
            assertStopped(command)
            assertAbortStatus(fixture.core, MoverAbortReason.MANUAL_INPUT)
        }
    }

    @Test
    public fun cruiseUsesThreeAxesAndOnlyArrivesInsideThreeDimensionalRadius(): Unit {
        val fixture = Fixture()
        startCruise(fixture)
        val target = requireNotNull(fixture.core.status().target)

        val outside = target.add(-0.4, 0.31, 0.0)
        val correcting = fixture.core.tick(
            fixture.context(playerPos = outside, onGround = false, flying = true),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(1.0, correcting.horizontalX, 0.0000001)
        assertEquals(-1, correcting.vertical)

        val driftedArrival = target.add(0.3, 0.2, 0.3)
        val arrived = fixture.core.tick(
            fixture.context(playerPos = driftedArrival, onGround = false, flying = true),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertStopped(arrived)
    }

    @Test
    public fun controlledMoverVelocityClearsCreativeVerticalMomentumWithoutStoppingHorizontalMotion(): Unit {
        val current = Vec3(0.24, -0.225, -0.12)
        val command = MoverCommand(
            jump = false,
            enableFlight = false,
            horizontalX = 1.0,
            horizontalZ = 0.0,
            vertical = -1,
            stopMovement = false,
        )

        assertEquals(Vec3(0.24, 0.0, -0.12), controlledMoverVelocity(current, command, flying = true))
        assertEquals(
            Vec3.ZERO,
            controlledMoverVelocity(current, command.copy(resetHorizontalVelocity = true), flying = true),
        )
        assertEquals(current, controlledMoverVelocity(current, command, flying = false))
        assertEquals(Vec3.ZERO, controlledMoverVelocity(current, command.copy(stopMovement = true), flying = true))
    }

    @Test
    public fun planModeTightCornerDoesNotRequestAnotherDescentInsideOneCreativeFlightStep(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 0.0, 0.5)
        var corner: Vec3? = null
        val probe: (Vec3, Vec3) -> PathProbeResult = { from, to ->
            val requiredCorner = corner
            val clear = requiredCorner == null || to == requiredCorner ||
                from.distanceToSqr(requiredCorner) <= 1.0E-9
            PathProbeResult(clear = clear, chunkLoaded = true)
        }
        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = probe,
            planStateAt = null,
            planTravelBounds = null,
        )
        val firstCorner = requireNotNull(fixture.core.status().target)
        corner = firstCorner

        val command = fixture.core.tick(
            fixture.context(
                playerPos = firstCorner.add(0.0, 0.11, 0.10),
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                pathProbe = probe,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(0, command.vertical)
    }

    @Test
    public fun planModeLongDiagonalDescentTracksSegmentHeightInsteadOfDroppingImmediately(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(-10.5, 5.05, 0.5)

        val initial = startPlanCruise(fixture, frontier, playerPos = playerStart)

        assertTrue(initial.horizontalX > 0.0)
        assertEquals(0, initial.vertical)

        val progressed = playerStart.add(3.0, 0.0, 0.0)
        val afterHorizontalProgress = fixture.core.tick(
            fixture.context(
                playerPos = progressed,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
            ),
        )
        assertEquals(-1, afterHorizontalProgress.vertical)
    }

    @Test
    public fun planModeQueueRevisionRebuildsWhenCurrentStandBecomesSolid(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerStart = Vec3(-10.5, 1.05, 0.5)
        val world = HashMap<BlockPos, BlockState>()
        val stateAt: (BlockPos) -> BlockState = { pos -> world[pos] ?: Blocks.AIR.defaultBlockState() }

        startPlanCruise(fixture, frontier, playerPos = playerStart, planStateAt = stateAt)
        val staleTarget = requireNotNull(fixture.core.status().target)
        val staleCell = floorCell(staleTarget)
        world[staleCell] = Blocks.STONE.defaultBlockState()

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerStart,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                planMode = true,
                planFrontier = frontier,
                planStateAt = stateAt,
            ),
        )

        assertNotEquals(staleCell, floorCell(requireNotNull(fixture.core.status().target)))
        assertFalse(command.stopMovement)
    }

    @Test
    public fun cruiseFlightLossRetakesOffAndResumesCurrentRoutePosition(): Unit {
        val fixture = Fixture()
        startCruise(fixture)
        val target = fixture.core.status().target

        val jump = fixture.core.tick(fixture.context(onGround = true, flying = false))
        assertEquals(MoverState.TAKEOFF, fixture.core.status().state)
        assertTrue(jump.jump)

        val enableFlight = fixture.core.tick(
            fixture.context(onGround = false, flying = false),
        )
        assertTrue(enableFlight.enableFlight)

        fixture.core.tick(fixture.context(onGround = false, flying = true))
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(target, fixture.core.status().target)
    }

    @Test
    public fun holdFlightLossRetakesOffAndResumesCurrentRoutePosition(): Unit {
        val fixture = Fixture()
        startCruise(fixture)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = target, onGround = false, flying = true),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        val jump = fixture.core.tick(
            fixture.context(playerPos = target, onGround = true, flying = false),
        )
        assertEquals(MoverState.TAKEOFF, fixture.core.status().state)
        assertTrue(jump.jump)

        val enableFlight = fixture.core.tick(
            fixture.context(playerPos = target, onGround = false, flying = false),
        )
        assertTrue(enableFlight.enableFlight)

        fixture.core.tick(
            fixture.context(
                playerPos = target.add(1.0, 0.0, 0.0),
                onGround = false,
                flying = true,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(target, fixture.core.status().target)
    }

    @Test
    public fun thirdFlightLossBansTargetAndRetriesSameAnchorCandidate(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)))
        startCruise(fixture)

        repeat(2) {
            recoverFlight(fixture)
        }

        val skipped = fixture.core.tick(fixture.context(onGround = true, flying = false))

        assertStopped(skipped)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(-2.0, fixture.core.status().target?.x)
    }

    @Test
    public fun changingWorkPositionResetsFlightLossCount(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)))
        startCruise(fixture)
        repeat(2) {
            recoverFlight(fixture)
        }

        val firstTarget = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )
        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = firstTarget,
                    onGround = false,
                    flying = true,
                    feedSnapshot = emptyFeed(),
                ),
            )
        }
        assertEquals(-2.0, fixture.core.status().target?.x)

        val jump = fixture.core.tick(fixture.context(onGround = true, flying = false))

        assertTrue(jump.jump)
        assertEquals(MoverState.TAKEOFF, fixture.core.status().state)
    }

    @Test
    public fun plannerExcludesUnplaceableCluster(): Unit {
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            isPlaceable = { worldPos -> worldPos.x == 100 },
        )

        startCruise(fixture)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(102.0, fixture.core.status().target?.x)
    }

    @Test
    public fun allMissingUnplaceableCompletesThroughPlannerFixpoint(): Unit {
        val fixture = Fixture(isPlaceable = { false })

        val command = startCruise(fixture)

        assertStopped(command)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(fixture.context(onGround = false, flying = true))

        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun terminalInvariantRejectionKeepsRebuildingUntilAllowed(): Unit {
        var completionAllowed = false
        var checks = 0
        val fixture = Fixture(
            isPlaceable = { false },
            canComplete = {
                checks++
                completionAllowed
            },
        )

        startCruise(fixture)
        fixture.core.tick(fixture.context(onGround = false, flying = true))

        assertEquals(1, checks)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        completionAllowed = true
        fixture.core.tick(fixture.context(onGround = false, flying = true))

        assertEquals(2, checks)
        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun plannerUncoverablePositionsAreReportedWithMoverCause(): Unit {
        val reported = mutableListOf<Pair<Set<BlockPos>, MoverDeferralCause>>()
        val fixture = Fixture(
            onPositionsUncoverable = { positions, cause -> reported.add(positions to cause) },
        )

        startCruise(fixture, gateY = null, reach = 0.0)

        assertEquals(
            listOf(setOf(BlockPos.ZERO) to MoverDeferralCause.MOVER_UNREACHABLE),
            reported,
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
    }

    @Test
    public fun ascendingLongLegTargetsBaseDirectlyWhenClear(): Unit {
        val fixture = Fixture()
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = true, chunkLoaded = true)
        }
        val playerPos = Vec3(-10.0, 0.0, 0.0)

        val cruiseCommand = startCruise(
            fixture,
            pathProbe = probe,
            playerPos = playerPos,
        )
        val baseTarget = Vec3(2.0, 1.4, 0.0)
        assertEquals(listOf(baseTarget), probedTargets)
        assertEquals(baseTarget, fixture.core.status().target)
        assertEquals(1, cruiseCommand.vertical)
    }

    @Test
    public fun descendingLongLegTargetsBaseDirectly(): Unit {
        val fixture = Fixture()
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = true, chunkLoaded = true)
        }

        val command = startCruise(
            fixture,
            pathProbe = probe,
            playerPos = Vec3(-10.0, 5.0, 0.0),
        )
        val baseTarget = Vec3(2.0, 1.4, 0.0)

        assertEquals(listOf(baseTarget), probedTargets)
        assertEquals(baseTarget, fixture.core.status().target)
        assertEquals(-1, command.vertical)
    }

    @Test
    public fun blockedDescendingLongLegBansCandidateInsteadOfEscalating(): Unit {
        val fixture = Fixture(isPassableCell = { false })
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = false, chunkLoaded = true)
        }

        val command = startCruise(
            fixture,
            pathProbe = probe,
            playerPos = Vec3(-10.0, 5.0, 0.0),
        )
        val baseTarget = Vec3(2.0, 1.4, 0.0)
        val nextCandidateTarget = Vec3(-2.0, 1.4, 0.0)

        assertEquals(listOf(baseTarget), probedTargets)
        assertEquals(nextCandidateTarget, fixture.core.status().target)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(0, fixture.core.pathTelemetry().aStarSuccesses)
        assertStopped(command)
    }

    @Test
    public fun playerPressedAgainstWallStartCellRescuesInsteadOfSkipBan(): Unit {
        val wallColumn = setOf(BlockPos(-10, 1, 0), BlockPos(-10, 2, 0))
        val playerPos = Vec3(-9.9, 1.0, 0.5)
        val baseTarget = Vec3(2.0, 1.4, 0.0)
        val abandoned = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            isPassableCell = { position -> position !in wallColumn },
            onWorkPositionAbandoned = { covered -> abandoned.add(covered) },
        )
        val probe: (Vec3, Vec3) -> PathProbeResult = { from, to ->
            PathProbeResult(clear = !(from == playerPos && to == baseTarget), chunkLoaded = true)
        }

        val command = startCruise(fixture, pathProbe = probe, playerPos = playerPos)

        assertTrue(abandoned.isEmpty())
        val telemetry = fixture.core.pathTelemetry()
        assertEquals(1, telemetry.aStarInvocations)
        assertEquals(1, telemetry.aStarSuccesses)
        assertEquals(0, telemetry.aStarFailStartOrGoalBlocked)
        assertEquals(1, telemetry.aStarResolveStartRescues)
        assertEquals(0, telemetry.aStarResolveGoalRescues)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun blockedDirectLegFollowsLateralWaypointsToHoldWithoutLadder(): Unit {
        val missing = BlockPos(10, 0, 0)
        val baseTarget = Vec3(12.0, 1.4, 0.0)
        val playerStart = Vec3(0.5, 5.0, 0.5)
        val probedTargets = mutableListOf<Vec3>()
        val abandoned = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            missingWorld = listOf(missing),
            isPassableCell = { position ->
                position.x != 4 || position.z !in -2..2
            },
            onWorkPositionAbandoned = { covered -> abandoned.add(covered) },
        )
        val probe = wallProbe(
            wallX = 4,
            minWallZ = -2,
            maxWallZ = 2,
            probedTargets = probedTargets,
        )

        startCruise(
            fixture = fixture,
            pathProbe = probe,
            playerPos = playerStart,
        )
        repeat(30) {
            if (fixture.core.status().state != MoverState.CRUISE) return@repeat
            val target = requireNotNull(fixture.core.status().target)
            fixture.core.tick(
                fixture.context(
                    playerPos = target,
                    onGround = false,
                    flying = true,
                    pathProbe = probe,
                ),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertTrue(abandoned.isEmpty())
        assertTrue(probedTargets.any { target -> target.z < -2.0 || target.z > 3.0 })
        assertTrue(probedTargets.none { target -> target.y >= baseTarget.y + 4.0 })
        val telemetry = fixture.core.pathTelemetry()
        assertEquals(1, telemetry.aStarInvocations)
        assertEquals(1, telemetry.aStarSuccesses)
        assertTrue(telemetry.smoothingWaypointsOut < telemetry.smoothingWaypointsIn)
    }

    @Test
    public fun noAStarPathBansCandidateAndRetriesNextOneDirectly(): Unit {
        val fixture = Fixture(
            missingWorld = listOf(BlockPos(5, 0, 0)),
            isPassableCell = { position -> position.x != 4 },
        )
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = target.x < 4.0, chunkLoaded = true)
        }
        val playerPos = Vec3(0.5, 1.0, 0.5)
        val farCandidate = Vec3(7.0, 1.4, 0.0)
        val nearCandidate = Vec3(3.0, 1.4, 0.0)

        val command = startCruise(fixture = fixture, pathProbe = probe, playerPos = playerPos)

        assertEquals(listOf(farCandidate), probedTargets)
        assertEquals(nearCandidate, fixture.core.status().target)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(0, fixture.core.pathTelemetry().aStarSuccesses)
        assertStopped(command)

        val advance = fixture.core.tick(
            fixture.context(playerPos = playerPos, onGround = false, flying = true, pathProbe = probe),
        )

        assertEquals(listOf(farCandidate, nearCandidate), probedTargets)
        assertEquals(nearCandidate, fixture.core.status().target)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)
        assertFalse(advance.stopMovement)
    }

    @Test
    public fun failedWaypointSegmentResearchesOnceThenBansWithoutLadder(): Unit {
        val baseTarget = Vec3(12.0, 1.4, 0.0)
        val nextCandidateTarget = Vec3(8.0, 1.4, 0.0)
        val abandoned = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            missingWorld = listOf(BlockPos(10, 0, 0)),
            isPassableCell = { position ->
                position.x != 4 || position.z !in -2..2
            },
            onWorkPositionAbandoned = { covered -> abandoned.add(covered) },
        )
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(
                clear = target.y >= baseTarget.y + 4.0,
                chunkLoaded = true,
            )
        }

        val command = startCruise(
            fixture = fixture,
            pathProbe = probe,
            playerPos = Vec3(0.5, 5.0, 0.5),
        )

        assertEquals(2, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(2, fixture.core.pathTelemetry().aStarSuccesses)
        assertEquals(listOf(setOf(BlockPos(10, 0, 0))), abandoned)
        assertEquals(nextCandidateTarget, fixture.core.status().target)
        assertTrue(probedTargets.none { target -> target.y >= baseTarget.y + 4.0 })
        assertStopped(command)
    }

    @Test
    public fun legAtDirectThresholdTargetsBaseDirectly(): Unit {
        val fixture = Fixture()

        startCruise(
            fixture = fixture,
            playerPos = Vec3(0.0, 1.0, 0.0),
        )

        assertEquals(Vec3(2.0, 1.4, 0.0), fixture.core.status().target)
    }

    @Test
    public fun baseTargetAboveCruiseAltitudeTargetsBaseDirectly(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 3, 0)))

        startCruise(fixture, gateY = 1)

        assertEquals(Vec3(2.0, 4.4, 0.0), fixture.core.status().target)
    }

    @Test
    public fun legWithoutGateTargetsBaseDirectly(): Unit {
        val fixture = Fixture()

        startCruise(fixture, gateY = null)

        assertEquals(Vec3(2.0, 1.4, 0.0), fixture.core.status().target)
    }

    @Test
    public fun blockedPathWithNoRouteAnywhereExhaustsAllCandidatesWithoutClimbing(): Unit {
        val uncoverable = mutableListOf<Pair<Set<BlockPos>, MoverDeferralCause>>()
        val fixture = Fixture(
            isPassableCell = { false },
            onPositionsUncoverable = { positions, cause -> uncoverable.add(positions to cause) },
        )
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = false, chunkLoaded = true)
        }

        val command = startCruise(fixture, pathProbe = probe)
        repeat(8) {
            fixture.core.tick(
                fixture.context(onGround = false, flying = true, pathProbe = probe),
            )
        }

        assertEquals(9, probedTargets.size)
        assertTrue(probedTargets.all { target -> target.y == 1.4 || target.y == 0.4 || target.y == 3.4 })
        assertEquals(
            listOf(setOf(BlockPos.ZERO) to MoverDeferralCause.MOVER_UNREACHABLE),
            uncoverable,
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertStopped(command)
    }

    @Test
    public fun blockedPathWithNoRouteBansFirstCandidateAndRetriesSameAnchor(): Unit {
        val abandoned = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            isPassableCell = { false },
            onWorkPositionAbandoned = { covered -> abandoned.add(covered) },
        )
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = false, chunkLoaded = true)
        }

        val command = startCruise(fixture, pathProbe = probe)
        val baseY = 1.4

        assertEquals(listOf(baseY), probedTargets.map { it.y })
        assertEquals(listOf(setOf(BlockPos.ZERO)), abandoned)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(2, fixture.core.status().remainingMissing)
        assertEquals(-2.0, fixture.core.status().target?.x)
        assertStopped(command)
    }

    @Test
    public fun unloadedChunkWaitsTwoHundredTicksThenSkipsTarget(): Unit {
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
        )
        var probeCalls = 0
        val unloadedProbe: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            probeCalls++
            PathProbeResult(clear = true, chunkLoaded = false)
        }

        startCruise(fixture, pathProbe = unloadedProbe)
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(2, fixture.core.status().remainingMissing)

        repeat(198) {
            fixture.core.tick(
                fixture.context(onGround = false, flying = true, pathProbe = unloadedProbe),
            )
        }
        assertEquals(199, probeCalls)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val timeout = fixture.core.tick(
            fixture.context(onGround = false, flying = true, pathProbe = unloadedProbe),
        )
        assertEquals(200, probeCalls)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(2, fixture.core.status().remainingMissing)
        assertEquals(-2.0, fixture.core.status().target?.x)
        assertStopped(timeout)
    }

    @Test
    public fun twoFedEmptyHoldTicksAdvanceToNextWorkPosition(): Unit {
        var onlyFarMissingIsPlaceable = false
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            isPlaceable = { worldPos -> !onlyFarMissingIsPlaceable || worldPos.x == 100 },
        )
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)
        onlyFarMissingIsPlaceable = true
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )

        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                feedSnapshot = emptyFeed(),
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val advance = fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                feedSnapshot = emptyFeed(),
            ),
        )

        assertStopped(advance)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(102.0, fixture.core.status().target?.x)
    }

    @Test
    public fun absentStaleSnapshotDoesNotReleaseHold(): Unit {
        var onlyFarMissingIsPlaceable = false
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            isPlaceable = { worldPos -> !onlyFarMissingIsPlaceable || worldPos.x == 100 },
        )
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)
        onlyFarMissingIsPlaceable = true
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )
        repeat(20) {
            fixture.core.tick(
                fixture.context(playerPos = firstTarget, onGround = false, flying = true),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(firstTarget, fixture.core.status().target)
    }

    @Test
    public fun inFlightSnapshotDoesNotReleaseHold(): Unit {
        val fixture = Fixture()
        startCruise(fixture)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = target, onGround = false, flying = true),
        )

        repeat(20) {
            fixture.core.tick(
                fixture.context(
                    playerPos = target,
                    onGround = false,
                    flying = true,
                    feedSnapshot = emptyFeed(inFlightCount = 1),
                ),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(target, fixture.core.status().target)
    }

    @Test
    public fun fedEmptyHoldBecomesUnproductiveAfterTwoTicks(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)))
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                feedSnapshot = emptyFeed(),
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(2, fixture.core.status().remainingMissing)

        val advance = fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                feedSnapshot = emptyFeed(),
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(2, fixture.core.status().remainingMissing)
        assertEquals(-2.0, fixture.core.status().target?.x)
        assertStopped(advance)
    }

    @Test
    public fun secondDistinctUnproductiveTargetRequestsNoProgressDeferral(): Unit {
        val events = mutableListOf<Pair<Set<BlockPos>, Set<BlockPos>>>()
        val fixture = Fixture(
            onWorkPositionUnproductive = { covered, deferPositions ->
                events.add(covered to deferPositions)
            },
        )
        startCruise(fixture)

        val firstTarget = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )
        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = firstTarget,
                    onGround = false,
                    flying = true,
                    feedSnapshot = emptyFeed(),
                ),
            )
        }

        val secondTarget = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(-2.0, 1.4, 0.0), secondTarget)
        fixture.core.tick(
            fixture.context(playerPos = secondTarget, onGround = false, flying = true),
        )
        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = secondTarget,
                    onGround = false,
                    flying = true,
                    feedSnapshot = emptyFeed(),
                ),
            )
        }

        assertEquals(
            listOf(
                setOf(BlockPos.ZERO) to emptySet(),
                setOf(BlockPos.ZERO) to setOf(BlockPos.ZERO),
            ),
            events,
        )
    }

    @Test
    public fun arrivalHoldHardCapLogsAndAdvances(): Unit {
        var hardCapEvents = 0
        val fixture = Fixture(missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)))
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )

        repeat(199) {
            fixture.core.tick(
                fixture.context(
                    playerPos = firstTarget,
                    onGround = false,
                    flying = true,
                    onHoldHardCap = { hardCapEvents++ },
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(0, hardCapEvents)

        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                onHoldHardCap = { hardCapEvents++ },
            ),
        )

        assertEquals(1, hardCapEvents)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(-2.0, fixture.core.status().target?.x)
    }

    @Test
    public fun routeConsumesWorkPositionsInSweepOrderAndCompletesAfterAllPlaced(): Unit {
        val first = BlockPos.ZERO
        val second = BlockPos(100, 0, 0)
        val fixture = Fixture(missingWorld = listOf(second, first))
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)
        assertEquals(2.0, firstTarget.x)

        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )
        val afterFirstPlacement = listOf(second)
        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                missingWorld = afterFirstPlacement,
                feedSnapshot = emptyFeed(queueRevision = 1L),
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                missingWorld = afterFirstPlacement,
                feedSnapshot = emptyFeed(queueRevision = 1L),
            ),
        )
        val secondTarget = requireNotNull(fixture.core.status().target)
        assertEquals(102.0, secondTarget.x)

        fixture.core.tick(
            fixture.context(
                playerPos = secondTarget,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                missingWorld = afterFirstPlacement,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = secondTarget,
                onGround = false,
                flying = true,
                queueRevision = 2L,
                missingWorld = emptyList(),
                feedSnapshot = emptyFeed(queueRevision = 2L),
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = secondTarget,
                onGround = false,
                flying = true,
                queueRevision = 2L,
                missingWorld = emptyList(),
                feedSnapshot = emptyFeed(queueRevision = 2L),
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = secondTarget,
                onGround = false,
                flying = true,
                queueRevision = 2L,
                missingWorld = emptyList(),
                feedSnapshot = emptyFeed(queueRevision = 2L),
            ),
        )

        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
        assertEquals(0, fixture.core.status().remainingMissing)
    }

    @Test
    public fun progressedRouteEndRebuildsAndContinues(): Unit {
        val unproductive = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            onWorkPositionUnproductive = { covered, _ -> unproductive.add(covered) },
        )
        startCruise(fixture)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = target, onGround = false, flying = true),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = target,
                onGround = false,
                flying = true,
                queueRevision = 1L,
            ),
        )

        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = target,
                    onGround = false,
                    flying = true,
                    queueRevision = 1L,
                    feedSnapshot = emptyFeed(queueRevision = 1L),
                ),
            )
        }

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(target, fixture.core.status().target)
        assertTrue(unproductive.isEmpty())
    }

    @Test
    public fun unprogressedRouteEndRetriesAnotherCandidateWithoutCompleting(): Unit {
        val fixture = Fixture()
        startCruise(fixture)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = target, onGround = false, flying = true),
        )
        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = target,
                    onGround = false,
                    flying = true,
                    feedSnapshot = emptyFeed(),
                ),
            )
        }

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-2.0, 1.4, 0.0), fixture.core.status().target)
    }

    @Test
    public fun gateLayerDrainDefersBelowGateStragglerUntilRecoveryTakesTheGate(): Unit {
        val straggler = BlockPos(0, 5, 0)
        val gateLayer = BlockPos(20, 6, 0)
        val fixture = Fixture(missingWorld = listOf(straggler, gateLayer))

        startCruise(fixture, gateY = 6)

        val gateLaneTarget = Vec3(20.5, 8.0, 0.5)
        assertEquals(gateLaneTarget, fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = gateLaneTarget,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                gateY = 6,
                missingWorld = listOf(straggler),
                feedSnapshot = emptyFeed(queueRevision = 1L),
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(1, fixture.core.status().remainingMissing)

        fixture.core.tick(
            fixture.context(
                playerPos = gateLaneTarget,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                gateY = 5,
                gatePhase = PrinterLayerGatePhase.RECOVERY,
                missingWorld = listOf(straggler),
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(0.5, 7.0, 0.5), fixture.core.status().target)
        assertEquals(1, fixture.core.status().remainingMissing)
    }

    @Test
    public fun latePlaceableWaitsForFrozenLayerDrainThenBatchCompletes(): Unit {
        val firstMember = BlockPos.ZERO
        val latePlaceable = BlockPos(100, 0, 0)
        val lastMember = BlockPos(200, 0, 0)
        val allMissing = listOf(firstMember, latePlaceable, lastMember)
        val placeable = linkedSetOf(firstMember, lastMember)
        val fixture = Fixture(
            missingWorld = allMissing,
            isPlaceable = { position -> position in placeable },
        )

        fun arriveAtCurrentWorkPosition(
            queueRevision: Long,
            missingWorld: List<BlockPos>,
        ): Vec3 {
            repeat(3) {
                val target = requireNotNull(fixture.core.status().target)
                fixture.core.tick(
                    fixture.context(
                        playerPos = target,
                        onGround = false,
                        flying = true,
                        queueRevision = queueRevision,
                        gateY = 0,
                        missingWorld = missingWorld,
                    ),
                )
                if (fixture.core.status().state == MoverState.HOLD) return target
            }
            error("current work position did not reach HOLD")
        }

        startCruise(fixture, gateY = 0)
        val firstTarget = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(0.5, 2.0, 0.5), firstTarget)

        placeable.remove(firstMember)
        placeable.add(latePlaceable)
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true, gateY = 0),
        )

        assertEquals(Vec3(200.5, 2.0, 0.5), fixture.core.status().target)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        val lastTarget = requireNotNull(fixture.core.status().target)
        val afterLastMember = listOf(firstMember, latePlaceable)
        fixture.core.tick(
            fixture.context(
                playerPos = lastTarget,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                gateY = 0,
                missingWorld = afterLastMember,
            ),
        )

        assertEquals(102.0, fixture.core.status().target?.x)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        val playerPos = arriveAtCurrentWorkPosition(
            queueRevision = 1L,
            missingWorld = afterLastMember,
        )
        placeable.remove(latePlaceable)
        val afterBatch = listOf(firstMember)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                queueRevision = 2L,
                gateY = 0,
                missingWorld = afterBatch,
            ),
        )
        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    queueRevision = 2L,
                    gateY = 0,
                    missingWorld = afterBatch,
                    feedSnapshot = emptyFeed(queueRevision = 2L),
                ),
            )
        }
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                queueRevision = 2L,
                gateY = 0,
                missingWorld = afterBatch,
            ),
        )

        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun gateYChangeRebuildsRoute(): Unit {
        var gateY = 10
        val fixture = Fixture(
            missingWorld = listOf(BlockPos(0, 10, 0), BlockPos.ZERO),
            isPlaceable = { worldPos -> worldPos.y <= gateY },
        )
        startCruise(fixture, gateY = gateY)
        val lowerTarget = requireNotNull(fixture.core.status().target)

        gateY = -1
        fixture.core.tick(
            fixture.context(
                playerPos = lowerTarget,
                onGround = false,
                flying = true,
                gateY = gateY,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = lowerTarget,
                onGround = false,
                flying = true,
                gateY = gateY,
            ),
        )

        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun upperOnlyConnectedBlockIsDeferredThenRecoveredFirstAfterSupport(): Unit {
        val lower = BlockPos.ZERO
        val upper = BlockPos(0, 1, 0)
        var lowerPlaceable = false
        val fixture = Fixture(
            missingWorld = listOf(lower, upper),
            isPlaceable = { worldPos -> worldPos != lower || lowerPlaceable },
        )

        startCruise(fixture, gateY = 1)
        assertEquals(Vec3(0.5, 3.0, 0.5), fixture.core.status().target)

        lowerPlaceable = true
        fixture.core.tick(
            fixture.context(
                onGround = false,
                flying = true,
                queueRevision = 1L,
                gateY = 0,
                missingWorld = listOf(lower),
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(0.5, 2.0, 0.5), fixture.core.status().target)
    }

    @Test
    public fun aStarFailureRetriesNextCandidateForTheSameAnchor(): Unit {
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            isPassableCell = { false },
        )
        val blockedProbe: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            PathProbeResult(clear = false, chunkLoaded = true)
        }

        val command = startCruise(fixture, pathProbe = blockedProbe)

        assertStopped(command)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(-2.0, fixture.core.status().target?.x)
    }

    @Test
    public fun aStarFailureAbandonmentReportsTheCoveredWorkPosition(): Unit {
        val abandoned = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            isPassableCell = { false },
            onWorkPositionAbandoned = { covered -> abandoned.add(covered) },
        )
        val blockedProbe: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            PathProbeResult(clear = false, chunkLoaded = true)
        }

        startCruise(fixture, pathProbe = blockedProbe)

        assertEquals(listOf(setOf(BlockPos.ZERO)), abandoned)
    }

    @Test
    public fun unproductiveHoldDoesNotReportPathAbandonment(): Unit {
        val abandoned = mutableListOf<Set<BlockPos>>()
        val fixture = Fixture(
            missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)),
            onWorkPositionAbandoned = { covered -> abandoned.add(covered) },
        )
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(playerPos = firstTarget, onGround = false, flying = true),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(2) {
            fixture.core.tick(
                fixture.context(
                    playerPos = firstTarget,
                    onGround = false,
                    flying = true,
                    feedSnapshot = emptyFeed(),
                ),
            )
        }

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(-2.0, fixture.core.status().target?.x)
        assertTrue(abandoned.isEmpty())
    }

    @Test
    public fun toggleResetStartsRouteFromFirstWorkPosition(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos.ZERO, BlockPos(100, 0, 0)))
        startCruise(fixture)
        val firstTarget = requireNotNull(fixture.core.status().target)

        assertEquals(MoverState.ABORTED, fixture.core.toggleRequested(true, true, true))
        assertEquals(MoverState.TAKEOFF, fixture.core.toggleRequested(true, true, true))
        finishTakeoff(fixture)
        val resetTarget = requireNotNull(fixture.core.status().target)
        assertEquals(firstTarget, resetTarget)
    }

    @Test
    public fun cruiseQueueRevisionChangeDoesNotRebuildRouteOrProbePath(): Unit {
        var pathProbeCalls = 0
        val fixture = Fixture()
        val countingProbe: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            pathProbeCalls++
            PathProbeResult(clear = true, chunkLoaded = true)
        }
        startCruise(fixture, pathProbe = countingProbe)
        pathProbeCalls = 0

        val command = fixture.core.tick(
            fixture.context(
                playerPos = Vec3(10.0, 1.0, 0.0),
                onGround = false,
                flying = true,
                queueRevision = 1L,
                pathProbe = countingProbe,
            ),
        )

        assertEquals(0, pathProbeCalls)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)
        assertEquals(2.0, fixture.core.status().target?.x)
        assertEquals(-1.0, command.horizontalX, 0.0000001)
    }

    @Test
    public fun oneHundredStationaryCruiseTicksRetryAStarWithoutClimbing(): Unit {
        val fixture = Fixture()
        val probedTargets = mutableListOf<Vec3>()
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            probedTargets.add(target)
            PathProbeResult(clear = true, chunkLoaded = true)
        }
        startCruise(fixture, pathProbe = probe)
        val baseTarget = requireNotNull(fixture.core.status().target)

        repeat(99) {
            fixture.core.tick(
                fixture.context(onGround = false, flying = true, pathProbe = probe),
            )
        }
        assertEquals(baseTarget, fixture.core.status().target)
        assertEquals(listOf(baseTarget), probedTargets)

        fixture.core.tick(
            fixture.context(onGround = false, flying = true, pathProbe = probe),
        )
        assertEquals(baseTarget, fixture.core.status().target)
        assertEquals(listOf(baseTarget, baseTarget, baseTarget), probedTargets)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(1, fixture.core.pathTelemetry().aStarSuccesses)
    }

    @Test
    public fun laneModeUsesSlowSpeedWhenFeedSnapshotIsAbsent(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)

        val command = startCruise(fixture, gateY = 5, playerPos = playerPos)

        assertEquals(0.3, command.horizontalX, 0.0000001)
        assertEquals(0, command.vertical)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun laneModeSlowsDownWhenAFreshSnapshotHasPendingCandidates(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = FeedSnapshot(
                    tick = 0L,
                    queueRevision = 0L,
                    candidateCount = 1,
                    submittedCount = 1,
                    rateLimitedRemainder = 0,
                    inFlightCount = 1,
                    acceptedThisTick = 0,
                ),
            ),
        )

        assertEquals(0.3, command.horizontalX, 0.0000001)
    }

    @Test
    public fun laneModeCruisesAtFullSpeedWhenAFreshSnapshotHasNoCandidates(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = emptyFeed(),
            ),
        )

        assertEquals(1.0, command.horizontalX, 0.0000001)
    }

    @Test
    public fun laneModeStopsWhenAFreshSnapshotHasRecentAcceptedProgress(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = FeedSnapshot(
                    tick = 0L,
                    queueRevision = 0L,
                    candidateCount = 5,
                    submittedCount = 1,
                    rateLimitedRemainder = 0,
                    inFlightCount = 1,
                    acceptedThisTick = 0,
                    ticksSinceLastAccept = RECENT_PROGRESS_WINDOW_TICKS,
                ),
            ),
        )

        assertEquals(0.0, command.horizontalX, 0.0000001)
        assertEquals(0.0, command.horizontalZ, 0.0000001)
        assertEquals(0, command.vertical)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun laneModeCrawlsWhenBacklogHasNoRecentAcceptedProgress(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = FeedSnapshot(
                    tick = 0L,
                    queueRevision = 0L,
                    candidateCount = 5,
                    submittedCount = 0,
                    rateLimitedRemainder = 0,
                    inFlightCount = 0,
                    acceptedThisTick = 0,
                    ticksSinceLastAccept = RECENT_PROGRESS_WINDOW_TICKS + 1L,
                ),
            ),
        )

        assertEquals(0.3, command.horizontalX, 0.0000001)
    }

    @Test
    public fun laneModeCruisesAtFullSpeedEvenWithRecentProgressWhenNoCandidatesRemain(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = emptyFeed().copy(ticksSinceLastAccept = 0L),
            ),
        )

        assertEquals(1.0, command.horizontalX, 0.0000001)
    }

    @Test
    public fun laneModeReleasesToFullSpeedTheTickTheBacklogDrains(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        val stopped = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = FeedSnapshot(
                    tick = 0L,
                    queueRevision = 0L,
                    candidateCount = 1,
                    submittedCount = 0,
                    rateLimitedRemainder = 0,
                    inFlightCount = 0,
                    acceptedThisTick = 1,
                    ticksSinceLastAccept = 0L,
                ),
            ),
        )
        assertEquals(0.0, stopped.horizontalX, 0.0000001)

        val released = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                gateY = 5,
                feedSnapshot = emptyFeed(),
            ),
        )
        assertEquals(1.0, released.horizontalX, 0.0000001)
    }

    @Test
    public fun laneModeZeroSpeedHoldSurvivesTheStallWatchdogWithoutLeavingTheLane(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)
        val initialTarget = fixture.core.status().target

        val recentSnapshot = FeedSnapshot(
            tick = 0L,
            queueRevision = 0L,
            candidateCount = 5,
            submittedCount = 0,
            rateLimitedRemainder = 0,
            inFlightCount = 1,
            acceptedThisTick = 0,
            ticksSinceLastAccept = 0L,
        )
        var lastCommand: MoverCommand? = null
        repeat(250) {
            lastCommand = fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    gateY = 5,
                    feedSnapshot = recentSnapshot,
                ),
            )
        }

        assertEquals(0.0, requireNotNull(lastCommand).horizontalX, 0.0000001)
        assertEquals(initialTarget, fixture.core.status().target)
        assertEquals(0, fixture.core.pathTelemetry().aStarInvocations)
    }

    @Test
    public fun blockedSegmentTriesAStarThenSkipsToTheNextSegmentOnFailure(): Unit {
        val fixture = Fixture(
            missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(8, 5, 0), BlockPos(16, 5, 0)),
            isPassableCell = { false },
        )
        val playerPos = Vec3(0.5, 7.0, 0.5)
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, to ->
            PathProbeResult(clear = to.x != 8.5, chunkLoaded = true)
        }

        val command = startCruise(fixture, pathProbe = probe, gateY = 5, playerPos = playerPos)

        assertEquals(Vec3(16.5, 7.0, 0.5), fixture.core.status().target)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(0, fixture.core.pathTelemetry().aStarSuccesses)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun fullLanePassWithProgressReplansFromTheCurrentPlaceableSet(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val start = Vec3(0.5, 7.0, 0.5)
        val end = Vec3(0.5, 7.0, 4.5)
        startCruise(fixture, gateY = 5, playerPos = start)
        assertEquals(end, fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = end,
                onGround = false,
                flying = true,
                gateY = 5,
                queueRevision = 1L,
            ),
        )

        assertEquals(start, fixture.core.status().target)
        assertEquals(2, fixture.core.laneTelemetry().passes)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
    }

    @Test
    public fun fullLanePassWithNoProgressDefersTheRemainingFrozenLayerAsNoProgress(): Unit {
        val deferred = mutableListOf<Pair<Set<BlockPos>, MoverDeferralCause>>()
        val fixture = Fixture(
            missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)),
            onPositionsUncoverable = { positions, cause -> deferred.add(positions to cause) },
        )
        val start = Vec3(0.5, 7.0, 0.5)
        val end = Vec3(0.5, 7.0, 4.5)
        startCruise(fixture, gateY = 5, playerPos = start)

        fixture.core.tick(
            fixture.context(playerPos = end, onGround = false, flying = true, gateY = 5),
        )

        assertEquals(
            listOf(setOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)) to MoverDeferralCause.NO_PROGRESS),
            deferred,
        )
    }

    @Test
    public fun stationaryPlayerWithNoReachableRouteAbortsTrappedAfterOneHundredTicks(): Unit {
        val fixture = Fixture(isPlaceable = { false }, canComplete = { false })
        startCruise(fixture)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(100) {
            fixture.core.tick(fixture.context(onGround = false, flying = true))
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val trapped = fixture.core.tick(fixture.context(onGround = false, flying = true))

        assertAbortStatus(fixture.core, MoverAbortReason.TRAPPED)
        assertStopped(trapped)
    }

    @Test
    public fun laneModeIsExemptFromTheTrappedWatchdog(): Unit {
        val fixture = Fixture(missingWorld = listOf(BlockPos(0, 5, 0), BlockPos(0, 5, 4)))
        val playerPos = Vec3(-10.0, 7.0, 0.5)
        startCruise(fixture, gateY = 5, playerPos = playerPos)

        repeat(150) {
            fixture.core.tick(
                fixture.context(playerPos = playerPos, onGround = false, flying = true, gateY = 5),
            )
        }

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeStandPositionCoversTargetAndAvoidsFrontierColumns(): Unit {
        val target = BlockPos.ZERO
        val sameColumnAsFirstCandidate = BlockPos(2, 50, 0)
        val fixture = Fixture()
        val frontier = frontierOf(target, sameColumnAsFirstCandidate)

        startPlanCruise(fixture, frontier)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planRouteExcludesOwnColumnTargetFromCoverageWhenScoringPlanModeCandidates(): Unit {
        val anchor = BlockPos(0, -1, 0)
        val satellites = listOf(BlockPos(8, 0, 0), BlockPos(-8, 0, 0), BlockPos(0, 0, 8), BlockPos(0, 0, -8))
        val missing = listOf(anchor) + satellites

        val withoutExclusion = planRoute(placeableMissing = missing, reach = 9.2)
        val bestWithoutExclusion = withoutExclusion.route.first()
        assertEquals(
            0 to 0,
            floor(bestWithoutExclusion.target.x).toInt() to floor(bestWithoutExclusion.target.z).toInt(),
        )
        assertEquals(5, bestWithoutExclusion.covered.size)

        val withExclusion = planRoute(placeableMissing = missing, reach = 9.2, excludeOwnColumnFromCoverage = true)
        val bestWithExclusion = withExclusion.route.first()
        assertNotEquals(
            0 to 0,
            floor(bestWithExclusion.target.x).toInt() to floor(bestWithExclusion.target.z).toInt(),
            "a stand directly above the cluster must no longer win once its own-column target is excluded",
        )
        assertEquals(2 to 0, floor(bestWithExclusion.target.x).toInt() to floor(bestWithExclusion.target.z).toInt())
        assertEquals(4, bestWithExclusion.covered.size)
    }

    @Test
    public fun planModeColumnBlockedOnlyEvacuatesOutOfTheBlockedColumn(): Unit {
        val blocked = BlockPos(-10, 1, 0)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val fixture = Fixture()
        val frontier = MoverPlanFrontier(
            waitingForReach = emptyList(),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )

        val command = startPlanCruise(fixture, frontier, playerPos = playerPos)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val evacTarget = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(-11.5, 1.05, 0.5), evacTarget)
        assertNotEquals(
            blocked.x to blocked.z,
            floor(evacTarget.x).toInt() to floor(evacTarget.z).toInt(),
        )
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeEvacuationKeepsAnExitAfterTheBlockingHeadIsPlaced(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val blocked = BlockPos(12, 9, 2)
        val trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.OPEN, true)
            .setValue(BlockStateProperties.POWERED, true)
        val playerPos = Vec3(12.5, 9.13, 2.5)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.x in 10..20 && pos.y in 9..10 && pos.z == 2) air else stone
        }
        val frontier = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(blocked, trapdoor)),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )
        val fixture = Fixture()
        val bounds = BlockPos(8, 7, 0) to BlockPos(22, 13, 4)

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerPos,
            planStateAt = stateAt,
            planTravelBounds = bounds,
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(14.5, 9.05, 2.5), fixture.core.status().target)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeColumnBlockedEvacuatesBeforeSimultaneousReachWork(): Unit {
        val blocked = BlockPos(-10, 1, 0)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val frontier = MoverPlanFrontier(
            waitingForReach = listOf(
                MoverPlanFrontierTarget(BlockPos(10, 1, 0), Blocks.STONE.defaultBlockState()),
            ),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )
        val fixture = Fixture()

        val command = startPlanCruise(fixture, frontier, playerPos = playerPos)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-11.5, 1.05, 0.5), fixture.core.status().target)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeDenseFloorColumnBlockEvacuatesVerticallyWhenEveryRingColumnIsPending(): Unit {
        val playerPos = Vec3(0.5, 0.05, 0.5)
        val blocked = BlockPos.ZERO
        val pendingRing = mutableListOf<MoverPlanFrontierTarget>()
        for (radius in 2..3) {
            for (deltaX in -radius..radius) {
                for (deltaZ in -radius..radius) {
                    if (maxOf(abs(deltaX), abs(deltaZ)) != radius) continue
                    pendingRing += MoverPlanFrontierTarget(
                        BlockPos(deltaX, 0, deltaZ),
                        Blocks.STONE.defaultBlockState(),
                    )
                }
            }
        }
        val frontier = MoverPlanFrontier(
            waitingForReach = pendingRing,
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )
        val fixture = Fixture()

        val command = startPlanCruise(fixture, frontier, playerPos = playerPos)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(0.5, 1.05, 0.5), fixture.core.status().target)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeVerticalEvacuationDoesNotArriveUntilPlayerClearsBlockedTarget(): Unit {
        val blocked = BlockPos.ZERO
        val pending = mutableListOf(
            MoverPlanFrontierTarget(blocked, Blocks.STONE.defaultBlockState()),
        )
        for (radius in 2..3) {
            for (deltaX in -radius..radius) {
                for (deltaZ in -radius..radius) {
                    if (maxOf(abs(deltaX), abs(deltaZ)) != radius) continue
                    pending += MoverPlanFrontierTarget(
                        BlockPos(deltaX, 0, deltaZ),
                        Blocks.STONE.defaultBlockState(),
                    )
                }
            }
        }
        val frontier = MoverPlanFrontier(
            waitingForReach = pending,
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )
        val fixture = Fixture()
        val start = Vec3(0.5, 0.05, 0.5)

        startPlanCruise(fixture, frontier, playerPos = start)
        val evacuationTarget = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(0.5, 1.05, 0.5), evacuationTarget)

        val stillBlocked = Vec3(0.5, 0.57, 0.5)
        val continueAscent = fixture.core.tick(
            fixture.context(
                playerPos = stillBlocked,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(evacuationTarget, fixture.core.status().target)
        assertEquals(1, continueAscent.vertical)

        val cleared = Vec3(0.5, 1.02, 0.5)
        val arrived = fixture.core.tick(
            fixture.context(
                playerPos = cleared,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertStopped(arrived)
    }

    @Test
    public fun planModeVerticalEvacuationClearsExpectedFenceShapeBelowFeet(): Unit {
        val playerPos = Vec3(0.5, 0.2, 0.5)
        val blockedFence = BlockPos.ZERO
        val pending = mutableListOf(
            MoverPlanFrontierTarget(blockedFence, Blocks.OAK_FENCE.defaultBlockState()),
        )
        for (radius in 2..3) {
            for (deltaX in -radius..radius) {
                for (deltaZ in -radius..radius) {
                    if (maxOf(abs(deltaX), abs(deltaZ)) != radius) continue
                    pending += MoverPlanFrontierTarget(
                        BlockPos(deltaX, 0, deltaZ),
                        Blocks.STONE.defaultBlockState(),
                    )
                }
            }
        }
        val frontier = MoverPlanFrontier(
            waitingForReach = pending,
            columnBlocked = listOf(blockedFence),
            inFlight = emptyList(),
        )
        val fixture = Fixture()
        val bounds = BlockPos(-10, -10, -10) to BlockPos(10, 10, 10)

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerPos,
            planStateAt = OPEN_AIR_STATE_AT,
            planTravelBounds = bounds,
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(0.5, 2.05, 0.5), fixture.core.status().target)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeEvacuationRingExitOnlyThroughAClosedDoorUsesTheCollisionProfile(): Unit {
        val doorLower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
        val doorUpper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val doorCell = BlockPos(2, 1, 0)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            when {
                pos == doorCell -> doorLower
                pos == doorCell.above() -> doorUpper
                (pos.y == 1 || pos.y == 2) && maxOf(abs(pos.x), abs(pos.z)) in 2..3 -> stone
                else -> air
            }
        }
        val fixture = Fixture(isPassableCell = { cell -> stateAt(cell) == air })
        val blocked = BlockPos(0, 1, 0)
        val playerPos = Vec3(0.5, 1.0, 0.5)
        val frontier = MoverPlanFrontier(
            waitingForReach = emptyList(),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerPos,
            planStateAt = stateAt,
            planTravelBounds = bounds,
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val target = requireNotNull(fixture.core.status().target)
        assertEquals(doorCell.x to doorCell.z, floor(target.x).toInt() to floor(target.z).toInt())
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeEmptyFrontierHoldsWithoutCompletingEvenWhenCanCompleteAllows(): Unit {
        var canCompleteChecks = 0
        val fixture = Fixture(canComplete = { canCompleteChecks++; true })
        val frontier = MoverPlanFrontier(emptyList(), emptyList(), emptyList())

        startPlanCruise(fixture, frontier)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(3) {
            fixture.core.tick(
                fixture.context(onGround = false, flying = true, planMode = true, planFrontier = frontier),
            )
            assertEquals(MoverState.HOLD, fixture.core.status().state)
        }
        assertEquals(0, canCompleteChecks)
    }

    @Test
    public fun planModeEmptyFrontierCompletesOnlyOnceSessionFinalHoldsAcrossBothConfirmationPhases(): Unit {
        val fixture = Fixture()
        val frontier = MoverPlanFrontier(emptyList(), emptyList(), emptyList())

        startPlanCruise(fixture, frontier)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(3) {
            fixture.core.tick(
                fixture.context(onGround = false, flying = true, planMode = true, planFrontier = frontier),
            )
            assertEquals(MoverState.HOLD, fixture.core.status().state)
        }

        fixture.core.tick(
            fixture.context(
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(onGround = false, flying = true, planMode = true, planFrontier = frontier),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun planModeIntentionalEmptyFrontierHoldNeverTriggersTrappedWatchdog(): Unit {
        val fixture = Fixture()
        val frontier = MoverPlanFrontier(emptyList(), emptyList(), emptyList())
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(120) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                ),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeFlagFlipOffOnTrappedThresholdTickRebuildsV3RouteInsteadOfAborting(): Unit {
        val v3Target = BlockPos(50, 0, 0)
        val fixture = Fixture(missingWorld = listOf(v3Target))
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, planMode = true, planFrontier = frontier, reach = 0.0,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, planMode = true, planFrontier = frontier, reach = 0.0,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                reach = 0.0,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    reach = 0.0,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val command = fixture.core.tick(
            fixture.context(playerPos = playerPos, onGround = false, flying = true, planMode = false),
        )

        assertNull(fixture.core.status().abortReason)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(52.0, fixture.core.status().target?.x)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeStallTwiceBansStandAndRetriesSameHeadNextCandidate(): Unit {
        val stalling = BlockPos.ZERO
        val reachable = BlockPos(100, 0, 0)
        val fixture = Fixture()
        val frontier = frontierOf(stalling, reachable)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)

        repeat(99) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                ),
            )
        }
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
        assertEquals(2, fixture.core.pathTelemetry().aStarInvocations)

        repeat(100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                ),
            )
        }

        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeStallOnOnlyPositionRetriesNextCandidateNotHold(): Unit {
        val only = BlockPos.ZERO
        val fixture = Fixture()
        val frontier = frontierOf(only)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        repeat(99) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                ),
            )
        }
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        repeat(100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                ),
            )
        }

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeBannedStandIsFirstChoiceAgainAfterAbortAndRestart(): Unit {
        val stalling = BlockPos.ZERO
        val reachable = BlockPos(100, 0, 0)
        val fixture = Fixture()
        val frontier = frontierOf(stalling, reachable)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        repeat(99) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
            ),
        )
        repeat(100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)

        fixture.core.tick(fixture.context(manualInput = true))
        assertEquals(MoverState.ABORTED, fixture.core.status().state)

        val resumed = startPlanCruise(fixture, frontier, playerPos = playerPos)

        assertFalse(resumed.stopMovement)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeThirdFlightLossBansStandCellAndSelectsNextCandidate(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        fun cyclePlanFlight(): Unit {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
                ),
            )
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = false, planMode = true, planFrontier = frontier,
                ),
            )
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        repeat(2) { cyclePlanFlight() }

        val skipped = fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
            ),
        )
        assertStopped(skipped)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeExpiredBanCellBecomesSelectableAgain(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        fun cyclePlanFlight(): Unit {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
                ),
            )
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = false, planMode = true, planFrontier = frontier,
                ),
            )
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        repeat(2) { cyclePlanFlight() }
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
            ),
        )
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)

        repeat(320) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }

        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeCorrectionsFarApartOnSameWorkPositionDoNotCompoundTowardBan(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        repeat(5000) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }

        val farLaterCorrection = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                correctionReceived = true,
            ),
        )

        assertNull(fixture.core.status().abortReason)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertFalse(farLaterCorrection.stopMovement)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeEvacuationSkipsBannedNearestRingCellAndPicksNextCandidate(): Unit {
        val blocked = BlockPos(-10, 1, 0)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val fixture = Fixture()
        val frontier = MoverPlanFrontier(
            waitingForReach = emptyList(),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        val firstTarget = requireNotNull(fixture.core.status().target)
        assertEquals(-12 to 0, floor(firstTarget.x).toInt() to floor(firstTarget.z).toInt())

        fun cyclePlanFlight(): Unit {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
                ),
            )
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = false, planMode = true, planFrontier = frontier,
                ),
            )
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        repeat(2) { cyclePlanFlight() }

        val skipped = fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
            ),
        )
        assertStopped(skipped)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val nextTarget = requireNotNull(fixture.core.status().target)
        assertNotEquals(-12 to 0, floor(nextTarget.x).toInt() to floor(nextTarget.z).toInt())

        val retryTakeoff = fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, flying = false, planMode = true, planFrontier = frontier,
            ),
        )
        assertTrue(retryTakeoff.jump)
        assertEquals(MoverState.TAKEOFF, fixture.core.status().state)
        assertEquals(nextTarget, fixture.core.status().target)
    }

    @Test
    public fun planModeFlagFlipOffRebuildsV3RouteAndIgnoresFrontier(): Unit {
        val v3Target = BlockPos(50, 0, 0)
        val planTarget = BlockPos.ZERO
        val fixture = Fixture(missingWorld = listOf(v3Target))
        val frontier = frontierOf(planTarget)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)

        val command = fixture.core.tick(
            fixture.context(playerPos = playerPos, onGround = false, flying = true, planMode = false),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(52.0, 1.4, 0.0), fixture.core.status().target)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeFlagFlipOnRebuildsPlanRouteAndIgnoresV3Missing(): Unit {
        val v3Target = BlockPos(50, 0, 0)
        val planTarget = BlockPos.ZERO
        val fixture = Fixture(missingWorld = listOf(v3Target))
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startCruise(fixture, playerPos = playerPos)
        assertEquals(Vec3(52.0, 1.4, 0.0), fixture.core.status().target)

        val frontier = frontierOf(planTarget)
        val command = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeArrivalHoldReleasesOnlyWhenTheWaitingSetChanges(): Unit {
        val first = BlockPos.ZERO
        val second = BlockPos(100, 0, 0)
        val fixture = Fixture()
        val frontierA = frontierOf(first)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontierA, playerPos = playerPos)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = frontierA,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(50) {
            fixture.core.tick(
                fixture.context(
                    playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = frontierA,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(target, fixture.core.status().target)

        val frontierB = frontierOf(second)
        val released = fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertStopped(released)

        val rebuilt = fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(102.5, 1.05, 0.5), fixture.core.status().target)
        assertFalse(rebuilt.stopMovement)
    }

    @Test
    public fun planModeArrivalHoldReleasesWhenColumnBlockedChangesWithWaitingSetUnchanged(): Unit {
        val first = BlockPos.ZERO
        val blockedColumn = BlockPos(2, 20, 0)
        val fixture = Fixture()
        val frontierA = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(first, Blocks.STONE.defaultBlockState())),
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontierA, playerPos = playerPos)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = frontierA,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val frontierB = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(first, Blocks.STONE.defaultBlockState())),
            columnBlocked = listOf(blockedColumn),
            inFlight = emptyList(),
        )
        val released = fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertStopped(released)
    }

    @Test
    public fun planModeArrivalHoldReleasesWhenLoosePoseOverlapsAdjacentBlockedColumn(): Unit {
        val head = BlockPos.ZERO
        val fixture = Fixture()
        val clear = frontierOf(head)

        startPlanCruise(fixture, clear)
        val stand = requireNotNull(fixture.core.status().target)
        val looseArrival = stand.add(0.0, 0.0, 0.29)
        fixture.core.tick(
            fixture.context(
                playerPos = looseArrival,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = clear,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val adjacentBlocked = BlockPos(floor(looseArrival.x).toInt(), floor(looseArrival.y).toInt(), 1)
        val blocked = MoverPlanFrontier(
            waitingForReach = clear.waitingForReach,
            columnBlocked = listOf(adjacentBlocked),
            inFlight = emptyList(),
        )
        val released = fixture.core.tick(
            fixture.context(
                playerPos = looseArrival,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = blocked,
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertStopped(released)
    }

    @Test
    public fun planModeArrivalHoldReleasesWhenFenceBelowLivePoseBlocksPlacement(): Unit {
        val fence = BlockPos.ZERO
        val fenceTarget = MoverPlanFrontierTarget(fence, Blocks.OAK_FENCE.defaultBlockState())
        val clear = MoverPlanFrontier(
            waitingForReach = listOf(fenceTarget),
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
        val fixture = Fixture()

        startPlanCruise(fixture, clear)
        val stand = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = stand,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = clear,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val aboveFenceEdge = Vec3(0.8, 1.13, 0.5)
        val blocked = MoverPlanFrontier(
            waitingForReach = listOf(fenceTarget),
            columnBlocked = listOf(fence),
            inFlight = emptyList(),
        )
        val released = fixture.core.tick(
            fixture.context(
                playerPos = aboveFenceEdge,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = blocked,
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertStopped(released)
    }

    @Test
    public fun planModeArrivalHoldAbandonsStandWhenLivePoseCannotReachCoveredHead(): Unit {
        val head = BlockPos.ZERO
        val support = head.below()
        var unproductiveCalls = 0
        val fixture = Fixture(
            onWorkPositionUnproductive = { covered, _ ->
                assertTrue(head in covered)
                unproductiveCalls++
            },
        )
        val frontier = frontierOf(head)
        val supportStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == support) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        startPlanCruise(fixture, frontier, planSupportStateAt = supportStateAt)
        val stand = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = stand,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planSupportStateAt = supportStateAt,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val correctedPose = stand.add(8.0, 0.0, 0.0)
        fixture.core.tick(
            fixture.context(
                playerPos = correctedPose,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planSupportStateAt = supportStateAt,
            ),
        )

        assertEquals(1, unproductiveCalls)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertNotEquals(stand, fixture.core.status().target)
    }

    @Test
    public fun planModeWorkPositionHysteresisHoldsFirstStandDespiteAlternatingIrrelevantColumnBlocks(): Unit {
        val target = BlockPos.ZERO
        val fixture = Fixture()
        val clear = frontierOf(target)
        val unrelatedColumnBlocked = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(target, Blocks.STONE.defaultBlockState())),
            columnBlocked = listOf(BlockPos(10, 20, 10)),
            inFlight = emptyList(),
        )

        startPlanCruise(fixture, clear)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val firstTarget = requireNotNull(fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget, onGround = false, flying = true, planMode = true, planFrontier = clear,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(firstTarget, fixture.core.status().target)

        repeat(20) { index ->
            val frontier = if (index % 2 == 0) unrelatedColumnBlocked else clear
            fixture.core.tick(
                fixture.context(
                    playerPos = firstTarget,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                ),
            )
            assertEquals(MoverState.HOLD, fixture.core.status().state, "tick $index")
            assertEquals(
                firstTarget,
                fixture.core.status().target,
                "the first selection must be held across an irrelevant columnBlocked alternation (tick $index)",
            )
        }
    }

    @Test
    public fun planModeArrivalHoldReleasesWhenTheFrontierHeadLeavesStandCoverage(): Unit {
        val a = BlockPos(10, 0, 0)
        val b = BlockPos(11, 0, 0)
        val d = BlockPos(12, 0, 0)
        val c = BlockPos(90, 0, 0)
        val fixture = Fixture()
        val initialFrontier = frontierOf(a, b, d)

        startPlanCruise(fixture, initialFrontier, reach = 8.0)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true,
                planFrontier = initialFrontier, reach = 8.0,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val advancedFrontier = MoverPlanFrontier(
            waitingForReach = listOf(
                MoverPlanFrontierTarget(c, Blocks.STONE.defaultBlockState()),
                MoverPlanFrontierTarget(d, Blocks.STONE.defaultBlockState()),
            ),
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
        val released = fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true,
                planFrontier = advancedFrontier, reach = 8.0,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertStopped(released)

        val rebuilt = fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true,
                planFrontier = advancedFrontier, reach = 8.0,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(92.5, 1.05, 0.5), fixture.core.status().target)
        assertFalse(rebuilt.stopMovement)
    }

    @Test
    public fun planModeArrivalHoldExtendsCurrentStandWhenNewHeadGainsReachableSupport(): Unit {
        val first = BlockPos.ZERO
        val dependent = BlockPos(1, 0, 0)
        val firstSupport = first.below()
        val initialStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == firstSupport) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val afterFirstStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == firstSupport || pos == first) {
                Blocks.STONE.defaultBlockState()
            } else {
                Blocks.AIR.defaultBlockState()
            }
        }
        val initialFrontier = frontierOf(first, dependent)
        val dependentFrontier = frontierOf(dependent)
        val fixture = Fixture()

        startPlanCruise(
            fixture,
            initialFrontier,
            planSupportStateAt = initialStateAt,
        )
        val stand = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = stand,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = initialFrontier,
                planSupportStateAt = initialStateAt,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        val buildsBefore = fixture.core.pathTelemetry().routeBuilds

        fixture.core.tick(
            fixture.context(
                playerPos = stand,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                planMode = true,
                planFrontier = dependentFrontier,
                planSupportStateAt = afterFirstStateAt,
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(stand, fixture.core.status().target)
        assertEquals(buildsBefore, fixture.core.pathTelemetry().routeBuilds)
    }

    @Test
    public fun planModeArrivalHoldUsesPlannedStandLayerAtLooseVerticalArrival(): Unit {
        val first = BlockPos.ZERO
        val nextLayer = first.above()
        val firstSupport = first.below()
        val initialStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == firstSupport) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val afterFirstStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == firstSupport || pos == first) {
                Blocks.STONE.defaultBlockState()
            } else {
                Blocks.AIR.defaultBlockState()
            }
        }
        val fixture = Fixture()
        val initialFrontier = frontierOf(first, nextLayer)

        startPlanCruise(fixture, initialFrontier, planSupportStateAt = initialStateAt)
        val stand = requireNotNull(fixture.core.status().target)
        assertEquals(nextLayer.y, floor(stand.y).toInt())
        val looseArrival = stand.add(0.0, -0.17, 0.0)
        assertTrue(floor(looseArrival.y).toInt() < nextLayer.y)
        fixture.core.tick(
            fixture.context(
                playerPos = looseArrival,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = initialFrontier,
                planSupportStateAt = initialStateAt,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        val buildsBefore = fixture.core.pathTelemetry().routeBuilds

        fixture.core.tick(
            fixture.context(
                playerPos = looseArrival,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                planMode = true,
                planFrontier = frontierOf(nextLayer),
                planSupportStateAt = afterFirstStateAt,
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(stand, fixture.core.status().target)
        assertEquals(buildsBefore, fixture.core.pathTelemetry().routeBuilds)
    }

    @Test
    public fun planModeArrivalHoldDoesNotExtendCurrentStandToHigherHead(): Unit {
        val first = BlockPos.ZERO
        val higher = first.above()
        val firstSupport = first.below()
        val initialStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == firstSupport) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val afterFirstStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == firstSupport || pos == first) {
                Blocks.STONE.defaultBlockState()
            } else {
                Blocks.AIR.defaultBlockState()
            }
        }
        val blockedRaisedCandidates = setOf(
            BlockPos(2, 1, 0),
            BlockPos(-2, 1, 0),
            BlockPos(0, 1, 2),
            BlockPos(0, 1, -2),
            BlockPos(0, 3, 0),
        )
        val sameLayerOnlyStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos in blockedRaisedCandidates) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val fixture = Fixture()
        val initialFrontier = frontierOf(first, higher)

        startPlanCruise(
            fixture,
            initialFrontier,
            planStateAt = sameLayerOnlyStateAt,
            planSupportStateAt = initialStateAt,
        )
        var stand = requireNotNull(fixture.core.status().target)
        var waypointSteps = 0
        while (fixture.core.status().state == MoverState.CRUISE && waypointSteps++ < 100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = stand,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = initialFrontier,
                    planStateAt = sameLayerOnlyStateAt,
                    planSupportStateAt = initialStateAt,
                ),
            )
            fixture.core.status().target?.let { target -> stand = target }
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertTrue(floor(stand.y).toInt() < higher.y)

        fixture.core.tick(
            fixture.context(
                playerPos = stand,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                planMode = true,
                planFrontier = frontierOf(higher),
                planStateAt = sameLayerOnlyStateAt,
                planSupportStateAt = afterFirstStateAt,
            ),
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
    }

    @Test
    public fun planModeArrivalHoldKeepsHoldingWhenTheFrontierHeadIsStillCovered(): Unit {
        val a = BlockPos(10, 0, 0)
        val b = BlockPos(11, 0, 0)
        val d = BlockPos(12, 0, 0)
        val c = BlockPos(90, 0, 0)
        val fixture = Fixture()
        val initialFrontier = frontierOf(a, b, d)

        startPlanCruise(fixture, initialFrontier, reach = 8.0)
        val target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true,
                planFrontier = initialFrontier, reach = 8.0,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val stillCoveredFrontier = MoverPlanFrontier(
            waitingForReach = listOf(
                MoverPlanFrontierTarget(d, Blocks.STONE.defaultBlockState()),
                MoverPlanFrontierTarget(c, Blocks.STONE.defaultBlockState()),
            ),
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
        repeat(30) { index ->
            fixture.core.tick(
                fixture.context(
                    playerPos = target, onGround = false, flying = true, planMode = true,
                    planFrontier = stillCoveredFrontier, reach = 8.0,
                ),
            )
            assertEquals(MoverState.HOLD, fixture.core.status().state, "tick $index")
            assertEquals(target, fixture.core.status().target, "tick $index")
        }
    }

    @Test
    public fun planModeArrivalHoldAdvancesInPlaceWhenALaterRouteEntryCoversTheNewHead(): Unit {
        val a = BlockPos(10, 0, 0)
        val b = BlockPos(90, 0, 0)
        val fixture = Fixture()
        val initialFrontier = frontierOf(a, b)

        startPlanCruise(fixture, initialFrontier)
        val firstTarget = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = firstTarget, onGround = false, flying = true, planMode = true,
                planFrontier = initialFrontier,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val advancedFrontier = frontierOf(b)
        val released = fixture.core.tick(
            fixture.context(
                playerPos = firstTarget, onGround = false, flying = true, planMode = true,
                planFrontier = advancedFrontier,
            ),
        )

        assertStopped(released)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertNotEquals(null, fixture.core.status().target)
    }

    @Test
    public fun planModeArrivalHoldAdvancesInPlaceThroughAWholeChainOfRouteEntriesWithoutRebuilding(): Unit {
        val a = BlockPos(0, 0, 0)
        val b = BlockPos(15, 0, 0)
        val c = BlockPos(30, 0, 0)
        val d = BlockPos(45, 0, 0)
        val fixture = Fixture()
        val fullFrontier = frontierOf(a, b, c, d)

        startPlanCruise(fixture, fullFrontier)
        var target = requireNotNull(fixture.core.status().target)
        fixture.core.tick(
            fixture.context(
                playerPos = target, onGround = false, flying = true, planMode = true, planFrontier = fullFrontier,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        for (remainingFrontier in listOf(frontierOf(b, c, d), frontierOf(c, d), frontierOf(d))) {
            val released = fixture.core.tick(
                fixture.context(
                    playerPos = target, onGround = false, flying = true, planMode = true,
                    planFrontier = remainingFrontier,
                ),
            )
            assertStopped(released)
            assertEquals(MoverState.CRUISE, fixture.core.status().state)
            target = requireNotNull(fixture.core.status().target)
            fixture.core.tick(
                fixture.context(
                    playerPos = target, onGround = false, flying = true, planMode = true,
                    planFrontier = remainingFrontier,
                ),
            )
            assertEquals(MoverState.HOLD, fixture.core.status().state)
        }
    }

    @Test
    public fun planModeUnroutableNonEmptyFrontierNeverCompletesEvenWithSessionFinalTrue(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = true,
                planMode = true,
                planFrontier = frontier,
                reach = 0.0,
                planSessionFinal = true,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                planMode = true,
                planFrontier = frontier,
                reach = 0.0,
                planSessionFinal = true,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                reach = 0.0,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(5) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    reach = 0.0,
                    planSessionFinal = true,
                ),
            )
            assertEquals(MoverState.HOLD, fixture.core.status().state)
        }
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeLiveStandSearchReportsAnUncoverableStrictHeadOnce(): Unit {
        val target = BlockPos.ZERO
        val reported = mutableListOf<BlockPos>()
        val fixture = Fixture(onPlanPositionUnreachable = reported::add)
        val frontier = frontierOf(target)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        startPlanCruise(
            fixture = fixture,
            frontier = frontier,
            reach = 0.0,
            planStateAt = stateAt,
            planSupportStateAt = stateAt,
        )

        assertEquals(listOf(target), reported)
        fixture.core.tick(
            fixture.context(
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                reach = 0.0,
                planStateAt = stateAt,
                planSupportStateAt = stateAt,
            ),
        )
        assertEquals(listOf(target), reported)
    }

    @Test
    public fun planModeSuccessfulRouteBuildClearsStaleArmBeforeALaterEmptyEpisode(): Unit {
        val target = BlockPos.ZERO
        val fixture = Fixture()
        val emptyFrontier = MoverPlanFrontier(emptyList(), emptyList(), emptyList())
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, emptyFrontier, playerPos = playerPos, planSessionFinal = true)
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val standTarget = Vec3(2.5, 1.05, 0.5)
        fixture.core.tick(
            fixture.context(
                playerPos = standTarget,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontierOf(target),
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(standTarget, fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = standTarget,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )

        fixture.core.tick(
            fixture.context(
                playerPos = standTarget,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = standTarget,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun planModeActiveEvacuationRouteStillRunsCruiseStallWatchdogWhenFrontierIsEmpty(): Unit {
        val blocked = BlockPos(-10, 1, 0)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val fixture = Fixture()
        val frontier = MoverPlanFrontier(
            waitingForReach = emptyList(),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )

        startPlanCruise(fixture, frontier, playerPos = playerPos)
        val evacTarget = requireNotNull(fixture.core.status().target)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)

        repeat(99) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
                ),
            )
        }
        assertEquals(evacTarget, fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true, planFrontier = frontier,
            ),
        )
        assertEquals(evacTarget, fixture.core.status().target)
        assertEquals(2, fixture.core.pathTelemetry().aStarInvocations)
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeCruiseReplansImmediatelyWhenFrontierChangesBeforeArrival(): Unit {
        val first = BlockPos.ZERO
        val second = BlockPos(100, 0, 0)
        val fixture = Fixture()
        val frontierA = frontierOf(first)
        val frontierB = frontierOf(second)
        val start = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontierA, playerPos = start)
        val standA = requireNotNull(fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = start, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val standB = requireNotNull(fixture.core.status().target)
        assertNotEquals(standA, standB)

        fixture.core.tick(
            fixture.context(
                playerPos = standB, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(standB, fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = standB, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(standB, fixture.core.status().target)
    }

    @Test
    public fun planModeCruiseReplansWhenFrontierHeadChangesButPositionSetDoesNot(): Unit {
        val first = BlockPos.ZERO
        val second = BlockPos(100, 0, 0)
        val fixture = Fixture()
        val initial = frontierOf(first, second)
        val reordered = frontierOf(second, first)
        val start = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, initial, playerPos = start)
        val firstStand = requireNotNull(fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = start,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = reordered,
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val secondStand = requireNotNull(fixture.core.status().target)
        assertNotEquals(firstStand, secondStand)
        assertTrue(secondStand.x > 90.0) { "expected a stand for the new head, was $secondStand" }
    }

    @Test
    public fun planModeCruiseCompletesWhenFrontierDrainsWithoutQueueRevisionChange(): Unit {
        val fixture = Fixture()
        val activeFrontier = frontierOf(BlockPos.ZERO)
        val emptyFrontier = MoverPlanFrontier(emptyList(), emptyList(), emptyList())
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, activeFrontier, playerPos = playerPos)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = emptyFrontier,
                planSessionFinal = true,
            ),
        )
        assertEquals(MoverState.COMPLETE, fixture.core.status().state)
    }

    @Test
    public fun planModeFrontierChangeReleaseNeverInvokesUnproductiveCallback(): Unit {
        val first = BlockPos.ZERO
        val second = BlockPos(100, 0, 0)
        val unproductiveCalls = mutableListOf<Pair<Set<BlockPos>, Set<BlockPos>>>()
        val fixture = Fixture(
            onWorkPositionUnproductive = { covered, defer -> unproductiveCalls.add(covered to defer) },
        )
        val frontierA = frontierOf(first)
        val frontierB = frontierOf(second)
        val start = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontierA, playerPos = start)
        val standA = requireNotNull(fixture.core.status().target)

        fixture.core.tick(
            fixture.context(
                playerPos = standA, onGround = false, flying = true, planMode = true, planFrontier = frontierA,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = standA, onGround = false, flying = true, planMode = true, planFrontier = frontierB,
            ),
        )

        assertEquals(emptyList<Pair<Set<BlockPos>, Set<BlockPos>>>(), unproductiveCalls)
    }

    @Test
    public fun planModeFlipWithEmptyFirstBuildDoesNotAbortOnTheOutgoingModesTrappedHistory(): Unit {
        val fixture = Fixture(isPlaceable = { false }, canComplete = { false })
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, planMode = true, planFrontier = frontier, reach = 0.0,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, planMode = true, planFrontier = frontier, reach = 0.0,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                reach = 0.0,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    reach = 0.0,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)

        fixture.core.tick(
            fixture.context(playerPos = playerPos, onGround = false, flying = true, planMode = false),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)

        fixture.core.tick(
            fixture.context(playerPos = playerPos, onGround = false, flying = true, planMode = false),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeColumnBlockedWithNoRoutableEvacuationRunsTrappedWatchdog(): Unit {
        val blocked = BlockPos(-10, 1, 0)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val stone = Blocks.STONE.defaultBlockState()
        val fixture = Fixture(isPassableCell = { false })
        val frontier = MoverPlanFrontier(
            waitingForReach = emptyList(),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )

        startPlanCruise(fixture, frontier, playerPos = playerPos, planStateAt = { stone })
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)

        repeat(100) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    planStateAt = { stone },
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val trapped = fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planStateAt = { stone },
            ),
        )

        assertAbortStatus(fixture.core, MoverAbortReason.TRAPPED)
        assertStopped(trapped)
    }

    @Test
    public fun planModeFrontierChangeReleaseDuringMultiLegRouteRebuildsFreshInsteadOfAdvancingStaleLeg(): Unit {
        val first = BlockPos.ZERO
        val second = BlockPos(100, 0, 0)
        val third = BlockPos(50, 0, 0)
        val fixture = Fixture()
        val twoLegFrontier = frontierOf(first, second)
        val changedFrontier = frontierOf(third)

        startPlanCruise(fixture, twoLegFrontier)
        val firstLegTarget = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(2.5, 1.05, 0.5), firstLegTarget)

        fixture.core.tick(
            fixture.context(
                playerPos = firstLegTarget, onGround = false, flying = true, planMode = true,
                planFrontier = twoLegFrontier,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        val released = fixture.core.tick(
            fixture.context(
                playerPos = firstLegTarget, onGround = false, flying = true, planMode = true,
                planFrontier = changedFrontier,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertStopped(released)

        val rebuilt = fixture.core.tick(
            fixture.context(
                playerPos = firstLegTarget, onGround = false, flying = true, planMode = true,
                planFrontier = changedFrontier,
            ),
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val standC = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(52.5, 1.05, 0.5), standC)
        assertFalse(rebuilt.stopMovement)

        fixture.core.tick(
            fixture.context(
                playerPos = standC, onGround = false, flying = true, planMode = true, planFrontier = changedFrontier,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(50) {
            fixture.core.tick(
                fixture.context(
                    playerPos = standC, onGround = false, flying = true, planMode = true,
                    planFrontier = changedFrontier,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(standC, fixture.core.status().target)
    }

    @Test
    public fun planModeTickSetsRemainingMissingFromFrontierSizes(): Unit {
        val fixture = Fixture()
        val busyFrontier = MoverPlanFrontier(
            waitingForReach = frontierOf(BlockPos.ZERO, BlockPos(100, 0, 0)).waitingForReach,
            columnBlocked = listOf(BlockPos(5, 0, 5)),
            inFlight = emptyList(),
        )
        val emptyFrontierState = MoverPlanFrontier(emptyList(), emptyList(), emptyList())

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(fixture.context(onGround = true, planMode = true, planFrontier = busyFrontier))
        assertEquals(3, fixture.core.status().remainingMissing)

        fixture.core.tick(fixture.context(onGround = true, planMode = true, planFrontier = emptyFrontierState))
        assertEquals(0, fixture.core.status().remainingMissing)
    }

    @Test
    public fun planModeRemainingMissingUsesFrontierTotalRemainingActionsField(): Unit {
        val fixture = Fixture()
        val frontierWithDivergentTotals = MoverPlanFrontier(
            waitingForReach = frontierOf(BlockPos.ZERO).waitingForReach,
            columnBlocked = listOf(BlockPos(5, 0, 5)),
            inFlight = emptyList(),
            totalWaitingForReach = 500,
            totalColumnBlocked = 7,
            totalRemainingActions = 812,
        )

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(onGround = true, planMode = true, planFrontier = frontierWithDivergentTotals),
        )

        assertEquals(812, fixture.core.status().remainingMissing)
    }

    @Test
    public fun nearestPlanFrontierTargetsCapsToTheNearestMaxCountTargets(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val near = MoverPlanFrontierTarget(BlockPos(1, 0, 0), stone)
        val middle = MoverPlanFrontierTarget(BlockPos(5, 0, 0), stone)
        val far = MoverPlanFrontierTarget(BlockPos(100, 0, 0), stone)

        val nearest = nearestPlanFrontierTargets(
            targets = listOf(far, near, middle),
            playerPosition = Vec3(0.5, 0.5, 0.5),
            maxCount = 2,
        )

        assertEquals(listOf(near, middle), nearest)
    }

    @Test
    public fun nearestPlanFrontierTargetsBreaksDistanceTiesByPositionCompare(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val plusX = MoverPlanFrontierTarget(BlockPos(1, 0, 0), stone)
        val minusX = MoverPlanFrontierTarget(BlockPos(-1, 0, 0), stone)

        val nearest = nearestPlanFrontierTargets(
            targets = listOf(plusX, minusX),
            playerPosition = Vec3(0.5, 0.5, 0.5),
            maxCount = 2,
        )

        assertEquals(listOf(minusX, plusX), nearest)
    }

    @Test
    public fun nearestPlanFrontierSnapshotTargetsCapsConversionCountToMaxCount(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val maxCount = 32
        val targets = (0 until 40).map { index -> PlanFrontierTarget(BlockPos(index, 0, 0), stone) }

        val nearest = nearestPlanFrontierSnapshotTargets(
            targets = targets,
            playerPosition = Vec3(0.5, 0.5, 0.5),
            maxCount = maxCount,
        )

        assertEquals(maxCount, nearest.size)
        assertEquals(
            (0 until maxCount).map { index ->
                MoverPlanFrontierTarget(BlockPos(index, 0, 0), stone)
            },
            nearest,
        )
    }

    @Test
    public fun nearestPlanFrontierSnapshotTargetsConvertsEveryEntryBelowMaxCount(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val targets = listOf(
            PlanFrontierTarget(BlockPos(1, 0, 0), stone),
            PlanFrontierTarget(BlockPos(2, 0, 0), stone),
        )

        val nearest = nearestPlanFrontierSnapshotTargets(
            targets = targets,
            playerPosition = Vec3(0.5, 0.5, 0.5),
            maxCount = 32,
        )

        assertEquals(
            listOf(
                MoverPlanFrontierTarget(BlockPos(1, 0, 0), stone),
                MoverPlanFrontierTarget(BlockPos(2, 0, 0), stone),
            ),
            nearest,
        )
    }

    @Test
    public fun planModeEmptyRouteRebuildIsGatedUntilWorkingSetChangesOrTicksElapse(): Unit {
        val target = BlockPos.ZERO
        val fixture = Fixture()
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, planMode = true,
                planFrontier = frontierOf(target), reach = 0.1,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, planMode = true,
                planFrontier = frontierOf(target), reach = 0.1,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true,
                planFrontier = frontierOf(target), reach = 0.1,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true,
                planFrontier = frontierOf(target), reach = 100.0,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(15) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true,
                    planFrontier = frontierOf(target), reach = 100.0,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(10) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos, onGround = false, flying = true, planMode = true,
                    planFrontier = frontierOf(target), reach = 100.0,
                ),
            )
        }
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
    }

    @Test
    public fun planModeEmptyRouteRebuildReattemptsImmediatelyWhenWorkingSetChanges(): Unit {
        val staleTarget = BlockPos.ZERO
        val freshTarget = BlockPos(50, 0, 0)
        val fixture = Fixture()
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = true, planMode = true,
                planFrontier = frontierOf(staleTarget), reach = 0.1,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, planMode = true,
                planFrontier = frontierOf(staleTarget), reach = 0.1,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true,
                planFrontier = frontierOf(staleTarget), reach = 0.1,
            ),
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        fixture.core.tick(
            fixture.context(
                playerPos = playerPos, onGround = false, flying = true, planMode = true,
                planFrontier = frontierOf(freshTarget), reach = 100.0,
            ),
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
    }

    @Test
    public fun planLegAcrossScriptedWallFollowsAStarDetourNotStraightLine(): Unit {
        val wallCells = HashSet<BlockPos>()
        for (y in -5..20) {
            for (z in -2..2) {
                wallCells.add(BlockPos(4, y, z))
            }
        }
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos in wallCells) stone else air }
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val probedTargets = mutableListOf<Vec3>()
        val probe = wallProbe(wallX = 4, minWallZ = -2, maxWallZ = 2, probedTargets = probedTargets)
        val playerStart = Vec3(0.5, 5.0, 0.5)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = probe,
            planStateAt = stateAt,
            planTravelBounds = bounds,
        )
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(1, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(1, fixture.core.pathTelemetry().aStarSuccesses)

        repeat(30) {
            if (fixture.core.status().state != MoverState.CRUISE) return@repeat
            val target = requireNotNull(fixture.core.status().target)
            fixture.core.tick(
                fixture.context(
                    playerPos = target,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    pathProbe = probe,
                    planStateAt = stateAt,
                    planTravelBounds = bounds,
                ),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertTrue(
            probedTargets.any { target -> target.z < -2.0 || target.z > 3.0 },
            "expected the A*-derived path to detour through an open column, not a straight line",
        )
    }

    @Test
    public fun scriptedAStarFailureWithinTightBoundsDoesNotEmitBlindOverTheTopMovement(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val wallCells = HashSet<BlockPos>()
        for (x in -2..14) {
            for (y in -6..6) {
                wallCells.add(BlockPos(x, y, 5))
            }
        }
        for (y in -6..6) wallCells.remove(BlockPos(-3, y, 5))
        val strictTarget = BlockPos(2, 0, 8)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos in wallCells || pos == strictTarget.below()) stone else air
        }
        val reportedUnreachable = mutableListOf<BlockPos>()
        val fixture = Fixture(onPlanPositionUnreachable = reportedUnreachable::add)
        val frontier = frontierOf(strictTarget)
        val playerStart = Vec3(0.5, 0.4, 2.5)
        val tightBounds = BlockPos(0, 0, 0) to BlockPos(4, 2, 10)

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = stateAt,
            planSupportStateAt = stateAt,
            planTravelBounds = tightBounds,
        )

        val telemetry = fixture.core.pathTelemetry()
        assertTrue(telemetry.aStarInvocations >= 1)
        assertEquals(0, telemetry.aStarSuccesses)
        assertTrue(telemetry.aStarFailNoPath >= 1)
        assertStopped(command)
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().target)
        assertTrue(
            reportedUnreachable.isEmpty(),
            "movement-candidate bans are not proof that the supported target itself is unreachable",
        )
    }

    @Test
    public fun planLegWithHugeGlobalTravelBoundsStillRunsCollisionAStarOnAShortLeg(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 0.0, 0.5)
        val hugeBounds = BlockPos(-2_000_000, -2_000_000, -2_000_000) to BlockPos(2_000_000, 2_000_000, 2_000_000)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planTravelBounds = hugeBounds,
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val telemetry = fixture.core.pathTelemetry()
        assertEquals(1, telemetry.aStarInvocations)
        assertEquals(1, telemetry.aStarSuccesses)
        assertEquals(0, telemetry.aStarFailVolumeClamped)
    }

    @Test
    public fun planModeStandNeverSelectedBelowItsOwnCoveredTargetLayer(): Unit {
        val lower = BlockPos.ZERO
        val higher = BlockPos(0, 2, 0)
        val fixture = Fixture()
        val frontier = frontierOf(lower, higher)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(fixture, frontier, planTravelBounds = bounds)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val target = requireNotNull(fixture.core.status().target)
        assertNotEquals(
            Vec3(2.5, 1.0, 0.5),
            target,
            "the naive offset (2,1,0) candidate sits a block below `higher` and must be rejected",
        )
    }

    @Test
    public fun planModeStandExactlyLevelWithCoveredTargetIsAccepted(): Unit {
        val anchor = BlockPos.ZERO
        val far = BlockPos(6, 0, 0)
        val fixture = Fixture()
        val frontier = frontierOf(anchor, far)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)
        startPlanCruise(fixture, frontier, planTravelBounds = bounds)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val target = requireNotNull(fixture.core.status().target)
        assertEquals(Vec3(3.5, 0.05, 0.5), target)
    }

    @Test
    public fun planModeLowRoofUsesCollisionSafeSameLayerSideStand(): Unit {
        val target = BlockPos.ZERO
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos.y >= 2) stone else air }
        val fixture = Fixture()
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(
            fixture,
            frontierOf(target),
            playerPos = Vec3(-10.0, 0.05, 0.5),
            planStateAt = stateAt,
            planTravelBounds = bounds,
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(3.5, 0.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeUsesNearSameLayerFallbackWhenRaisedAndRadiusThreeStandsAreBlocked(): Unit {
        val target = BlockPos.ZERO
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val desiredStand = BlockPos(-2, 0, 0)
        val blockedCandidateCells = setOf(
            BlockPos(2, 1, 0), BlockPos(-2, 1, 0), BlockPos(0, 1, 2), BlockPos(0, 1, -2),
            BlockPos(0, 3, 0), BlockPos(3, 0, 0), BlockPos(-3, 0, 0), BlockPos(0, 0, 3),
            BlockPos(0, 0, -3), BlockPos(3, 1, 0), BlockPos(-3, 1, 0), BlockPos(0, 1, 3),
            BlockPos(0, 1, -3), BlockPos(2, 1, 2), BlockPos(2, 1, -2), BlockPos(-2, 1, 2),
            BlockPos(-2, 1, -2), BlockPos(1, 2, 0), BlockPos(-1, 2, 0), BlockPos(0, 2, 1),
            BlockPos(0, 2, -1),
            BlockPos(2, 0, 0), BlockPos(0, 0, 2), BlockPos(0, 0, -2),
            BlockPos(2, 0, 2), BlockPos(2, 0, -2), BlockPos(-2, 0, 2), BlockPos(-2, 0, -2),
        )
        val blockedCollisionCells = blockedCandidateCells.mapTo(mutableSetOf()) { candidate ->
            if (candidate.y == 0) candidate else candidate.above()
        }
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.below() || pos in blockedCollisionCells) stone else air
        }
        val fixture = Fixture()
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)
        val playerAtDesiredStand = Vec3(desiredStand.x + 0.5, desiredStand.y.toDouble(), desiredStand.z + 0.5)

        startPlanCruise(
            fixture,
            frontierOf(target),
            playerPos = playerAtDesiredStand,
            planStateAt = stateAt,
            planSupportStateAt = stateAt,
            planTravelBounds = bounds,
        )

        repeat(30) {
            if (fixture.core.status().state != MoverState.CRUISE) return@repeat
            val targetWaypoint = requireNotNull(fixture.core.status().target)
            fixture.core.tick(
                fixture.context(
                    playerPos = targetWaypoint,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontierOf(target),
                    planStateAt = stateAt,
                    planSupportStateAt = stateAt,
                    planTravelBounds = bounds,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(playerAtDesiredStand, fixture.core.status().target)
    }

    @Test
    public fun planModeStandUnderOverhangRejectedByCollisionPredicateFallsToNextCandidate(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val overhang = BlockPos(2, 2, 0)
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == overhang) stone else air }
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(fixture, frontier, planStateAt = stateAt, planTravelBounds = bounds)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(-1.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeStrictlyAboveStandCoveringFenceTargetStaysAccepted(): Unit {
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == BlockPos.ZERO) fence else air }
        val fixture = Fixture()
        val frontier = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(BlockPos.ZERO, fence)),
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(fixture, frontier, planStateAt = stateAt, planTravelBounds = bounds)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        repeat(30) {
            if (fixture.core.status().state != MoverState.CRUISE) return@repeat
            val target = requireNotNull(fixture.core.status().target)
            fixture.core.tick(
                fixture.context(
                    playerPos = target,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    planStateAt = stateAt,
                    planTravelBounds = bounds,
                ),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertEquals(Vec3(2.5, 1.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeStandOnBottomSlabWaypointLandsAtItsOwnFittedHeight(): Unit {
        val bottomSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
        val air = Blocks.AIR.defaultBlockState()
        val standCell = BlockPos(2, 1, 0)
        val stateAt: (BlockPos) -> BlockState = { pos -> if (pos == standCell) bottomSlab else air }
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(fixture, frontier, planStateAt = stateAt, planTravelBounds = bounds)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(2.5, 1.55, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeCoverageScoredFromCenteredPoseNotCornerPose(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        startPlanCruise(fixture, frontier, playerPos = playerPos, reach = 2.9)

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertEquals(Vec3(2.5, 0.05, 0.5), fixture.core.status().target)
    }

    @Test
    public fun planModeAllStandsRejectedHoldsInsteadOfTrappedWithPeriodicRebuildAttempts(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.y in -5..5 && pos.x in -5..5 && pos.z in -5..5) stone else air
        }
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerPos,
            planStateAt = stateAt,
            planTravelBounds = bounds,
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)

        repeat(150) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    planStateAt = stateAt,
                    planTravelBounds = bounds,
                ),
            )
        }

        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)
    }

    @Test
    public fun planModeLatchClearsWhenFrontierMovesToADifferentUnroutableSituation(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val standRejectingStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos.y in -5..5 && pos.x in -5..5 && pos.z in -5..5) stone else air
        }
        val fixture = Fixture(isPassableCell = { false })
        val stalledStandFrontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)
        val bounds = BlockPos(-40, -40, -40) to BlockPos(40, 40, 40)

        startPlanCruise(
            fixture,
            stalledStandFrontier,
            playerPos = playerPos,
            planStateAt = standRejectingStateAt,
            planTravelBounds = bounds,
        )
        assertEquals(MoverState.HOLD, fixture.core.status().state)

        repeat(30) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = stalledStandFrontier,
                    planStateAt = standRejectingStateAt,
                    planTravelBounds = bounds,
                ),
            )
        }
        assertEquals(MoverState.HOLD, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)

        val blocked = BlockPos(-10, 1, 0)
        val columnBlockedFrontier = MoverPlanFrontier(
            waitingForReach = emptyList(),
            columnBlocked = listOf(blocked),
            inFlight = emptyList(),
        )

        repeat(150) {
            fixture.core.tick(
                fixture.context(
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = columnBlockedFrontier,
                    planStateAt = { stone },
                ),
            )
        }

        assertAbortStatus(fixture.core, MoverAbortReason.TRAPPED)
    }

    @Test
    public fun planModeNullContextFieldsFallBackWithoutCrashing(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val playerPos = Vec3(-10.0, 1.0, 0.0)

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerPos,
            planStateAt = null,
            planTravelBounds = null,
        )

        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        assertNull(fixture.core.status().abortReason)
        assertEquals(0, fixture.core.pathTelemetry().aStarInvocations)
        val target = requireNotNull(fixture.core.status().target)
        assertTrue(target.y > playerPos.y)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeCornerDecelerationHalvesSpeedWithinFinalApproachToATurningWaypoint(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 0.0, 0.5)

        startPlanCruise(fixture, frontier, playerPos = playerStart, planStateAt = null, planTravelBounds = null)
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
        val ascend = requireNotNull(fixture.core.status().target)

        val farCommand = fixture.core.tick(
            fixture.context(
                playerPos = ascend,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )
        val cross = requireNotNull(fixture.core.status().target)
        assertNotEquals(ascend, cross)
        assertEquals(1.0, farCommand.horizontalX, 1.0E-9)

        val nearPosition = Vec3(cross.x - 1.0, cross.y, cross.z)
        val nearCommand = fixture.core.tick(
            fixture.context(
                playerPos = nearPosition,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )
        assertEquals(cross, fixture.core.status().target)
        assertEquals(0.5, nearCommand.horizontalX, 1.0E-9)
    }

    @Test
    public fun planModeSharpHorizontalWaypointTransitionStopsMomentumBeforeNextSegment(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(4, 0, 0))
        val playerStart = Vec3(-3.5, 1.05, 0.5)
        val world = HashMap<BlockPos, BlockState>()
        for (y in -1..6) {
            for (z in -2..2) {
                world[BlockPos(0, y, z)] = Blocks.STONE.defaultBlockState()
            }
        }
        val stateAt: (BlockPos) -> BlockState = { pos -> world[pos] ?: Blocks.AIR.defaultBlockState() }
        val travelBounds = BlockPos(-12, -4, -12) to BlockPos(12, 12, 12)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = stateAt,
            planTravelBounds = travelBounds,
        )

        var previousWaypoint = playerStart
        repeat(32) {
            val currentWaypoint = requireNotNull(fixture.core.status().target)
            val command = fixture.core.tick(
                fixture.context(
                    playerPos = currentWaypoint,
                    onGround = false,
                    flying = true,
                    planMode = true,
                    planFrontier = frontier,
                    planStateAt = stateAt,
                    planTravelBounds = travelBounds,
                ),
            )
            val nextWaypoint = fixture.core.status().target ?: return@repeat
            if (nextWaypoint != currentWaypoint) {
                val incoming = Vec3(
                    currentWaypoint.x - previousWaypoint.x,
                    0.0,
                    currentWaypoint.z - previousWaypoint.z,
                )
                val outgoing = Vec3(
                    nextWaypoint.x - currentWaypoint.x,
                    0.0,
                    nextWaypoint.z - currentWaypoint.z,
                )
                if (
                    incoming.lengthSqr() > 1.0E-9 &&
                    outgoing.lengthSqr() > 1.0E-9 &&
                    incoming.normalize().dot(outgoing.normalize()) < 0.94
                ) {
                    assertFalse(command.stopMovement)
                    assertTrue(command.resetHorizontalVelocity)
                    return
                }
                previousWaypoint = currentWaypoint
            }
        }
        assertTrue(false, "test route did not contain a sharp horizontal waypoint")
    }

    @Test
    public fun planModeTightCorridorDoesNotDiscardFittedStartConnectorFromLooseArrival(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(0, 4, -4))
        val playerStart = Vec3(0.584, 1.17, 2.324)
        val fittedStart = Vec3(0.5, 1.05, 2.5)
        val world = HashMap<BlockPos, BlockState>()
        for (x in -2..2) {
            for (z in -1..4) {
                world[BlockPos(x, 3, z)] = Blocks.STONE.defaultBlockState()
            }
        }
        val stateAt: (BlockPos) -> BlockState = { pos -> world[pos] ?: Blocks.AIR.defaultBlockState() }
        val travelBounds = BlockPos(-12, -4, -12) to BlockPos(12, 12, 12)

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = stateAt,
            planTravelBounds = travelBounds,
        )

        assertEquals(fittedStart, fixture.core.status().target)
        assertEquals(0.0, command.horizontalX, 0.0)
        assertEquals(0.0, command.horizontalZ, 0.0)
        assertEquals(-1, command.vertical)

        val settledPlayer = Vec3(playerStart.x, 1.02, playerStart.z)
        val onward = fixture.core.tick(
            fixture.context(
                playerPos = settledPlayer,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planStateAt = stateAt,
                planTravelBounds = travelBounds,
            ),
        )

        assertNotEquals(fittedStart, fixture.core.status().target)
        assertEquals(0, onward.vertical)
        assertTrue(abs(onward.horizontalX) + abs(onward.horizontalZ) > 0.0)
    }

    @Test
    public fun planModeLooseIntermediateArrivalTightensBeforeBlockedNextSegment(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 0.0, 0.5)
        var corner: Vec3? = null
        val probe: (Vec3, Vec3) -> PathProbeResult = { from, to ->
            val requiredCorner = corner
            val clear = requiredCorner == null ||
                to == requiredCorner ||
                from.distanceToSqr(requiredCorner) <= 1.0E-9
            PathProbeResult(clear = clear, chunkLoaded = true)
        }

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = probe,
            planStateAt = null,
            planTravelBounds = null,
        )
        val firstCorner = requireNotNull(fixture.core.status().target)
        corner = firstCorner
        val looseArrival = firstCorner.add(0.0, 0.26, 0.10)

        val tighten = fixture.core.tick(
            fixture.context(
                playerPos = looseArrival,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                pathProbe = probe,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )

        assertEquals(firstCorner, fixture.core.status().target)
        assertEquals(-1, tighten.vertical)

        fixture.core.tick(
            fixture.context(
                playerPos = firstCorner,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                pathProbe = probe,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )
        assertNotEquals(firstCorner, fixture.core.status().target)
    }

    @Test
    public fun planModeFinalWaypointAcceptsLooseArrivalBeforeBlockedTinyConnector(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos.ZERO)
        val looseArrival = Vec3(2.5, 1.13, 0.5)
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, to ->
            PathProbeResult(clear = fixture.core.status().target != to, chunkLoaded = true)
        }

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = looseArrival,
            pathProbe = probe,
        )

        assertEquals(MoverState.HOLD, fixture.core.status().state) {
            "target=${fixture.core.status().target} command=$command"
        }
        assertStopped(command)
    }

    @Test
    public fun planModeRoutesAroundTypedInFlightFenceShape(): Unit {
        val fixture = Fixture()
        val target = BlockPos(10, 0, 0)
        val futureFence = BlockPos(2, 0, 0)
        val fence = Blocks.OAK_FENCE.defaultBlockState()
        val frontier = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(target, Blocks.STONE.defaultBlockState())),
            columnBlocked = emptyList(),
            inFlight = listOf(futureFence),
            inFlightCollisionStates = listOf(MoverPlanFrontierTarget(futureFence, fence)),
        )
        val playerStart = Vec3(0.5, 1.13, 0.5)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = OPEN_AIR_STATE_AT,
        )

        val firstWaypoint = requireNotNull(fixture.core.status().target)
        val reservedStateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == futureFence) fence else Blocks.AIR.defaultBlockState()
        }
        assertTrue(sweptVolumeCollisionFree(playerStart, firstWaypoint, reservedStateAt)) {
            "first waypoint $firstWaypoint crosses the typed in-flight fence"
        }
    }

    @Test
    public fun planModeUnrelatedWorldRevisionKeepsCollisionProvenPath(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 1.05, 5.5)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = OPEN_AIR_STATE_AT,
        )
        val firstTarget = requireNotNull(fixture.core.status().target)
        val searchesBefore = fixture.core.pathTelemetry().aStarInvocations
        val distantWrite = BlockPos(80, 0, 15)
        val worldAfterWrite: (BlockPos) -> BlockState = { pos ->
            if (pos == distantWrite) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        fixture.core.tick(
            fixture.context(
                playerPos = playerStart,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                planMode = true,
                planFrontier = frontier,
                planStateAt = worldAfterWrite,
            ),
        )

        assertEquals(searchesBefore, fixture.core.pathTelemetry().aStarInvocations)
        assertEquals(firstTarget, fixture.core.status().target)
    }

    @Test
    public fun planModeObstructingWorldRevisionReplansCollisionPath(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 1.05, 0.5)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = OPEN_AIR_STATE_AT,
        )
        val searchesBefore = fixture.core.pathTelemetry().aStarInvocations
        val obstacle = BlockPos(5, 1, 0)
        val obstructedWorld: (BlockPos) -> BlockState = { pos ->
            if (pos == obstacle) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        fixture.core.tick(
            fixture.context(
                playerPos = playerStart,
                onGround = false,
                flying = true,
                queueRevision = 1L,
                planMode = true,
                planFrontier = frontier,
                planStateAt = obstructedWorld,
            ),
        )

        assertTrue(fixture.core.pathTelemetry().aStarInvocations > searchesBefore)
    }

    @Test
    public fun planModeLooseArrivalKeepsVerticalCornerBeforeInFlightBlock(): Unit {
        val fixture = Fixture()
        val target = BlockPos(10, 0, 0)
        val futureSolid = BlockPos(1, 2, 0)
        val frontier = MoverPlanFrontier(
            waitingForReach = listOf(MoverPlanFrontierTarget(target, Blocks.STONE.defaultBlockState())),
            columnBlocked = emptyList(),
            inFlight = listOf(futureSolid),
        )
        val playerStart = Vec3(0.5, 0.0, 0.5)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = CLEAR_PROBE,
            planStateAt = OPEN_AIR_STATE_AT,
            planTravelBounds = null,
        )
        val verticalCorner = requireNotNull(fixture.core.status().target)
        val looseArrival = verticalCorner.add(0.0, -0.26, 0.0)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = looseArrival,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                pathProbe = CLEAR_PROBE,
                planStateAt = OPEN_AIR_STATE_AT,
                planTravelBounds = null,
            ),
        )

        assertEquals(verticalCorner, fixture.core.status().target)
        assertEquals(1, command.vertical)
    }

    @Test
    public fun planModeRisingSegmentStartsAscendingBeforeHorizontalMotion(): Unit {
        val fixture = Fixture()
        val playerPos = Vec3(5.4996, 14.98, 6.2561)
        val command = startPlanCruise(
            fixture,
            frontierOf(BlockPos(4, 14, 3)),
            playerPos = playerPos,
        )
        val risingTarget = requireNotNull(fixture.core.status().target)

        assertTrue(risingTarget.y > playerPos.y)
        assertTrue(command.horizontalX != 0.0 || command.horizontalZ != 0.0)
        assertEquals(1, command.vertical)
    }

    @Test
    public fun planModeOffCenterStartKeepsResolvedStartCenterAsConnector(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 5))
        val playerStart = Vec3(3.9, 0.05, 5.5)
        val startCenter = Vec3(3.5, 0.05, 5.5)
        val probe: (Vec3, Vec3) -> PathProbeResult = { from, to ->
            PathProbeResult(
                clear = from != playerStart || to == startCenter,
                chunkLoaded = true,
            )
        }

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = probe,
        )

        assertEquals(startCenter, fixture.core.status().target)
        assertTrue(command.horizontalX < 0.0)
        assertFalse(command.stopMovement)
    }

    @Test
    public fun planModeOffCenterStartRejectsBlockedNaiveCenterConnector(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 5))
        val playerStart = Vec3(3.5, 0.05, 5.25)
        val rescuedCenter = Vec3(3.5, 0.05, 4.5)
        val probe: (Vec3, Vec3) -> PathProbeResult = { from, to ->
            PathProbeResult(
                clear = from != playerStart || to == rescuedCenter,
                chunkLoaded = true,
            )
        }

        val command = startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = probe,
        )

        assertEquals(rescuedCenter, fixture.core.status().target)
        assertTrue(command.horizontalZ < 0.0)
        assertFalse(command.stopMovement)
        assertEquals(1, fixture.core.pathTelemetry().aStarResolveStartRescues)
    }

    @Test
    public fun planModeNearVerticalCornerStopsHorizontalInputAndFinishesAltitude(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 0.0, 0.5)

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            planStateAt = null,
            planTravelBounds = null,
        )
        val verticalCorner = requireNotNull(fixture.core.status().target)
        val nearCorner = Vec3(verticalCorner.x + 0.10, verticalCorner.y - 0.60, verticalCorner.z)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = nearCorner,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )

        assertEquals(verticalCorner, fixture.core.status().target)
        assertEquals(0.0, command.horizontalX, 0.0)
        assertEquals(0.0, command.horizontalZ, 0.0)
        assertEquals(1, command.vertical)
    }

    @Test
    public fun planModeTightCornerAscendsForAnyPositiveSafeHeightError(): Unit {
        val fixture = Fixture()
        val frontier = frontierOf(BlockPos(10, 0, 0))
        val playerStart = Vec3(0.5, 0.0, 0.5)
        var corner: Vec3? = null
        val probe: (Vec3, Vec3) -> PathProbeResult = { from, to ->
            val requiredCorner = corner
            val clear = requiredCorner == null ||
                to == requiredCorner ||
                from.distanceToSqr(requiredCorner) <= 1.0E-9
            PathProbeResult(clear = clear, chunkLoaded = true)
        }

        startPlanCruise(
            fixture,
            frontier,
            playerPos = playerStart,
            pathProbe = probe,
            planStateAt = null,
            planTravelBounds = null,
        )
        val verticalCorner = requireNotNull(fixture.core.status().target)
        corner = verticalCorner
        val justBelowSafeHeight = verticalCorner.add(0.0, -0.02, 0.0)

        val command = fixture.core.tick(
            fixture.context(
                playerPos = justBelowSafeHeight,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                pathProbe = probe,
                planStateAt = null,
                planTravelBounds = null,
            ),
        )

        assertEquals(verticalCorner, fixture.core.status().target)
        assertEquals(1, command.vertical)
    }

    private fun activeFixture(): Fixture {
        val fixture = Fixture()
        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(fixture.context(onGround = true))
        return fixture
    }

    private fun wallProbe(
        wallX: Int,
        minWallZ: Int,
        maxWallZ: Int,
        probedTargets: MutableList<Vec3>,
    ): (Vec3, Vec3) -> PathProbeResult {
        return { from, to ->
            probedTargets.add(to)
            val deltaX = to.x - from.x
            val wallFraction = if (deltaX == 0.0) {
                Double.NaN
            } else {
                (wallX + 0.5 - from.x) / deltaX
            }
            val wallZ = from.z + (to.z - from.z) * wallFraction
            val blocked = wallFraction in 0.0..1.0 &&
                wallZ >= minWallZ.toDouble() &&
                wallZ < maxWallZ + 1.0
            PathProbeResult(clear = !blocked, chunkLoaded = true)
        }
    }

    private fun recoverFlight(fixture: Fixture): Unit {
        val jump = fixture.core.tick(fixture.context(onGround = true, flying = false))
        assertTrue(jump.jump)
        val enableFlight = fixture.core.tick(
            fixture.context(onGround = false, flying = false),
        )
        assertTrue(enableFlight.enableFlight)
        fixture.core.tick(fixture.context(onGround = false, flying = true))
        assertEquals(MoverState.CRUISE, fixture.core.status().state)
    }

    private fun startCruise(
        fixture: Fixture,
        pathProbe: (Vec3, Vec3) -> PathProbeResult = CLEAR_PROBE,
        gateY: Int? = null,
        gatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT,
        reach: Double = 4.5,
        playerPos: Vec3 = Vec3(-10.0, 1.0, 0.0),
    ): MoverCommand {
        fixture.core.toggleRequested(true, true, true)
        return finishTakeoff(
            fixture = fixture,
            pathProbe = pathProbe,
            gateY = gateY,
            gatePhase = gatePhase,
            reach = reach,
            playerPos = playerPos,
        )
    }

    private fun finishTakeoff(
        fixture: Fixture,
        pathProbe: (Vec3, Vec3) -> PathProbeResult = CLEAR_PROBE,
        queueRevision: Long = 0L,
        gateY: Int? = null,
        gatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT,
        reach: Double = 4.5,
        playerPos: Vec3 = Vec3(-10.0, 1.0, 0.0),
    ): MoverCommand {
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = true,
                pathProbe = pathProbe,
                queueRevision = queueRevision,
                gateY = gateY,
                gatePhase = gatePhase,
                reach = reach,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                pathProbe = pathProbe,
                queueRevision = queueRevision,
                gateY = gateY,
                gatePhase = gatePhase,
                reach = reach,
            ),
        )
        return fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                pathProbe = pathProbe,
                queueRevision = queueRevision,
                gateY = gateY,
                gatePhase = gatePhase,
                reach = reach,
            ),
        )
    }

    private fun startPlanCruise(
        fixture: Fixture,
        frontier: MoverPlanFrontier,
        playerPos: Vec3 = Vec3(-10.0, 1.0, 0.0),
        planSessionFinal: Boolean = false,
        pathProbe: (Vec3, Vec3) -> PathProbeResult = CLEAR_PROBE,
        planStateAt: ((BlockPos) -> BlockState)? = OPEN_AIR_STATE_AT,
        planSupportStateAt: ((BlockPos) -> BlockState)? = null,
        planTravelBounds: Pair<BlockPos, BlockPos>? = UNBOUNDED_TRAVEL_BOUNDS,
        reach: Double = 4.5,
    ): MoverCommand {
        fixture.core.toggleRequested(true, true, true)
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = true,
                planMode = true,
                planFrontier = frontier,
                planSessionFinal = planSessionFinal,
                pathProbe = pathProbe,
                planStateAt = planStateAt,
                planSupportStateAt = planSupportStateAt,
                planTravelBounds = planTravelBounds,
                reach = reach,
            ),
        )
        fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                planMode = true,
                planFrontier = frontier,
                planSessionFinal = planSessionFinal,
                pathProbe = pathProbe,
                planStateAt = planStateAt,
                planSupportStateAt = planSupportStateAt,
                planTravelBounds = planTravelBounds,
                reach = reach,
            ),
        )
        return fixture.core.tick(
            fixture.context(
                playerPos = playerPos,
                onGround = false,
                flying = true,
                planMode = true,
                planFrontier = frontier,
                planSessionFinal = planSessionFinal,
                pathProbe = pathProbe,
                planStateAt = planStateAt,
                planSupportStateAt = planSupportStateAt,
                planTravelBounds = planTravelBounds,
                reach = reach,
            ),
        )
    }

    private fun frontierOf(vararg positions: BlockPos): MoverPlanFrontier {
        return MoverPlanFrontier(
            waitingForReach = positions.map { pos -> MoverPlanFrontierTarget(pos, Blocks.STONE.defaultBlockState()) },
            columnBlocked = emptyList(),
            inFlight = emptyList(),
        )
    }

    private fun assertAbort(
        fixture: Fixture,
        context: MoverTickContext,
        expectedReason: MoverAbortReason,
    ): Unit {
        val command = fixture.core.tick(context)
        assertAbortStatus(fixture.core, expectedReason)
        assertStopped(command)
    }

    private fun assertAbortStatus(core: MoverCore, expectedReason: MoverAbortReason): Unit {
        assertEquals(MoverState.ABORTED, core.status().state)
        assertEquals(expectedReason, core.status().abortReason)
    }

    private fun assertStopped(command: MoverCommand): Unit {
        assertFalse(command.jump)
        assertFalse(command.enableFlight)
        assertEquals(0.0, command.horizontalX, 0.0)
        assertEquals(0.0, command.horizontalZ, 0.0)
        assertEquals(0, command.vertical)
        assertTrue(command.stopMovement)
    }

    private class Fixture(
        val missingWorld: List<BlockPos> = listOf(BlockPos.ZERO),
        val isPlaceable: (BlockPos) -> Boolean = { true },
        val isPassableCell: (BlockPos) -> Boolean = { true },
        val onWorkPositionAbandoned: (Set<BlockPos>) -> Unit = {},
        val onWorkPositionUnproductive: (Set<BlockPos>, Set<BlockPos>) -> Unit = { _, _ -> },
        val onPositionsUncoverable: (Set<BlockPos>, MoverDeferralCause) -> Unit = { _, _ -> },
        val onPlanPositionUnreachable: (BlockPos) -> Unit = {},
        val canComplete: () -> Boolean = { true },
    ) {
        val levelIdentity: Any = Any()
        val contentIdentity: Any = Any()
        val transformRevision: Long = 10L
        val sessionKey = MoverSessionKey(levelIdentity, contentIdentity, transformRevision)
        val core = MoverCore()

        fun context(
            sessionKey: MoverSessionKey = this.sessionKey,
            playerPos: Vec3 = Vec3(-10.0, 1.0, 0.0),
            onGround: Boolean = true,
            flying: Boolean = false,
            mayfly: Boolean = true,
            isCreative: Boolean = true,
            guiOpen: Boolean = false,
            hurt: Boolean = false,
            manualInput: Boolean = false,
            correctionReceived: Boolean = false,
            queueRevision: Long = 0L,
            feedSnapshot: FeedSnapshot? = null,
            gateY: Int? = null,
            gatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT,
            missingWorld: List<BlockPos> = this.missingWorld,
            isPlaceable: (BlockPos) -> Boolean = this.isPlaceable,
            isPassableCell: (BlockPos) -> Boolean = this.isPassableCell,
            reach: Double = 4.5,
            pathProbe: (Vec3, Vec3) -> PathProbeResult = CLEAR_PROBE,
            onWorkPositionAbandoned: (Set<BlockPos>) -> Unit = this.onWorkPositionAbandoned,
            onWorkPositionUnproductive: (Set<BlockPos>, Set<BlockPos>) -> Unit =
                this.onWorkPositionUnproductive,
            onPositionsUncoverable: (Set<BlockPos>, MoverDeferralCause) -> Unit =
                this.onPositionsUncoverable,
            onPlanPositionUnreachable: (BlockPos) -> Unit = this.onPlanPositionUnreachable,
            canComplete: () -> Boolean = this.canComplete,
            onHoldHardCap: () -> Unit = {},
            planMode: Boolean = false,
            planFrontier: MoverPlanFrontier? = null,
            planSessionFinal: Boolean = false,
            planStateAt: ((BlockPos) -> BlockState)? = OPEN_AIR_STATE_AT,
            planSupportStateAt: ((BlockPos) -> BlockState)? = null,
            planTravelBounds: Pair<BlockPos, BlockPos>? = UNBOUNDED_TRAVEL_BOUNDS,
        ): MoverTickContext {
            return MoverTickContext(
                sessionKey = sessionKey,
                playerPos = playerPos,
                onGround = onGround,
                flying = flying,
                mayfly = mayfly,
                isCreative = isCreative,
                guiOpen = guiOpen,
                hurt = hurt,
                manualInput = manualInput,
                correctionReceived = correctionReceived,
                queueRevision = queueRevision,
                feedSnapshot = feedSnapshot,
                gateY = gateY,
                gatePhase = gatePhase,
                missingWorld = missingWorld,
                isPlaceable = isPlaceable,
                isPassableCell = isPassableCell,
                reach = reach,
                pathProbe = pathProbe,
                onWorkPositionAbandoned = onWorkPositionAbandoned,
                onWorkPositionUnproductive = onWorkPositionUnproductive,
                onPositionsUncoverable = onPositionsUncoverable,
                onPlanPositionUnreachable = onPlanPositionUnreachable,
                canComplete = canComplete,
                onHoldHardCap = onHoldHardCap,
                planMode = planMode,
                planFrontier = planFrontier,
                planSessionFinal = planSessionFinal,
                planStateAt = planStateAt,
                planSupportStateAt = planSupportStateAt,
                planTravelBounds = planTravelBounds,
            )
        }
    }

    private companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private val RECENT_PROGRESS_WINDOW_TICKS: Long =
            PrinterAttemptTracker.DEADLINE_TICKS + PrinterRateLimiter.DEFAULT_INTERVAL_TICKS

        private fun emptyFeed(
            inFlightCount: Int = 0,
            queueRevision: Long = 0L,
        ): FeedSnapshot {
            return FeedSnapshot(
                tick = 0L,
                queueRevision = queueRevision,
                candidateCount = 0,
                submittedCount = 0,
                rateLimitedRemainder = 0,
                inFlightCount = inFlightCount,
                acceptedThisTick = 0,
            )
        }

        private val CLEAR_PROBE: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            PathProbeResult(clear = true, chunkLoaded = true)
        }

        private val OPEN_AIR_STATE_AT: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        private val UNBOUNDED_TRAVEL_BOUNDS: Pair<BlockPos, BlockPos> =
            BlockPos(-20, -20, -20) to BlockPos(105, 20, 20)
    }
}
