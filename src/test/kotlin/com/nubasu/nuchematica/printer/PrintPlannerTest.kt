package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

internal class PrintPlannerTest {
    @BeforeEach
    internal fun resetPrinterSettings() {
        PrinterSettingsHolder.printerSettings = PrinterSettings()
    }

    @Test
    internal fun bandTileSnakeAndWithinLayerColumnSnakeOrdering() {
        val stone = Blocks.STONE.defaultBlockState()
        val content = ArrayList<Pair<BlockPos, BlockState>>()
        for (x in 0..3) {
            for (y in 0..3) {
                for (z in 0..3) {
                    content.add(BlockPos(x, y, z) to stone)
                }
            }
        }
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos.y < 0) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 2, tileSize = 2))

        assertEquals(
            listOf(
                Triple(0, 0, 0), Triple(0, 0, 1), Triple(0, 1, 1), Triple(0, 1, 0),
                Triple(1, 0, 0), Triple(1, 0, 1), Triple(1, 1, 1), Triple(1, 1, 0),
            ),
            plan.units.map { unit -> Triple(unit.band, unit.tileX, unit.tileZ) },
            "band-major, x-row tile snake (even rows z-ascending, odd rows z-descending)",
        )
        assertEquals(
            listOf(
                BlockPos(0, 0, 0), BlockPos(0, 0, 1), BlockPos(1, 0, 1), BlockPos(1, 0, 0),
                BlockPos(0, 1, 0), BlockPos(0, 1, 1), BlockPos(1, 1, 1), BlockPos(1, 1, 0),
            ),
            plan.units[0].actions.map { action -> (action as PlanAction.PlaceTarget).pos },
            "within a tile: y ascending, x ascending, and z reverses on each x column",
        )
        assertEquals(64, plan.report.directCount)
        assertEquals(0, plan.report.scaffoldCount + plan.report.reservedCount + plan.report.unreachableCount)
    }

    @Test
    internal fun layerTraversalChoosesDescendingStartWhenItMakesSupportAvailable() {
        val stone = Blocks.STONE.defaultBlockState()
        val target = BlockPos(0, 0, 0)
        val support = BlockPos(0, 0, 1)
        val trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == support.below()) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(
            content = listOf(target to trapdoor, support to stone),
            worldState = worldState,
            params = PrintPlanParams(
                bandHeight = 1,
                tileSize = 2,
                bounds = { pos -> pos == target || pos == support },
            ),
        )

        assertEquals(
            listOf(support, target),
            plan.units.single().actions.filterIsInstance<PlanAction.PlaceTarget>().map(PlanAction.PlaceTarget::pos),
        )
        assertEquals(2, plan.report.directCount)
        assertTrue(plan.reservations.isEmpty())
    }

    @Test
    internal fun layerTraversalChoosesAscendingStartWhenItMakesSupportAvailable() {
        val stone = Blocks.STONE.defaultBlockState()
        val support = BlockPos(0, 0, 0)
        val target = BlockPos(0, 0, 1)
        val trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == support.below()) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(
            content = listOf(support to stone, target to trapdoor),
            worldState = worldState,
            params = PrintPlanParams(
                bandHeight = 1,
                tileSize = 2,
                bounds = { pos -> pos == target || pos == support },
            ),
        )

        assertEquals(
            listOf(support, target),
            plan.units.single().actions.filterIsInstance<PlanAction.PlaceTarget>().map(PlanAction.PlaceTarget::pos),
        )
        assertEquals(2, plan.report.directCount)
        assertTrue(plan.reservations.isEmpty())
    }

    @Test
    internal fun eachClassificationBucketOccursAtLeastOnce() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()

        val alreadyPlaced = BlockPos(0, 0, 0)
        val direct = BlockPos(20, 0, 0)
        val scaffoldTarget = BlockPos(40, 5, 0)
        val reservedA = BlockPos(60, 0, 0)
        val reservedTriggerB = BlockPos(60, 1, 0)
        val excludedCategory = BlockPos(80, 0, 0)
        val excludedOccupied = BlockPos(100, 0, 0)
        val excludedFalling = BlockPos(120, 0, 0)
        val unreachable = BlockPos(140, 0, 0)

        val content = listOf(
            alreadyPlaced to stone,
            direct to stone,
            scaffoldTarget to stone,
            reservedA to stone,
            reservedTriggerB to stone,
            excludedCategory to Blocks.RED_BED.defaultBlockState(),
            excludedOccupied to stone,
            excludedFalling to Blocks.SAND.defaultBlockState(),
            unreachable to stone,
        )

        val overrides = mapOf(
            alreadyPlaced to stone,
            direct.below() to stone,
            BlockPos(40, 2, 0) to stone,
            reservedA.below() to craftingTable,
            reservedA.east() to craftingTable,
            reservedA.west() to craftingTable,
            reservedA.north() to craftingTable,
            reservedA.south() to craftingTable,
            reservedTriggerB.east() to stone,
            excludedOccupied to Blocks.NETHERRACK.defaultBlockState(),
            unreachable.above() to craftingTable,
            unreachable.below() to craftingTable,
            unreachable.east() to craftingTable,
            unreachable.west() to craftingTable,
            unreachable.north() to craftingTable,
            unreachable.south() to craftingTable,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: air }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.alreadyPlacedCount, "already-placed")
        assertEquals(2, plan.report.directCount, "direct (the standalone cell plus the reservation's trigger)")
        assertEquals(1, plan.report.scaffoldCount, "scaffold-assisted")
        assertEquals(1, plan.report.reservedCount, "reserved-and-resolved")
        assertEquals(1, plan.report.excludedCategoryCount, "category-excluded")
        assertEquals(1, plan.report.excludedOccupiedCount, "occupied-excluded")
        assertEquals(1, plan.report.excludedFallingCount, "falling-unsupported-excluded")
        assertEquals(1, plan.report.unreachableCount, "unreachable")
        assertEquals(listOf(unreachable), plan.report.unreachablePositions)
        assertTrue(
            plan.report.excludedPositions.containsAll(
                listOf(
                    ExcludedPosition(excludedCategory, ExclusionReason.CATEGORY),
                    ExcludedPosition(excludedOccupied, ExclusionReason.OCCUPIED),
                    ExcludedPosition(excludedFalling, ExclusionReason.FALLING),
                ),
            ),
        )

        val allActions = plan.units.flatMap { it.actions } + plan.reservations.values.flatten()
        val placeTargetGroupIds = allActions.filterIsInstance<PlanAction.PlaceTarget>().map { it.groupId }
        assertEquals(4, placeTargetGroupIds.size)
        assertEquals(placeTargetGroupIds.size, placeTargetGroupIds.toSet().size, "every target's groupId is unique")
        assertTrue(placeTargetGroupIds.all { it > 0 }, "every assigned groupId must be a positive integer")

        assertTrue(allActions.filterIsInstance<PlanAction.PlaceTarget>().all { it.stance == null })
    }

    @Test
    internal fun directCandidateThatCannotSurviveItsWorldSupportIsUnreachable() {
        val target = BlockPos.ZERO
        val grass = Blocks.GRASS.defaultBlockState()
        val stone = Blocks.STONE.defaultBlockState()
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == target.below()) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(
            content = listOf(target to grass),
            worldState = worldState,
            params = PrintPlanParams(bounds = { pos -> pos == target }),
        )

        assertEquals(0, plan.report.directCount)
        assertEquals(1, plan.report.unreachableCount)
        assertEquals(listOf(target), plan.report.unreachablePositions)
    }

    @Test
    internal fun scaffoldChainInterleavesPlaceThenTargetThenReverseRemoval() {
        val stone = Blocks.STONE.defaultBlockState()
        val target = BlockPos(0, 5, 0)
        val content = listOf(target to stone)
        val solidPos = BlockPos(0, 2, 0)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == solidPos) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.scaffoldCount)
        assertEquals(
            listOf(
                PlanAction.PlaceScaffold(BlockPos(0, 3, 0)),
                PlanAction.PlaceScaffold(BlockPos(0, 4, 0)),
                PlanAction.PlaceTarget(target, stone),
                PlanAction.RemoveScaffold(BlockPos(0, 4, 0)),
                PlanAction.RemoveScaffold(BlockPos(0, 3, 0)),
            ),
            plan.units.single().actions.map(::stripGroupId),
        )
        assertEquals(listOf(BlockPos(0, 3, 0), BlockPos(0, 4, 0)), plan.report.scaffoldMaterialPositions)
        assertEquals(
            1,
            plan.units.single().actions.map(::groupIdOf).toSet().size,
            "chain cells + target + removal all share one groupId",
        )
    }

    @Test
    internal fun reservationResolvesOnceItsFutureSupportIsPlaced() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val a = BlockPos(0, 0, 0)
        val b = BlockPos(0, 1, 0)
        val content = listOf(a to stone, b to stone)
        val overrides = mapOf(
            a.below() to craftingTable,
            a.east() to craftingTable,
            a.west() to craftingTable,
            a.north() to craftingTable,
            a.south() to craftingTable,
            b.east() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.directCount)
        assertEquals(1, plan.report.reservedCount)
        assertEquals(0, plan.report.unreachableCount)
        assertEquals(listOf(PlanAction.PlaceTarget(b, stone)), plan.units.single().actions.map(::stripGroupId))
        assertEquals(
            listOf(PlanAction.PlaceTarget(a, stone, dependsOn = listOf(b))),
            plan.reservations.values.single().map(::stripGroupId),
        )
        val bGroupId = groupIdOf(plan.units.single().actions.single())
        val aGroupId = groupIdOf(plan.reservations.values.single().single())
        assertTrue(bGroupId > 0 && aGroupId > 0)
        assertTrue(bGroupId != aGroupId, "different targets must never share a groupId")

        val detail = plan.reservationDetails.single()
        assertEquals(a, detail.pos)
        assertEquals(ReservationOrigin.SUPPORT, detail.origin)
        assertEquals(ReservationMethod.DIRECT, detail.effectiveMethod)
        assertEquals(0, detail.triggerUnit, "the plan's single (band, tile) unit")
        assertEquals(listOf(b), detail.dependsOn)
    }

    @Test
    internal fun mutuallyDependentPairAlwaysFallsToScaffoldOrUnreachable() {
        val (content, worldState) = mutualDependencyFixture()

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(0, plan.report.reservedCount)
        assertEquals(2, plan.report.unreachableCount)
        assertEquals(
            listOf(BlockPos(0, 0, 0), BlockPos(0, 0, 1)),
            plan.report.unreachablePositions.sortedWith(compareBy({ it.x }, { it.y }, { it.z })),
        )
    }

    @Test
    internal fun mutuallyDependentPairDoesNotLoopForever() {
        val (content, worldState) = mutualDependencyFixture()

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val plan = PrintPlanner.plan(content, worldState)
            assertEquals(2, plan.report.unreachableCount)
        }
    }

    @Test
    internal fun precedingCellMustBeActuallyPlacedNotJustEarlierInOrderToCountAsSupport() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val bed = Blocks.RED_BED.defaultBlockState()
        val d = BlockPos(0, 0, 0)
        val c = BlockPos(0, 0, 1)
        val content = listOf(d to bed, c to stone)
        val overrides = mapOf(
            c.above() to craftingTable,
            c.below() to craftingTable,
            c.east() to craftingTable,
            c.west() to craftingTable,
            c.south() to craftingTable,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.excludedCategoryCount)
        assertEquals(1, plan.report.unreachableCount)
        assertEquals(listOf(c), plan.report.unreachablePositions)
    }

    @Test
    internal fun reservationTriggerPropagatesThroughMultiUnitChain() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val topSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
        val a = BlockPos(0, 0, 0)
        val b = BlockPos(0, 1, 0)
        val c = BlockPos(0, 2, 0)
        val content = listOf(a to stone, b to topSlab, c to stone)
        val overrides = mapOf(
            a.below() to craftingTable,
            a.east() to craftingTable,
            a.west() to craftingTable,
            a.north() to craftingTable,
            a.south() to craftingTable,
            b.east() to craftingTable,
            b.west() to craftingTable,
            b.north() to craftingTable,
            b.south() to craftingTable,
            c.east() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 1, tileSize = 48))

        assertEquals(1, plan.report.directCount, "c only")
        assertEquals(2, plan.report.reservedCount, "a and b")
        assertEquals(0, plan.report.unreachableCount)
        val cUnit = plan.units.indexOfFirst { unit -> unit.actions.any { it is PlanAction.PlaceTarget && it.pos == c } }
        assertTrue(cUnit >= 0, "c must resolve DIRECT in some unit")
        val cTriggerActions = plan.reservations.getValue(cUnit)
        assertEquals(
            listOf(b, a),
            cTriggerActions.map { (it as PlanAction.PlaceTarget).pos },
            "b (a's support) must resolve before a within the same trigger unit",
        )
        assertEquals(listOf(c), (cTriggerActions[0] as PlanAction.PlaceTarget).dependsOn)
        assertEquals(listOf(b), (cTriggerActions[1] as PlanAction.PlaceTarget).dependsOn)
    }

    @Test
    internal fun reservationRescuedByAlternateCandidateAfterSmallestOrderCandidateFails() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val a = BlockPos(0, 0, 0)
        val failing = BlockPos(1, 0, 0)
        val rescuer = BlockPos(0, 1, 0)
        val content = listOf(a to stone, failing to log, rescuer to stone)
        val overrides = mapOf(
            a.below() to craftingTable,
            a.north() to craftingTable,
            a.south() to craftingTable,
            a.west() to craftingTable,
            failing.above() to craftingTable,
            failing.below() to craftingTable,
            failing.north() to craftingTable,
            failing.south() to craftingTable,
            failing.east() to craftingTable,
            rescuer.north() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 1, tileSize = 48))

        assertEquals(1, plan.report.directCount, "rescuer only")
        assertEquals(1, plan.report.reservedCount, "a, rescued via rescuer")
        assertEquals(1, plan.report.unreachableCount, "failing")
        assertEquals(listOf(failing), plan.report.unreachablePositions)
        val rescuerUnit = plan.units.indexOfFirst { unit ->
            unit.actions.any { it is PlanAction.PlaceTarget && it.pos == rescuer }
        }
        val resolvedA = plan.reservations.getValue(rescuerUnit).single() as PlanAction.PlaceTarget
        assertEquals(a, resolvedA.pos)
        assertEquals(listOf(rescuer), resolvedA.dependsOn)
    }

    @Test
    internal fun reservationTriggerDefersToLaterAvailableAnchorSupportViaScaffoldChain() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val t = BlockPos(0, 0, 0)
        val x = BlockPos(1, 0, 0)
        val bridge = BlockPos(0, 1, 0)
        val anchorCell = BlockPos(0, 2, 0)
        val late = BlockPos(0, 3, 0)
        val content = listOf(t to stone, x to log, late to stone)
        val overrides = mapOf(
            t.below() to craftingTable,
            t.north() to craftingTable,
            t.south() to craftingTable,
            t.west() to craftingTable,
            bridge.north() to craftingTable,
            bridge.south() to craftingTable,
            bridge.east() to craftingTable,
            bridge.west() to craftingTable,
            anchorCell.north() to craftingTable,
            anchorCell.south() to craftingTable,
            anchorCell.east() to craftingTable,
            anchorCell.west() to craftingTable,
            x.above() to craftingTable,
            x.below() to craftingTable,
            x.north() to craftingTable,
            x.south() to craftingTable,
            x.east() to craftingTable,
            late.east() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 1, tileSize = 48))

        assertEquals(1, plan.report.directCount, "late only")
        assertEquals(1, plan.report.reservedCount, "t, rescued via scaffold chain")
        assertEquals(1, plan.report.unreachableCount, "x")
        assertEquals(listOf(x), plan.report.unreachablePositions)
        val lateUnit = plan.units.indexOfFirst { unit ->
            unit.actions.any { it is PlanAction.PlaceTarget && it.pos == late }
        }
        val tTriggerActions = plan.reservations.getValue(lateUnit)
        val tPlacement = tTriggerActions.single { it is PlanAction.PlaceTarget } as PlanAction.PlaceTarget
        assertEquals(t, tPlacement.pos)
        assertEquals(listOf(late), tPlacement.dependsOn)
        val anchorAction = tTriggerActions.first() as PlanAction.PlaceScaffold
        assertEquals(anchorCell, anchorAction.pos)
        assertEquals(listOf(late), anchorAction.dependsOn)
        val bridgeAction = tTriggerActions[1] as PlanAction.PlaceScaffold
        assertEquals(bridge, bridgeAction.pos)
        assertEquals(emptyList<BlockPos>(), bridgeAction.dependsOn)

        val tDetail = plan.reservationDetails.single()
        assertEquals(t, tDetail.pos)
        assertEquals(ReservationOrigin.SUPPORT, tDetail.origin)
        assertEquals(ReservationMethod.SCAFFOLD, tDetail.effectiveMethod)
        assertEquals(lateUnit, tDetail.triggerUnit)
        assertEquals(listOf(late), tDetail.dependsOn)
    }

    @Test
    internal fun rescueSweepResolvesReservationWithAvailableCandidateDespiteStillPendingSibling() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val a = BlockPos(0, 0, 0)
        val b = a.east()
        val c = a.south()
        val content = listOf(a to stone, b to stone, c to stone)
        val overrides = mapOf(
            a.above() to craftingTable,
            a.below() to craftingTable,
            a.west() to craftingTable,
            a.north() to craftingTable,
            b.above() to craftingTable,
            b.below() to stone,
            b.north() to craftingTable,
            b.south() to craftingTable,
            b.east() to craftingTable,
            c.above() to craftingTable,
            c.below() to craftingTable,
            c.east() to craftingTable,
            c.west() to craftingTable,
            c.south() to craftingTable,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.directCount, "b only")
        assertEquals(2, plan.report.reservedCount, "a (via b) and c (via a)")
        assertEquals(0, plan.report.unreachableCount)
        val bUnit = plan.units.indexOfFirst { unit -> unit.actions.any { it is PlanAction.PlaceTarget && it.pos == b } }
        assertTrue(bUnit >= 0, "b must resolve DIRECT in some unit")
        val bTriggerActions = plan.reservations.getValue(bUnit)
        assertEquals(
            listOf(a, c),
            bTriggerActions.map { (it as PlanAction.PlaceTarget).pos },
            "a (rescued via b) must resolve before c (rescued via a) within the same trigger unit",
        )
        assertEquals(listOf(b), (bTriggerActions[0] as PlanAction.PlaceTarget).dependsOn)
        assertEquals(listOf(a), (bTriggerActions[1] as PlanAction.PlaceTarget).dependsOn)
    }

    @Test
    internal fun reservationCrossingTileBoundaryResolvesToNeighborTilesUnit() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val a = BlockPos(0, 0, 0)
        val support = BlockPos(1, 0, 0)
        val content = listOf(a to stone, support to stone)
        val overrides = mapOf(
            a.above() to craftingTable,
            a.below() to craftingTable,
            a.west() to craftingTable,
            a.north() to craftingTable,
            a.south() to craftingTable,
            support.above() to craftingTable,
            support.north() to craftingTable,
            support.south() to craftingTable,
            support.below() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 1, tileSize = 1))

        assertEquals(1, plan.report.directCount, "support only")
        assertEquals(1, plan.report.reservedCount, "a, rescued via support in the neighboring tile")
        assertEquals(0, plan.report.unreachableCount)
        val supportUnitIndex = plan.units.indexOfFirst { unit ->
            unit.actions.any { it is PlanAction.PlaceTarget && it.pos == support }
        }
        assertTrue(supportUnitIndex >= 0, "support must resolve DIRECT in some unit")
        assertEquals(1, plan.units[supportUnitIndex].tileX, "support's own tile is the neighboring one, not a's")
        assertEquals(setOf(supportUnitIndex), plan.reservations.keys)
        val resolvedA = plan.reservations.getValue(supportUnitIndex).single() as PlanAction.PlaceTarget
        assertEquals(a, resolvedA.pos)
        assertEquals(listOf(support), resolvedA.dependsOn)
    }

    @Test
    internal fun sameInputProducesIdenticalPlanAcrossTwoRuns() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val topSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
        val a = BlockPos(0, 0, 0)
        val b = BlockPos(0, 1, 0)
        val c = BlockPos(0, 2, 0)
        val d = BlockPos(200, 0, 0)
        val e = BlockPos(200, 1, 0)
        val content = listOf(a to stone, b to topSlab, c to stone, d to stone, e to stone)
        val overrides = mapOf(
            a.below() to craftingTable,
            a.east() to craftingTable,
            a.west() to craftingTable,
            a.north() to craftingTable,
            a.south() to craftingTable,
            b.east() to craftingTable,
            b.west() to craftingTable,
            b.north() to craftingTable,
            b.south() to craftingTable,
            c.east() to stone,
            d.below() to craftingTable,
            d.east() to craftingTable,
            d.west() to craftingTable,
            d.north() to craftingTable,
            d.south() to craftingTable,
            e.east() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }
        val params = PrintPlanParams(bandHeight = 1, tileSize = 48)

        val first = PrintPlanner.plan(content, worldState, params)
        val second = PrintPlanner.plan(content, worldState, params)

        assertEquals(first, second)
        assertTrue(first.reservations.keys.size >= 2, "fixture must exercise more than one trigger unit")
        assertEquals(first.reservations.entries.toList(), second.reservations.entries.toList())
    }

    @Test
    internal fun scaffoldChainOutsideConfiguredBoundsFallsUnreachable() {
        val stone = Blocks.STONE.defaultBlockState()
        val target = BlockPos(0, 5, 0)
        val content = listOf(target to stone)
        val solidPos = BlockPos(0, 2, 0)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == solidPos) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bounds = { pos -> pos.y >= 5 }))

        assertEquals(0, plan.report.scaffoldCount, "the only route/anchor lies outside the configured bounds")
        assertEquals(0, plan.report.reservedCount)
        assertEquals(1, plan.report.unreachableCount)
        assertEquals(listOf(target), plan.report.unreachablePositions)
    }

    @Test
    internal fun reservationRetryNeverReadsAnOutOfBoundsAnchorSupport() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val t = BlockPos(0, 0, 0)
        val x = BlockPos(1, 0, 0)
        val bridge = BlockPos(0, 1, 0)
        val anchorCell = BlockPos(0, 2, 0)
        val poison = BlockPos(0, 3, 0)
        val content = listOf(t to stone, x to log)
        val overrides = mapOf(
            t.below() to craftingTable,
            t.north() to craftingTable,
            t.south() to craftingTable,
            t.west() to craftingTable,
            bridge.north() to craftingTable,
            bridge.south() to craftingTable,
            bridge.east() to craftingTable,
            bridge.west() to craftingTable,
            anchorCell.north() to craftingTable,
            anchorCell.south() to craftingTable,
            anchorCell.east() to craftingTable,
            anchorCell.west() to craftingTable,
            x.above() to craftingTable,
            x.below() to craftingTable,
            x.north() to craftingTable,
            x.south() to craftingTable,
            x.east() to craftingTable,
        )
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == poison) throw AssertionError("read out-of-bounds position $pos") else overrides[pos] ?: Blocks.AIR.defaultBlockState()
        }
        val bounds: (BlockPos) -> Boolean = { pos -> pos != poison }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 1, tileSize = 48, bounds = bounds))

        assertEquals(0, plan.report.directCount, "no schematic cell here has independent ground support")
        assertEquals(0, plan.report.reservedCount, "t's only anchor support lies outside bounds")
        assertEquals(2, plan.report.unreachableCount, "t (no in-bounds anchor support) and x (no support anywhere)")
        assertEquals(
            listOf(t, x),
            plan.report.unreachablePositions.sortedWith(compareBy({ it.x }, { it.y }, { it.z })),
        )
    }

    @Test
    internal fun reservationRetryBestSupportSkipsOutOfBoundsNeighborInFavorOfInBoundsSupport() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val t = BlockPos(0, 0, 0)
        val x = BlockPos(1, 0, 0)
        val bridge = BlockPos(0, 1, 0)
        val anchorCell = BlockPos(0, 2, 0)
        val late = BlockPos(0, 3, 0)
        val poison = anchorCell.north()
        val content = listOf(t to stone, x to log, late to stone)
        val overrides = mapOf(
            t.below() to craftingTable,
            t.north() to craftingTable,
            t.south() to craftingTable,
            t.west() to craftingTable,
            bridge.north() to craftingTable,
            bridge.south() to craftingTable,
            bridge.east() to craftingTable,
            bridge.west() to craftingTable,
            anchorCell.south() to craftingTable,
            anchorCell.east() to craftingTable,
            anchorCell.west() to craftingTable,
            x.above() to craftingTable,
            x.below() to craftingTable,
            x.north() to craftingTable,
            x.south() to craftingTable,
            x.east() to craftingTable,
            late.east() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == poison) throw AssertionError("read out-of-bounds position $pos") else overrides[pos] ?: Blocks.AIR.defaultBlockState()
        }
        val bounds: (BlockPos) -> Boolean = { pos -> pos != poison }

        val plan = PrintPlanner.plan(content, worldState, PrintPlanParams(bandHeight = 1, tileSize = 48, bounds = bounds))

        assertEquals(1, plan.report.directCount, "late only")
        assertEquals(1, plan.report.reservedCount, "t, rescued via scaffold chain anchored on late")
        assertEquals(1, plan.report.unreachableCount, "x")
        val lateUnit = plan.units.indexOfFirst { unit -> unit.actions.any { it is PlanAction.PlaceTarget && it.pos == late } }
        assertTrue(lateUnit >= 0, "late must resolve DIRECT in some unit")
        val tDetail = plan.reservationDetails.single()
        assertEquals(t, tDetail.pos)
        assertEquals(ReservationMethod.SCAFFOLD, tDetail.effectiveMethod)
        assertEquals(lateUnit, tDetail.triggerUnit, "must defer to late's own unit, never the out-of-bounds neighbor's unit 0")
        assertEquals(listOf(late), tDetail.dependsOn)
    }

    @Test
    internal fun scaffoldReachablePressurePlateWithNoPermanentFloorAnywhereFallsUnreachable() {
        val pressurePlate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState()
        val target = BlockPos(0, 5, 0)
        val content = listOf(target to pressurePlate)
        val solidPos = BlockPos(0, 2, 0)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == solidPos) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(0, plan.report.scaffoldCount, "a pressure plate cannot be held once its only support is removed")
        assertEquals(1, plan.report.unreachableCount, "no schematic content anywhere could ever become its floor")
        assertEquals(listOf(target), plan.report.unreachablePositions)
        assertEquals(0, plan.report.reservedCount)
    }

    @Test
    internal fun survivalReservationDefersScaffoldPlacementUntilFutureFloorCellIsConfirmed() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val pressurePlate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState()
        val t = BlockPos(0, 5, 0)
        val f = t.below()
        val g = f.east()
        val content = listOf(t to pressurePlate, f to stone, g to stone)
        val overrides = mapOf(
            t.north().north() to stone,
            f.below() to craftingTable,
            f.north() to craftingTable,
            f.south() to craftingTable,
            f.west() to craftingTable,
            g.below() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.directCount, "g only")
        assertEquals(2, plan.report.reservedCount, "f (via g) and t (via f)")
        assertEquals(0, plan.report.unreachableCount)
        assertEquals(0, plan.report.scaffoldCount, "t must not resolve on the plain (unconditional) scaffold path")

        val gUnit = plan.units.indexOfFirst { unit -> unit.actions.any { it is PlanAction.PlaceTarget && it.pos == g } }
        assertTrue(gUnit >= 0, "g must resolve DIRECT in some unit")
        val gTriggerActions = plan.reservations.getValue(gUnit)

        val fActionIndex = gTriggerActions.indexOfFirst { it is PlanAction.PlaceTarget && it.pos == f }
        assertTrue(fActionIndex >= 0, "f's placement must be scheduled in g's trigger unit")
        assertEquals(listOf(g), (gTriggerActions[fActionIndex] as PlanAction.PlaceTarget).dependsOn)

        val tPlaceIndex = gTriggerActions.indexOfFirst { it is PlanAction.PlaceTarget && it.pos == t }
        assertTrue(tPlaceIndex >= 0, "t's placement must be scheduled in g's trigger unit")
        assertTrue(fActionIndex < tPlaceIndex, "f (the floor) must resolve before t (rescued via f)")
        assertEquals(listOf(f), (gTriggerActions[tPlaceIndex] as PlanAction.PlaceTarget).dependsOn)

        val tAnchorAction = gTriggerActions[tPlaceIndex - 1] as PlanAction.PlaceScaffold
        assertEquals(t.north(), tAnchorAction.pos)
        assertEquals(listOf(f), tAnchorAction.dependsOn)
        val tRemoveAction = gTriggerActions[tPlaceIndex + 1] as PlanAction.RemoveScaffold
        assertEquals(t.north(), tRemoveAction.pos)
        assertEquals(
            setOf(groupIdOf(tAnchorAction)),
            setOf(tAnchorAction, gTriggerActions[tPlaceIndex], tRemoveAction).map(::groupIdOf).toSet(),
        )
        assertTrue(groupIdOf(tAnchorAction) != groupIdOf(gTriggerActions[fActionIndex]))

        val fDetail = plan.reservationDetails.single { it.pos == f }
        assertEquals(ReservationOrigin.SUPPORT, fDetail.origin)
        assertEquals(ReservationMethod.DIRECT, fDetail.effectiveMethod)
        assertEquals(gUnit, fDetail.triggerUnit)
        assertEquals(listOf(g), fDetail.dependsOn)

        val tDetail = plan.reservationDetails.single { it.pos == t }
        assertEquals(ReservationOrigin.SURVIVAL, tDetail.origin)
        assertEquals(ReservationMethod.SCAFFOLD, tDetail.effectiveMethod)
        assertEquals(gUnit, tDetail.triggerUnit, "t's trigger must be f's own effective trigger, not f's home unit")
        assertEquals(listOf(f), tDetail.dependsOn)
    }

    @Test
    internal fun survivalReservationResolutionRerunsSurvivalCheckAndStillAcceptsAConfirmedCandidate() {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val pressurePlate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState()
        val t = BlockPos(0, 5, 0)
        val f = t.below()
        val g = f.east()
        val content = listOf(t to pressurePlate, f to stone, g to stone)
        val overrides = mapOf(
            t.north().north() to stone,
            f.below() to craftingTable,
            f.north() to craftingTable,
            f.south() to craftingTable,
            f.west() to craftingTable,
            g.below() to stone,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(0, plan.report.unreachableCount)
        val gUnit = plan.units.indexOfFirst { unit -> unit.actions.any { it is PlanAction.PlaceTarget && it.pos == g } }
        val gTriggerActions = plan.reservations.getValue(gUnit)
        val tPlaceAction = gTriggerActions.single { it is PlanAction.PlaceTarget && it.pos == t } as PlanAction.PlaceTarget
        assertEquals(listOf(f), tPlaceAction.dependsOn, "t must still resolve via f after the resolution-time recheck")
    }

    @Test
    internal fun scaffoldOnStoneStillClassifiesAsScaffoldAssisted() {
        val stone = Blocks.STONE.defaultBlockState()
        val target = BlockPos(0, 5, 0)
        val content = listOf(target to stone)
        val solidPos = BlockPos(0, 2, 0)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == solidPos) stone else Blocks.AIR.defaultBlockState()
        }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(1, plan.report.scaffoldCount)
        assertEquals(0, plan.report.reservedCount)
        assertEquals(0, plan.report.unreachableCount)
    }

    @Test
    internal fun scaffoldChainThroughAReplaceableWorldVineMustNotCountItAsPermanentSupport() {
        val vineNorth = Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true)
        val target = BlockPos(0, 5, 0)
        val content = listOf(target to vineNorth)
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val overrides = mapOf(
            target.below() to craftingTable,
            target.east() to craftingTable,
            target.south() to craftingTable,
            target.west() to craftingTable,
            target.above() to vineNorth,
            target.above().north() to Blocks.STONE.defaultBlockState(),
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }

        val plan = PrintPlanner.plan(content, worldState)

        assertEquals(0, plan.report.scaffoldCount, "the real vine above is removed with the chain, not permanent")
        assertEquals(0, plan.report.reservedCount)
        assertEquals(1, plan.report.unreachableCount, "no schematic neighbor could ever become a permanent floor")
        assertEquals(listOf(target), plan.report.unreachablePositions)
    }

    @Test
    internal fun classificationFollowsInjectedBehaviorNotTheLiveHolder() {
        val doubleSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
        val target = BlockPos(0, 5, 0)
        val content = listOf(target to doubleSlab)
        val worldState: (BlockPos) -> BlockState = { pos ->
            if (pos == target.below()) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }

        PrinterSettingsHolder.printerSettings = PrinterSettings(substituteLookalikes = false)
        val substitutionOn = PrintPlanner.plan(
            content,
            worldState,
            PrintPlanParams(behavior = PlacementBehaviorSettings(substituteLookalikes = true, placeWaterloggedDry = false)),
        )
        assertEquals(1, substitutionOn.report.directCount, "holder says off, param says on -- param must win")
        assertEquals(0, substitutionOn.report.excludedCategoryCount)

        PrinterSettingsHolder.printerSettings = PrinterSettings(substituteLookalikes = true)
        val substitutionOff = PrintPlanner.plan(
            content,
            worldState,
            PrintPlanParams(behavior = PlacementBehaviorSettings(substituteLookalikes = false, placeWaterloggedDry = false)),
        )
        assertEquals(0, substitutionOff.report.directCount, "holder says on, param says off -- param must win")
        assertEquals(1, substitutionOff.report.excludedCategoryCount)
    }

    private fun groupIdOf(action: PlanAction): Int = when (action) {
        is PlanAction.PlaceTarget -> action.groupId
        is PlanAction.PlaceScaffold -> action.groupId
        is PlanAction.RemoveScaffold -> action.groupId
    }

    private fun stripGroupId(action: PlanAction): PlanAction = when (action) {
        is PlanAction.PlaceTarget -> action.copy(groupId = 0)
        is PlanAction.PlaceScaffold -> action.copy(groupId = 0)
        is PlanAction.RemoveScaffold -> action.copy(groupId = 0)
    }

    private fun mutualDependencyFixture(): Pair<List<Pair<BlockPos, BlockState>>, (BlockPos) -> BlockState> {
        val stone = Blocks.STONE.defaultBlockState()
        val craftingTable = Blocks.CRAFTING_TABLE.defaultBlockState()
        val a = BlockPos(0, 0, 0)
        val b = BlockPos(0, 0, 1)
        val content = listOf(a to stone, b to stone)
        val overrides = mapOf(
            a.above() to craftingTable,
            a.below() to craftingTable,
            a.east() to craftingTable,
            a.west() to craftingTable,
            a.north() to craftingTable,
            b.above() to craftingTable,
            b.below() to craftingTable,
            b.east() to craftingTable,
            b.west() to craftingTable,
            b.south() to craftingTable,
        )
        val worldState: (BlockPos) -> BlockState = { pos -> overrides[pos] ?: Blocks.AIR.defaultBlockState() }
        return content to worldState
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
