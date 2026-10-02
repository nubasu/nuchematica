package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.renderer.section.SectionKey
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class MissingOverlaySectionIndexTest {

    @Test
    public fun rebuildAllGroupsPositionsIntoSectionsAcrossBoundaries(): Unit {
        val index = MissingOverlaySectionIndex()

        val missing = listOf(BlockPos(0, 0, 0), BlockPos(15, 0, 0), BlockPos(16, 0, 0))
        val wrongBlock = listOf(BlockPos(-1, 0, 0))

        index.rebuildAll(missing, wrongBlock, emptyList())

        assertEquals(
            setOf(SectionKey(0, 0, 0), SectionKey(1, 0, 0), SectionKey(-1, 0, 0)),
            index.sectionKeys,
        )
        assertEquals(3, index.dirtyCount)

        val inputs = index.takeDirty(10).associateBy { it.key }
        assertEquals(
            listOf(BlockPos(0, 0, 0), BlockPos(15, 0, 0)),
            inputs.getValue(SectionKey(0, 0, 0)).missingPositions.sortedBy { it.x },
        )
        assertEquals(listOf(BlockPos(16, 0, 0)), inputs.getValue(SectionKey(1, 0, 0)).missingPositions)
        assertEquals(listOf(BlockPos(-1, 0, 0)), inputs.getValue(SectionKey(-1, 0, 0)).wrongBlockPositions)
    }

    @Test
    public fun applyMarksOnlyItsOwnSectionDirtyAndTracksTransitions(): Unit {
        val index = MissingOverlaySectionIndex()
        val target = BlockPos(0, 0, 0)
        val other = BlockPos(100, 0, 0)
        index.rebuildAll(listOf(target, other), emptyList(), emptyList())
        index.takeDirty(10) // drain the initial full-rebuild dirty state

        index.apply(target, missing = false, wrongBlock = true, extra = false)

        assertEquals(1, index.dirtyCount)
        val inputs = index.takeDirty(10)
        assertEquals(1, inputs.size)
        assertEquals(SectionKey.of(target), inputs[0].key)
        assertTrue(inputs[0].missingPositions.isEmpty())
        assertEquals(listOf(target), inputs[0].wrongBlockPositions)
    }

    @Test
    public fun lastPositionSatisfiedDrainsWithEmptyListsAndLeavesSectionKeys(): Unit {
        val index = MissingOverlaySectionIndex()
        val pos = BlockPos(5, 5, 5)
        index.rebuildAll(listOf(pos), emptyList(), emptyList())
        index.takeDirty(10)

        index.apply(pos, missing = false, wrongBlock = false, extra = false)
        val inputs = index.takeDirty(10)

        assertEquals(1, inputs.size)
        assertTrue(inputs[0].missingPositions.isEmpty())
        assertTrue(inputs[0].wrongBlockPositions.isEmpty())
        assertTrue(inputs[0].extraPositions.isEmpty())
        assertFalse(index.sectionKeys.contains(SectionKey.of(pos)))
    }

    @Test
    public fun generationBecomesStaleAfterApplyAndFreshAfterNextTakeDirty(): Unit {
        val index = MissingOverlaySectionIndex()
        val pos = BlockPos(1, 1, 1)
        index.rebuildAll(listOf(pos), emptyList(), emptyList())

        val first = index.takeDirty(10).single()
        assertTrue(index.isCurrent(first.key, first.generation))

        index.apply(pos, missing = false, wrongBlock = true, extra = false)
        assertFalse(index.isCurrent(first.key, first.generation))

        val second = index.takeDirty(10).single()
        assertTrue(index.isCurrent(second.key, second.generation))
        assertFalse(index.isCurrent(first.key, first.generation))
    }

    @Test
    public fun rebuildAllReturnsOnlyVanishedKeys(): Unit {
        val index = MissingOverlaySectionIndex()
        val survivor = BlockPos(0, 0, 0)
        val doomed = BlockPos(32, 0, 0)
        index.rebuildAll(listOf(survivor, doomed), emptyList(), emptyList())

        val vanished = index.rebuildAll(listOf(survivor), emptyList(), emptyList())

        assertEquals(setOf(SectionKey.of(doomed)), vanished)
        assertFalse(vanished.contains(SectionKey.of(survivor)))
    }

    @Test
    public fun takeDirtyHonoursLimitAndReturnsIndependentCopies(): Unit {
        val index = MissingOverlaySectionIndex()
        val positions = (0 until 5).map { BlockPos(it * SectionKey.SIZE, 0, 0) }
        index.rebuildAll(positions, emptyList(), emptyList())

        val firstBatch = index.takeDirty(2)
        assertEquals(2, firstBatch.size)
        assertEquals(3, index.dirtyCount)

        val takenKey = firstBatch[0].key
        val snapshotBefore = firstBatch[0].missingPositions.toList()
        val extraPosInSameSection = takenKey.minBlock().offset(1, 0, 0)
        index.apply(extraPosInSameSection, missing = true, wrongBlock = false, extra = false)

        assertEquals(snapshotBefore, firstBatch[0].missingPositions)
    }

    @Test
    public fun extraOnlySectionIsIndexedDirtiedAndDrainsEmptyOnceCleared(): Unit {
        val index = MissingOverlaySectionIndex()
        val extra = BlockPos(3, 3, 3)

        index.rebuildAll(emptyList(), emptyList(), listOf(extra))

        assertEquals(setOf(SectionKey.of(extra)), index.sectionKeys)
        assertEquals(1, index.dirtyCount)

        val inputs = index.takeDirty(10)
        assertEquals(1, inputs.size)
        assertEquals(SectionKey.of(extra), inputs[0].key)
        assertTrue(inputs[0].missingPositions.isEmpty())
        assertTrue(inputs[0].wrongBlockPositions.isEmpty())
        assertEquals(listOf(extra), inputs[0].extraPositions)

        index.apply(extra, missing = false, wrongBlock = false, extra = false)
        val drained = index.takeDirty(10)

        assertEquals(1, drained.size)
        assertTrue(drained[0].extraPositions.isEmpty())
        assertFalse(index.sectionKeys.contains(SectionKey.of(extra)))
    }
}
