package com.nubasu.nuchematica.automode

import com.nubasu.nuchematica.mover.MoverAbortReason
import com.nubasu.nuchematica.mover.MoverState
import com.nubasu.nuchematica.printer.PlacementBehaviorSettings
import com.nubasu.nuchematica.printer.PrinterSettingsCodec
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.schematic.reader.WorldEditSchematicReader
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Random

@Tag("automode-full")
internal class AutomodeFullSimulationTest {
    @Test
    internal fun fantasySeedsLatencyAndFaultMatrixProduceMachineReadableEvidence(): Unit {
        val wallStart = System.nanoTime()
        val cleanResults = mutableListOf<AutomodeSimulationResult>()
        val recoveryResults = mutableListOf<AutomodeSimulationResult>()

        val fantasyContent = loadFantasyFixture()
        val observedFantasySettings = PrinterSettingsCodec.decode(OBSERVED_FANTASY_SETTINGS_JSON)
        assertTrue(observedFantasySettings.planFirstMode)
        val fantasy = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "fantasy-big-house",
                content = fantasyContent,
                seed = 20260823L,
                latencyTicks = listOf(0, 2, 10),
                attemptsPerTick = 8,
                maxTicks = 250_000L,
                printerSettings = observedFantasySettings,
                behavior = PlacementBehaviorSettings(
                    substituteLookalikes = observedFantasySettings.substituteLookalikes,
                    placeWaterloggedDry = observedFantasySettings.placeWaterloggedDry,
                ),
            ),
        ).run()
        val criticalPositions = fantasyContent.filter { (_, state) ->
            state.block is StairBlock || state.block is TrapDoorBlock ||
                state.block == Blocks.OAK_FENCE || state.block == Blocks.GLOWSTONE
        }.map { (pos, _) -> pos }.toSet()
        val unplannedCritical = criticalPositions - fantasy.plannedTargetPositions
        val missingCritical = criticalPositions.intersect(fantasy.missingPlannedTargets.toSet())
        val fantasyCriticalViolations = buildList {
            if (unplannedCritical.isNotEmpty()) add("critical not planned=$unplannedCritical")
            if (missingCritical.isNotEmpty()) add("critical not placed=$missingCritical")
        }
        println(
            "[automode-full] fantasyCritical=${criticalPositions.size} " +
                "unplanned=${unplannedCritical.size} missing=${missingCritical.size}",
        )
        cleanResults += fantasy

        for (seed in FIXED_SEEDS) {
            cleanResults += DeterministicAutomodeSimulator(
                AutomodeScenario(
                    name = "seed-$seed",
                    content = seededStructure(seed),
                    seed = seed,
                    latencyTicks = listOf(0, 2, 10),
                    attemptsPerTick = 4,
                    maxTicks = 15_000L,
                ),
            ).run()
        }

        for (latency in LATENCY_MATRIX) {
            cleanResults += DeterministicAutomodeSimulator(
                AutomodeScenario(
                    name = "latency-$latency",
                    content = seededStructure(73L),
                    seed = 73L,
                    latencyTicks = listOf(latency),
                    attemptsPerTick = 4,
                    maxTicks = 15_000L,
                ),
            ).run()
        }

        recoveryResults += recoveryScenario("reject", SimulatedPacketOutcome.REJECT)
        recoveryResults += recoveryScenario("timeout", SimulatedPacketOutcome.TIMEOUT)
        recoveryResults += recoveryScenario("wrong-state", SimulatedPacketOutcome.WRONG_STATE_THEN_CORRECT)

        val correctionAbort = DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "correction-storm",
                content = lineBuilding(24),
                seed = 101L,
                latencyTicks = listOf(2),
                correctionTicks = setOf(5L, 6L, 7L, 8L),
                maxTicks = 5_000L,
            ),
        ).run()

        val repeatScenario = AutomodeScenario(
            name = "trace-repeat",
            content = seededStructure(211L),
            seed = 211L,
            latencyTicks = listOf(0, 2, 10),
            attemptsPerTick = 4,
            maxTicks = 15_000L,
        )
        val repeatFirst = DeterministicAutomodeSimulator(repeatScenario).run()
        val repeatSecond = DeterministicAutomodeSimulator(repeatScenario).run()

        val repeatViolations = buildList {
            if (repeatFirst.normalizedTraceHash != repeatSecond.normalizedTraceHash) {
                add("repeat hash mismatch: ${repeatFirst.normalizedTraceHash} != ${repeatSecond.normalizedTraceHash}")
            }
            if (repeatFirst.trace != repeatSecond.trace) add("repeat normalized traces differ")
        }
        val evaluations = buildList {
            for (result in cleanResults) {
                val criticalViolations = if (result === fantasy) fantasyCriticalViolations else emptyList()
                add(
                    ScenarioEvaluation(
                        result,
                        result.cleanViolations() + regressionViolations(result) + criticalViolations,
                    ),
                )
            }
            for (result in recoveryResults) add(ScenarioEvaluation(result, recoveryViolations(result)))
            add(ScenarioEvaluation(correctionAbort, correctionAbortViolations(correctionAbort)))
            add(
                ScenarioEvaluation(
                    repeatFirst,
                    repeatFirst.cleanViolations() + regressionViolations(repeatFirst) + repeatViolations,
                ),
            )
            add(
                ScenarioEvaluation(
                    repeatSecond,
                    repeatSecond.cleanViolations() + regressionViolations(repeatSecond) + repeatViolations,
                ),
            )
        }
        val elapsedMillis = (System.nanoTime() - wallStart) / 1_000_000L
        val violations = evaluations.flatMap { evaluation ->
            evaluation.violations.map { violation ->
                "${evaluation.result.scenario}[seed=${evaluation.result.seed}]: $violation"
            }
        } + if (elapsedMillis > MAX_WALL_MILLIS) {
            listOf("full-matrix[seed=all]: elapsedMillis=$elapsedMillis>$MAX_WALL_MILLIS")
        } else {
            emptyList()
        }
        writeEvidence(evaluations, violations, elapsedMillis)

        println(
            "[automode-full] status=${if (violations.isEmpty()) "PASS" else "FAIL"} " +
                "scenarios=${evaluations.size} fantasyTicks=${fantasy.metrics.ticks} " +
                "fantasyDistance=${decimal(fantasy.metrics.totalDistance)} elapsedMillis=$elapsedMillis " +
                "traceHash=${repeatFirst.normalizedTraceHash}",
        )
        assertTrue(violations.isEmpty()) { failureMessage(evaluations, violations) }
        assertEquals(repeatFirst.normalizedTraceHash, repeatSecond.normalizedTraceHash)
    }

    private fun recoveryScenario(name: String, outcome: SimulatedPacketOutcome): AutomodeSimulationResult {
        return DeterministicAutomodeSimulator(
            AutomodeScenario(
                name = "fault-$name",
                content = lineBuilding(6),
                seed = 97L,
                latencyTicks = listOf(2),
                faults = listOf(SimulatedNetworkFault(submissionIndex = 1, outcome = outcome)),
                maxTicks = 10_000L,
            ),
        ).run()
    }

    private fun recoveryViolations(result: AutomodeSimulationResult): List<String> {
        val violations = mutableListOf<String>()
        if (result.moverState != MoverState.COMPLETE) violations += "mover=${result.moverState}/${result.abortReason}"
        if (!result.cursorComplete) violations += "cursor incomplete"
        if (result.missingPlannedTargets.isNotEmpty()) violations += "missing=${result.missingPlannedTargets.size}"
        if (result.orphanScaffolds.isNotEmpty()) violations += "orphanScaffolds=${result.orphanScaffolds.size}"
        if (result.metrics.bodyCollisions != 0) violations += "bodyCollisions=${result.metrics.bodyCollisions}"
        if (result.metrics.outOfBoundsBody != 0) violations += "outOfBoundsBody=${result.metrics.outOfBoundsBody}"
        if (result.metrics.invalidPlacements != 0) violations += "invalidPlacements=${result.metrics.invalidPlacements}"
        if (result.metrics.reachOutsideClicks != 0) {
            violations += "reachOutsideClicks=${result.metrics.reachOutsideClicks}"
        }
        if (result.metrics.supportMissingClicks != 0) {
            violations += "supportMissingClicks=${result.metrics.supportMissingClicks}"
        }
        if (result.metrics.playerColumnClicks != 0) {
            violations += "playerColumnClicks=${result.metrics.playerColumnClicks}"
        }
        if (result.metrics.hardCaps != 0) violations += "hardCaps=${result.metrics.hardCaps}"
        if (result.metrics.stallEscapes != 0) violations += "stallEscapes=${result.metrics.stallEscapes}"
        if (result.metrics.nonproductiveMovementLegs != 0) {
            violations += "nonproductiveMovementLegs=${result.metrics.nonproductiveMovementLegs}"
        }
        return violations
    }

    private fun correctionAbortViolations(result: AutomodeSimulationResult): List<String> {
        val violations = mutableListOf<String>()
        if (result.moverState != MoverState.ABORTED || result.abortReason != MoverAbortReason.SERVER_CORRECTION) {
            violations += "expected safe SERVER_CORRECTION abort, got ${result.moverState}/${result.abortReason}"
        }
        if (result.metrics.bodyCollisions != 0) violations += "bodyCollisions=${result.metrics.bodyCollisions}"
        if (result.metrics.outOfBoundsBody != 0) violations += "outOfBoundsBody=${result.metrics.outOfBoundsBody}"
        if (result.metrics.invalidPlacements != 0) violations += "invalidPlacements=${result.metrics.invalidPlacements}"
        if (result.metrics.hardCaps != 0) violations += "hardCaps=${result.metrics.hardCaps}"
        return violations
    }

    private fun regressionViolations(result: AutomodeSimulationResult): List<String> {
        val ceiling = REGRESSION_CEILINGS[result.scenario] ?: return emptyList()
        val metrics = result.metrics
        return buildList {
            if (metrics.ticks > ceiling.ticks) add("ticks=${metrics.ticks}>${ceiling.ticks}")
            if (metrics.totalDistance > ceiling.distance) {
                add("distance=${decimal(metrics.totalDistance)}>${decimal(ceiling.distance)}")
            }
            if (metrics.verticalDistance > ceiling.verticalDistance) {
                add(
                    "verticalDistance=${decimal(metrics.verticalDistance)}>" +
                        decimal(ceiling.verticalDistance),
                )
            }
            if (metrics.aStarInvocations > ceiling.aStarInvocations) {
                add("aStarInvocations=${metrics.aStarInvocations}>${ceiling.aStarInvocations}")
            }
            if (metrics.routeRebuilds > ceiling.routeRebuilds) {
                add("routeRebuilds=${metrics.routeRebuilds}>${ceiling.routeRebuilds}")
            }
        }
    }

    private fun failureMessage(evaluations: List<ScenarioEvaluation>, violations: List<String>): String {
        val failed = evaluations.firstOrNull { evaluation -> evaluation.violations.isNotEmpty() }
        val scenario = failed?.result?.scenario ?: "full-matrix"
        val seed = failed?.result?.seed?.toString() ?: "all"
        val firstViolation = failed?.violations?.firstOrNull() ?: violations.firstOrNull() ?: "unknown"
        return "scenario=$scenario seed=$seed firstViolation=$firstViolation\n" +
            "rerun=.\\gradlew.bat automodeSimulation --tests \"${AutomodeFullSimulationTest::class.qualifiedName}\" " +
            "--rerun-tasks --console=plain"
    }

    private fun seededStructure(seed: Long): List<Pair<BlockPos, BlockState>> {
        val random = Random(seed)
        val content = mutableListOf<Pair<BlockPos, BlockState>>()
        for (x in 0..5) {
            for (z in 0..5) {
                val floor = if (random.nextBoolean()) {
                    Blocks.STONE.defaultBlockState()
                } else {
                    Blocks.OAK_PLANKS.defaultBlockState()
                }
                content += BlockPos(x, 0, z) to floor
            }
        }
        for (x in 0..5) {
            for (z in 0..5) {
                if (x !in setOf(0, 5) && z !in setOf(0, 5)) continue
                if (x == 0 && z == 2) continue
                content += BlockPos(x, 1, z) to Blocks.OAK_PLANKS.defaultBlockState()
                if ((x + z + seed.toInt()) and 1 == 0) {
                    val shapedState = if ((x * 31 + z + seed.toInt()) and 2 == 0) {
                        Blocks.OAK_FENCE.defaultBlockState()
                    } else {
                        Blocks.STONE_SLAB.defaultBlockState()
                    }
                    content += BlockPos(x, 2, z) to shapedState
                }
            }
        }
        return content
    }

    private fun loadFantasyFixture(): List<Pair<BlockPos, BlockState>> {
        val resource = requireNotNull(javaClass.getResource(FANTASY_RESOURCE)) {
            "required fixture missing: $FANTASY_RESOURCE"
        }
        val file = File(resource.toURI())
        val clipboard = WorldEditSchematicReader.read(SchematicFormatDetector.readRootTag(file))
        return buildList {
            for (index in clipboard.position.indices) {
                val state = clipboard.block[index]
                if (!state.isAir) add(clipboard.position[index].immutable() to state)
            }
        }
    }

    private fun writeEvidence(
        evaluations: List<ScenarioEvaluation>,
        violations: List<String>,
        elapsedMillis: Long,
    ): Unit {
        val reportDir = Path.of("build", "reports", "automode-sim")
        Files.createDirectories(reportDir)
        for ((index, evaluation) in evaluations.withIndex()) {
            val result = evaluation.result
            val safeName = result.scenario.replace(Regex("[^A-Za-z0-9._-]"), "-")
            Files.writeString(
                reportDir.resolve("%02d-%s.jsonl".format(Locale.ROOT, index, safeName)),
                result.trace.joinToString(separator = "\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }
        val summary = buildString {
            append("{\n  \"status\": \"").append(if (violations.isEmpty()) "PASS" else "FAIL").append("\",")
            append("\n  \"elapsedMillis\": ").append(elapsedMillis).append(',')
            append("\n  \"hardViolationCount\": ").append(violations.size).append(',')
            append("\n  \"violations\": [")
            violations.forEachIndexed { index, violation ->
                if (index > 0) append(',')
                append("\n    \"").append(jsonEscape(violation)).append('\"')
            }
            if (violations.isNotEmpty()) append('\n')
            append("  ],\n  \"scenarios\": [")
            evaluations.forEachIndexed { index, evaluation ->
                val result = evaluation.result
                if (index > 0) append(',')
                append("\n    {")
                append("\"name\":\"").append(jsonEscape(result.scenario)).append("\",")
                append("\"seed\":").append(result.seed).append(',')
                append("\"result\":\"").append(if (evaluation.violations.isEmpty()) "PASS" else "FAIL").append("\",")
                append("\"violations\":").append(stringsJson(evaluation.violations)).append(',')
                append("\"mover\":\"").append(result.moverState.name).append("\",")
                append("\"abortReason\":")
                    .append(result.abortReason?.let { reason -> "\"${reason.name}\"" } ?: "null").append(',')
                append("\"cursorComplete\":").append(result.cursorComplete).append(',')
                append("\"sourceBlocks\":").append(result.sourceBlockCount).append(',')
                append("\"plannedTargets\":").append(result.plannedTargetCount).append(',')
                append("\"matchedSourceBlocks\":").append(result.matchedSourceBlockCount).append(',')
                append("\"missingTargets\":").append(result.missingPlannedTargets.size).append(',')
                append("\"missingTargetPositions\":").append(blockPositionsJson(result.missingPlannedTargets)).append(',')
                append("\"orphanScaffolds\":").append(result.orphanScaffolds.size).append(',')
                append("\"orphanScaffoldPositions\":").append(blockPositionsJson(result.orphanScaffolds)).append(',')
                append("\"attemptFailedActions\":").append(result.attemptFailedActions).append(',')
                append("\"attemptSkippedActions\":").append(result.attemptSkippedActions).append(',')
                append("\"unresolvedFailedActions\":").append(result.unresolvedFailedActions).append(',')
                append("\"unresolvedSkippedActions\":").append(result.unresolvedSkippedActions).append(',')
                append("\"actionIssues\":").append(actionIssuesJson(result.actionIssues)).append(',')
                append("\"ticks\":").append(result.metrics.ticks).append(',')
                append("\"distance\":").append(decimal(result.metrics.totalDistance)).append(',')
                append("\"horizontalDistance\":").append(decimal(result.metrics.horizontalDistance)).append(',')
                append("\"verticalDistance\":").append(decimal(result.metrics.verticalDistance)).append(',')
                append("\"ascentDistance\":").append(decimal(result.metrics.ascentDistance)).append(',')
                append("\"descentDistance\":").append(decimal(result.metrics.descentDistance)).append(',')
                append("\"movementLegs\":").append(result.metrics.movementLegs).append(',')
                append("\"wastedMovementLegs\":").append(result.metrics.nonproductiveMovementLegs).append(',')
                append("\"wastedMovementDistance\":")
                    .append(decimal(result.metrics.wastedMovementDistance)).append(',')
                append("\"targetChanges\":").append(result.metrics.targetChanges).append(',')
                append("\"holdTicks\":").append(result.metrics.holdTicks).append(',')
                append("\"blockedMovementSteps\":").append(result.metrics.blockedMovementSteps).append(',')
                append("\"pathProbeInvocations\":").append(result.metrics.pathProbeInvocations).append(',')
                append("\"pathProbeBlocked\":").append(result.metrics.pathProbeBlocked).append(',')
                append("\"bodyCollisions\":").append(result.metrics.bodyCollisions).append(',')
                append("\"flightLosses\":").append(result.metrics.flightLosses).append(',')
                append("\"outOfBoundsBody\":").append(result.metrics.outOfBoundsBody).append(',')
                append("\"invalidPlacements\":").append(result.metrics.invalidPlacements).append(',')
                append("\"reachOutsideClicks\":").append(result.metrics.reachOutsideClicks).append(',')
                append("\"supportMissingClicks\":").append(result.metrics.supportMissingClicks).append(',')
                append("\"playerColumnClicks\":").append(result.metrics.playerColumnClicks).append(',')
                append("\"unexpectedWorldWrites\":").append(result.metrics.unexpectedWorldWrites).append(',')
                append("\"hardCaps\":").append(result.metrics.hardCaps).append(',')
                append("\"stallEscapes\":").append(result.metrics.stallEscapes).append(',')
                append("\"nonproductiveMovementLegs\":").append(result.metrics.nonproductiveMovementLegs).append(',')
                append("\"serverCorrections\":").append(result.metrics.serverCorrections).append(',')
                append("\"submissions\":").append(result.metrics.submissions).append(',')
                append("\"placementSubmissions\":").append(result.metrics.placementSubmissions).append(',')
                append("\"removalSubmissions\":").append(result.metrics.removalSubmissions).append(',')
                append("\"acceptedOutcomes\":").append(result.metrics.acceptedOutcomes).append(',')
                append("\"rejectedOutcomes\":").append(result.metrics.rejectedOutcomes).append(',')
                append("\"wrongStateOutcomes\":").append(result.metrics.wrongStateOutcomes).append(',')
                append("\"timeoutOutcomes\":").append(result.metrics.timeoutOutcomes).append(',')
                append("\"retries\":").append(result.metrics.retries).append(',')
                append("\"scaffoldsPlaced\":").append(result.metrics.scaffoldsPlaced).append(',')
                append("\"scaffoldsBroken\":").append(result.metrics.scaffoldsBroken).append(',')
                append("\"aStarInvocations\":").append(result.metrics.aStarInvocations).append(',')
                append("\"aStarSuccesses\":").append(result.metrics.aStarSuccesses).append(',')
                append("\"routeRebuilds\":").append(result.metrics.routeRebuilds).append(',')
                append("\"smoothingWaypointsIn\":").append(result.metrics.smoothingWaypointsIn).append(',')
                append("\"smoothingWaypointsOut\":").append(result.metrics.smoothingWaypointsOut).append(',')
                append("\"traceHash\":\"").append(result.normalizedTraceHash).append("\"")
                append('}')
            }
            append("\n  ]\n}\n")
        }
        Files.writeString(reportDir.resolve("summary.json"), summary, StandardCharsets.UTF_8)
    }

    private fun jsonEscape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private fun blockPositionsJson(positions: Collection<BlockPos>): String {
        return positions
            .sortedWith(compareBy<BlockPos>({ pos -> pos.y }, { pos -> pos.x }, { pos -> pos.z }))
            .joinToString(prefix = "[", postfix = "]") { pos -> "[${pos.x},${pos.y},${pos.z}]" }
    }

    private fun actionIssuesJson(issues: List<AutomodeActionIssue>): String {
        return issues.joinToString(prefix = "[", postfix = "]") { issue ->
            "{\"pass\":${issue.pass},\"actionId\":${issue.actionId},\"unit\":${issue.unitIndex}," +
                "\"kind\":\"${jsonEscape(issue.kind)}\",\"pos\":[${issue.pos.x},${issue.pos.y},${issue.pos.z}]," +
                "\"outcome\":\"${jsonEscape(issue.outcome)}\"}"
        }
    }

    private fun stringsJson(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { value ->
            "\"${jsonEscape(value)}\""
        }
    }

    private data class ScenarioEvaluation(
        val result: AutomodeSimulationResult,
        val violations: List<String>,
    )

    private data class RegressionCeiling(
        val ticks: Long,
        val distance: Double,
        val verticalDistance: Double,
        val aStarInvocations: Int,
        val routeRebuilds: Int,
    )

    internal companion object {
        private const val FANTASY_RESOURCE: String = "/test_schematic/Fantasy_BigHouse1.schematic"
        private const val OBSERVED_FANTASY_SETTINGS_JSON: String =
            "{\"placementIntervalTicks\":3,\"placeWaterloggedDry\":true}"
        private const val MAX_WALL_MILLIS: Long = 300_000L
        private val REGRESSION_CEILINGS: Map<String, RegressionCeiling> = mapOf(
            "fantasy-big-house" to RegressionCeiling(30_959L, 7_058.436858, 450.582, 4_790, 880),
            "seed-11" to RegressionCeiling(258L, 57.622, 6.237, 86, 13),
            "seed-29" to RegressionCeiling(291L, 65.709, 3.762, 83, 13),
            "seed-47" to RegressionCeiling(220L, 46.216, 5.247, 71, 13),
            "latency-0" to RegressionCeiling(156L, 45.371, 3.762, 63, 13),
            "latency-2" to RegressionCeiling(178L, 49.001, 3.762, 73, 11),
            "latency-10" to RegressionCeiling(264L, 54.611, 7.557, 62, 17),
            "trace-repeat" to RegressionCeiling(274L, 60.169, 5.082, 77, 14),
        )
        private val FIXED_SEEDS: List<Long> = listOf(11L, 29L, 47L)
        private val LATENCY_MATRIX: List<Int> = listOf(0, 2, 10)

        @JvmStatic
        @BeforeAll
        internal fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
