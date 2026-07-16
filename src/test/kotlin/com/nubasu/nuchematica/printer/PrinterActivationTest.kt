package com.nubasu.nuchematica.printer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

public class PrinterActivationTest {
    @Test
    public fun creativeRequestTogglesEnabledState(): Unit {
        val activation = PrinterActivation()

        assertEquals(PrinterActivationEvent.ENABLED, activation.toggleRequested(isCreative = true))
        assertTrue(activation.enabled)
        assertEquals(PrinterActivationEvent.DISABLED, activation.toggleRequested(isCreative = true))
        assertFalse(activation.enabled)
    }

    @Test
    public fun nonCreativeRequestRefusesAndDisables(): Unit {
        val activation = PrinterActivation()
        activation.toggleRequested(isCreative = true)

        assertEquals(PrinterActivationEvent.REQUIRES_CREATIVE, activation.toggleRequested(isCreative = false))
        assertFalse(activation.enabled)
    }

    @Test
    public fun leavingCreativeAutoDisablesOnlyOnce(): Unit {
        val activation = PrinterActivation()
        activation.toggleRequested(isCreative = true)

        assertEquals(PrinterActivationEvent.AUTO_DISABLED, activation.tick(isCreative = false))
        assertFalse(activation.enabled)
        assertNull(activation.tick(isCreative = false))
    }
}
