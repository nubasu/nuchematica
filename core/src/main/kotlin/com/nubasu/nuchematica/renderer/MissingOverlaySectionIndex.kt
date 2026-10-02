package com.nubasu.nuchematica.renderer

import com.nubasu.nuchematica.renderer.section.SectionKey
import net.minecraft.core.BlockPos

internal class MissingOverlaySectionBuildInput(
    internal val key: SectionKey,
    internal val generation: Long,
    internal val wrongBlockPositions: List<BlockPos>,
    internal val missingPositions: List<BlockPos>,
    internal val extraPositions: List<BlockPos>,
)

internal class MissingOverlaySectionEntry(internal var generation: Long = 0L) {
    internal val wrongBlock: MutableSet<BlockPos> = LinkedHashSet()
    internal val missing: MutableSet<BlockPos> = LinkedHashSet()
    internal val extra: MutableSet<BlockPos> = LinkedHashSet()

    internal fun isEmpty(): Boolean = wrongBlock.isEmpty() && missing.isEmpty() && extra.isEmpty()
}

/** Groups missing-overlay positions by their 16-block section, main-thread-only bookkeeping. */
internal class MissingOverlaySectionIndex {
    private val sections: HashMap<SectionKey, MissingOverlaySectionEntry> = HashMap()
    private val dirtyKeys: LinkedHashSet<SectionKey> = LinkedHashSet()

    internal val dirtyCount: Int
        get() = dirtyKeys.size

    internal val sectionKeys: Set<SectionKey>
        get() = sections.entries.asSequence().filter { !it.value.isEmpty() }.mapTo(LinkedHashSet()) { it.key }

    /** Replaces the whole index; marks every remaining section dirty; returns keys that vanished. */
    internal fun rebuildAll(
        missingPositions: Iterable<BlockPos>,
        wrongBlockPositions: Iterable<BlockPos>,
        extraPositions: Iterable<BlockPos>,
    ): Set<SectionKey> {
        val previousKeys = sectionKeys
        val nextSections = HashMap<SectionKey, MissingOverlaySectionEntry>()

        fun entryFor(pos: BlockPos): MissingOverlaySectionEntry {
            val key = SectionKey.of(pos)
            return nextSections.getOrPut(key) {
                MissingOverlaySectionEntry(generation = sections[key]?.generation ?: 0L)
            }
        }
        for (pos in wrongBlockPositions) entryFor(pos).wrongBlock.add(pos)
        for (pos in missingPositions) entryFor(pos).missing.add(pos)
        for (pos in extraPositions) entryFor(pos).extra.add(pos)

        sections.clear()
        sections.putAll(nextSections)
        dirtyKeys.clear()
        dirtyKeys.addAll(sections.keys)

        return previousKeys - sections.keys
    }

    /** Records the current status of one position and marks its section dirty. All false = satisfied. */
    internal fun apply(localPos: BlockPos, missing: Boolean, wrongBlock: Boolean, extra: Boolean): SectionKey {
        val key = SectionKey.of(localPos)
        val entry = sections.getOrPut(key) { MissingOverlaySectionEntry() }
        if (wrongBlock) entry.wrongBlock.add(localPos) else entry.wrongBlock.remove(localPos)
        if (missing) entry.missing.add(localPos) else entry.missing.remove(localPos)
        if (extra) entry.extra.add(localPos) else entry.extra.remove(localPos)
        dirtyKeys.add(key)
        return key
    }

    /** Pops up to [limit] dirty sections, bumping each section's generation, with snapshot lists. */
    internal fun takeDirty(limit: Int): List<MissingOverlaySectionBuildInput> {
        if (limit <= 0 || dirtyKeys.isEmpty()) return emptyList()
        val result = ArrayList<MissingOverlaySectionBuildInput>(minOf(limit, dirtyKeys.size))
        val iterator = dirtyKeys.iterator()
        while (iterator.hasNext() && result.size < limit) {
            val key = iterator.next()
            iterator.remove()
            val entry = sections.getValue(key)
            entry.generation++
            result.add(
                MissingOverlaySectionBuildInput(
                    key = key,
                    generation = entry.generation,
                    wrongBlockPositions = entry.wrongBlock.toList(),
                    missingPositions = entry.missing.toList(),
                    extraPositions = entry.extra.toList(),
                ),
            )
        }
        return result
    }

    /** True when [generation] is still the newest generation issued for [key] and it has not been re-dirtied since. */
    internal fun isCurrent(key: SectionKey, generation: Long): Boolean {
        val entry = sections[key] ?: return false
        return entry.generation == generation && key !in dirtyKeys
    }
}
