package com.nubasu.nuchematica.renderer.section

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import java.util.Collections

internal class SectionedValueSnapshot<T> private constructor(
    internal val allValues: Map<BlockPos, T>,
    internal val valuesBySection: Map<SectionKey, Map<BlockPos, T>>,
) {
    internal fun valueAt(pos: BlockPos): T? {
        return allValues[pos]
    }

    internal fun valuesInSection(key: SectionKey): Map<BlockPos, T> {
        return valuesBySection[key] ?: emptyMap()
    }

    internal companion object {
        internal fun <T> copyOf(source: Map<BlockPos, T>): SectionedValueSnapshot<T> {
            val orderedEntries = source.entries
                .map { entry -> BlockPos(entry.key.x, entry.key.y, entry.key.z) to entry.value }
                .sortedWith(
                    compareBy<Pair<BlockPos, T>>(
                        { it.first.x },
                        { it.first.y },
                        { it.first.z },
                    ),
                )

            val allValues = LinkedHashMap<BlockPos, T>(orderedEntries.size)
            val mutableSections = LinkedHashMap<SectionKey, LinkedHashMap<BlockPos, T>>()
            for ((pos, value) in orderedEntries) {
                allValues[pos] = value
                mutableSections.getOrPut(SectionKey.of(pos), ::LinkedHashMap)[pos] = value
            }

            val valuesBySection = LinkedHashMap<SectionKey, Map<BlockPos, T>>(mutableSections.size)
            for ((key, values) in mutableSections) {
                valuesBySection[key] = Collections.unmodifiableMap(values)
            }
            return SectionedValueSnapshot(
                allValues = Collections.unmodifiableMap(allValues),
                valuesBySection = Collections.unmodifiableMap(valuesBySection),
            )
        }
    }
}

internal class SchematicContentSnapshot private constructor(
    private val values: SectionedValueSnapshot<BlockState>,
    private val airBlockState: BlockState,
) {
    internal val allBlocks: Map<BlockPos, BlockState>
        get() = values.allValues

    internal val blocksBySection: Map<SectionKey, Map<BlockPos, BlockState>>
        get() = values.valuesBySection

    internal fun blockStateAt(pos: BlockPos): BlockState {
        return values.valueAt(pos) ?: airBlockState
    }

    internal fun fluidStateAt(pos: BlockPos): FluidState {
        return blockStateAt(pos).fluidState
    }

    internal fun blocksInSection(key: SectionKey): Map<BlockPos, BlockState> {
        return values.valuesInSection(key)
    }

    internal companion object {
        internal fun copyOf(
            source: Map<BlockPos, BlockState>,
            airBlockState: BlockState,
        ): SchematicContentSnapshot {
            return SchematicContentSnapshot(SectionedValueSnapshot.copyOf(source), airBlockState)
        }
    }
}
