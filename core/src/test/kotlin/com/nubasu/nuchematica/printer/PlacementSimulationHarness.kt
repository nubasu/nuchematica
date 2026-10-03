package com.nubasu.nuchematica.printer

import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.schematic.reader.WorldEditSchematicReader
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

internal object PlacementSimulationHarness {
    internal data class SimulationResult(
        internal val world: Map<BlockPos, BlockState>,
        internal val remaining: Set<BlockPos>,
        internal val topPlacedY: Int,
        internal val minY: Int,
        internal val gateDescents: Int,
        internal val distinctEligibleLayers: Int,
        internal val ascentOrderViolations: List<BlockPos>,
        internal val scaffoldsPlaced: Int = 0,
    )

    internal fun loadExpected(): Map<BlockPos, BlockState>? {
        val resourceFile = javaClass.getResource(FIXTURE_RESOURCE_PATH)?.let { resource ->
            File(resource.toURI())
        }
        val file = resourceFile?.takeIf(File::exists) ?: File(FIXTURE_PATH)
        assumeTrue(file.exists(), "fixture schematic not present")
        if (!file.exists()) return null

        val clipboard = WorldEditSchematicReader.read(SchematicFormatDetector.readRootTag(file))
        val expected = HashMap<BlockPos, BlockState>()
        for (index in clipboard.position.indices) {
            val state = clipboard.block[index]
            if (!state.isAir) {
                expected[clipboard.position[index].immutable()] = state
            }
        }
        return expected
    }

    internal fun simulate(
        expected: Map<BlockPos, BlockState>,
        wrongWorldBlocks: Map<BlockPos, BlockState>,
        unreachablePositions: Set<BlockPos> = emptySet(),
        scaffoldAssist: Boolean = false,
    ): SimulationResult {
        val minY = expected.keys.minOf { it.y }
        val ground = Blocks.STONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val world = HashMap(wrongWorldBlocks)
        val stateAt: (BlockPos) -> BlockState = { pos ->
            world[pos] ?: if (pos.y < minY) ground else air
        }
        val eligibleKeys = expected.filterValues { eligiblePrinterBlockItem(it) != null }.keys
        val missing = HashSet(expected.keys)
        val isSchematicPosition: (BlockPos) -> Boolean = { pos -> pos in expected }
        var revision = 0L
        val ledger = PrinterDeferralLedger()
        ledger.synchronize(1L, expected, world)
        val actionable = { pos: BlockPos ->
            isReplaceableTarget(stateAt(pos)) &&
                (
                    hasSupportNeighbor(pos, expected.getValue(pos), stateAt) ||
                        (
                            scaffoldAssist &&
                                isScaffoldAssistable(pos, expected.getValue(pos), stateAt, isSchematicPosition, null)
                            )
                    ) &&
                !ledger.isDeferred(pos, stateAt, revision)
        }
        val gate = PrinterLayerGate()
        var topPlacedY = Int.MIN_VALUE
        var previousGateY: Int? = null
        var gateDescents = 0
        var runningAscentMax = Int.MIN_VALUE
        val ascentOrderViolations = mutableListOf<BlockPos>()
        var scaffoldsPlaced = 0
        var rounds = 0
        while (true) {
            rounds++
            check(rounds <= MAX_ROUNDS) { "simulation did not converge in $MAX_ROUNDS rounds" }
            val eligibleYs = missing.asSequence()
                .filter { it in eligibleKeys }
                .map { it.y }
                .distinct()
                .sorted()
                .toList()
            val gateY = gate.update(
                eligibleYs = eligibleYs,
                isLayerSupported = { y ->
                    missing.any { it.y == y && it in eligibleKeys && actionable(it) }
                },
                inReachAboveGate = 0,
                inReachEligibleAtOrBelowGate = 0,
                acceptedThisTick = 0,
                playerMoved = true,
            )
            val phase = gate.currentPhase()
            if (previousGateY != null && gateY != null && gateY < requireNotNull(previousGateY)) {
                gateDescents++
            }
            previousGateY = gateY
            if (phase == PrinterLayerGatePhase.ASCENT && gateY != null && gateY > runningAscentMax) {
                runningAscentMax = gateY
            }
            val placeable = missing.filter { pos ->
                pos in eligibleKeys && actionable(pos) && when {
                    gateY == null -> true
                    phase == PrinterLayerGatePhase.ASCENT -> pos.y == gateY
                    else -> pos.y <= gateY
                }
            }
            if (placeable.isEmpty()) break
            var placedThisRound = false
            for (pos in placeable) {
                if (pos in unreachablePositions) {
                    ledger.defer(
                        pos,
                        PrinterDeferralReason.MOVER_UNREACHABLE,
                        stateAt,
                        revision,
                    )
                    continue
                }
                if (phase == PrinterLayerGatePhase.ASCENT && pos.y < runningAscentMax) {
                    ascentOrderViolations.add(pos)
                }
                if (scaffoldAssist && !hasSupportNeighbor(pos, expected.getValue(pos), stateAt)) {
                    scaffoldsPlaced++
                }
                world[pos] = effectivePlacementState(expected.getValue(pos))
                missing.remove(pos)
                placedThisRound = true
                if (pos.y > topPlacedY) topPlacedY = pos.y
            }
            if (placedThisRound) revision++
        }
        return SimulationResult(
            world = world,
            remaining = missing,
            topPlacedY = topPlacedY,
            minY = minY,
            gateDescents = gateDescents,
            distinctEligibleLayers = eligibleKeys.map { position -> position.y }.distinct().size,
            ascentOrderViolations = ascentOrderViolations,
            scaffoldsPlaced = scaffoldsPlaced,
        )
    }

    private const val FIXTURE_RESOURCE_PATH: String = "/test_schematic/Fantasy_BigHouse1.schematic"
    private const val FIXTURE_PATH: String = "run/schematics/Fantasy_BigHouse1.schematic"
    private const val MAX_ROUNDS: Int = 500
}
