package com.nubasu.nuchematica.automode

import com.nubasu.nuchematica.mover.MoverCommand
import com.nubasu.nuchematica.mover.MoverCore
import com.nubasu.nuchematica.mover.MoverSessionKey
import com.nubasu.nuchematica.mover.MoverState
import com.nubasu.nuchematica.mover.MoverTickContext
import com.nubasu.nuchematica.mover.PathProbeResult
import com.nubasu.nuchematica.mover.isFootprintCollisionFree
import com.nubasu.nuchematica.mover.sweptVolumeCollisionFree
import com.nubasu.nuchematica.printer.FeedSnapshot
import com.nubasu.nuchematica.printer.PrinterCandidateSelector
import com.nubasu.nuchematica.printer.PrinterLayerGatePhase
import com.nubasu.nuchematica.printer.PrinterSettings
import com.nubasu.nuchematica.printer.SCAFFOLD_BLOCK_STATE
import com.nubasu.nuchematica.printer.isActionableMissing
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

internal class AutomodeSimulationSmokeTest {
    @Test
    internal fun productionPlannerPrinterAndMoverCompleteOneLayerWithoutViolations(): Unit {
        val result = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "one-layer-line",
                content = lineBuilding(length = 8),
                seed = 17L,
                latencyTicks = listOf(0, 2, 10),
                maxTicks = 5_000L,
            ),
        ).run()

        println("[automode-sim] ${result.scenario} ${result.metrics} hash=${result.normalizedTraceHash}")
        printFirstBodyCollisionContext(result)
        assertTrue(result.cleanViolations().isEmpty()) {
            "strict simulation violations: ${result.cleanViolations()}"
        }
    }

    @Test
    internal fun sameSeedProducesTheSameNormalizedTrace(): Unit {
        val scenario = AutomodeScenario(
            name = "repeatability",
            content = listOf(
                BlockPos(0, 0, 0) to Blocks.STONE.defaultBlockState(),
                BlockPos(0, 1, 0) to Blocks.OAK_PLANKS.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.STONE.defaultBlockState(),
            ),
            seed = 20260823L,
            latencyTicks = listOf(0, 2, 10),
            maxTicks = 5_000L,
        )

        val first = DeterministicAutomodeSimulator(scenario).run()
        val second = DeterministicAutomodeSimulator(scenario).run()

        assertEquals(first.normalizedTraceHash, second.normalizedTraceHash)
        assertEquals(first.trace, second.trace)
    }

    @Test
    internal fun initialOrphanScaffoldIsDetectedEvenWhenItWasNotPlanned(): Unit {
        val orphan = BlockPos(12, 0, 12)
        val result = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "initial-orphan-scaffold",
                content = lineBuilding(length = 1),
                initialWorld = mapOf(orphan to SCAFFOLD_BLOCK_STATE),
                seed = 19L,
                maxTicks = 1_000L,
            ),
        ).run()

        assertEquals(listOf(orphan), result.orphanScaffolds)
        assertTrue("orphanScaffolds=1" in result.cleanViolations())
    }

    @Test
    internal fun denseFloorDoesNotDeadlockOnPendingTargetColumns(): Unit {
        val stone = Blocks.STONE.defaultBlockState()
        val denseFloor = buildList {
            for (x in 0..5) {
                for (z in 0..5) add(BlockPos(x, 0, z) to stone)
            }
        }
        val result = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "dense-floor",
                content = denseFloor,
                seed = 41L,
                latencyTicks = listOf(2),
                attemptsPerTick = 4,
                maxTicks = 5_000L,
            ),
        ).run()

        println("[automode-sim] dense-floor ${result.metrics}")
        printFirstBodyCollisionContext(result)
        assertTrue(result.cleanViolations().isEmpty()) {
            "strict simulation violations: ${result.cleanViolations()}"
        }
    }

    @Test
    internal fun hangingFenceGlowstoneAndEveryStairOrientationUseRealPlacementPrediction(): Unit {
        val content = linkedMapOf<BlockPos, BlockState>()
        for (x in 0..24) content[BlockPos(x, 0, 0)] = Blocks.STONE.defaultBlockState()

        val facings = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
        for ((index, facing) in facings.withIndex()) {
            val bottomTarget = BlockPos(index * 3 + 1, 1, 0)
            content[bottomTarget] = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)

            val topTarget = BlockPos(index * 3 + 14, 1, 0)
            content[topTarget.west()] = Blocks.STONE.defaultBlockState()
            content[topTarget] = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.HALF, Half.TOP)
        }

        for (y in 0..4) content[BlockPos(29, y, 0)] = Blocks.OAK_PLANKS.defaultBlockState()
        content[BlockPos(30, 4, 0)] = Blocks.OAK_PLANKS.defaultBlockState()
        content[BlockPos(30, 3, 0)] = Blocks.OAK_FENCE.defaultBlockState()
        content[BlockPos(30, 2, 0)] = Blocks.OAK_FENCE.defaultBlockState()
        content[BlockPos(30, 1, 0)] = Blocks.GLOWSTONE.defaultBlockState()

        val result = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "oriented-stairs-and-hanging-chain",
                content = content.entries.map { (pos, state) -> pos to state },
                seed = 83L,
                latencyTicks = listOf(0, 2, 10),
                attemptsPerTick = 4,
                maxTicks = 20_000L,
            ),
        ).run()

        assertTrue(result.cleanViolations().isEmpty()) {
            "strict simulation violations: ${result.cleanViolations()} issues=${result.actionIssues.take(10)}"
        }
        assertEquals(content.size, result.plannedTargetCount)
        assertEquals(content.size, result.matchedSourceBlockCount)
    }

    @Test
    internal fun seriousSimulatorRefusesTheExplicitLegacyPathInsteadOfSilentlyTestingAnotherMode(): Unit {
        assertThrows(IllegalArgumentException::class.java) {
            AutomodeScenario(
                name = "legacy-mode-is-not-plan-simulation",
                content = lineBuilding(length = 1),
                printerSettings = PrinterSettings(planFirstMode = false),
            )
        }
    }

    @Test
    internal fun legacyV3PrinterAndMoverCompleteARepresentativeLine(): Unit {
        val expected = lineBuilding(length = 12).toMap()
        val air = Blocks.AIR.defaultBlockState()
        val ground = Blocks.STONE.defaultBlockState()
        val live = LinkedHashMap<BlockPos, BlockState>()
        val stateAt: (BlockPos) -> BlockState = { pos -> live[pos] ?: if (pos.y < 0) ground else air }
        val selector = PrinterCandidateSelector(predictPlacement = { _, _ -> ground })
        val mover = MoverCore()
        val session = MoverSessionKey(Any(), Any(), 1L)
        var player = Vec3(-5.5, 0.01, -5.5)
        var onGround = true
        var flying = false
        var queueRevision = 0L
        var lastAcceptTick: Long? = null
        var missing = expected.keys.toList()
        var collisions = 0

        mover.toggleRequested(mayfly = true, isCreative = true, hasMissing = true)
        for (tick in 0L until 2_000L) {
            missing = expected.keys.filter { pos -> stateAt(pos) != expected[pos] }
            val candidates = selector.select(
                missingLocal = missing,
                expectedStateAt = expected::get,
                localToWorld = { pos -> pos },
                stateAt = stateAt,
                placementContext = { _, _ -> mockk(relaxed = true) },
                eyePosition = player.add(0.0, 1.62, 0.0),
                reach = 4.5,
                playerFeetPos = player,
            )
            val submitted = candidates.take(4)
            for (candidate in submitted) live[candidate.worldPos] = candidate.placementState
            if (submitted.isNotEmpty()) {
                queueRevision += submitted.size
                lastAcceptTick = tick
            }
            missing = expected.keys.filter { pos -> stateAt(pos) != expected[pos] }
            val feed = FeedSnapshot(
                tick = tick,
                queueRevision = queueRevision,
                candidateCount = candidates.size,
                submittedCount = submitted.size,
                rateLimitedRemainder = candidates.size - submitted.size,
                inFlightCount = 0,
                acceptedThisTick = submitted.size,
                ticksSinceLastAccept = lastAcceptTick?.let { acceptedTick -> tick - acceptedTick },
            )
            val command = mover.tick(
                legacyMoverContext(
                    session = session,
                    player = player,
                    onGround = onGround,
                    flying = flying,
                    queueRevision = queueRevision,
                    feed = feed,
                    missing = missing,
                    expected = expected,
                    stateAt = stateAt,
                ),
            )
            val moved = applyLegacyMovement(command, player, onGround, stateAt)
            player = moved.player
            onGround = moved.onGround
            if (command.enableFlight) flying = true
            if (!isFootprintCollisionFree(player.x, player.y, player.z, stateAt)) collisions++
            if (mover.status().state == MoverState.COMPLETE) break
        }

        assertTrue(missing.isEmpty()) { "legacy printer left ${missing.size} blocks" }
        assertEquals(MoverState.COMPLETE, mover.status().state)
        assertEquals(0, collisions)
    }

    private fun legacyMoverContext(
        session: MoverSessionKey,
        player: Vec3,
        onGround: Boolean,
        flying: Boolean,
        queueRevision: Long,
        feed: FeedSnapshot,
        missing: List<BlockPos>,
        expected: Map<BlockPos, BlockState>,
        stateAt: (BlockPos) -> BlockState,
    ): MoverTickContext {
        return MoverTickContext(
            sessionKey = session,
            playerPos = player,
            onGround = onGround,
            flying = flying,
            mayfly = true,
            isCreative = true,
            guiOpen = false,
            hurt = false,
            manualInput = false,
            correctionReceived = false,
            queueRevision = queueRevision,
            feedSnapshot = feed,
            gateY = 0,
            gatePhase = PrinterLayerGatePhase.ASCENT,
            missingWorld = missing,
            isPlaceable = { pos ->
                val expectedState = expected[pos]
                expectedState != null && isActionableMissing(pos, expectedState, stateAt)
            },
            expectedStateAt = expected::get,
            isPassableCell = { pos ->
                stateAt(pos).getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty
            },
            reach = 4.5,
            pathProbe = { from, to ->
                PathProbeResult(sweptVolumeCollisionFree(from, to, stateAt), chunkLoaded = true)
            },
            onWorkPositionAbandoned = {},
            onWorkPositionUnproductive = { _, _ -> Unit },
            onPositionsUncoverable = { _, _ -> Unit },
            canComplete = { missing.isEmpty() },
        )
    }

    private fun applyLegacyMovement(
        command: MoverCommand,
        player: Vec3,
        onGround: Boolean,
        stateAt: (BlockPos) -> BlockState,
    ): LegacyMovement {
        var position = player
        var grounded = onGround
        if (command.jump && grounded) {
            val jumped = position.add(0.0, 0.42, 0.0)
            if (sweptVolumeCollisionFree(position, jumped, stateAt)) position = jumped
            grounded = false
        }
        if (command.stopMovement) return LegacyMovement(position, grounded)
        val delta = Vec3(command.horizontalX * 0.30, command.vertical * 0.24, command.horizontalZ * 0.30)
        val destination = position.add(delta)
        if (sweptVolumeCollisionFree(position, destination, stateAt)) position = destination
        return LegacyMovement(position, grounded)
    }

    private data class LegacyMovement(val player: Vec3, val onGround: Boolean)

    private fun printFirstBodyCollisionContext(result: AutomodeSimulationResult): Unit {
        val collisionIndex = result.trace.indexOfFirst { line -> "\"body\":0" !in line }
        if (collisionIndex < 0) return
        result.trace.subList(maxOf(0, collisionIndex - 3), collisionIndex + 1).forEach(::println)
    }

    internal companion object {
        @JvmStatic
        @BeforeAll
        internal fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
