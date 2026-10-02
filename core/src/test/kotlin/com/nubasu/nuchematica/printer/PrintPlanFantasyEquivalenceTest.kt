package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PrintPlanFantasyEquivalenceTest {
    @BeforeEach
    internal fun resetPrinterSettings() {
        PrinterSettingsHolder.printerSettings = PrinterSettings()
    }

    @Test
    internal fun plannedPlaceableSupersetsSurvivableV3AndRemainderIsNoWorse() {
        val expected = PlacementSimulationHarness.loadExpected() ?: return

        val v3Result = PlacementSimulationHarness.simulate(expected, wrongWorldBlocks = emptyMap(), scaffoldAssist = true)
        val v3Placed = expected.keys - v3Result.remaining

        val minY = expected.keys.minOf { it.y }
        val ground = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val v3FinalState: (BlockPos) -> BlockState = { pos ->
            v3Result.world[pos] ?: if (pos.y < minY) ground else air
        }
        val survivableV3Placed = v3Placed.filterTo(HashSet()) { pos ->
            survivesWithoutScaffold(pos, expected.getValue(pos), v3FinalState)
        }
        val optimisticV3Only = v3Placed - survivableV3Placed
        val worldState: (BlockPos) -> BlockState = { pos -> if (pos.y < minY) ground else air }
        val content = expected.entries.map { (pos, state) -> pos to state }

        val plan = PrintPlanner.plan(content, worldState)
        val lateTrapdoorRegressionTargets = setOf(BlockPos(8, 8, 2), BlockPos(8, 9, 2))
        val naturalTargets = plan.units.flatMap { unit ->
            unit.actions.filterIsInstance<PlanAction.PlaceTarget>().map { action -> action.pos }
        }
        assertTrue(lateTrapdoorRegressionTargets.all { target -> target in naturalTargets }) {
            "Fantasy trapdoors were deferred out of natural layer work: " +
                (lateTrapdoorRegressionTargets - naturalTargets.toSet())
        }
        assertTrue(plan.reservationDetails.none { detail -> detail.pos in lateTrapdoorRegressionTargets })
        for (target in lateTrapdoorRegressionTargets) {
            val support = target.south()
            assertTrue(naturalTargets.indexOf(support) in 0 until naturalTargets.indexOf(target)) {
                "Fantasy trapdoor $target must follow its south support $support"
            }
        }
        val lateWallTorch = BlockPos(8, 9, 4)
        val wallTorchSupport = lateWallTorch.north()
        val wallTorchDetail = plan.reservationDetails.singleOrNull { detail -> detail.pos == lateWallTorch }
        assertTrue(wallTorchDetail?.dependsOn == listOf(wallTorchSupport)) {
            "Fantasy wall torch must reserve against its north support: $wallTorchDetail"
        }
        val executableUnits = executablePlanUnits(plan)
        val supportUnitIndex = executableUnits.indexOfFirst { unit ->
            unit.actions.any { action -> action is PlanAction.PlaceTarget && action.pos == wallTorchSupport }
        }
        val wallTorchUnitIndex = executableUnits.indexOfFirst { unit ->
            unit.actions.any { action -> action is PlanAction.PlaceTarget && action.pos == lateWallTorch }
        }
        assertTrue(supportUnitIndex >= 0 && wallTorchUnitIndex == supportUnitIndex + 1) {
            "Fantasy wall torch unit $wallTorchUnitIndex must immediately follow support unit $supportUnitIndex"
        }
        assertTrue(executableUnits[wallTorchUnitIndex].isReservation)
        val v4Planned = HashSet<BlockPos>()
        for (unit in plan.units) {
            for (action in unit.actions) {
                if (action is PlanAction.PlaceTarget) v4Planned.add(action.pos)
            }
        }
        for (actions in plan.reservations.values) {
            for (action in actions) {
                if (action is PlanAction.PlaceTarget) v4Planned.add(action.pos)
            }
        }

        val v3OnlyPositions = survivableV3Placed - v4Planned
        val v4NewlyRescued = v4Planned - survivableV3Placed
        val v4Remainder = plan.report.excludedCategoryCount + plan.report.excludedOccupiedCount +
            plan.report.excludedFallingCount + plan.report.unreachableCount
        val realisticV3Remainder = v3Result.remaining.size + optimisticV3Only.size
        println(
            "[PrintPlanFantasyEquivalenceTest] survivableV3Placed=${survivableV3Placed.size} " +
                "realisticV3Remainder=$realisticV3Remainder optimisticV3Only=${optimisticV3Only.size} " +
                "v4Planned=${v4Planned.size} v4Remainder=$v4Remainder " +
                "v4NewlyRescued=${v4NewlyRescued.size} v3OnlyPositions=${v3OnlyPositions.size}",
        )
        val intentionallyExcludedBlocks = setOf(
            Blocks.WATER,
            Blocks.OAK_DOOR,
            Blocks.OAK_WALL_SIGN,
            Blocks.PISTON_HEAD,
            Blocks.RED_BED,
        )
        val unexpectedExcluded = plan.report.excludedPositions.filter { excluded ->
            expected.getValue(excluded.pos).block !in intentionallyExcludedBlocks
        }
        assertTrue(unexpectedExcluded.isEmpty()) {
            "Fantasy structural blocks were excluded from the plan: $unexpectedExcluded"
        }
        val unexpectedUnreachable = plan.report.unreachablePositions.filter { pos ->
            expected.getValue(pos).block != Blocks.GRASS
        }
        assertTrue(unexpectedUnreachable.isEmpty()) {
            "Fantasy structural blocks were unreachable: $unexpectedUnreachable"
        }

        assertTrue(v3OnlyPositions.isEmpty()) {
            "v3 kept a survivable position v4's plan does not: ${v3OnlyPositions.take(20)} " +
                "(total ${v3OnlyPositions.size})"
        }

        assertTrue(v4Remainder <= realisticV3Remainder) {
            "v4 remainder ($v4Remainder) exceeds realistic v3 remainder ($realisticV3Remainder)"
        }
    }

    internal companion object {
        @JvmStatic
        @BeforeAll
        internal fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
