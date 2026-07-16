package com.nubasu.nuchematica.printer

public enum class PrinterActivationEvent {
    ENABLED,
    DISABLED,
    REQUIRES_CREATIVE,
    AUTO_DISABLED,
}

public class PrinterActivation {
    public var enabled: Boolean = false
        private set

    public fun toggleRequested(isCreative: Boolean): PrinterActivationEvent {
        if (!isCreative) {
            enabled = false
            return PrinterActivationEvent.REQUIRES_CREATIVE
        }

        enabled = !enabled
        return if (enabled) PrinterActivationEvent.ENABLED else PrinterActivationEvent.DISABLED
    }

    public fun tick(isCreative: Boolean): PrinterActivationEvent? {
        if (!enabled || isCreative) return null
        enabled = false
        return PrinterActivationEvent.AUTO_DISABLED
    }
}
