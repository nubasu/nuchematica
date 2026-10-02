package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class FantasyBigHousePlacementSimulationTest {
    @Test
    internal fun lookalikeSubstitutionReducesExclusionsAndUnlocksHigherLayers() {
        val expected = PlacementSimulationHarness.loadExpected() ?: return

        PrinterSettingsHolder.printerSettings.substituteLookalikes = false
        val withoutSubstitutionExcluded = expected.count { (_, state) ->
            eligiblePrinterBlockItem(state) == null
        }
        val withoutSubstitution = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap())

        PrinterSettingsHolder.printerSettings.substituteLookalikes = true
        val withSubstitutionExcluded = expected.count { (_, state) ->
            eligiblePrinterBlockItem(state) == null
        }
        val withSubstitution = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap())

        assertEquals(66, withoutSubstitutionExcluded)
        assertEquals(33, withSubstitutionExcluded)
        assertEquals(140, withoutSubstitution.remaining.size)
        assertEquals(REMAINING_WITH_SUBSTITUTION, withSubstitution.remaining.size)
        assertEquals(19, withoutSubstitution.topPlacedY)
        assertEquals(TOP_PLACEABLE_Y, withSubstitution.topPlacedY)
        assertNoAscentOrderViolations(withoutSubstitution)
        assertNoAscentOrderViolations(withSubstitution)
        assertNoActionableWorkRemains(expected, withSubstitution)
    }

    @BeforeEach
    internal fun resetPrinterSettings() {
        PrinterSettingsHolder.printerSettings = PrinterSettings()
    }

    @Test
    internal fun placementReachesTopPlaceableLayer() {
        val expected = PlacementSimulationHarness.loadExpected() ?: return

        val result = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap())

        assertEquals(TOP_PLACEABLE_Y, result.topPlacedY) {
            "structurally placeable content ends at y=$TOP_PLACEABLE_Y in this fixture"
        }
        assertEquals(REMAINING_WITH_SUBSTITUTION, result.remaining.size)
        assertGateDescentsBounded(result)
        assertNoAscentOrderViolations(result)
        assertNoActionableWorkRemains(expected, result)
    }

    @Test
    internal fun wrongStateBlocksOnSlabDeckDoNotPinTheLayerGate() {
        val expected = PlacementSimulationHarness.loadExpected() ?: return
        val eligibleDeckPositions = expected.entries
            .filter { (pos, state) -> pos.y == 10 && eligiblePrinterBlockItem(state) != null }
            .map { (pos, _) -> pos }
            .sortedWith(compareBy({ it.x }, { it.z }))
        val wrongWorldBlocks = eligibleDeckPositions.take(3)
            .associateWith { Blocks.NETHERRACK.defaultBlockState() }
        assumeTrue(wrongWorldBlocks.size == 3, "fixture no longer has an eligible y=10 deck")

        val baseline = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap())
        val result = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = wrongWorldBlocks)

        assertEquals(TOP_PLACEABLE_Y, result.topPlacedY) {
            "gate must advance past wrong-state blocks instead of pinning on their layer"
        }
        assertEquals(
            baseline.remaining + wrongWorldBlocks.keys,
            result.remaining,
            "wrong-state positions must be the only additional leftovers",
        )
        assertGateDescentsBounded(baseline)
        assertGateDescentsBounded(result)
        assertNoAscentOrderViolations(baseline)
        assertNoAscentOrderViolations(result)
        assertNoActionableWorkRemains(expected, result)
    }

    @Test
    internal fun unreachableBlocksOnLayerFourDoNotGateTheLayersAbove() {
        val expected = PlacementSimulationHarness.loadExpected() ?: return
        val unreachableY4Positions = expected.entries
            .filter { (pos, state) -> pos.y == 4 && eligiblePrinterBlockItem(state) != null }
            .map { (pos, _) -> pos }
            .sortedWith(compareBy({ it.x }, { it.z }))
            .take(3)
            .toSet()
        assumeTrue(unreachableY4Positions.size == 3, "fixture no longer has 3 eligible y=4 positions")

        val baseline = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap())
        val result = PlacementSimulationHarness.simulate(
            expected,
            wrongWorldBlocks = emptyMap(),
            unreachablePositions = unreachableY4Positions,
        )

        assertEquals(TOP_PLACEABLE_Y, result.topPlacedY) {
            "gate must advance past an unreachable position instead of pinning on its layer"
        }
        assertEquals(
            baseline.remaining + unreachableY4Positions,
            result.remaining,
            "unreachable positions must be the only additional leftovers",
        )
        assertGateDescentsBounded(baseline)
        assertGateDescentsBounded(result)
        assertNoAscentOrderViolations(baseline)
        assertNoAscentOrderViolations(result)
    }

    @Test
    internal fun scaffoldAssistShrinksTheUnsupportedRemainderAtTheStructuralFixpoint() {
        val expected = PlacementSimulationHarness.loadExpected() ?: return

        val withoutScaffold = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap())
        val withScaffold = PlacementSimulationHarness.simulate(
            expected,
            wrongWorldBlocks = emptyMap(),
            scaffoldAssist = true,
        )

        assertTrue(withScaffold.scaffoldsPlaced > 0) {
            "expected at least one scaffold-assisted placement in this fixture"
        }
        assertTrue(withScaffold.remaining.size < withoutScaffold.remaining.size) {
            "scaffold assist must shrink the unsupported remainder: " +
                "without=${withoutScaffold.remaining.size} with=${withScaffold.remaining.size}"
        }
        assertEquals(REMAINING_WITH_SCAFFOLD_ASSIST, withScaffold.remaining.size)
        assertEquals(SCAFFOLDS_PLACED, withScaffold.scaffoldsPlaced)
        assertNoAscentOrderViolations(withScaffold)
        assertNoActionableWorkRemains(expected, withScaffold, scaffoldAssist = true)
    }

    private fun assertNoAscentOrderViolations(result: PlacementSimulationHarness.SimulationResult) {
        assertTrue(result.ascentOrderViolations.isEmpty()) {
            "ascent placed below its own running gate maximum: " +
                "${result.ascentOrderViolations.take(5)} (total ${result.ascentOrderViolations.size})"
        }
    }

    private fun assertGateDescentsBounded(result: PlacementSimulationHarness.SimulationResult) {
        assertTrue(result.gateDescents <= 2 * result.distinctEligibleLayers) {
            "gate descended ${result.gateDescents} times across " +
                "${result.distinctEligibleLayers} distinct eligible layers"
        }
    }

    private fun assertNoActionableWorkRemains(
        expected: Map<BlockPos, BlockState>,
        result: PlacementSimulationHarness.SimulationResult,
        scaffoldAssist: Boolean = false,
    ) {
        val ground = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val stateAt: (BlockPos) -> BlockState = { pos ->
            result.world[pos] ?: if (pos.y < result.minY) ground else air
        }
        val isSchematicPosition: (BlockPos) -> Boolean = { pos -> pos in expected }
        val abandoned = result.remaining.filter { pos ->
            eligiblePrinterBlockItem(expected.getValue(pos)) != null &&
                isReplaceableTarget(stateAt(pos)) &&
                (
                    hasSupportNeighbor(pos, expected.getValue(pos), stateAt) ||
                        (
                            scaffoldAssist &&
                                isScaffoldAssistable(pos, expected.getValue(pos), stateAt, isSchematicPosition, null)
                            )
                    )
        }
        assertTrue(abandoned.isEmpty()) {
            "fixpoint left actionable work behind: ${abandoned.take(5)} (total ${abandoned.size})"
        }
    }

    internal companion object {
        private const val TOP_PLACEABLE_Y: Int = 21
        private const val REMAINING_WITH_SUBSTITUTION: Int = 95

        private const val REMAINING_WITH_SCAFFOLD_ASSIST: Int = 33
        private const val SCAFFOLDS_PLACED: Int = 73

        @JvmStatic
        @BeforeAll
        internal fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
