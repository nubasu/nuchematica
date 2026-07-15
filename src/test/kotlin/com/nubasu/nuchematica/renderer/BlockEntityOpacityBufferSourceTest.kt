package com.nubasu.nuchematica.renderer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

public class BlockEntityOpacityBufferSourceTest {

    @Test
    public fun renderStateIsClearedWhenDrawFails(): Unit {
        var setupCalls = 0
        var drawCalls = 0
        var clearCalls = 0

        assertThrows(ExpectedFailure::class.java) {
            withRenderStateRestored(
                setup = { setupCalls++ },
                draw = {
                    drawCalls++
                    throw ExpectedFailure()
                },
                clear = { clearCalls++ },
            )
        }
        assertEquals(1, setupCalls)
        assertEquals(1, drawCalls)
        assertEquals(1, clearCalls)
    }

    private class ExpectedFailure : RuntimeException()
}
