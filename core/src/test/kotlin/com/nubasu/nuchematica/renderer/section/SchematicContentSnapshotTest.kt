package com.nubasu.nuchematica.renderer.section

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class SchematicContentSnapshotTest {

    @Test
    public fun genericCoreCopiesSourceAndSplitsSignedSectionsOnce(): Unit {
        val mutableKey = BlockPos.MutableBlockPos(-1, 2, 3)
        val source = linkedMapOf<BlockPos, String>(
            mutableKey to "west",
            BlockPos(16, 2, 3) to "east",
        )
        val snapshot = SectionedValueSnapshot.copyOf(source)

        mutableKey.set(80, 2, 3)
        source.clear()

        assertEquals("west", snapshot.valueAt(BlockPos(-1, 2, 3)))
        assertEquals("east", snapshot.valueAt(BlockPos(16, 2, 3)))
        assertEquals(setOf(SectionKey(-1, 0, 0), SectionKey(1, 0, 0)), snapshot.valuesBySection.keys)
        assertFalse(snapshot.allValues.containsKey(BlockPos(80, 2, 3)))
    }

    @Test
    public fun minecraftAdapterUsesRealBlockStatesAndAirOutsideContent(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val water = Blocks.WATER.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val snapshot = SchematicContentSnapshot.copyOf(
            mapOf(
                BlockPos(15, 0, 0) to stone,
                BlockPos(16, 0, 0) to water,
            ),
            air,
        )

        assertSame(stone, snapshot.blockStateAt(BlockPos(15, 0, 0)))
        assertSame(water, snapshot.blockStateAt(BlockPos(16, 0, 0)))
        assertSame(air, snapshot.blockStateAt(BlockPos(17, 0, 0)))
        assertSame(water.fluidState, snapshot.fluidStateAt(BlockPos(16, 0, 0)))
        assertEquals(setOf(SectionKey(0, 0, 0), SectionKey(1, 0, 0)), snapshot.blocksBySection.keys)
    }

    @Test
    public fun sourceIsIteratedOnceAndPublishedMapsAreReused(): Unit {
        val source = IterationCountingMap(
            linkedMapOf(
                BlockPos.ZERO to Blocks.STONE.defaultBlockState(),
                BlockPos(16, 0, 0) to Blocks.GLASS.defaultBlockState(),
            ),
        )
        val snapshot = SchematicContentSnapshot.copyOf(source, Blocks.AIR.defaultBlockState())
        val publishedAllBlocks = snapshot.allBlocks
        val publishedSections = snapshot.blocksBySection

        repeat(5) {
            assertSame(publishedAllBlocks, snapshot.allBlocks)
            assertSame(publishedSections, snapshot.blocksBySection)
            snapshot.blocksInSection(SectionKey(0, 0, 0))
        }

        assertEquals(1, source.iterations)
    }

    private class IterationCountingMap<K, V>(
        private val delegate: Map<K, V>,
    ) : AbstractMap<K, V>() {
        var iterations: Int = 0
            private set

        override val entries: Set<Map.Entry<K, V>>
            get() = object : AbstractSet<Map.Entry<K, V>>() {
                override val size: Int
                    get() = delegate.size

                override fun iterator(): Iterator<Map.Entry<K, V>> {
                    iterations++
                    return delegate.entries.iterator()
                }
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
