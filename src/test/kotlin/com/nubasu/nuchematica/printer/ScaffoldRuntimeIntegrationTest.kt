package com.nubasu.nuchematica.printer

import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class ScaffoldRuntimeIntegrationTest {
    @Test
    public fun eaveRowScenarioCompletesWithZeroScaffoldsLeftUsingRecoveryPhaseAssist(): Unit {
        val targets = (0 until 4).map { i -> BlockPos(i, 5, 0) }
        val harness = Harness(targets, groundY = 3)

        var ticks = 0
        while (!harness.isFullyDrained() && ticks < MAX_TICKS) {
            ticks++
            harness.tick(phase = PrinterLayerGatePhase.RECOVERY)
        }

        assertTrue(harness.missing.isEmpty()) { "remaining=${harness.missing} after $ticks ticks" }
        assertTrue(harness.ledger.snapshot().isEmpty()) { "scaffold left outstanding in the ledger" }
        assertTrue(harness.scaffoldsUsed > 0)
        assertFalse(harness.world.values.any { state -> state == SCAFFOLD_BLOCK_STATE }) {
            "no scaffold material may remain in the world"
        }
    }

    @Test
    public fun ascentPhaseNowResolvesAScaffoldCandidateForAnUnsupportedOnlyTarget(): Unit {
        val target = BlockPos(0, 5, 0)
        val harness = Harness(listOf(target), groundY = 3)

        var ticks = 0
        while (!harness.isFullyDrained() && ticks < MAX_TICKS) {
            ticks++
            harness.tick(phase = PrinterLayerGatePhase.ASCENT)
        }

        assertTrue(harness.missing.isEmpty()) { "remaining=${harness.missing} after $ticks ticks" }
        assertTrue(harness.ledger.snapshot().isEmpty())
        assertTrue(harness.scaffoldsUsed > 0)
        assertFalse(harness.world.values.any { state -> state == SCAFFOLD_BLOCK_STATE }) {
            "no scaffold material may remain in the world"
        }
    }

    @Test
    public fun atMostOneScaffoldIsEverInFlightAtOnce(): Unit {
        val targets = listOf(BlockPos(0, 5, 0), BlockPos(10, 5, 0))
        val harness = Harness(targets, groundY = 3)
        var maxObservedOutstanding = 0

        var ticks = 0
        while (!harness.isFullyDrained() && ticks < MAX_TICKS) {
            ticks++
            harness.tick(phase = PrinterLayerGatePhase.RECOVERY)
            val outstanding = harness.ledger.snapshot().size +
                (if (harness.pendingScaffoldPlan != null) 1 else 0)
            if (outstanding > maxObservedOutstanding) maxObservedOutstanding = outstanding
        }

        assertTrue(harness.missing.isEmpty())
        assertEquals(1, maxObservedOutstanding)
        assertEquals(2, harness.scaffoldsUsed)
    }

    @Test
    public fun stalledScaffoldPlanWithoutAbandonmentPermanentlyBlocksAnUnrelatedTarget(): Unit {
        val stuckTarget = BlockPos(0, 5, 0)
        val stuckScaffoldCell = BlockPos(0, 4, 0)
        val reachableTarget = BlockPos(10, 5, 0)
        val harness = Harness(
            targets = listOf(stuckTarget, reachableTarget),
            groundY = 3,
            unreachableScaffoldCells = setOf(stuckScaffoldCell),
            stallAbandonTicks = null,
        )

        repeat(500) { harness.tick(phase = PrinterLayerGatePhase.RECOVERY) }

        assertTrue(reachableTarget in harness.missing) {
            "confirmed mechanism: reachableTarget must still be stuck behind the " +
                "permanently-stalled stuckTarget plan -- activeScaffoldPlan never " +
                "releases, so findScaffoldPlan never even looks at reachableTarget"
        }
        assertEquals(stuckTarget, harness.activeScaffoldPlan?.targetPos) {
            "the stalled plan must still be the one occupying the single in-flight slot"
        }
        assertEquals(0, harness.scaffoldsUsed) {
            "stuckScaffoldCell can never resolve to a candidate, so its own cell never lands either"
        }
    }

    @Test
    public fun stallAbandonmentFreesTheLedgerSoAnUnrelatedTargetStillCompletes(): Unit {
        val stuckTarget = BlockPos(0, 5, 0)
        val stuckScaffoldCell = BlockPos(0, 4, 0)
        val reachableTarget = BlockPos(10, 5, 0)
        val harness = Harness(
            targets = listOf(stuckTarget, reachableTarget),
            groundY = 3,
            unreachableScaffoldCells = setOf(stuckScaffoldCell),
            stallAbandonTicks = 5,
        )

        var ticks = 0
        while (reachableTarget in harness.missing && ticks < MAX_TICKS) {
            ticks++
            harness.tick(phase = PrinterLayerGatePhase.RECOVERY)
        }

        assertFalse(reachableTarget in harness.missing) {
            "reachableTarget must complete once the stalled plan is abandoned and " +
                "deferred -- ticks=$ticks"
        }
        assertTrue(stuckTarget in harness.missing) {
            "stuckTarget itself must NEVER be fabricated as placed -- it stays " +
                "genuinely missing, exactly like any other NO_PROGRESS position waiting" +
                " on backoff (this run's own abandon-then-revive cycles proved the" +
                " deferral fired at all: reachableTarget could only complete once" +
                " stuckTarget was, at some point, filtered out of the plan search)"
        }
    }

    @Test
    public fun pendantScenarioReachableOnlyViaATwoCellChainCompletesWithZeroScaffoldsLeft(): Unit {
        val target = BlockPos(0, 6, 0)
        val harness = Harness(listOf(target), groundY = 3)
        assertNull(
            ScaffoldPlanner.plan(
                targetPos = target,
                expectedState = Blocks.STONE.defaultBlockState(),
                isSchematicPosition = { it == target },
                stateAt = { pos -> if (pos.y <= 3) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState() },
                playerFeetPos = null,
            ),
        )

        var ticks = 0
        while (!harness.isFullyDrained() && ticks < MAX_TICKS) {
            ticks++
            harness.tick(phase = PrinterLayerGatePhase.RECOVERY)
        }

        assertTrue(harness.missing.isEmpty()) { "remaining=${harness.missing} after $ticks ticks" }
        assertTrue(harness.ledger.snapshot().isEmpty()) { "scaffold left outstanding in the ledger" }
        assertEquals(Blocks.STONE.defaultBlockState(), harness.world[target])
        assertFalse(harness.world.values.any { state -> state == SCAFFOLD_BLOCK_STATE }) {
            "no scaffold material may remain in the world"
        }
    }

    @Test
    public fun cleanupMidChainOnAbortTearsDownEveryCellPlacedSoFar(): Unit {
        val target = BlockPos(0, 6, 0)
        val harness = Harness(listOf(target), groundY = 3)

        var ticks = 0
        while (harness.ledger.snapshot().isEmpty() && ticks < MAX_TICKS) {
            ticks++
            harness.tick(phase = PrinterLayerGatePhase.RECOVERY)
        }
        assertTrue(harness.ledger.snapshot().isNotEmpty()) { "chain never started placing within $ticks ticks" }
        assertTrue(target in harness.missing) { "the real target must not have placed yet -- test aborts too late" }

        val leftover = harness.cleanupScaffolds()

        assertEquals(0, leftover)
        assertTrue(harness.ledger.snapshot().isEmpty())
        assertNull(harness.activeScaffoldPlan)
        assertNull(harness.pendingScaffoldPlan)
        assertFalse(harness.world.values.any { state -> state == SCAFFOLD_BLOCK_STATE }) {
            "no scaffold material may remain in the world after cleanup"
        }
        assertTrue(target in harness.missing) { "abort must not fabricate the real target's placement" }
    }

    @Test
    public fun terminalConfirmReArmsAStuckScaffoldOnceThenForceCleansUpAndAllowsCompletion(): Unit {
        val ledger = ScaffoldLedger()
        val scaffoldPos = BlockPos(0, 1, 0)
        val targetPos = BlockPos(1, 1, 0)
        ledger.recordPlaced(scaffoldPos, targetPos)
        ledger.markConsumed(targetPos)
        var pendingScaffoldPlan: ScaffoldStep? = ScaffoldStep(BlockPos(9, 1, 9), BlockPos(9, 2, 9))
        var rearmed = false
        val stillThere = Blocks.SLIME_BLOCK.defaultBlockState()
        var destroyCalls = 0
        val stateAt: (BlockPos) -> BlockState = { stillThere }
        val destroy: (BlockPos) -> Boolean = { destroyCalls++; false }

        fun exhaustRetries(startTick: Long): Long {
            var tick = startTick
            ledger.tickBreaks(tick, stateAt, destroy)
            val exhaustionTick = startTick + PrinterAttemptTracker.DEADLINE_TICKS *
                (ScaffoldLedger.MAX_BREAK_RETRIES + 1).toLong()
            while (tick < exhaustionTick) {
                tick++
                ledger.tickBreaks(tick = tick, stateAt = stateAt, destroy = destroy)
            }
            return tick
        }

        val afterFirstExhaustion = exhaustRetries(0L)
        assertTrue(ledger.snapshot().single().exhausted)

        var outcome = scaffoldTerminalOutcome(
            actionable = 0,
            scaffoldOutstanding = ledger.isOutstanding() || pendingScaffoldPlan != null,
            alreadyRearmedThisEncounter = rearmed,
        )
        rearmed = outcome == ScaffoldTerminalOutcome.REARM_AND_REFUSE
        assertEquals(ScaffoldTerminalOutcome.REARM_AND_REFUSE, outcome)
        pendingScaffoldPlan = null
        val rearmedCount = ledger.rearmExhausted()
        assertEquals(1, rearmedCount)
        assertNull(pendingScaffoldPlan)
        assertFalse(ledger.snapshot().single().exhausted) { "the re-armed round must reset exhausted" }

        exhaustRetries(afterFirstExhaustion + 1)
        assertTrue(ledger.isOutstanding())

        outcome = scaffoldTerminalOutcome(
            actionable = 0,
            scaffoldOutstanding = ledger.isOutstanding(),
            alreadyRearmedThisEncounter = rearmed,
        )
        rearmed = outcome == ScaffoldTerminalOutcome.REARM_AND_REFUSE
        assertEquals(ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP, outcome)
        val leftover = ledger.cleanupAll(stateAt = stateAt, destroy = destroy)

        assertEquals(1, leftover) { "the permanently-stuck cell is reported, never silently dropped" }
        assertFalse(ledger.isOutstanding()) { "cleanupAll evicts every record regardless of outcome" }
        assertFalse(rearmed) { "the guard must reset once the encounter resolves" }
    }

    @Test
    public fun orphanSweepFeedsTheLedgerAndTheOrphanIsFullyBrokenWithoutTouchingSchematicSlime(): Unit {
        val schematicSlimePos = BlockPos(5, 1, 5)
        val orphanPos = BlockPos(1, 1, 1)
        val expected = mapOf(
            BlockPos(0, 1, 0) to Blocks.STONE.defaultBlockState(),
            schematicSlimePos to Blocks.SLIME_BLOCK.defaultBlockState(),
        )
        val world = HashMap<BlockPos, BlockState>()
        world[orphanPos] = SCAFFOLD_BLOCK_STATE
        world[schematicSlimePos] = SCAFFOLD_BLOCK_STATE
        val stateAt: (BlockPos) -> BlockState = { pos -> world[pos] ?: Blocks.AIR.defaultBlockState() }
        val destroy: (BlockPos) -> Boolean = { pos -> world[pos] = Blocks.AIR.defaultBlockState(); true }
        val ledger = ScaffoldLedger()

        val bounds = requireNotNull(
            schematicWorldBoundingBox(localPositions = expected.keys, localToWorld = { it }, margin = 2),
        )
        val orphans = findOrphanScaffolds(
            boundsMin = bounds.first,
            boundsMax = bounds.second,
            expectedAt = expected::get,
            stateAt = stateAt,
        )
        assertEquals(listOf(orphanPos), orphans)
        for (orphan in orphans) {
            ledger.recordPlaced(orphan, orphan)
            ledger.markConsumed(orphan)
        }

        var ticks = 0L
        while (ledger.isOutstanding() && ticks < MAX_TICKS) {
            ticks++
            ledger.tickBreaks(tick = ticks, stateAt = stateAt, destroy = destroy)
        }

        assertFalse(ledger.isOutstanding())
        assertEquals(Blocks.AIR.defaultBlockState(), world[orphanPos])
        assertEquals(SCAFFOLD_BLOCK_STATE, world[schematicSlimePos]) {
            "the schematic's own slime block must be untouched"
        }
    }

    @Test
    public fun orphanSweepNeverRetouchesAPositionTheLedgerAlreadyTracks(): Unit {
        val inFlightScaffold = BlockPos(1, 1, 1)
        val realTarget = BlockPos(2, 1, 1)
        val expected = mapOf(BlockPos(0, 1, 0) to Blocks.STONE.defaultBlockState())
        val world = HashMap<BlockPos, BlockState>()
        world[inFlightScaffold] = SCAFFOLD_BLOCK_STATE
        val stateAt: (BlockPos) -> BlockState = { pos -> world[pos] ?: Blocks.AIR.defaultBlockState() }
        val ledger = ScaffoldLedger()
        ledger.recordPlaced(inFlightScaffold, realTarget)

        val bounds = requireNotNull(
            schematicWorldBoundingBox(localPositions = expected.keys, localToWorld = { it }, margin = 2),
        )
        val orphans = findOrphanScaffolds(
            boundsMin = bounds.first,
            boundsMax = bounds.second,
            expectedAt = expected::get,
            stateAt = stateAt,
        )
        assertEquals(listOf(inFlightScaffold), orphans) { "findOrphanScaffolds itself has no ledger visibility" }
        for (orphan in orphans) {
            if (ledger.isScaffoldCell(orphan)) continue
            ledger.recordPlaced(orphan, orphan)
            ledger.markConsumed(orphan)
        }

        assertEquals(ScaffoldState.PLACED, ledger.snapshot().single().state) {
            "the sweep's ledger guard must leave an already-tracked in-flight scaffold untouched"
        }
    }

    private class Harness(
        targets: List<BlockPos>,
        private val groundY: Int,
        private val unreachableScaffoldCells: Set<BlockPos> = emptySet(),
        private val stallAbandonTicks: Int? = null,
    ) {
        val missing: MutableSet<BlockPos> = targets.toMutableSet()
        val world: HashMap<BlockPos, BlockState> = HashMap()
        val ledger: ScaffoldLedger = ScaffoldLedger()
        var activeScaffoldPlan: ScaffoldPlan? = null
        var pendingScaffoldPlan: ScaffoldStep? = null
        var scaffoldsUsed: Int = 0
        val deferralLedger: PrinterDeferralLedger = PrinterDeferralLedger()
        private var scaffoldStallTicks: Int = 0

        private var tickCounter = 0L
        private val expectedStates: Map<BlockPos, BlockState> =
            targets.associateWith { Blocks.STONE.defaultBlockState() }
        private val schematicPositions: Set<BlockPos> = targets.toSet()
        private val sessionKey = PrinterSessionKey(Any(), Any(), 1L)
        private val selector = PrinterCandidateSelector(
            predictPlacement = { item, _ ->
                when (item) {
                    Blocks.STONE.asItem() -> Blocks.STONE.defaultBlockState()
                    Blocks.SLIME_BLOCK.asItem() -> SCAFFOLD_BLOCK_STATE
                    else -> null
                }
            },
        )
        private val runtime = PrinterRuntime(candidateSelector = selector)

        private val stateAt: (BlockPos) -> BlockState = { pos ->
            world[pos] ?: if (pos.y <= groundY) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        fun isFullyDrained(): Boolean {
            return missing.isEmpty() &&
                ledger.snapshot().isEmpty() &&
                pendingScaffoldPlan == null &&
                activeScaffoldPlan == null
        }

        fun cleanupScaffolds(): Int {
            pendingScaffoldPlan = null
            activeScaffoldPlan = null
            return ledger.cleanupAll(
                stateAt = stateAt,
                destroy = { pos -> world[pos] = Blocks.AIR.defaultBlockState(); true },
            )
        }

        fun tick(phase: PrinterLayerGatePhase): Unit {
            tickCounter++
            val eyePosition = Vec3(0.5, 100.0, 0.5)
            val reach = 1000.0

            var scaffoldStepForTick: ScaffoldStep? = null
            var scaffoldCandidateForTick: PrinterCandidate? = null
            if (pendingScaffoldPlan == null) {
                val currentPlan = activeScaffoldPlan
                val step = if (currentPlan != null) {
                    val next = currentPlan.nextStep(ledger::isScaffoldCell)
                    if (next == null) {
                        activeScaffoldPlan = null
                        null
                    } else {
                        next
                    }
                } else if (!ledger.isOutstanding()) {
                    val plan = missing.asSequence()
                        .filter { pos -> !hasSupportNeighbor(pos, expectedStates.getValue(pos), stateAt) }
                        .filter { pos -> !deferralLedger.isDeferred(pos, stateAt, tickCounter) }
                        .sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
                        .firstNotNullOfOrNull { pos ->
                            ScaffoldPlanner.plan(
                                targetPos = pos,
                                expectedState = expectedStates.getValue(pos),
                                isSchematicPosition = { candidate -> candidate in schematicPositions },
                                stateAt = stateAt,
                                playerFeetPos = null,
                            ) ?: ScaffoldChainPlanner.plan(
                                targetPos = pos,
                                expectedState = expectedStates.getValue(pos),
                                isSchematicPosition = { candidate -> candidate in schematicPositions },
                                stateAt = stateAt,
                                playerFeetPos = null,
                            )
                        }
                    if (plan != null) {
                        activeScaffoldPlan = plan
                        plan.nextStep(ledger::isScaffoldCell)
                    } else {
                        null
                    }
                } else {
                    null
                }
                if (step != null) {
                    scaffoldStepForTick = step
                    scaffoldCandidateForTick = if (step.scaffoldPos in unreachableScaffoldCells) {
                        null
                    } else {
                        selector.select(
                            missingLocal = listOf(step.scaffoldPos),
                            expectedStateAt = { SCAFFOLD_BLOCK_STATE },
                            localToWorld = { step.scaffoldPos },
                            stateAt = stateAt,
                            placementContext = { _, _ -> mockk(relaxed = true) },
                            eyePosition = eyePosition,
                            reach = reach,
                        ).firstOrNull()
                    }
                }
            }

            val completed = runtime.tick(
                PrinterTickContext(
                    tick = tickCounter,
                    queueRevision = 0L,
                    sessionKey = sessionKey,
                    missingLocal = missing.toList(),
                    expectedStateAt = expectedStates::get,
                    localToWorld = { it },
                    stateAt = stateAt,
                    placementContext = { _, _ -> mockk(relaxed = true) },
                    eyePosition = eyePosition,
                    reach = reach,
                    itemSupplier = ItemSupplier { true },
                    placementGateway = PlacementGateway { hit, _ ->
                        val targetPos = hit.blockPos.relative(hit.direction)
                        when {
                            scaffoldCandidateForTick != null && targetPos == scaffoldCandidateForTick.worldPos ->
                                world[targetPos] = SCAFFOLD_BLOCK_STATE
                            expectedStates.containsKey(targetPos) ->
                                world[targetPos] = expectedStates.getValue(targetPos)
                        }
                        true
                    },
                    scaffoldCandidate = scaffoldCandidateForTick,
                ),
            )

            if (
                scaffoldCandidateForTick != null && scaffoldStepForTick != null &&
                runtime.attemptTracker.isInFlight(scaffoldCandidateForTick.worldPos)
            ) {
                pendingScaffoldPlan = scaffoldStepForTick
            }
            for (result in completed) {
                val step = pendingScaffoldPlan
                if (step != null && result.attempt.worldPos == step.scaffoldPos) {
                    if (result.outcome == PrinterAttemptOutcome.ACCEPTED) {
                        ledger.recordPlaced(step.scaffoldPos, step.targetPos)
                        scaffoldsUsed++
                        scaffoldStallTicks = 0
                    }
                    pendingScaffoldPlan = null
                    continue
                }
                when (result.outcome) {
                    PrinterAttemptOutcome.ACCEPTED, PrinterAttemptOutcome.WRONG_STATE -> {
                        missing.remove(result.attempt.worldPos)
                        ledger.markConsumed(result.attempt.worldPos)
                    }
                    else -> Unit
                }
            }

            ledger.tickBreaks(
                tick = tickCounter,
                stateAt = stateAt,
                destroy = { pos -> world[pos] = Blocks.AIR.defaultBlockState(); true },
                onBroken = { scaffoldPos -> ledger.markConsumed(scaffoldPos) },
            )

            if (activeScaffoldPlan != null) {
                scaffoldStallTicks++
                val threshold = stallAbandonTicks
                if (threshold != null && scaffoldStallTicks >= threshold) {
                    val stalledTarget = activeScaffoldPlan?.targetPos
                    cleanupScaffolds()
                    if (stalledTarget != null) {
                        deferralLedger.defer(
                            stalledTarget,
                            PrinterDeferralReason.NO_PROGRESS,
                            stateAt,
                            queueRevision = tickCounter,
                        )
                    }
                    scaffoldStallTicks = 0
                }
            } else {
                scaffoldStallTicks = 0
            }
        }
    }

    public companion object {
        private const val MAX_TICKS: Int = 2000

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
