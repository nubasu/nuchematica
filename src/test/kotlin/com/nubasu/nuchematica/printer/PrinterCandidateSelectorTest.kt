package com.nubasu.nuchematica.printer

import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    public fun candidatesAreOrderedByEyeDistance(): Unit {
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

    private fun select(
        missing: List<BlockPos>,
        states: Map<BlockPos, BlockState>,
        eyePosition: Vec3,
        reach: Double,
        skipLog: PrinterSkipLog = PrinterSkipLog(),
        expected: BlockState = expectedState,
        predictedState: BlockState = expected,
    ): List<PrinterCandidate> {
        val selector = PrinterCandidateSelector(
            skipLog = skipLog,
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
        )
    }

    private fun stateReader(states: Map<BlockPos, BlockState>): (BlockPos) -> BlockState {
        return { pos -> states[pos] ?: Blocks.AIR.defaultBlockState() }
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
