package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.MissingBlockChange
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class SchematicPrinterCacheTest {
    @Test
    public fun eligibilityIsLazilyMemoizedPerPositionAndInvalidatesOnlyOnStructuralChange(): Unit {
        val eligibleState = mockk<BlockState>()
        val excludedState = mockk<BlockState>()
        var eligibilityCalls = 0
        var transformCalls = 0
        var offsetX = 0
        val cache = LayerGateMissingCache(
            eligibleState = { state ->
                eligibilityCalls++
                state === eligibleState
            },
            localToWorld = { localPos ->
                transformCalls++
                localPos.offset(offsetX, 0, 0)
            },
        )
        val first = BlockPos.ZERO
        val second = BlockPos(1, 0, 0)
        val states = linkedMapOf(first to eligibleState, second to excludedState)
        val content = Any()

        cache.synchronize(content, states, 10L)
        assertEquals(0, eligibilityCalls) { "synchronize() alone must not compute any entry" }
        assertEquals(0, transformCalls)

        val initialFirst = requireNotNull(cache[first])
        assertTrue(initialFirst.eligible)
        assertFalse(requireNotNull(cache[second]).eligible)
        assertEquals(2, eligibilityCalls)
        assertEquals(2, transformCalls)

        cache.synchronize(content, states, 10L)
        assertSame(initialFirst, cache[first])
        assertEquals(2, eligibilityCalls)
        assertEquals(2, transformCalls)

        cache.synchronize(content, states, 10L, substituteLookalikes = false)
        assertEquals(2, eligibilityCalls)
        assertEquals(2, transformCalls)
        assertNotSame(initialFirst, cache[first])
        assertEquals(3, eligibilityCalls)
        assertEquals(3, transformCalls)

        offsetX = 10
        cache.synchronize(content, states, 11L, substituteLookalikes = false)
        assertEquals(BlockPos(10, 0, 0), requireNotNull(cache[first]).worldPos)
        assertEquals(4, eligibilityCalls)
        assertEquals(4, transformCalls)

        cache.synchronize(Any(), states, 11L, substituteLookalikes = false)
        assertEquals(4, eligibilityCalls)
        assertEquals(4, transformCalls)
        cache[first]
        assertEquals(5, eligibilityCalls)
        assertEquals(5, transformCalls)
    }

    @Test
    public fun contentChangeNeverTriggersAFullContentPassOnlyPerPositionAccessDoes(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        var eligibilityCalls = 0
        var transformCalls = 0
        val cache = LayerGateMissingCache(
            eligibleState = { eligibilityCalls++; true },
            localToWorld = { localPos -> transformCalls++; localPos },
        )
        val largeContent = (0 until 100_000).associate { i -> BlockPos(i, 0, 0) to stone }

        cache.synchronize(Any(), largeContent, 1L)
        assertEquals(0, eligibilityCalls) { "synchronize() must not eagerly scan the content map" }
        assertEquals(0, transformCalls)

        val touched = BlockPos(42, 0, 0)
        assertEquals(touched, requireNotNull(cache[touched]).localPos)
        assertEquals(1, eligibilityCalls)
        assertEquals(1, transformCalls)
        assertNull(cache[BlockPos(-1, 0, 0)]) { "a position absent from content must stay absent" }
        assertEquals(1, eligibilityCalls) { "a miss on an absent position must not compute anything" }
    }

    @Test
    public fun supportMemoIsLazyAndInvalidatesEachRevisionKeyComponent(): Unit {
        val memo = LayerGateSupportMemo()
        val content = Any()
        val level = Any()
        val target = BlockPos(0, 1, 0)
        val expectedState = Blocks.STONE.defaultBlockState()
        var stateReads = 0
        val stateAt: (BlockPos) -> BlockState = { worldPos ->
            stateReads++
            if (worldPos == target.below()) {
                Blocks.STONE.defaultBlockState()
            } else {
                Blocks.AIR.defaultBlockState()
            }
        }

        memo.synchronize(1L, 10L, content, level)
        assertEquals(0, stateReads)
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        val initialReads = stateReads
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        assertEquals(initialReads, stateReads)

        memo.synchronize(1L, 10L, content, level)
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        assertEquals(initialReads, stateReads)

        memo.synchronize(2L, 10L, content, level)
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        val missingRevisionReads = stateReads
        assertTrue(missingRevisionReads > initialReads)

        memo.synchronize(2L, 11L, content, level)
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        val transformRevisionReads = stateReads
        assertTrue(transformRevisionReads > missingRevisionReads)

        val changedContent = Any()
        memo.synchronize(2L, 11L, changedContent, level)
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        val contentIdentityReads = stateReads
        assertTrue(contentIdentityReads > transformRevisionReads)

        memo.synchronize(2L, 11L, changedContent, Any())
        assertTrue(memo.hasSupport(target, expectedState, stateAt))
        assertTrue(stateReads > contentIdentityReads)
    }

    @Test
    public fun supportMemoExcludesDownNeighborForTopHalfExpectedState(): Unit {
        val memo = LayerGateSupportMemo()
        val target = BlockPos(0, 1, 0)
        val topSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        memo.synchronize(1L, 10L, Any(), Any())
        assertFalse(memo.hasSupport(target, topSlab, stateAt))
    }

    @Test
    public fun predictionMismatchDeferralIsStickyUntilNeighborChangesOrKeyInvalidates(): Unit {
        val ledger = PrinterDeferralLedger()
        val target = BlockPos(0, 1, 0)
        val content = Any()
        val level = Any()
        var westState = Blocks.STONE.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == target.west()) westState else Blocks.AIR.defaultBlockState()
        }

        ledger.synchronize(10L, content, level)
        ledger.defer(target, PrinterDeferralReason.PREDICTION_MISMATCH, stateAt)
        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 0L))

        ledger.synchronize(10L, content, level)
        assertTrue(ledger.isDeferred(target, stateAt, queueRevision = 0L))

        westState = Blocks.DIRT.defaultBlockState()
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 0L))
        westState = Blocks.STONE.defaultBlockState()
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 0L))

        ledger.defer(target, PrinterDeferralReason.PREDICTION_MISMATCH, stateAt)
        ledger.synchronize(11L, content, level)
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 0L))

        ledger.defer(target, PrinterDeferralReason.PREDICTION_MISMATCH, stateAt)
        ledger.synchronize(11L, Any(), level)
        assertFalse(ledger.isDeferred(target, stateAt, queueRevision = 0L))
    }

    @Test
    public fun scaffoldMemoIsLazyAndInvalidatesEachRevisionKeyComponent(): Unit {
        val memo = LayerGateScaffoldMemo()
        val content = Any()
        val level = Any()
        val target = BlockPos(0, 1, 0)
        val support = target.below().below()
        val expected = Blocks.STONE.defaultBlockState()
        var stateReads = 0
        val stateAt: (BlockPos) -> BlockState = { pos ->
            stateReads++
            if (pos == support) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { false }

        memo.synchronize(1L, 10L, content, level)
        assertEquals(0, stateReads)
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        val initialReads = stateReads
        assertTrue(initialReads > 0)
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        assertEquals(initialReads, stateReads)

        memo.synchronize(1L, 10L, content, level)
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        assertEquals(initialReads, stateReads)

        memo.synchronize(2L, 10L, content, level)
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        val missingRevisionReads = stateReads
        assertTrue(missingRevisionReads > initialReads)

        memo.synchronize(2L, 11L, content, level)
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        val transformRevisionReads = stateReads
        assertTrue(transformRevisionReads > missingRevisionReads)

        val changedContent = Any()
        memo.synchronize(2L, 11L, changedContent, level)
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        val contentIdentityReads = stateReads
        assertTrue(contentIdentityReads > transformRevisionReads)

        memo.synchronize(2L, 11L, changedContent, Any())
        assertTrue(memo.canScaffold(target, expected, stateAt, isSchematicPosition, null))
        assertTrue(stateReads > contentIdentityReads)
    }

    @Test
    public fun classifyMissingBucketsScaffoldPlannablePositionsSeparatelyFromTrulyUnsupportedOnes(): Unit {
        val plannable = BlockPos(0, 1, 0)
        val scaffoldCandidate = plannable.below()
        val scaffoldSupport = scaffoldCandidate.below()
        val unplannable = BlockPos(10, 1, 0)
        val expected = Blocks.STONE.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == scaffoldSupport) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { false }
        val ledger = PrinterDeferralLedger()
        ledger.synchronize(1L, Any(), Any())

        val classification = classifyMissing(
            entries = listOf(plannable to expected, unplannable to expected),
            stateAt = stateAt,
            queueRevision = 0L,
            deferralLedger = ledger,
            isSchematicPosition = isSchematicPosition,
            playerFeetPos = null,
        )

        assertEquals(1, classification.unsupported)
        assertEquals(1, classification.scaffoldAssisted)
        assertEquals(listOf(plannable to expected), classification.actionable)
    }

    @Test
    public fun classifyMissingBucketsAChainOnlyPositionAsScaffoldAssistedNotUnsupported(): Unit {
        val plannableViaChain = BlockPos(20, 5, 0)
        val expected = Blocks.STONE.defaultBlockState()
        val solidGround = BlockPos(20, 2, 0)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            if (pos == solidGround) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { pos -> pos == plannableViaChain }
        val ledger = PrinterDeferralLedger()
        ledger.synchronize(1L, Any(), Any())

        val classification = classifyMissing(
            entries = listOf(plannableViaChain to expected),
            stateAt = stateAt,
            queueRevision = 0L,
            deferralLedger = ledger,
            isSchematicPosition = isSchematicPosition,
            playerFeetPos = null,
        )

        assertEquals(0, classification.unsupported)
        assertEquals(1, classification.scaffoldAssisted)
        assertEquals(listOf(plannableViaChain to expected), classification.actionable)
    }

    @Test
    public fun eligibleMissingCacheSortsDistinctYsAndInvalidatesEachKeyComponent(): Unit {
        val first = BlockPos(0, 0, 0)
        val second = BlockPos(1, 0, 0)
        val lower = BlockPos(2, 0, 0)
        val excluded = BlockPos(3, 0, 0)
        val stone = Blocks.STONE.defaultBlockState()
        val entries = mapOf(
            first to LayerGateMissing(first, BlockPos(10, 5, 0), eligible = true, expectedState = stone),
            second to LayerGateMissing(second, BlockPos(11, 5, 0), eligible = true, expectedState = stone),
            lower to LayerGateMissing(lower, BlockPos(12, 2, 0), eligible = true, expectedState = stone),
            excluded to LayerGateMissing(excluded, BlockPos(13, 1, 0), eligible = false, expectedState = stone),
        )
        var entryReads = 0
        val cache = LayerGateEligibleMissingCache { localPos ->
            entryReads++
            entries[localPos]
        }
        val missingLocal = listOf(second, lower, first, excluded)
        val content = Any()
        val noChanges: (Long) -> List<MissingBlockChange>? = { emptyList() }

        val initial = cache.sync(content, 10L, true, 1L, missingLocal, noChanges)
        assertEquals(listOf(2, 5), initial.sortedDistinctYs)
        assertEquals(
            listOf(BlockPos(11, 5, 0), BlockPos(10, 5, 0)),
            initial.byY[5]?.map { entry -> entry.worldPos },
        )
        assertEquals(4, entryReads)
        assertEquals(1, cache.rebuildCount)

        val unchanged = cache.sync(content, 10L, true, 1L, missingLocal, noChanges)
        assertEquals(listOf(2, 5), unchanged.sortedDistinctYs)
        assertEquals(4, entryReads)
        assertEquals(1, cache.rebuildCount)

        val eligibilityChanged = cache.sync(content, 10L, false, 1L, missingLocal, noChanges)
        assertEquals(8, entryReads)
        assertEquals(2, cache.rebuildCount)

        val missingRevisionChanged = cache.sync(content, 10L, false, 2L, missingLocal, noChanges)
        assertEquals(eligibilityChanged.sortedDistinctYs, missingRevisionChanged.sortedDistinctYs)
        assertEquals(8, entryReads)
        assertEquals(2, cache.rebuildCount)

        val transformRevisionChanged = cache.sync(content, 11L, false, 2L, missingLocal, noChanges)
        assertEquals(12, entryReads)
        assertEquals(3, cache.rebuildCount)

        val contentChanged = cache.sync(Any(), 11L, false, 2L, missingLocal, noChanges)
        assertEquals(16, entryReads)
        assertEquals(4, cache.rebuildCount)
        assertEquals(listOf(2, 5), contentChanged.sortedDistinctYs)
        assertEquals(listOf(2, 5), transformRevisionChanged.sortedDistinctYs)
    }

    @Test
    public fun eligibleMissingCacheNeverRebuildsOnOrdinaryRevisionBumpsAndMatchesFromScratchRebuild(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val worldOf: (BlockPos) -> BlockPos = { local -> BlockPos(local.x, local.x % 4, local.z) }
        val allLocal = (0 until 40).map { i -> BlockPos(i, 0, 0) }
        val entryAt: (BlockPos) -> LayerGateMissing? = { local ->
            LayerGateMissing(local, worldOf(local), eligible = true, expectedState = stone)
        }
        val cache = LayerGateEligibleMissingCache(entryAt = entryAt)
        val content = Any()

        var missingLocal = allLocal.toMutableList()
        cache.sync(content, 10L, true, 0L, missingLocal) { null }
        assertEquals(1, cache.rebuildCount)

        val log = mutableListOf<MissingBlockChange>()
        var revision = 0L
        for (i in 0 until 25) {
            val removedLocal = missingLocal.removeAt(0)
            revision++
            log.add(MissingBlockChange(removedLocal, overlayChanged = true, satisfiedChanged = true, satisfied = true))
            val changesSince: (Long) -> List<MissingBlockChange>? = { since -> log.drop(since.toInt()) }
            cache.sync(content, 10L, true, revision, missingLocal, changesSince)
        }

        assertEquals(1, cache.rebuildCount) { "ordinary placement traffic must never rebuild" }

        val incremental = cache.sync(content, 10L, true, revision, missingLocal) { emptyList() }
        val fromScratch = LayerGateEligibleMissingCache(entryAt = entryAt).rebuild(content, 10L, missingLocal)

        assertEquals(fromScratch.sortedDistinctYs, incremental.sortedDistinctYs)
        for (y in fromScratch.sortedDistinctYs) {
            assertEquals(
                fromScratch.byY.getValue(y).map { it.localPos }.toSet(),
                incremental.byY.getValue(y).map { it.localPos }.toSet(),
                "mismatch at y=$y",
            )
        }
    }

    @Test
    public fun eligibleMissingCacheRebuildIsBudgetedPerSyncCallAndConvergesToAFullRebuild(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val allLocal = (0 until 7).map { i -> BlockPos(i, 0, 0) }
        val worldOf: (BlockPos) -> BlockPos = { local -> BlockPos(local.x, local.x, 0) }
        var entryReads = 0
        val entryAt: (BlockPos) -> LayerGateMissing? = { local ->
            entryReads++
            LayerGateMissing(local, worldOf(local), eligible = true, expectedState = stone)
        }
        val budget = 3
        val cache = LayerGateEligibleMissingCache(entryAt = entryAt, rebuildBudget = budget)
        val content = Any()
        val noChanges: (Long) -> List<MissingBlockChange>? = { emptyList() }

        val firstCall = cache.sync(content, 10L, true, 0L, allLocal, noChanges)
        assertEquals(budget, entryReads) { "one sync() call must never visit more than the budget" }
        assertTrue(cache.isRebuilding()) { "a 7-entry rebuild over a budget of 3 must span multiple calls" }
        assertEquals(1, cache.rebuildCount) { "the episode counts once, not once per pumped chunk" }
        assertTrue(firstCall.sortedDistinctYs.isEmpty()) {
            "the OLD (empty, pre-rebuild) result must stay visible untorn mid-rebuild"
        }

        val secondCall = cache.sync(content, 10L, true, 0L, allLocal, noChanges)
        assertEquals(2 * budget, entryReads)
        assertTrue(cache.isRebuilding())
        assertEquals(1, cache.rebuildCount)
        assertTrue(secondCall.sortedDistinctYs.isEmpty())

        val thirdCall = cache.sync(content, 10L, true, 0L, allLocal, noChanges)
        assertEquals(7, entryReads)
        assertFalse(cache.isRebuilding())
        assertEquals(1, cache.rebuildCount)

        cache.sync(content, 10L, true, 0L, allLocal, noChanges)
        assertEquals(7, entryReads) { "a completed rebuild must not be re-pumped" }

        val plainEntryAt: (BlockPos) -> LayerGateMissing? = { local ->
            LayerGateMissing(local, worldOf(local), eligible = true, expectedState = stone)
        }
        val fromScratch = LayerGateEligibleMissingCache(entryAt = plainEntryAt).rebuild(content, 10L, allLocal)
        assertEquals(fromScratch.sortedDistinctYs, thirdCall.sortedDistinctYs)
        for (y in fromScratch.sortedDistinctYs) {
            assertEquals(
                fromScratch.byY.getValue(y).map { it.localPos }.toSet(),
                thirdCall.byY.getValue(y).map { it.localPos }.toSet(),
                "mismatch at y=$y",
            )
        }
    }

    @Test
    public fun rebuildAcrossMultipleCallsStillReplaysAChangeThatLandedWhileItWasPumping(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val allLocal = (0 until 7).map { i -> BlockPos(i, 0, 0) }
        val worldOf: (BlockPos) -> BlockPos = { local -> BlockPos(local.x, local.x, 0) }
        val entryAt: (BlockPos) -> LayerGateMissing? = { local ->
            LayerGateMissing(local, worldOf(local), eligible = true, expectedState = stone)
        }
        val budget = 3
        val cache = LayerGateEligibleMissingCache(entryAt = entryAt, rebuildBudget = budget)
        val content = Any()

        val satisfiedMidRebuild = allLocal[0]
        val delta = MissingBlockChange(
            localPos = satisfiedMidRebuild,
            overlayChanged = true,
            satisfiedChanged = true,
            satisfied = true,
        )
        val changesSince: (Long) -> List<MissingBlockChange>? = { since ->
            if (since == 0L) listOf(delta) else emptyList()
        }

        cache.sync(content, 10L, true, 0L, allLocal, changesSince)
        assertTrue(cache.isRebuilding())

        cache.sync(content, 10L, true, 1L, allLocal, changesSince)
        assertTrue(cache.isRebuilding())

        val completed = cache.sync(content, 10L, true, 1L, allLocal, changesSince)
        assertFalse(cache.isRebuilding())
        assertTrue(
            completed.byY.values.flatten().any { it.localPos == satisfiedMidRebuild },
        ) { "the rebuild's own swapped-in result must reflect missingLocal as of its own start, satisfiedMidRebuild included" }

        val afterReplay = cache.sync(content, 10L, true, 1L, allLocal, changesSince)
        assertFalse(
            afterReplay.byY.values.flatten().any { it.localPos == satisfiedMidRebuild },
        ) {
            "a change that landed mid-rebuild must still be replayed once the rebuild " +
                "completes -- the cache must not believe it is already caught up to " +
                "revision 1 just because that was the revision passed to the call that " +
                "happened to finish the pump"
        }
    }

    @Test
    public fun topSlabIslandLayerIsUnsupportedSoGateAdvancesToLayerAbove(): Unit {
        val slabLocal = BlockPos(0, 0, 0)
        val stoneLocal = BlockPos(1, 0, 0)
        val slabWorld = BlockPos(0, 5, 0)
        val stoneWorld = BlockPos(0, 6, 0)
        val topSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
        val plainStone = Blocks.STONE.defaultBlockState()
        val entries = mapOf(
            slabLocal to LayerGateMissing(slabLocal, slabWorld, eligible = true, expectedState = topSlab),
            stoneLocal to LayerGateMissing(stoneLocal, stoneWorld, eligible = true, expectedState = plainStone),
        )
        val eligibleCache = LayerGateEligibleMissingCache { localPos -> entries[localPos] }
        val eligibleMissing = eligibleCache.rebuild(
            contentIdentity = Any(),
            transformRevision = 10L,
            missingLocal = listOf(slabLocal, stoneLocal),
        )
        assertEquals(listOf(5, 6), eligibleMissing.sortedDistinctYs)

        val stateAt: (BlockPos) -> BlockState = { pos ->
            when (pos) {
                slabWorld.below() -> Blocks.STONE.defaultBlockState()
                stoneWorld.relative(Direction.EAST) -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
        }
        val supportMemo = LayerGateSupportMemo()
        supportMemo.synchronize(
            missingRevision = 1L,
            transformRevision = 10L,
            contentIdentity = Any(),
            levelIdentity = Any(),
        )
        val isLayerSupported: (Int) -> Boolean = { y ->
            eligibleMissing.byY[y]?.any { entry ->
                supportMemo.hasSupport(entry.worldPos, entry.expectedState, stateAt)
            } == true
        }

        assertFalse(isLayerSupported(5))
        assertTrue(isLayerSupported(6))

        val gateY = PrinterLayerGate().update(
            eligibleYs = eligibleMissing.sortedDistinctYs,
            isLayerSupported = isLayerSupported,
            inReachAboveGate = 0,
            inReachEligibleAtOrBelowGate = 0,
            acceptedThisTick = 0,
            playerMoved = false,
        )
        assertEquals(6, gateY)
    }

    @Test
    public fun scaffoldTerminalOutcomeRefusesWheneverOtherActionableWorkRemains(): Unit {
        assertEquals(
            ScaffoldTerminalOutcome.REFUSE,
            scaffoldTerminalOutcome(actionable = 1, scaffoldOutstanding = false, alreadyRearmedThisEncounter = false),
        )
        assertEquals(
            ScaffoldTerminalOutcome.REFUSE,
            scaffoldTerminalOutcome(actionable = 1, scaffoldOutstanding = true, alreadyRearmedThisEncounter = true),
        )
    }

    @Test
    public fun scaffoldTerminalOutcomeAllowsImmediatelyWithNothingActionableAndNoScaffoldOutstanding(): Unit {
        assertEquals(
            ScaffoldTerminalOutcome.ALLOW_CLEAR,
            scaffoldTerminalOutcome(actionable = 0, scaffoldOutstanding = false, alreadyRearmedThisEncounter = false),
        )
        assertEquals(
            ScaffoldTerminalOutcome.ALLOW_CLEAR,
            scaffoldTerminalOutcome(actionable = 0, scaffoldOutstanding = false, alreadyRearmedThisEncounter = true),
        )
    }

    @Test
    public fun scaffoldTerminalOutcomeConvergesToAllowWithinTwoCallsUnderSustainedRefusalAndNeverLoops(): Unit {
        var rearmed = false
        var scaffoldOutstanding = true
        val outcomes = mutableListOf<ScaffoldTerminalOutcome>()
        repeat(6) {
            val outcome = scaffoldTerminalOutcome(
                actionable = 0,
                scaffoldOutstanding = scaffoldOutstanding,
                alreadyRearmedThisEncounter = rearmed,
            )
            outcomes.add(outcome)
            rearmed = outcome == ScaffoldTerminalOutcome.REARM_AND_REFUSE
            if (outcome == ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP) scaffoldOutstanding = false
        }

        assertEquals(ScaffoldTerminalOutcome.REARM_AND_REFUSE, outcomes[0])
        assertEquals(ScaffoldTerminalOutcome.ALLOW_FORCE_CLEANUP, outcomes[1])
        assertTrue(outcomes.drop(2).all { it == ScaffoldTerminalOutcome.ALLOW_CLEAR }) {
            "outcomes=$outcomes"
        }
    }

    @Test
    public fun scaffoldTerminalOutcomeStartsAFreshEncounterOnceScaffoldClearsNaturally(): Unit {
        val first = scaffoldTerminalOutcome(
            actionable = 0,
            scaffoldOutstanding = true,
            alreadyRearmedThisEncounter = false,
        )
        assertEquals(ScaffoldTerminalOutcome.REARM_AND_REFUSE, first)
        val rearmedAfterFirst = first == ScaffoldTerminalOutcome.REARM_AND_REFUSE

        val second = scaffoldTerminalOutcome(
            actionable = 0,
            scaffoldOutstanding = false,
            alreadyRearmedThisEncounter = rearmedAfterFirst,
        )
        assertEquals(ScaffoldTerminalOutcome.ALLOW_CLEAR, second)
        val rearmedAfterSecond = second == ScaffoldTerminalOutcome.REARM_AND_REFUSE
        assertFalse(rearmedAfterSecond)

        val third = scaffoldTerminalOutcome(
            actionable = 0,
            scaffoldOutstanding = true,
            alreadyRearmedThisEncounter = rearmedAfterSecond,
        )
        assertEquals(ScaffoldTerminalOutcome.REARM_AND_REFUSE, third)
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
