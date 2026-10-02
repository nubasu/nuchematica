package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterLayerGateTest {
    @Test
    public fun initialGateUsesMinimumEligibleY(): Unit {
        val gate = PrinterLayerGate()
        val missingByEligibility = mapOf(
            1 to false,
            8 to true,
            3 to true,
            5 to true,
        )
        val eligibleMissingYs = missingByEligibility
            .filterValues { eligible -> eligible }
            .keys
            .sorted()

        val gateY = gate.update(
            eligibleYs = eligibleMissingYs,
            isLayerSupported = ALL_LAYERS_SUPPORTED,
            inReachAboveGate = 1,
            inReachEligibleAtOrBelowGate = 1,
            acceptedThisTick = 0,
            playerMoved = false,
        )

        assertEquals(3, gateY)
    }

    @Test
    public fun unsupportedLowerLayersAreSkippedWithoutStartingStallCounter(): Unit {
        val gate = PrinterLayerGate()
        val eligibleYs = listOf(0, 10, 20)
        val isLayerSupported: (Int) -> Boolean = { y -> y == 10 || y == 20 }

        assertEquals(
            10,
            gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false),
        )
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(
                10,
                gate.update(eligibleYs, isLayerSupported, 1, 0, 0, false),
            )
        }

        assertEquals(
            20,
            gate.update(eligibleYs, isLayerSupported, 1, 0, 0, false),
        )
    }

    @Test
    public fun floorYEscalationIsReportedOnceWhenStallRatchetBumps(): Unit {
        val gate = PrinterLayerGate()
        val escalated = mutableListOf<Int>()

        repeat(PrinterLayerGate.STALL_TICKS) {
            gate.update(
                eligibleYs = listOf(10, 20),
                isLayerSupported = ALL_LAYERS_SUPPORTED,
                inReachAboveGate = 1,
                inReachEligibleAtOrBelowGate = 0,
                acceptedThisTick = 0,
                playerMoved = false,
                onFloorYEscalated = { floorY -> escalated.add(floorY) },
            )
        }

        assertEquals(listOf(11), escalated)
    }

    @Test
    public fun newlySupportedLowerLayerIsRecoveredOnlyAfterTheHeldLayerSummitsAndDrains(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }

        assertEquals(
            10,
            gate.update(listOf(0, 10), isLayerSupported, 0, 0, 0, false),
        )

        supportedYs = setOf(0, 10)
        assertEquals(
            10,
            gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false),
        )
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())

        supportedYs = setOf(0)
        assertEquals(
            0,
            gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false),
        )
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
    }

    @Test
    public fun ascentNeverDescendsWhenDrainRevealsBothHigherAndLowerWork(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }

        assertEquals(10, gate.update(listOf(0, 10, 20), isLayerSupported, 0, 0, 0, false))
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())

        supportedYs = setOf(0, 20)
        val gateY = gate.update(listOf(0, 10, 20), isLayerSupported, 0, 1, 0, false)

        assertEquals(20, gateY)
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())
    }

    @Test
    public fun summitTransitionsToRecoveryAndReturnsTheHighestSupportedLayerBelow(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }
        val eligibleYs = listOf(0, 5, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))

        supportedYs = setOf(0, 5)
        val gateY = gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false)

        assertEquals(5, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
    }

    @Test
    public fun recoveryIteratesDownwardSkippingEmptyLayers(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }

        assertEquals(10, gate.update(listOf(0, 5, 10), isLayerSupported, 0, 0, 0, false))
        supportedYs = setOf(0, 5)
        assertEquals(5, gate.update(listOf(0, 5, 10), isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        supportedYs = setOf(0)
        val gateY = gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false)

        assertEquals(0, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
    }

    @Test
    public fun recoveryStallEscalationPermanentlySkipsToTheNextLowerSupportedLayer(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }
        val eligibleYs = listOf(-5, 0, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))
        supportedYs = setOf(-5, 0)
        assertEquals(0, gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, gate.update(eligibleYs, isLayerSupported, 1, 0, 0, false))
        }
        val gateY = gate.update(eligibleYs, isLayerSupported, 1, 0, 0, false)

        assertEquals(-5, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
    }

    @Test
    public fun resetRestoresAscentPhase(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }
        val eligibleYs = listOf(0, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))
        supportedYs = setOf(0)
        assertEquals(0, gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        gate.reset()

        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())
        supportedYs = setOf(0, 10)
        assertEquals(0, gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())
    }

    @Test
    public fun newlySupportedHigherLayerStillMovesGateUpImmediately(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(0)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }

        assertEquals(0, gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false))
        supportedYs = setOf(10)

        assertEquals(10, gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false))
    }

    @Test
    public fun resetClearsTheHeldLayer(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }

        assertEquals(10, gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false))
        supportedYs = setOf(0, 10)
        assertEquals(10, gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false))

        gate.reset()

        assertEquals(0, gate.update(listOf(0, 10), isLayerSupported, 0, 1, 0, false))
    }

    @Test
    public fun allUnsupportedLayersReleaseGate(): Unit {
        val gate = PrinterLayerGate()

        assertEquals(
            null,
            gate.update(listOf(0, 10), { false }, 1, 0, 0, false),
        )
    }

    @Test
    public fun completingLowestEligibleLayerAdvancesToNextLayer(): Unit {
        val gate = PrinterLayerGate()
        assertEquals(2, gate.update(listOf(2, 5), ALL_LAYERS_SUPPORTED, 1, 1, 0, false))

        val gateY = gate.update(
            eligibleYs = listOf(5),
            isLayerSupported = ALL_LAYERS_SUPPORTED,
            inReachAboveGate = 0,
            inReachEligibleAtOrBelowGate = 1,
            acceptedThisTick = 0,
            playerMoved = false,
        )

        assertEquals(5, gateY)
    }

    @Test
    public fun softStallAdvancesOnlyAfterSixtyConsecutiveQualifyingTicks(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, qualifyingSoftStallTick(gate))
        }

        assertEquals(10, qualifyingSoftStallTick(gate))
    }

    @Test
    public fun acceptedAndNoAboveMissingResetSoftStallCounter(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, qualifyingSoftStallTick(gate))
        }
        assertEquals(0, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 1, 0, 1, false))
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, qualifyingSoftStallTick(gate))
        }
        assertEquals(0, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 0, 0, 0, false))
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, qualifyingSoftStallTick(gate))
        }

        assertEquals(10, qualifyingSoftStallTick(gate))
    }

    @Test
    public fun acceptedResetsHardStallCounter(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.HARD_STALL_TICKS - 1) {
            assertEquals(0, hardStallTick(gate))
        }
        assertEquals(0, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 1, 1, 1, false))
        repeat(PrinterLayerGate.HARD_STALL_TICKS - 1) {
            assertEquals(0, hardStallTick(gate))
        }

        assertEquals(10, hardStallTick(gate))
    }

    @Test
    public fun noAboveMissingResetsHardStallCounter(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.HARD_STALL_TICKS - 1) {
            assertEquals(0, hardStallTick(gate))
        }
        assertEquals(0, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 0, 1, 0, false))
        repeat(PrinterLayerGate.HARD_STALL_TICKS - 1) {
            assertEquals(0, hardStallTick(gate))
        }

        assertEquals(10, hardStallTick(gate))
    }

    @Test
    public fun hardStallAdvancesAtTwoHundredTicksEvenWithEligibleAtGate(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.HARD_STALL_TICKS - 1) {
            assertEquals(0, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 1, 1, 0, false))
        }

        assertEquals(10, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 1, 1, 0, false))
    }

    @Test
    public fun resetRestoresInitialFloor(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.STALL_TICKS) {
            qualifyingSoftStallTick(gate)
        }
        assertEquals(10, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 0, 1, 0, false))

        gate.reset()

        assertEquals(0, gate.update(listOf(0, 10), ALL_LAYERS_SUPPORTED, 0, 1, 0, false))
    }

    @Test
    public fun advancedGateNeverReturnsToEligibleYBelowFloor(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.STALL_TICKS) {
            qualifyingSoftStallTick(gate)
        }

        val gateY = gate.update(
            eligibleYs = listOf(-5, 0, 10),
            isLayerSupported = ALL_LAYERS_SUPPORTED,
            inReachAboveGate = 0,
            inReachEligibleAtOrBelowGate = 2,
            acceptedThisTick = 0,
            playerMoved = false,
        )

        assertEquals(10, gateY)
    }

    @Test
    public fun playerMovementResetsStallCountersUntilMovementStops(): Unit {
        val gate = PrinterLayerGate()
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, qualifyingSoftStallTick(gate))
        }
        repeat(PrinterLayerGate.HARD_STALL_TICKS) {
            assertEquals(0, qualifyingSoftStallTick(gate, playerMoved = true))
        }
        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(0, qualifyingSoftStallTick(gate))
        }

        assertEquals(10, qualifyingSoftStallTick(gate))
    }

    private fun qualifyingSoftStallTick(
        gate: PrinterLayerGate,
        playerMoved: Boolean = false,
    ): Int? {
        return gate.update(
            eligibleYs = listOf(0, 10),
            isLayerSupported = ALL_LAYERS_SUPPORTED,
            inReachAboveGate = 1,
            inReachEligibleAtOrBelowGate = 0,
            acceptedThisTick = 0,
            playerMoved = playerMoved,
        )
    }

    private fun hardStallTick(gate: PrinterLayerGate): Int? {
        return gate.update(
            eligibleYs = listOf(0, 10),
            isLayerSupported = ALL_LAYERS_SUPPORTED,
            inReachAboveGate = 1,
            inReachEligibleAtOrBelowGate = 1,
            acceptedThisTick = 0,
            playerMoved = false,
        )
    }

    @Test
    public fun recoveryMultiPassRestartsFromTheSummitAfterProgressWithinThePass(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }
        val eligibleYs = listOf(0, 5, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())

        supportedYs = setOf(5)
        assertEquals(5, gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        assertEquals(5, gate.update(eligibleYs, isLayerSupported, 0, 0, 1, false))

        supportedYs = setOf(10)
        val gateY = gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false)

        assertEquals(10, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
    }

    @Test
    public fun recoveryTerminatesWhenAFullPassMakesNoProgress(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }
        val eligibleYs = listOf(0, 5, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))
        supportedYs = setOf(5)
        assertEquals(5, gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        supportedYs = emptySet()
        val gateY = gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false)

        assertEquals(null, gateY)
    }

    @Test
    public fun recoveryMultiPassRestartsAfterAStallEscapeExhaustsTheCeiling(): Unit {
        val gate = PrinterLayerGate()
        var supportedYs = setOf(10)
        val isLayerSupported: (Int) -> Boolean = { y -> y in supportedYs }
        val eligibleYs = listOf(0, 5, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())

        supportedYs = setOf(5)
        assertEquals(5, gate.update(eligibleYs, isLayerSupported, 0, 1, 0, false))
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        assertEquals(5, gate.update(eligibleYs, isLayerSupported, 0, 0, 1, false))

        repeat(PrinterLayerGate.STALL_TICKS - 1) {
            assertEquals(5, gate.update(eligibleYs, isLayerSupported, 1, 0, 0, false))
        }

        supportedYs = setOf(5, 10)
        val gateY = gate.update(eligibleYs, isLayerSupported, 1, 0, 0, false)

        assertEquals(10, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
    }

    @Test
    public fun recoveryMultiPassRecoversABackoffDeferredAnchorWithoutManualRetoggle(): Unit {
        val gate = PrinterLayerGate()
        val ledger = PrinterDeferralLedger()
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }
        val anchor = BlockPos(0, 5, 0)
        var queueRevision = 0L
        ledger.synchronize(transformRevision = 1L, contentIdentity = Any(), levelIdentity = Any())
        repeat(3) { ledger.defer(anchor, PrinterDeferralReason.MOVER_UNREACHABLE, stateAt, queueRevision) }

        var summitPlaced = false
        var lowerWorkAppeared = false
        var lowerWorkRemaining = 5
        val isLayerSupported: (Int) -> Boolean = { y ->
            when (y) {
                10 -> !summitPlaced
                5 -> !ledger.isDeferred(anchor, stateAt, queueRevision)
                3 -> lowerWorkAppeared && lowerWorkRemaining > 0
                else -> false
            }
        }
        val eligibleYs = listOf(3, 5, 10)

        assertEquals(10, gate.update(eligibleYs, isLayerSupported, 0, 0, 0, false))
        assertEquals(PrinterLayerGatePhase.ASCENT, gate.currentPhase())
        summitPlaced = true
        lowerWorkAppeared = true
        queueRevision++

        var gateY = gate.update(eligibleYs, isLayerSupported, 0, 1, 1, false)
        assertEquals(3, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())

        repeat(5) {
            lowerWorkRemaining--
            queueRevision++
            gateY = gate.update(eligibleYs, isLayerSupported, 0, 0, 1, false)
        }

        assertEquals(5, gateY)
        assertEquals(PrinterLayerGatePhase.RECOVERY, gate.currentPhase())
        assertFalse(ledger.isDeferred(anchor, stateAt, queueRevision))
    }

    public companion object {
        private val ALL_LAYERS_SUPPORTED: (Int) -> Boolean = { true }

        @JvmStatic
        @BeforeAll
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
