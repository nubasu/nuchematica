package com.nubasu.nuchematica.printer

/**
 * ASCENT ratchets upward; RECOVERY sweeps supported leftovers downward in bounded passes.
 */
public enum class PrinterLayerGatePhase {
    ASCENT,
    RECOVERY,
}

/** Stateful two-phase layer selector with stall-driven escalation. */
public class PrinterLayerGate {
    private var phase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT
    private var floorY: Int = Int.MIN_VALUE
    private var recoveryCeiling: Int = Int.MAX_VALUE
    private var recoverySummit: Int = Int.MAX_VALUE
    private var recoveryPassProgress: Int = 0
    private var stallTicks: Int = 0
    private var hardStallTicks: Int = 0
    private var heldGateY: Int? = null

    /**
     * Advances the gate and returns its supported work layer, or null when exhausted.
     *
     * [eligibleYs] must be sorted in ascending order and contain no duplicates.
     */
    public fun update(
        eligibleYs: List<Int>,
        isLayerSupported: (Int) -> Boolean,
        inReachAboveGate: Int,
        inReachEligibleAtOrBelowGate: Int,
        acceptedThisTick: Int,
        playerMoved: Boolean,
        onFloorYEscalated: (Int) -> Unit = {},
    ): Int? {
        if (phase == PrinterLayerGatePhase.RECOVERY) {
            recoveryPassProgress += acceptedThisTick
        }
        var gateY = searchGate(eligibleYs, isLayerSupported)
        if (gateY == null && phase == PrinterLayerGatePhase.ASCENT && heldGateY != null) {
            phase = PrinterLayerGatePhase.RECOVERY
            recoveryCeiling = heldGateY!!
            recoverySummit = heldGateY!!
            recoveryPassProgress = 0
            gateY = searchGate(eligibleYs, isLayerSupported)
        }
        gateY = restartRecoveryPassIfExhaustedWithProgress(gateY, eligibleYs, isLayerSupported)
        if (gateY == null) {
            resetCounters()
            heldGateY = null
            return null
        }

        if (playerMoved || acceptedThisTick > 0 || inReachAboveGate == 0) {
            resetCounters()
            heldGateY = gateY
            return gateY
        }

        hardStallTicks++
        if (inReachEligibleAtOrBelowGate == 0) {
            stallTicks++
        } else {
            stallTicks = 0
        }

        if (stallTicks >= STALL_TICKS || hardStallTicks >= HARD_STALL_TICKS) {
            when (phase) {
                PrinterLayerGatePhase.ASCENT -> {
                    floorY = gateY + 1
                    onFloorYEscalated(floorY)
                }
                PrinterLayerGatePhase.RECOVERY -> {
                    recoveryCeiling = gateY - 1
                }
            }
            resetCounters()
            gateY = searchGate(eligibleYs, isLayerSupported)
            gateY = restartRecoveryPassIfExhaustedWithProgress(gateY, eligibleYs, isLayerSupported)
            if (gateY == null) {
                heldGateY = null
                return null
            }
        }
        heldGateY = gateY
        return gateY
    }

    private fun restartRecoveryPassIfExhaustedWithProgress(
        gateY: Int?,
        eligibleYs: List<Int>,
        isLayerSupported: (Int) -> Boolean,
    ): Int? {
        if (gateY != null || phase != PrinterLayerGatePhase.RECOVERY || recoveryPassProgress <= 0) {
            return gateY
        }
        recoveryCeiling = recoverySummit
        heldGateY = null
        recoveryPassProgress = 0
        resetCounters()
        return searchGate(eligibleYs, isLayerSupported)
    }

    public fun reset(): Unit {
        phase = PrinterLayerGatePhase.ASCENT
        floorY = Int.MIN_VALUE
        recoveryCeiling = Int.MAX_VALUE
        recoverySummit = Int.MAX_VALUE
        recoveryPassProgress = 0
        heldGateY = null
        resetCounters()
    }

    public fun currentPhase(): PrinterLayerGatePhase = phase

    private fun searchGate(
        sortedDistinctEligibleYs: List<Int>,
        isLayerSupported: (Int) -> Boolean,
    ): Int? {
        return when (phase) {
            PrinterLayerGatePhase.ASCENT -> {
                val minY = maxOf(floorY, heldGateY ?: Int.MIN_VALUE)
                sortedDistinctEligibleYs.asSequence()
                    .filter { y -> y >= minY }
                    .firstOrNull(isLayerSupported)
            }
            PrinterLayerGatePhase.RECOVERY -> {
                val maxY = minOf(recoveryCeiling, heldGateY ?: recoveryCeiling)
                sortedDistinctEligibleYs.asReversed().asSequence()
                    .filter { y -> y <= maxY }
                    .firstOrNull(isLayerSupported)
            }
        }
    }

    private fun resetCounters(): Unit {
        stallTicks = 0
        hardStallTicks = 0
    }

    public companion object {
        public const val STALL_TICKS: Int = 60
        public const val HARD_STALL_TICKS: Int = 200
    }
}
