package com.nubasu.nuchematica.printer

// Two-phase build: ASCENT climbs the schematic layer by layer and never
// backslides to pick up a lower straggler mid-climb (the ratchet); once nothing
// supported remains at or above the highest layer ever held, RECOVERY takes over
// and sweeps back down top-to-bottom, visiting only layers that still have
// supported work (empty/unsupported layers are skipped naturally -- they never win
// the search). RECOVERY is multi-pass -- a descent pass that drains its
// ceiling restarts from the recorded summit if it placed anything, and only reports
// terminal (null) after a full pass with zero progress.
public enum class PrinterLayerGatePhase {
    ASCENT,
    RECOVERY,
}

public class PrinterLayerGate {
    private var phase: PrinterLayerGatePhase = PrinterLayerGatePhase.ASCENT
    private var floorY: Int = Int.MIN_VALUE
    private var recoveryCeiling: Int = Int.MAX_VALUE
    // The ceiling RECOVERY was seeded with when ASCENT handed off
    // (distinct from recoveryCeiling, which stall escapes shrink permanently within a
    // pass) -- multi-pass restarts reset recoveryCeiling back to this recorded summit
    // rather than to Int.MAX_VALUE, so a fresh pass re-sweeps exactly the height range
    // the two-phase build already committed to, not the whole schematic.
    private var recoverySummit: Int = Int.MAX_VALUE
    // Sum of acceptedThisTick since the current RECOVERY pass began (reset at the
    // ASCENT->RECOVERY handoff and at every subsequent pass restart). A pass may only
    // restart from the summit if it placed at least one block -- this is what
    // guarantees deterministic termination (see the gateY==null handling below).
    private var recoveryPassProgress: Int = 0
    private var stallTicks: Int = 0
    private var hardStallTicks: Int = 0
    private var heldGateY: Int? = null

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
            // The ratchet has nothing left to climb to: the highest layer ever held
            // has drained and no supported layer sits above it -- the summit. Hand
            // off to the top-down recovery pass, seeded with the summit as its
            // ceiling, and retry once under its rules before reporting an empty
            // gate for this tick.
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
            // Mirrored stall escapes: ASCENT permanently forbids returning to the
            // stuck layer by raising floorY past it; RECOVERY permanently forbids
            // returning to it by lowering the recovery ceiling below it. Either way
            // this is a give-up-on-THIS-layer escape, distinct from an ordinary
            // drain -- the layer may still nominally be "supported" (just stuck:
            // unreachable, or nobody is making progress on it).
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
            // A stall escape can exhaust the (now-shrunk)
            // ceiling just as completely as an ordinary drain -- this re-search
            // needs the exact same multi-pass check, or a pass that made progress
            // before getting stuck on its final layer would terminate here instead
            // of restarting from the summit.
            gateY = restartRecoveryPassIfExhaustedWithProgress(gateY, eligibleYs, isLayerSupported)
            if (gateY == null) {
                heldGateY = null
                return null
            }
        }
        heldGateY = gateY
        return gateY
    }

    // Shared by both places a fresh gateY can come back null
    // while in RECOVERY (an ordinary drain at the top of update(), and a stall-escape
    // re-search). A pass may restart from the recorded summit only if it placed at
    // least one block since it began (recoveryPassProgress > 0) -- this is what
    // guarantees deterministic termination: a pass with zero progress always reports
    // terminal instead of looping.
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

    // TEMP C3DBG (remove after the layer-pin investigation)
    internal fun debugCounters(): String =
        "phase=$phase floorY=$floorY recoveryCeiling=$recoveryCeiling " +
            "recoverySummit=$recoverySummit recoveryPassProgress=$recoveryPassProgress " +
            "stallTicks=$stallTicks hardStallTicks=$hardStallTicks"

    // The single search both phases share: holding at the last-returned gate while
    // it remains supported, and advancing past it once it drains, both fall out of
    // one bound -- ASCENT never considers a Y below heldGateY (the ratchet);
    // RECOVERY never considers a Y above heldGateY (capped by recoveryCeiling once
    // heldGateY itself resets to null, e.g. right after entering RECOVERY or after
    // an idle gap). See the class doc for the two-phase contract this implements.
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
