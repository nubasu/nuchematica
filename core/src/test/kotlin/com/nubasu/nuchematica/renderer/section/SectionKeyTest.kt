package com.nubasu.nuchematica.renderer.section

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

public class SectionKeyTest {

    @Test
    public fun mapsSignedBlockBoundariesWithArithmeticShift(): Unit {
        val expected = mapOf(
            -17 to -2,
            -16 to -1,
            -1 to -1,
            0 to 0,
            15 to 0,
            16 to 1,
            17 to 1,
        )

        for ((coordinate, section) in expected) {
            assertEquals(
                SectionKey(section, section, section),
                SectionKey.of(BlockPos(coordinate, coordinate, coordinate)),
                "coordinate=$coordinate",
            )
        }
    }

    @Test
    public fun exposesInclusiveMinimumAndExclusiveMaximum(): Unit {
        val key = SectionKey(-2, 1, 3)

        assertEquals(BlockPos(-32, 16, 48), key.minBlock())
        assertEquals(BlockPos(-16, 32, 64), key.maxExclusiveBlock())
    }
}
