package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class PrinterCandidateSelectorTest {
    private val expectedState: BlockState = Blocks.STONE.defaultBlockState()

    @AfterEach
    public fun resetPrinterSettings(): Unit {
        PrinterSettingsHolder.printerSettings = PrinterSettings()
    }

    @Test
    public fun reachUsesHitLocationAndIncludesExactBoundary(): Unit {
        val target = BlockPos(0, 1, 0)
        val support = target.below()
        val states = mapOf(support to Blocks.STONE.defaultBlockState())
        val hitCenter = Vec3(0.5, 0.9999, 0.5)
        val boundaryEye = hitCenter.add(-4.5, 0.0, 0.0)

        val boundary = select(
            missing = listOf(target),
            states = states,
            eyePosition = boundaryEye,
            reach = 4.5,
        )

        assertEquals(1, boundary.size)
        assertEquals(4.5 * 4.5, boundary.single().distanceSquared, 0.0000001)

        val log = PrinterSkipLog()
        val outside = select(
            missing = listOf(target),
            states = states,
            eyePosition = hitCenter.add(-4.50001, 0.0, 0.0),
            reach = 4.5,
            skipLog = log,
        )
        assertTrue(outside.isEmpty())
        assertEquals(1, log.count(PrinterSkipReason.OUT_OF_REACH))
    }

    @Test
    public fun farPositionBeyondPrefilterMarginSkipsStateAndPredictionReads(): Unit {
        val target = BlockPos(100, 1, 0)
        val log = PrinterSkipLog()
        var stateAtCalls = 0
        var predictionCalls = 0
        val selector = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ ->
                predictionCalls++
                expectedState
            },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expectedState },
            localToWorld = { it },
            stateAt = { _ ->
                stateAtCalls++
                Blocks.AIR.defaultBlockState()
            },
            placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 4.5,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(0, stateAtCalls)
        assertEquals(0, predictionCalls)
        assertEquals(1, log.count(PrinterSkipReason.OUT_OF_REACH))
    }

    @Test
    public fun missingSupportFaceAndPredictionMismatchAreCounted(): Unit {
        val target = BlockPos(0, 1, 0)
        val noFaceLog = PrinterSkipLog()

        assertTrue(
            select(
                missing = listOf(target),
                states = emptyMap(),
                eyePosition = Vec3.ZERO,
                reach = 10.0,
                skipLog = noFaceLog,
            ).isEmpty(),
        )
        assertEquals(1, noFaceLog.count(PrinterSkipReason.NO_SUPPORT_FACE))

        val predictionLog = PrinterSkipLog()
        assertTrue(
            select(
                missing = listOf(target),
                states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
                eyePosition = Vec3.ZERO,
                reach = 10.0,
                skipLog = predictionLog,
                predictedState = Blocks.DIRT.defaultBlockState(),
            ).isEmpty(),
        )
        assertEquals(1, predictionLog.count(PrinterSkipReason.PREDICTION_MISMATCH))
    }

    @Test
    public fun predictionMismatchInvokesOnSkipCallback(): Unit {
        val target = BlockPos(0, 1, 0)
        val callbacks = mutableListOf<Pair<PrinterSkipReason, BlockPos>>()

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3.ZERO,
            reach = 10.0,
            predictedState = Blocks.DIRT.defaultBlockState(),
            onSkip = { reason, worldPos -> callbacks += reason to worldPos },
        )

        assertTrue(candidates.isEmpty())
        assertEquals(listOf(PrinterSkipReason.PREDICTION_MISMATCH to target), callbacks)
    }

    @Test
    public fun equivalentPlacedLeavesPredictionCreatesCandidate(): Unit {
        val target = BlockPos(0, 1, 0)
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
            .setValue(BlockStateProperties.DISTANCE, 1)
        val log = PrinterSkipLog()

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3.ZERO,
            reach = 10.0,
            skipLog = log,
            expected = naturalLeaves,
            predictedState = placedLeaves,
        )

        assertEquals(1, candidates.size)
        assertEquals(naturalLeaves, candidates.single().expectedState)
        assertEquals(0, log.count(PrinterSkipReason.PREDICTION_MISMATCH))
    }

    @Test
    public fun dryFencePredictionCreatesCandidateWhenDryPlacementEnabled(): Unit {
        PrinterSettingsHolder.printerSettings.placeWaterloggedDry = true
        val target = BlockPos(0, 1, 0)
        val waterloggedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)
        val dryFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false)

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3.ZERO,
            reach = 10.0,
            expected = waterloggedFence,
            predictedState = dryFence,
        )

        assertEquals(1, candidates.size)
    }

    @Test
    public fun unsafeOnlySupportRecordsNoFaceWithoutPrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val log = PrinterSkipLog()
        var predictionCalls = 0
        val selector = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ ->
                predictionCalls++
                expectedState
            },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expectedState },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.CHEST.defaultBlockState())),
            placementContext = { _, _ -> mockk(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(1, log.count(PrinterSkipReason.NO_SUPPORT_FACE))
        assertEquals(0, predictionCalls)
    }

    @Test
    public fun safeSupportIsSelectedWhenOppositeSupportIsUnsafe(): Unit {
        val target = BlockPos(0, 1, 0)
        val unsafeSupport = target.below()
        val safeSupport = target.above()

        val candidates = select(
            missing = listOf(target),
            states = mapOf(
                unsafeSupport to Blocks.CHEST.defaultBlockState(),
                safeSupport to Blocks.STONE.defaultBlockState(),
            ),
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertEquals(1, candidates.size)
        assertEquals(safeSupport, candidates.single().hit.blockPos)
    }

    @Test
    public fun doorCategoryIsExcludedBeforePrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val log = PrinterSkipLog()
        var predictionCalls = 0
        val selector = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ ->
                predictionCalls++
                Blocks.OAK_DOOR.defaultBlockState()
            },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { Blocks.OAK_DOOR.defaultBlockState() },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { _, _ -> mockk(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(1, log.count(PrinterSkipReason.CATEGORY_EXCLUDED))
        assertEquals(0, predictionCalls)
    }

    @Test
    public fun signCategoryIsExcludedBeforePrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val expectedState = Blocks.OAK_SIGN.defaultBlockState()
        val log = PrinterSkipLog()
        var predictionCalls = 0
        val selector = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ ->
                predictionCalls++
                expectedState
            },
        )

        assertNull(eligiblePrinterBlockItem(expectedState))
        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expectedState },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { _, _ -> mockk(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(1, log.count(PrinterSkipReason.CATEGORY_EXCLUDED))
        assertEquals(0, predictionCalls)
    }

    @Test
    public fun topStoneSlabUsesUpperSideHitAndRealPlacementPrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
        val attemptedHits = mutableListOf<BlockHitResult>()
        val selector = PrinterCandidateSelector()

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(
                mapOf(
                    target.below() to Blocks.STONE.defaultBlockState(),
                    target.west() to Blocks.STONE.defaultBlockState(),
                ),
            ),
            placementContext = { _, hit ->
                attemptedHits += hit
                slabPlacementContext(hit)
            },
            eyePosition = Vec3(0.5, 1.0, 0.5),
            reach = 10.0,
        )

        val candidate = candidates.single()
        val predicted = requireNotNull(
            eligiblePrinterBlockItem(expected)?.getPlacementState(slabPlacementContext(candidate.hit)),
        )
        assertEquals(SlabType.TOP, predicted.getValue(BlockStateProperties.SLAB_TYPE))
        assertEquals(1.75, candidate.hit.location.y, 0.0000001)
        assertTrue(attemptedHits.none { hit -> hit.direction == Direction.UP })
    }

    @Test
    public fun bottomStoneSlabUsesLowerSideHitAndRealPlacementPrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
        val attemptedHits = mutableListOf<BlockHitResult>()
        val selector = PrinterCandidateSelector()

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(
                mapOf(
                    target.above() to Blocks.STONE.defaultBlockState(),
                    target.west() to Blocks.STONE.defaultBlockState(),
                ),
            ),
            placementContext = { _, hit ->
                attemptedHits += hit
                slabPlacementContext(hit)
            },
            eyePosition = Vec3(0.5, 2.0, 0.5),
            reach = 10.0,
        )

        val candidate = candidates.single()
        val predicted = requireNotNull(
            eligiblePrinterBlockItem(expected)?.getPlacementState(slabPlacementContext(candidate.hit)),
        )
        assertEquals(SlabType.BOTTOM, predicted.getValue(BlockStateProperties.SLAB_TYPE))
        assertEquals(1.25, candidate.hit.location.y, 0.0000001)
        assertTrue(attemptedHits.none { hit -> hit.direction == Direction.DOWN })
    }

    @Test
    public fun doubleSlabIsCategoryExcludedWhenLookalikeSubstitutionIsDisabled(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.OAK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
        PrinterSettingsHolder.printerSettings.substituteLookalikes = false
        val log = PrinterSkipLog()
        var predictionCalls = 0
        val selector = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ ->
                predictionCalls++
                expected
            },
        )

        assertNull(eligiblePrinterBlockItem(expected))
        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { _, _ -> mockk(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(1, log.count(PrinterSkipReason.CATEGORY_EXCLUDED))
        assertEquals(0, predictionCalls)
    }

    @Test
    public fun doubleOakSlabUsesOakPlanksForSelectionAndPrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.OAK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
        val placementStates = mutableListOf<BlockState>()
        val predictedItems = mutableListOf<Any>()
        val selector = PrinterCandidateSelector(
            predictPlacement = { item, _ ->
                predictedItems += item
                Blocks.OAK_PLANKS.defaultBlockState()
            },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { placementState, _ ->
                placementStates += placementState
                mockk(relaxed = true)
            },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertEquals(Blocks.OAK_PLANKS.asItem(), predictedItems.single())
        assertEquals(listOf(Blocks.OAK_PLANKS.defaultBlockState()), placementStates)
        val candidate = candidates.single()
        assertEquals(target, candidate.worldPos)
        assertEquals(expected, candidate.expectedState)
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), candidate.placementState)
    }

    @Test
    public fun infestedStoneBricksUsesNormalStoneBricksForSelectionAndPrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val expected = Blocks.INFESTED_STONE_BRICKS.defaultBlockState()
        val placementStates = mutableListOf<BlockState>()
        val predictedItems = mutableListOf<Any>()
        val selector = PrinterCandidateSelector(
            predictPlacement = { item, _ ->
                predictedItems += item
                Blocks.STONE_BRICKS.defaultBlockState()
            },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { placementState, _ ->
                placementStates += placementState
                mockk(relaxed = true)
            },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertEquals(Blocks.STONE_BRICKS.asItem(), predictedItems.single())
        assertEquals(listOf(Blocks.STONE_BRICKS.defaultBlockState()), placementStates)
        val candidate = candidates.single()
        assertEquals(target, candidate.worldPos)
        assertEquals(expected, candidate.expectedState)
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), candidate.placementState)
    }

    // hasSupportNeighbor (used by the gate memo and the mover, unlike
    // supportHits above which only the selector calls) must exclude the exact same
    // faces supportHits already excludes, so a TOP/BOTTOM half expected state is never
    // reported "supported" by a neighbor no real placement could ever use.
    @Test
    public fun hasSupportNeighborExcludesBelowNeighborForTopHalfExpectedState(): Unit {
        val target = BlockPos(0, 1, 0)
        val topSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)

        // Regression guard: a naive plain 6-direction check would return true here,
        // disagreeing with supportHits (which already excludes this face for the
        // selector) and letting the gate/mover treat an unplaceable top-slab island as
        // supported.
        assertFalse(
            hasSupportNeighbor(
                target,
                topSlab,
                stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            ),
        )
    }

    @Test
    public fun hasSupportNeighborTrueForTopHalfExpectedStateWithSideSupport(): Unit {
        val target = BlockPos(0, 1, 0)
        val topSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)

        assertTrue(
            hasSupportNeighbor(
                target,
                topSlab,
                stateReader(mapOf(target.west() to Blocks.STONE.defaultBlockState())),
            ),
        )
    }

    @Test
    public fun hasSupportNeighborExcludesAboveNeighborForBottomHalfExpectedState(): Unit {
        val target = BlockPos(0, 1, 0)
        val bottomSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)

        assertFalse(
            hasSupportNeighbor(
                target,
                bottomSlab,
                stateReader(mapOf(target.above() to Blocks.STONE.defaultBlockState())),
            ),
        )
    }

    @Test
    public fun hasSupportNeighborUnaffectedForHalflessExpectedState(): Unit {
        val target = BlockPos(0, 1, 0)

        assertTrue(
            hasSupportNeighbor(
                target,
                Blocks.STONE.defaultBlockState(),
                stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            ),
        )
    }

    // isActionableMissing's canScaffold term. A position with no
    // usable support face is still actionable when the supplied canScaffold reports
    // a scaffold plan exists for it -- this is the single OR term the gate, the mover,
    // and classifyMissing all route through instead of re-deriving "supported OR
    // scaffoldable" independently (avoiding a definition-drift class of bug).
    @Test
    public fun scaffoldPlannablePositionIsActionableWithoutRealSupport(): Unit {
        val target = BlockPos(0, 1, 0)
        val noSupport: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        assertFalse(
            isActionableMissing(target, expectedState, noSupport),
            "sanity: no support and the default canScaffold (always false) must not be actionable",
        )
        assertTrue(
            isActionableMissing(
                target,
                expectedState,
                noSupport,
                canScaffold = { _, _, _ -> true },
            ),
        )
    }

    @Test
    public fun unplannablePositionStaysNonActionableEvenWithCanScaffoldSupplied(): Unit {
        val target = BlockPos(0, 1, 0)
        val noSupport: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        assertFalse(
            isActionableMissing(
                target,
                expectedState,
                noSupport,
                canScaffold = { _, _, _ -> false },
            ),
        )
    }

    @Test
    public fun hasSupportNeighborRejectsHorizontalNeighborsForVerticalPillar(): Unit {
        val target = BlockPos(0, 1, 0)
        val verticalLog = Blocks.OAK_LOG.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val horizontalSupports = mapOf(
            target.west() to Blocks.STONE.defaultBlockState(),
            target.north() to Blocks.STONE.defaultBlockState(),
        )

        assertFalse(hasSupportNeighbor(target, verticalLog, stateReader(horizontalSupports)))
    }

    @Test
    public fun verticalPillarSupportHitsExcludeHorizontalNeighbors(): Unit {
        val target = BlockPos(0, 1, 0)
        val verticalLog = Blocks.OAK_LOG.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        val log = PrinterSkipLog()
        var placementContextCalls = 0
        val candidates = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ -> verticalLog },
        ).select(
            missingLocal = listOf(target),
            expectedStateAt = { verticalLog },
            localToWorld = { it },
            stateAt = stateReader(
                mapOf(
                    target.west() to Blocks.STONE.defaultBlockState(),
                    target.east() to Blocks.STONE.defaultBlockState(),
                ),
            ),
            placementContext = { _, _ ->
                placementContextCalls++
                mockk(relaxed = true)
            },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(0, placementContextCalls)
        assertEquals(1, log.count(PrinterSkipReason.NO_SUPPORT_FACE))
    }

    @Test
    public fun horizontalPillarUsesMatchingSideFaceAndRealPlacementPrediction(): Unit {
        val target = BlockPos(0, 1, 0)
        val verticalTrunk = target.west()
        val horizontalLog = Blocks.OAK_LOG.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X)
        val mockContext = OrientedMockContext(horizontalDirection = Direction.NORTH)

        assertTrue(
            hasSupportNeighbor(
                target,
                horizontalLog,
                stateReader(mapOf(verticalTrunk to Blocks.OAK_LOG.defaultBlockState())),
            ),
        )

        val candidate = PrinterCandidateSelector().select(
            missingLocal = listOf(target),
            expectedStateAt = { horizontalLog },
            localToWorld = { it },
            stateAt = stateReader(mapOf(verticalTrunk to Blocks.OAK_LOG.defaultBlockState())),
            placementContext = { _, hit -> mockContext.forHit(hit) },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        ).single()

        assertEquals(verticalTrunk, candidate.hit.blockPos)
        assertEquals(Direction.EAST, candidate.hit.direction)
        val predicted = requireNotNull(
            eligiblePrinterBlockItem(horizontalLog)
                ?.getPlacementState(mockContext.forHit(candidate.hit)),
        )
        assertEquals(Direction.Axis.X, predicted.getValue(BlockStateProperties.AXIS))
        assertTrue(BlockStateEquivalence.matches(horizontalLog, predicted))
    }

    // HALF-aware hit correction: the slab-only SLAB_TYPE bias in supportHits
    // was generalized to also read StairBlock/TrapDoorBlock's HALF property. Confirmed
    // against StairBlock.getStateForPlacement / TrapDoorBlock.getStateForPlacement
    // bytecode (javap, forge-1.18.2-40.3.0_mapped_official_1.18.2.jar): a DOWN-face hit
    // always yields HALF=TOP, an UP-face hit always yields HALF=BOTTOM, and a side-face
    // hit yields TOP only when (clickLocation.y - clickedPos.y) > 0.5 -- the same rule
    // slabs already used. These tests exercise the real BlockItem.getPlacementState
    // against a single shared mock BlockPlaceContext (see OrientedMockContext below):
    // per-trial mockk construction has previously stalled the test worker.
    @Test
    public fun topOakStairsUsesUpperSideHitAndMatchesHorizontalFacing(): Unit {
        val target = BlockPos(0, 1, 0)
        val support = target.west()
        val expected = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        val mockContext = OrientedMockContext(horizontalDirection = Direction.EAST)
        val selector = PrinterCandidateSelector()

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(mapOf(support to Blocks.STONE.defaultBlockState())),
            placementContext = { _, hit -> mockContext.forHit(hit) },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        )

        val candidate = candidates.single()
        assertEquals(Direction.EAST, candidate.hit.direction)
        assertEquals(1.75, candidate.hit.location.y, 0.0000001)
        val predicted = requireNotNull(
            eligiblePrinterBlockItem(expected)?.getPlacementState(mockContext.forHit(candidate.hit)),
        )
        assertEquals(Half.TOP, predicted.getValue(BlockStateProperties.HALF))
        assertEquals(Direction.EAST, predicted.getValue(BlockStateProperties.HORIZONTAL_FACING))
    }

    @Test
    public fun bottomOakStairsUsesLowerSideHitAndMatchesHorizontalFacing(): Unit {
        val target = BlockPos(0, 1, 0)
        val support = target.east()
        val expected = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
        val mockContext = OrientedMockContext(horizontalDirection = Direction.SOUTH)
        val selector = PrinterCandidateSelector()

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(mapOf(support to Blocks.STONE.defaultBlockState())),
            placementContext = { _, hit -> mockContext.forHit(hit) },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        )

        val candidate = candidates.single()
        assertEquals(Direction.WEST, candidate.hit.direction)
        assertEquals(1.25, candidate.hit.location.y, 0.0000001)
        val predicted = requireNotNull(
            eligiblePrinterBlockItem(expected)?.getPlacementState(mockContext.forHit(candidate.hit)),
        )
        assertEquals(Half.BOTTOM, predicted.getValue(BlockStateProperties.HALF))
        assertEquals(Direction.SOUTH, predicted.getValue(BlockStateProperties.HORIZONTAL_FACING))
    }

    @Test
    public fun topOakTrapdoorUsesUpperSideHitAndMatchesClickedFaceFacing(): Unit {
        val target = BlockPos(0, 1, 0)
        val support = target.west()
        val expected = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        val mockContext = OrientedMockContext(horizontalDirection = Direction.EAST)
        val selector = PrinterCandidateSelector()

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(mapOf(support to Blocks.STONE.defaultBlockState())),
            placementContext = { _, hit -> mockContext.forHit(hit) },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        )

        val candidate = candidates.single()
        assertEquals(Direction.EAST, candidate.hit.direction)
        assertEquals(1.75, candidate.hit.location.y, 0.0000001)
        val predicted = requireNotNull(
            eligiblePrinterBlockItem(expected)?.getPlacementState(mockContext.forHit(candidate.hit)),
        )
        assertEquals(Half.TOP, predicted.getValue(BlockStateProperties.HALF))
        assertEquals(Direction.EAST, predicted.getValue(BlockStateProperties.HORIZONTAL_FACING))
    }

    @Test
    public fun nonSlabSupportHitsKeepAllFaceCentersUnchanged(): Unit {
        val target = BlockPos(0, 1, 0)
        val supports = Direction.values().associate { direction ->
            target.relative(direction) to Blocks.STONE.defaultBlockState()
        }
        val attemptedHits = mutableListOf<BlockHitResult>()
        val selector = PrinterCandidateSelector(
            predictPlacement = { _, _ -> Blocks.DIRT.defaultBlockState() },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { Blocks.STONE.defaultBlockState() },
            localToWorld = { it },
            stateAt = stateReader(supports),
            placementContext = { _, hit ->
                attemptedHits += hit
                mockk(relaxed = true)
            },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(6, attemptedHits.size)
        val byFace = attemptedHits.associateBy { hit -> hit.direction }
        assertHit(byFace, Direction.UP, target.below(), Vec3(0.5, 0.9999, 0.5))
        assertHit(byFace, Direction.DOWN, target.above(), Vec3(0.5, 2.0001, 0.5))
        assertHit(byFace, Direction.NORTH, target.south(), Vec3(0.5, 1.5, 1.0001))
        assertHit(byFace, Direction.SOUTH, target.north(), Vec3(0.5, 1.5, -0.0001))
        assertHit(byFace, Direction.WEST, target.east(), Vec3(1.0001, 1.5, 0.5))
        assertHit(byFace, Direction.EAST, target.west(), Vec3(-0.0001, 1.5, 0.5))
    }

    // potentialSupportHitPoints and supportHits must share one
    // geometry function structurally, not two copies that could drift apart. When
    // every neighbor is a real support, the world-checked supportHits locations
    // (attemptedHits above) and the pure envelope must be the exact same 6 points.
    @Test
    public fun potentialSupportHitPointsMatchesSupportHitsExactlyWhenEveryNeighborSupports(): Unit {
        val target = BlockPos(0, 1, 0)
        val expectedState = Blocks.STONE.defaultBlockState()
        val supports = Direction.values().associate { direction ->
            target.relative(direction) to Blocks.STONE.defaultBlockState()
        }
        val attemptedHits = mutableListOf<BlockHitResult>()
        PrinterCandidateSelector(
            predictPlacement = { _, _ -> Blocks.DIRT.defaultBlockState() },
        ).select(
            missingLocal = listOf(target),
            expectedStateAt = { expectedState },
            localToWorld = { it },
            stateAt = stateReader(supports),
            placementContext = { _, hit ->
                attemptedHits += hit
                mockk(relaxed = true)
            },
            eyePosition = Vec3(0.5, 1.5, 0.5),
            reach = 10.0,
        )

        val envelope = potentialSupportHitPoints(target, expectedState)
        assertEquals(6, envelope.size)
        assertEquals(attemptedHits.map { it.location }.toSet(), envelope.toSet())
    }

    // The envelope is pure geometry, independent of world state: it must still report
    // the same 5 usable faces (all but the excluded support-from-below) for a TOP-half
    // stair even though no neighbor here actually has a block in it.
    @Test
    public fun potentialSupportHitPointsExcludesBelowFaceForTopHalfRegardlessOfWorldState(): Unit {
        val target = BlockPos(0, 1, 0)
        val topStairs = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HALF, Half.TOP)

        val envelope = potentialSupportHitPoints(target, topStairs)

        assertEquals(5, envelope.size)
        assertTrue(envelope.none { point -> point.y < 1.0 })
    }

    @Test
    public fun airAndReplaceableTargetsAreEligibleButSolidTargetIsNot(): Unit {
        val airTarget = BlockPos(0, 1, 0)
        val replaceableTarget = BlockPos(2, 1, 0)
        val solidTarget = BlockPos(4, 1, 0)
        val states = mapOf(
            airTarget.below() to Blocks.STONE.defaultBlockState(),
            replaceableTarget to Blocks.TALL_GRASS.defaultBlockState(),
            replaceableTarget.below() to Blocks.STONE.defaultBlockState(),
            solidTarget to Blocks.COBBLESTONE.defaultBlockState(),
            solidTarget.below() to Blocks.STONE.defaultBlockState(),
        )

        val candidates = select(
            missing = listOf(airTarget, replaceableTarget, solidTarget),
            states = states,
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertEquals(setOf(airTarget, replaceableTarget), candidates.map { it.worldPos }.toSet())
    }

    @Test
    public fun lowerLayerIsOrderedBeforeCloserHigherLayer(): Unit {
        val lower = BlockPos(8, 1, 0)
        val higher = BlockPos(1, 5, 0)
        val states = mapOf(
            lower.below() to Blocks.STONE.defaultBlockState(),
            higher.below() to Blocks.STONE.defaultBlockState(),
        )

        val candidates = select(
            missing = listOf(higher, lower),
            states = states,
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 20.0,
        )

        assertEquals(listOf(lower, higher), candidates.map { it.worldPos })
    }

    @Test
    public fun orientedPredictionSuppliesRequiredRotationWhenBaselinePredictionMismatches(): Unit {
        val target = BlockPos(0, 1, 0)
        val rotation = PlacementRotation(yaw = 90f, pitch = 0f)
        val selector = PrinterCandidateSelector(
            predictPlacement = { _, _ -> Blocks.DIRT.defaultBlockState() },
            orientedPrediction = { _, _, _ -> rotation },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expectedState },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertEquals(1, candidates.size)
        assertEquals(rotation, candidates.single().requiredRotation)
    }

    @Test
    public fun orientedPredictionReturningNullKeepsPredictionMismatchSkip(): Unit {
        val target = BlockPos(0, 1, 0)
        val log = PrinterSkipLog()
        val selector = PrinterCandidateSelector(
            skipLog = log,
            predictPlacement = { _, _ -> Blocks.DIRT.defaultBlockState() },
            orientedPrediction = { _, _, _ -> null },
        )

        val candidates = selector.select(
            missingLocal = listOf(target),
            expectedStateAt = { expectedState },
            localToWorld = { it },
            stateAt = stateReader(mapOf(target.below() to Blocks.STONE.defaultBlockState())),
            placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
            eyePosition = Vec3.ZERO,
            reach = 10.0,
        )

        assertTrue(candidates.isEmpty())
        assertEquals(1, log.count(PrinterSkipReason.PREDICTION_MISMATCH))
    }

    @Test
    public fun resolveOrientedRotationReturnsSecondTrialMatchAndRestoresRotation(): Unit {
        val setCalls = mutableListOf<Pair<Float, Float>>()
        val currentYaw = 10f
        val currentPitch = 5f

        val result = resolveOrientedRotation(
            currentYaw = currentYaw,
            currentPitch = currentPitch,
            setRotation = { yaw, pitch -> setCalls += yaw to pitch },
            matches = { trial -> trial == PlacementRotation(90f, 0f) },
        )

        assertEquals(PlacementRotation(90f, 0f), result)
        assertEquals(
            listOf(0f to 0f, 90f to 0f, currentYaw to currentPitch),
            setCalls,
        )
    }

    @Test
    public fun resolveOrientedRotationReturnsNullAndRestoresWhenNoTrialMatches(): Unit {
        val setCalls = mutableListOf<Pair<Float, Float>>()
        val currentYaw = 20f
        val currentPitch = -5f

        val result = resolveOrientedRotation(
            currentYaw = currentYaw,
            currentPitch = currentPitch,
            setRotation = { yaw, pitch -> setCalls += yaw to pitch },
            matches = { false },
        )

        assertNull(result)
        assertEquals(6, setCalls.size - 1)
        assertEquals(currentYaw to currentPitch, setCalls.last())
    }

    @Test
    public fun candidatesOnSameLayerAreOrderedByEyeDistance(): Unit {
        val near = BlockPos(1, 1, 0)
        val far = BlockPos(5, 1, 0)
        val states = mapOf(
            near.below() to Blocks.STONE.defaultBlockState(),
            far.below() to Blocks.STONE.defaultBlockState(),
        )

        val candidates = select(
            missing = listOf(far, near),
            states = states,
            eyePosition = Vec3(0.5, 1.0, 0.5),
            reach = 10.0,
        )

        assertEquals(listOf(near, far), candidates.map { it.worldPos })
    }

    // Player-column placement guard. A candidate the player is
    // currently standing in/on/over must be skipped transiently (plain continue) --
    // not recorded to the skip log or deferred -- since it is not a structural
    // rejection, just momentarily unavailable until the mover moves the player away.
    @Test
    public fun candidateInPlayerFeetCellIsSkippedWithoutRecordingAnySkipReason(): Unit {
        val target = BlockPos(0, 1, 0)
        val log = PrinterSkipLog()

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            skipLog = log,
            playerFeetPos = Vec3(0.5, 1.0, 0.5),
        )

        assertTrue(candidates.isEmpty())
        assertEquals(0, PrinterSkipReason.values().sumOf { reason -> log.count(reason) })
    }

    @Test
    public fun candidateInPlayerHeadCellIsSkipped(): Unit {
        val target = BlockPos(0, 2, 0)

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            // Feet at y=1: occupies cell 1 (feet) and cell 2 (head).
            playerFeetPos = Vec3(0.5, 1.0, 0.5),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    public fun candidateOneCellAboveHeadIsSkippedToCoverFractionalHover(): Unit {
        val target = BlockPos(0, 3, 0)

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            // Feet at y=1: guarded range is feet..head+1 == 1..3.
            playerFeetPos = Vec3(0.5, 1.0, 0.5),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    public fun candidateTwoCellsAboveHeadIsNotSkipped(): Unit {
        val target = BlockPos(0, 4, 0)

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            playerFeetPos = Vec3(0.5, 1.0, 0.5),
        )

        assertEquals(1, candidates.size)
    }

    @Test
    public fun candidateInADifferentColumnIsNeverSkippedRegardlessOfHeight(): Unit {
        val target = BlockPos(5, 1, 0)

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            playerFeetPos = Vec3(9.5, 1.0, 0.5),
        )

        assertEquals(1, candidates.size)
    }

    @Test
    public fun candidateIsSelectedOnceThePlayerMovesAwayFromItsColumn(): Unit {
        val target = BlockPos(0, 1, 0)
        val states = mapOf(target.below() to Blocks.STONE.defaultBlockState())

        val whileStandingOnIt = select(
            missing = listOf(target),
            states = states,
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            playerFeetPos = Vec3(0.5, 1.0, 0.5),
        )
        val afterMovingAway = select(
            missing = listOf(target),
            states = states,
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            playerFeetPos = Vec3(10.5, 1.0, 10.5),
        )

        assertTrue(whileStandingOnIt.isEmpty())
        assertEquals(listOf(target), afterMovingAway.map { it.worldPos })
    }

    @Test
    public fun noPlayerFeetPosNeverSkipsAnyCandidate(): Unit {
        val target = BlockPos(0, 1, 0)

        val candidates = select(
            missing = listOf(target),
            states = mapOf(target.below() to Blocks.STONE.defaultBlockState()),
            eyePosition = Vec3(0.5, 5.0, 0.5),
            reach = 10.0,
            playerFeetPos = null,
        )

        assertEquals(1, candidates.size)
    }

    private fun select(
        missing: List<BlockPos>,
        states: Map<BlockPos, BlockState>,
        eyePosition: Vec3,
        reach: Double,
        skipLog: PrinterSkipLog = PrinterSkipLog(),
        expected: BlockState = expectedState,
        predictedState: BlockState = expected,
        onSkip: (PrinterSkipReason, BlockPos) -> Unit = { _, _ -> },
        playerFeetPos: Vec3? = null,
    ): List<PrinterCandidate> {
        val selector = PrinterCandidateSelector(
            skipLog = skipLog,
            onSkip = onSkip,
            predictPlacement = { _, _ -> predictedState },
        )
        return selector.select(
            missingLocal = missing,
            expectedStateAt = { expected },
            localToWorld = { it },
            stateAt = stateReader(states),
            placementContext = { _, _ -> mockk<BlockPlaceContext>(relaxed = true) },
            eyePosition = eyePosition,
            reach = reach,
            playerFeetPos = playerFeetPos,
        )
    }

    private fun stateReader(states: Map<BlockPos, BlockState>): (BlockPos) -> BlockState {
        return { pos -> states[pos] ?: Blocks.AIR.defaultBlockState() }
    }

    private fun slabPlacementContext(hit: BlockHitResult): BlockPlaceContext {
        val level = mockk<Level>()
        val context = mockk<BlockPlaceContext>()
        every { context.level } returns level
        every { context.player } returns null
        every { context.clickedPos } returns hit.blockPos.relative(hit.direction)
        every { context.clickedFace } returns hit.direction
        every { context.clickLocation } returns hit.location
        every { context.nearestLookingDirections } returns arrayOf(Direction.NORTH)
        every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
        every { level.getFluidState(any()) } returns Fluids.EMPTY.defaultFluidState()
        every {
            level.isUnobstructed(any(), any(), any<CollisionContext>())
        } returns true
        return context
    }

    private fun assertHit(
        hitsByFace: Map<Direction, BlockHitResult>,
        face: Direction,
        expectedBlockPos: BlockPos,
        expectedLocation: Vec3,
    ): Unit {
        val hit = requireNotNull(hitsByFace[face])
        assertEquals(expectedBlockPos, hit.blockPos)
        assertEquals(expectedLocation.x, hit.location.x, 0.0000001)
        assertEquals(expectedLocation.y, hit.location.y, 0.0000001)
        assertEquals(expectedLocation.z, hit.location.z, 0.0000001)
    }

    // Single mock BlockPlaceContext (plus a mutable holder for the per-hit fields)
    // reused across every hit tried within one test, instead of re-mocking per hit like
    // slabPlacementContext above: constructing mockk instances in a loop (as the oriented
    // rotation trials for stairs/trapdoors would require, one hit per direction) has
    // previously stalled the test worker. Mirrors slabPlacementContext's stubs, including
    // BlockPlaceContext.getClickedPos() resolving to the target position (hit.blockPos
    // .relative(hit.direction)), not the clicked support block itself: real
    // BlockPlaceContext returns the placement position when the clicked block is solid.
    private class OrientedMockContext(horizontalDirection: Direction) {
        private val level: Level = mockk()
        private val context: BlockPlaceContext = mockk()
        private var clickedPos: BlockPos = BlockPos.ZERO
        private var clickedFace: Direction = Direction.UP
        private var clickLocation: Vec3 = Vec3.ZERO

        init {
            every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
            every { level.getFluidState(any()) } returns Fluids.EMPTY.defaultFluidState()
            every { level.hasNeighborSignal(any()) } returns false
            every {
                level.isUnobstructed(any(), any(), any<CollisionContext>())
            } returns true
            every { context.level } returns level
            every { context.player } returns null
            every { context.clickedPos } answers { clickedPos }
            every { context.clickedFace } answers { clickedFace }
            every { context.clickLocation } answers { clickLocation }
            every { context.horizontalDirection } returns horizontalDirection
            every { context.replacingClickedOnBlock() } returns false
            every { context.nearestLookingDirections } returns arrayOf(Direction.NORTH)
        }

        fun forHit(hit: BlockHitResult): BlockPlaceContext {
            clickedPos = hit.blockPos.relative(hit.direction)
            clickedFace = hit.direction
            clickLocation = hit.location
            return context
        }
    }

    public companion object {
        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
