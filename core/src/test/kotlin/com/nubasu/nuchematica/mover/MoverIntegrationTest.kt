package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.printer.PrinterCandidateSelector
import com.nubasu.nuchematica.printer.PrinterDeferralLedger
import com.nubasu.nuchematica.printer.PrinterDeferralReason
import com.nubasu.nuchematica.printer.FeedSnapshot
import com.nubasu.nuchematica.printer.PrinterLayerGate
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.isActionableMissing
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class MoverIntegrationTest {
    @Test
    public fun finalLowestLayerAbandonmentWaitsForFreshGateThenContinuesAbove(): Unit {
        val lower = BlockPos(0, 1, 0)
        val upper = BlockPos(20, 2, 0)
        val harness = Harness(listOf(lower, upper))
        harness.start()

        harness.tick(pathProbe = BLOCKED_PROBE)

        assertNotEquals(MoverState.COMPLETE, harness.core.status().state)
        assertEquals(MoverState.HOLD, harness.core.status().state)
        assertEquals(1, harness.gateY)
        assertEquals(
            setOf(PrinterDeferralReason.NO_PROGRESS),
            harness.ledger.activeReasons(lower, harness.stateAt, harness.queueRevision),
        )

        harness.tick(pathProbe = CLEAR_PROBE)

        assertEquals(MoverState.CRUISE, harness.core.status().state)
        assertEquals(2, harness.gateY)
        val upperTarget = requireNotNull(harness.core.status().target)
        harness.playerPos = upperTarget
        harness.tick(pathProbe = CLEAR_PROBE, submitReachablePlacement = true)
        assertTrue(harness.placements.isEmpty())

        harness.tick(pathProbe = CLEAR_PROBE)

        assertEquals(listOf(upper), harness.placements)
        assertFalse(upper in harness.missing)
    }

    @Test
    public fun neverPlacingGatewayGetsOneFinalSweepAfterNoProgressBackoff(): Unit {
        val position = BlockPos(0, 1, 0)
        val harness = Harness(listOf(position))
        harness.start()
        harness.tick(pathProbe = CLEAR_PROBE)

        harness.playerPos = requireNotNull(harness.core.status().target)
        harness.tick(pathProbe = CLEAR_PROBE)

        assertEquals(MoverState.HOLD, harness.core.status().state)
        assertEquals(
            setOf(PrinterDeferralReason.NO_PROGRESS),
            harness.ledger.activeReasons(position, harness.stateAt, harness.queueRevision),
        )

        harness.tick(pathProbe = CLEAR_PROBE)

        assertEquals(MoverState.CRUISE, harness.core.status().state)
        assertEquals(1, harness.finalSweepGrants)
        assertFalse(harness.ledger.isDeferred(position, harness.stateAt, harness.queueRevision))
        assertEquals(CompletionBreakdown(totalRemaining = 1, actionable = 0), harness.breakdown)
    }

    @Test
    public fun finalSweepPlacesBackoffDeferredWorkInsteadOfCompletingEarly(): Unit {
        val position = BlockPos(0, 1, 0)
        val harness = Harness(listOf(position))
        harness.start()
        harness.deferForTest(position, PrinterDeferralReason.MOVER_UNREACHABLE)

        harness.tick(pathProbe = CLEAR_PROBE)
        assertEquals(MoverState.HOLD, harness.core.status().state)
        harness.tick(pathProbe = CLEAR_PROBE)

        assertEquals(1, harness.finalSweepGrants)
        assertEquals(MoverState.CRUISE, harness.core.status().state)
        harness.playerPos = requireNotNull(harness.core.status().target)
        harness.tick(pathProbe = CLEAR_PROBE, submitReachablePlacement = true)
        harness.tick(pathProbe = CLEAR_PROBE)

        assertEquals(listOf(position), harness.placements)
        assertFalse(position in harness.missing)
        assertNotEquals(MoverState.COMPLETE, harness.core.status().state)
    }

    @Test
    public fun trulyUnplaceableFinalSweepCompletesAfterOneGrant(): Unit {
        val position = BlockPos(0, 1, 0)
        val harness = Harness(listOf(position))
        harness.start()
        harness.deferForTest(position, PrinterDeferralReason.MOVER_UNREACHABLE)

        harness.tick(pathProbe = CLEAR_PROBE)
        harness.tick(pathProbe = CLEAR_PROBE)
        assertEquals(MoverState.CRUISE, harness.core.status().state)

        harness.setWorldState(position.below(), Blocks.AIR.defaultBlockState())
        harness.playerPos = requireNotNull(harness.core.status().target)
        harness.tick(pathProbe = CLEAR_PROBE)
        repeat(FED_EMPTY_TICKS + 2) {
            harness.tick(pathProbe = CLEAR_PROBE)
        }

        assertEquals(MoverState.COMPLETE, harness.core.status().state)
        assertEquals(1, harness.finalSweepGrants)
    }

    @Test
    public fun moverFinishesHeldLayerBeforeOneDescentToNewlySupportedLowerWork(): Unit {
        val lower = BlockPos(0, 5, 0)
        val workingLayer = listOf(
            BlockPos(0, 6, 0),
            BlockPos(20, 6, 0),
            BlockPos(40, 6, 0),
        )
        val harness = Harness(listOf(lower) + workingLayer)
        harness.setWorldState(lower, Blocks.AIR.defaultBlockState())
        harness.setWorldState(lower.below(), Blocks.AIR.defaultBlockState())
        harness.setWorldState(workingLayer.first().west(), Blocks.STONE.defaultBlockState())
        harness.start()
        var workingPlacementsWhenStragglerFirstTargeted: Int? = null
        var integrationTicks = 0

        while (
            harness.core.status().state != MoverState.COMPLETE &&
            integrationTicks < MAX_INTEGRATION_TICKS
        ) {
            integrationTicks++
            harness.core.status().target?.let { target ->
                if (target.y < 7.5 && workingPlacementsWhenStragglerFirstTargeted == null) {
                    workingPlacementsWhenStragglerFirstTargeted =
                        harness.placements.count { position -> position.y == 6 }
                }
                harness.playerPos = target
            }
            harness.tick(
                pathProbe = CLEAR_PROBE,
                submitReachablePlacement = true,
                placementFilter = { candidate ->
                    candidate.y == 6 || workingLayer.none { position -> position in harness.missing }
                },
            )
        }

        assertEquals(3, workingPlacementsWhenStragglerFirstTargeted)
        assertEquals(1, harness.gateDescentCount())
        assertEquals(workingLayer + lower, harness.placements)
        assertTrue(harness.missing.isEmpty())
        assertEquals(CompletionBreakdown(totalRemaining = 0, actionable = 0), harness.breakdown)
        assertEquals(MoverState.COMPLETE, harness.core.status().state)
    }

    @Test
    public fun blockedFirstCandidateRetriesSecondAndPlacesWithoutDeferral(): Unit {
        val blocked = BlockPos(0, 1, 0)
        val reachable = BlockPos(8, 1, 0)
        val harness = Harness(listOf(blocked, reachable))
        val probe: (Vec3, Vec3) -> PathProbeResult = { _, target ->
            PathProbeResult(clear = target.x != 0.5, chunkLoaded = true)
        }
        harness.start()

        harness.tick(pathProbe = probe, isPassableCell = { false })

        val secondTarget = requireNotNull(harness.core.status().target)
        assertEquals(8.5, secondTarget.x, 0.0)

        harness.playerPos = secondTarget
        harness.tick(pathProbe = probe, submitReachablePlacement = true)
        harness.tick(pathProbe = probe)

        assertEquals(listOf(reachable), harness.placements)
        assertFalse(reachable in harness.missing)
    }

    @Test
    public fun lateralPathAroundWallPlacesBlockWithoutDeferral(): Unit {
        val position = BlockPos(10, 1, 0)
        val harness = Harness(listOf(position))
        harness.playerPos = Vec3(0.5, 5.0, 0.5)
        val passable: (BlockPos) -> Boolean = { cell ->
            cell.x != 4 || cell.z !in -2..2
        }
        val probedTargets = mutableListOf<Vec3>()
        val probe = wallProbe(
            wallX = 4,
            minWallZ = -2,
            maxWallZ = 2,
            probedTargets = probedTargets,
        )
        harness.start()
        var ticks = 0

        while (position in harness.missing && ticks < 30) {
            ticks++
            harness.core.status().target?.let { target -> harness.playerPos = target }
            harness.tick(
                pathProbe = probe,
                isPassableCell = passable,
                submitReachablePlacement = true,
            )
        }

        assertEquals(listOf(position), harness.placements)
        assertFalse(harness.ledger.isDeferred(position, harness.stateAt, harness.queueRevision))
        assertTrue(probedTargets.any { target -> target.z < -2.0 || target.z > 3.0 })
        assertTrue(probedTargets.none { target -> target.y >= 6.4 })
    }

    @Test
    public fun autoMoveRetoggleClearsMoverCausesAndRetriesPosition(): Unit {
        val position = BlockPos(0, 1, 0)
        val harness = Harness(listOf(position))
        harness.start()
        harness.tick(pathProbe = BLOCKED_PROBE)
        assertTrue(harness.ledger.isDeferred(position, harness.stateAt, harness.queueRevision))

        harness.stop()
        harness.start()
        harness.tick(pathProbe = CLEAR_PROBE)

        assertFalse(harness.ledger.isDeferred(position, harness.stateAt, harness.queueRevision))
        assertEquals(Vec3(0.5, 3.0, 0.5), harness.core.status().target)
        assertEquals(MoverState.CRUISE, harness.core.status().state)
    }

    @Test
    public fun envelopeAlignedCoverageFindsCandidateTheOldCenterModelMissed(): Unit {
        val target = BlockPos(0, 0, 0)
        val expected = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP)
        val stateAt: (BlockPos) -> BlockState = { position ->
            if (position == target.north()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val reach = 4.0
        val alreadyBannedByFailedPathing = setOf(
            Vec3(2.0, 1.4, 0.0),
            Vec3(0.0, 1.4, 2.0),
            Vec3(3.0, 0.4, 0.0),
            Vec3(0.0, 0.4, 3.0),
        )

        val oldModelPlan = planRouteWithCenterDistanceCoverage(
            placeableMissing = listOf(target),
            reach = reach,
            bannedTargets = alreadyBannedByFailedPathing,
        )
        assertTrue(oldModelPlan.route.isEmpty())
        assertEquals(listOf(target), oldModelPlan.uncoverable)

        val newModelPlan = planRoute(
            placeableMissing = listOf(target),
            reach = reach,
            bannedTargets = alreadyBannedByFailedPathing,
            expectedStateAt = { expected },
        )
        assertTrue(newModelPlan.uncoverable.isEmpty())
        val workPosition = newModelPlan.route.single().target
        val eyePosition = Vec3(workPosition.x, workPosition.y + EYE_HEIGHT, workPosition.z)

        val candidates = PrinterCandidateSelector(
            predictPlacement = { _, _ -> expected },
        ).select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateAt,
            placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
            eyePosition = eyePosition,
            reach = reach,
        )
        assertEquals(1, candidates.size)
        assertEquals(target, candidates.single().worldPos)
    }

    private fun planRouteWithCenterDistanceCoverage(
        placeableMissing: List<BlockPos>,
        reach: Double,
        bannedTargets: Set<Vec3>,
    ): RoutePlan {
        val remaining = placeableMissing.map { it.immutable() }.distinct().toMutableList()
        val route = mutableListOf<PlannedWorkPosition>()
        val uncoverable = mutableListOf<BlockPos>()
        val offsets = listOf(
            BlockPos(2, 1, 0), BlockPos(-2, 1, 0), BlockPos(0, 1, 2), BlockPos(0, 1, -2),
            BlockPos(0, 3, 0), BlockPos(3, 0, 0), BlockPos(-3, 0, 0), BlockPos(0, 0, 3), BlockPos(0, 0, -3),
        )
        while (remaining.isNotEmpty()) {
            val anchor = remaining.first()
            val best = offsets.mapIndexed { index, offset ->
                val target = Vec3(
                    (anchor.x + offset.x).toDouble(),
                    (anchor.y + offset.y).toDouble() + HOVER_CLEARANCE,
                    (anchor.z + offset.z).toDouble(),
                )
                val effectiveReach = (reach - 0.5).coerceAtLeast(0.0)
                val reachSquared = effectiveReach * effectiveReach
                val eyePosition = Vec3(target.x, target.y + EYE_HEIGHT, target.z)
                val covered = remaining.filter { missing ->
                    val center = Vec3(missing.x + 0.5, missing.y + 0.5, missing.z + 0.5)
                    eyePosition.distanceToSqr(center) <= reachSquared
                }
                Triple(index, target, covered)
            }.filterNot { (_, target, _) -> target in bannedTargets }
                .minWithOrNull(
                    compareByDescending<Triple<Int, Vec3, List<BlockPos>>> { it.third.size }
                        .thenBy { it.first },
                )
            if (best == null || best.third.isEmpty()) {
                uncoverable += anchor
                remaining.removeAt(0)
                continue
            }
            route += PlannedWorkPosition(target = best.second, covered = best.third.toSet())
            remaining.removeAll(best.third)
        }
        return RoutePlan(route = route, uncoverable = uncoverable)
    }

    private class Harness(initialMissing: List<BlockPos>) {
        internal val core: MoverCore = MoverCore()
        internal val ledger: PrinterDeferralLedger = PrinterDeferralLedger()
        internal val missing: LinkedHashSet<BlockPos> = initialMissing
            .mapTo(LinkedHashSet()) { position -> position.immutable() }
        internal val placements: MutableList<BlockPos> = mutableListOf()
        internal var playerPos: Vec3 = Vec3(-10.0, 2.0, 0.0)
        internal var queueRevision: Long = 0L
        internal var gateY: Int? = null
        internal var gatePhase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT
        internal var breakdown: CompletionBreakdown? = null
        internal var finalSweepGrants: Int = 0
        private var tick: Long = 0L

        private val layerGate = PrinterLayerGate()
        private val gateHistory: MutableList<Int?> = mutableListOf()
        private val states: HashMap<BlockPos, BlockState> = HashMap()
        private val levelIdentity: Any = Any()
        private val contentIdentity: Any = Any()
        private val sessionKey = MoverSessionKey(levelIdentity, contentIdentity, TRANSFORM_REVISION)
        private var pendingAcceptedPlacement: BlockPos? = null
        internal val stateAt: (BlockPos) -> BlockState = { position ->
            states[position] ?: Blocks.AIR.defaultBlockState()
        }

        init {
            for (position in missing) {
                states[position.below().immutable()] = Blocks.STONE.defaultBlockState()
            }
            ledger.synchronize(TRANSFORM_REVISION, contentIdentity, levelIdentity)
        }

        internal fun start(): Unit {
            ledger.clearMoverCauses()
            assertEquals(MoverState.TAKEOFF, core.toggleRequested(true, true, missing.isNotEmpty()))
        }

        internal fun stop(): Unit {
            assertEquals(MoverState.ABORTED, core.toggleRequested(true, true, true))
        }

        internal fun setWorldState(position: BlockPos, state: BlockState): Unit {
            states[position.immutable()] = state
        }

        internal fun deferForTest(
            position: BlockPos,
            reason: PrinterDeferralReason,
        ): Unit {
            defer(setOf(position), reason)
        }

        internal fun gateDescentCount(): Int {
            return gateHistory.filterNotNull().zipWithNext().count { (previous, current) ->
                current < previous
            }
        }

        internal fun tick(
            pathProbe: (Vec3, Vec3) -> PathProbeResult,
            isPassableCell: (BlockPos) -> Boolean = { true },
            submitReachablePlacement: Boolean = false,
            placementFilter: (BlockPos) -> Boolean = { true },
        ): MoverCommand {
            tick++
            val acceptedThisTick = processTerminalOutcome()
            updateGate()
            val selectedPlacement = if (submitReachablePlacement) {
                selectOneReachable(placementFilter)
            } else {
                null
            }
            val feedSnapshot = FeedSnapshot(
                tick = tick,
                queueRevision = queueRevision,
                candidateCount = if (selectedPlacement == null) 0 else 1,
                submittedCount = if (selectedPlacement == null) 0 else 1,
                rateLimitedRemainder = 0,
                inFlightCount = if (selectedPlacement == null) 0 else 1,
                acceptedThisTick = acceptedThisTick,
            )
            val command = core.tick(
                MoverTickContext(
                    sessionKey = sessionKey,
                    playerPos = playerPos,
                    onGround = false,
                    flying = true,
                    mayfly = true,
                    isCreative = true,
                    guiOpen = false,
                    hurt = false,
                    manualInput = false,
                    correctionReceived = false,
                    queueRevision = queueRevision,
                    feedSnapshot = feedSnapshot,
                    gateY = gateY,
                    gatePhase = gatePhase,
                    missingWorld = missing.toList(),
                    isPlaceable = { position -> isPlaceable(position) },
                    isPassableCell = isPassableCell,
                    reach = REACH,
                    pathProbe = pathProbe,
                    onWorkPositionAbandoned = { _ -> Unit },
                    onWorkPositionUnproductive = { _, deferPositions ->
                        defer(deferPositions, PrinterDeferralReason.NO_PROGRESS)
                    },
                    onPositionsUncoverable = { positions, cause ->
                        val reason = when (cause) {
                            MoverDeferralCause.MOVER_UNREACHABLE ->
                                PrinterDeferralReason.MOVER_UNREACHABLE
                            MoverDeferralCause.NO_PROGRESS -> PrinterDeferralReason.NO_PROGRESS
                        }
                        defer(positions, reason)
                    },
                    canComplete = {
                        breakdown = CompletionBreakdown(
                            totalRemaining = missing.size,
                            actionable = actionableRemaining(),
                        )
                        breakdown?.actionable == 0
                    },
                    onFinalSweepBackoffBypass = {
                        finalSweepGrants++
                        ledger.grantFinalSweepBackoffBypass()
                    },
                ),
            )
            if (selectedPlacement != null) {
                check(pendingAcceptedPlacement == null)
                pendingAcceptedPlacement = selectedPlacement.immutable()
            }
            return command
        }

        private fun updateGate(): Unit {
            val eligibleYs = missing.map { position -> position.y }.distinct().sorted()
            gateY = layerGate.update(
                eligibleYs = eligibleYs,
                isLayerSupported = { y ->
                    missing.any { position -> position.y == y && isPlaceableWithoutGate(position) }
                },
                inReachAboveGate = 0,
                inReachEligibleAtOrBelowGate = 0,
                acceptedThisTick = 0,
                playerMoved = false,
            )
            gatePhase = layerGate.currentPhase()
            gateHistory.add(gateY)
        }

        private fun selectOneReachable(placementFilter: (BlockPos) -> Boolean): BlockPos? {
            val eyePosition = Vec3(playerPos.x, playerPos.y + EYE_HEIGHT, playerPos.z)
            return missing
                .sortedWith(POSITION_ORDER)
                .firstOrNull { candidate ->
                    if (!placementFilter(candidate)) return@firstOrNull false
                    if (!isPlaceable(candidate)) return@firstOrNull false
                    val center = Vec3(
                        candidate.x + 0.5,
                        candidate.y + 0.5,
                        candidate.z + 0.5,
                    )
                    eyePosition.distanceToSqr(center) <= REACH * REACH
                }
        }

        private fun processTerminalOutcome(): Int {
            val position = pendingAcceptedPlacement ?: return 0
            states[position] = Blocks.STONE.defaultBlockState()
            missing.remove(position)
            placements.add(position)
            queueRevision++
            pendingAcceptedPlacement = null
            return 1
        }

        private fun isPlaceable(position: BlockPos): Boolean {
            return (gateY == null || position.y <= requireNotNull(gateY)) &&
                isPlaceableWithoutGate(position)
        }

        private fun isPlaceableWithoutGate(position: BlockPos): Boolean {
            return position in missing &&
                isActionableMissing(
                    position,
                    Blocks.STONE.defaultBlockState(),
                    stateAt,
                ) &&
                !ledger.isDeferred(position, stateAt, queueRevision)
        }

        private fun actionableRemaining(): Int {
            return missing.count { position -> isPlaceableWithoutGate(position) }
        }

        private fun defer(
            positions: Set<BlockPos>,
            reason: PrinterDeferralReason,
        ): Unit {
            for (position in positions) {
                ledger.defer(position, reason, stateAt, queueRevision)
            }
        }
    }

    private data class CompletionBreakdown(
        val totalRemaining: Int,
        val actionable: Int,
    )

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

    public companion object {
        private const val MAX_INTEGRATION_TICKS: Int = 500
        private const val FED_EMPTY_TICKS: Int = 2
        private const val TRANSFORM_REVISION: Long = 1L
        private const val REACH: Double = 4.0
        private const val EYE_HEIGHT: Double = 1.62

        private val POSITION_ORDER: Comparator<BlockPos> =
            compareBy<BlockPos> { position -> position.y }
                .thenBy { position -> position.x }
                .thenBy { position -> position.z }
        private val CLEAR_PROBE: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            PathProbeResult(clear = true, chunkLoaded = true)
        }
        private val BLOCKED_PROBE: (Vec3, Vec3) -> PathProbeResult = { _, _ ->
            PathProbeResult(clear = false, chunkLoaded = true)
        }

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
