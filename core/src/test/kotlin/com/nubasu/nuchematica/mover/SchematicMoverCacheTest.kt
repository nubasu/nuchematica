package com.nubasu.nuchematica.mover

import com.nubasu.nuchematica.schematic.MissingBlockChange
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class SchematicMoverCacheTest {
    @Test
    public fun cacheReusesMatchingKeysAndRebuildsOnlyOnStructuralIdentityChange(): Unit {
        var transformCalls = 0
        var offsetX = 0
        val cache = MoverMissingWorldCache { localPos ->
            transformCalls++
            localPos.offset(offsetX, 0, 0)
        }
        val content = EqualIdentity(1)
        val missingLocal = listOf(BlockPos.ZERO, BlockPos(1, 0, 0))
        val noChanges: (Long) -> List<MissingBlockChange>? = { emptyList() }

        val initial = cache.sync(1L, 10L, content, missingLocal, noChanges)
        assertEquals(setOf(BlockPos.ZERO, BlockPos(1, 0, 0)), initial.toSet())
        assertEquals(2, transformCalls)
        assertEquals(1, cache.rebuildCount)

        val reused = cache.sync(1L, 10L, content, listOf(BlockPos(99, 0, 0)), noChanges)
        assertEquals(initial.toSet(), reused.toSet())
        assertEquals(2, transformCalls)
        assertEquals(1, cache.rebuildCount)

        val revisionChangedNoDelta = cache.sync(2L, 10L, content, missingLocal, noChanges)
        assertEquals(initial.toSet(), revisionChangedNoDelta.toSet())
        assertEquals(2, transformCalls)
        assertEquals(1, cache.rebuildCount)

        offsetX = 10
        val transformChanged = cache.sync(2L, 11L, content, missingLocal, noChanges)
        assertEquals(2, cache.rebuildCount)
        assertEquals(4, transformCalls)
        assertTrue(transformChanged.contains(BlockPos(10, 0, 0)))

        val equalButDifferentContent = EqualIdentity(1)
        val contentChanged = cache.sync(2L, 11L, equalButDifferentContent, missingLocal, noChanges)
        assertEquals(3, cache.rebuildCount)
        assertEquals(6, transformCalls)
        assertTrue(contentChanged.isNotEmpty())
    }

    @Test
    public fun worldToLocalMapUpdatesIncrementallyViaChangesSince(): Unit {
        val cache = MoverMissingWorldCache { localPos -> localPos.offset(10, 0, 0) }
        val content = Any()
        val firstLocal = BlockPos.ZERO
        val secondLocal = BlockPos(1, 0, 0)

        cache.sync(0L, 10L, content, listOf(firstLocal)) { emptyList() }
        assertEquals(firstLocal, cache.localPosition(BlockPos(10, 0, 0)))
        assertEquals(1, cache.rebuildCount)

        val satisfied = MissingBlockChange(firstLocal, overlayChanged = true, satisfiedChanged = true, satisfied = true)
        cache.sync(1L, 10L, content, emptyList()) { listOf(satisfied) }
        assertNull(cache.localPosition(BlockPos(10, 0, 0)))
        assertEquals(1, cache.rebuildCount)

        val newlyMissing = MissingBlockChange(secondLocal, overlayChanged = true, satisfiedChanged = true, satisfied = false)
        cache.sync(2L, 10L, content, listOf(secondLocal)) { listOf(newlyMissing) }
        assertEquals(secondLocal, cache.localPosition(BlockPos(11, 0, 0)))
        assertEquals(1, cache.rebuildCount)
    }

    @Test
    public fun neverRebuildsOnOrdinaryRevisionBumpsAndMatchesFromScratchRebuild(): Unit {
        val localToWorld: (BlockPos) -> BlockPos = { local -> local.offset(100, 0, 0) }
        val allLocal = (0 until 40).map { i -> BlockPos(i, 0, 0) }
        val cache = MoverMissingWorldCache(localToWorld = localToWorld)
        val content = Any()

        var missingLocal = allLocal.toMutableList()
        cache.sync(0L, 10L, content, missingLocal) { null }
        assertEquals(1, cache.rebuildCount)

        val log = mutableListOf<MissingBlockChange>()
        var revision = 0L
        for (i in 0 until 25) {
            val removedLocal = missingLocal.removeAt(0)
            revision++
            log.add(MissingBlockChange(removedLocal, overlayChanged = true, satisfiedChanged = true, satisfied = true))
            cache.sync(revision, 10L, content, missingLocal) { since -> log.drop(since.toInt()) }
        }

        assertEquals(1, cache.rebuildCount) { "ordinary placement traffic must never rebuild" }

        val incremental = cache.sync(revision, 10L, content, missingLocal) { emptyList() }
        val fromScratch = MoverMissingWorldCache(localToWorld = localToWorld).rebuild(content, 10L, missingLocal)

        assertEquals(fromScratch.toSet(), incremental.toSet())
    }

    @Test
    public fun budgetedRebuildProcessesAtMostBudgetEntriesPerSyncCallAndConvergesToAFullRebuild(): Unit {
        var transformCalls = 0
        val localToWorld: (BlockPos) -> BlockPos = { local -> transformCalls++; local.offset(100, 0, 0) }
        val allLocal = (0 until 5).map { i -> BlockPos(i, 0, 0) }
        val budget = 2
        val cache = MoverMissingWorldCache(rebuildBudget = budget, localToWorld = localToWorld)
        val content = Any()
        val noChanges: (Long) -> List<MissingBlockChange>? = { emptyList() }

        val firstCall = cache.sync(0L, 10L, content, allLocal, noChanges)
        assertEquals(budget, transformCalls) { "one sync() call must never visit more than the budget" }
        assertTrue(cache.isRebuilding()) { "a 5-entry rebuild over a budget of 2 must span multiple calls" }
        assertEquals(1, cache.rebuildCount) { "the episode counts once, not once per pumped chunk" }
        assertTrue(firstCall.isEmpty()) { "the OLD (empty, pre-rebuild) result must stay visible untorn mid-rebuild" }

        val secondCall = cache.sync(0L, 10L, content, allLocal, noChanges)
        assertEquals(2 * budget, transformCalls)
        assertTrue(cache.isRebuilding())
        assertEquals(1, cache.rebuildCount)
        assertTrue(secondCall.isEmpty())

        val thirdCall = cache.sync(0L, 10L, content, allLocal, noChanges)
        assertEquals(5, transformCalls)
        assertFalse(cache.isRebuilding())
        assertEquals(1, cache.rebuildCount)
        assertEquals(allLocal.map { local -> local.offset(100, 0, 0) }.toSet(), thirdCall.toSet())

        cache.sync(0L, 10L, content, allLocal, noChanges)
        assertEquals(5, transformCalls) { "a completed rebuild must not be re-pumped" }
    }

    @Test
    public fun structuralRebuildLeavesThePreviousCompleteResultUntornUntilThePassFinishes(): Unit {
        val cache = MoverMissingWorldCache(rebuildBudget = 2) { local -> local.offset(100, 0, 0) }
        val contentA = Any()
        val setA = listOf(BlockPos(0, 0, 0), BlockPos(1, 0, 0))
        val expectedA = setA.map { local -> local.offset(100, 0, 0) }.toSet()

        val initial = cache.sync(0L, 10L, contentA, setA) { emptyList() }
        assertEquals(expectedA, initial.toSet())
        assertFalse(cache.isRebuilding())
        assertEquals(1, cache.rebuildCount)

        val contentB = Any()
        val setB = (0 until 5).map { i -> BlockPos(20 + i, 0, 0) }
        val expectedB = setB.map { local -> local.offset(100, 0, 0) }.toSet()

        val firstRebuildCall = cache.sync(0L, 10L, contentB, setB) { emptyList() }
        assertTrue(cache.isRebuilding())
        assertEquals(expectedA, firstRebuildCall.toSet()) {
            "mid-rebuild sync() must return the OLD complete result, not a prefix of the new one"
        }

        val secondRebuildCall = cache.sync(0L, 10L, contentB, setB) { emptyList() }
        assertTrue(cache.isRebuilding())
        assertEquals(expectedA, secondRebuildCall.toSet())

        val completed = cache.sync(0L, 10L, contentB, setB) { emptyList() }
        assertFalse(cache.isRebuilding())
        assertEquals(expectedB, completed.toSet())
        assertEquals(2, cache.rebuildCount)
    }

    @Test
    public fun rebuildAcrossMultipleSyncCallsStillReplaysAChangeThatLandedWhileItWasPumping(): Unit {
        val allLocal = (0 until 7).map { i -> BlockPos(i, 0, 0) }
        val cache = MoverMissingWorldCache(rebuildBudget = 3) { local -> local.offset(100, 0, 0) }
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

        cache.sync(0L, 10L, content, allLocal, changesSince)
        assertTrue(cache.isRebuilding())

        cache.sync(1L, 10L, content, allLocal, changesSince)
        assertTrue(cache.isRebuilding())

        val completed = cache.sync(1L, 10L, content, allLocal, changesSince)
        assertFalse(cache.isRebuilding())
        assertTrue(completed.contains(satisfiedMidRebuild.offset(100, 0, 0))) {
            "the rebuild's own swapped-in result must reflect missingLocal as of its own " +
                "start, satisfiedMidRebuild included"
        }

        val afterReplay = cache.sync(1L, 10L, content, allLocal, changesSince)
        assertFalse(afterReplay.contains(satisfiedMidRebuild.offset(100, 0, 0))) {
            "a change that landed mid-rebuild must still be replayed once the rebuild " +
                "completes -- the cache must not believe it is already caught up to " +
                "revision 1 just because that was the revision passed to the call that " +
                "happened to finish the pump"
        }
    }

    @Test
    public fun placeabilityMemoInvalidatesWhenLevelIdentityChanges(): Unit {
        val memo = MoverPlaceabilityMemo()
        val content = Any()
        val firstLevel = Any()
        var calculated = true
        var calculations = 0

        memo.synchronize(1L, 10L, content, firstLevel)
        assertTrue(memo.isPlaceable(BlockPos.ZERO) {
            calculations++
            calculated
        })

        calculated = false
        memo.synchronize(1L, 10L, content, firstLevel)
        assertTrue(memo.isPlaceable(BlockPos.ZERO) {
            calculations++
            calculated
        })
        assertEquals(1, calculations)

        memo.synchronize(1L, 10L, content, Any())
        assertFalse(memo.isPlaceable(BlockPos.ZERO) {
            calculations++
            calculated
        })
        assertEquals(2, calculations)
    }

    private data class EqualIdentity(val value: Int)
}
