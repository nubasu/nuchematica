package com.nubasu.nuchematica.e2e

import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.Nuchematica
import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.RenderSettingHolder
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.mover.MoverState
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.printer.PlacementBehaviorSettings
import com.nubasu.nuchematica.printer.PlanAction
import com.nubasu.nuchematica.printer.PrintPlanParams
import com.nubasu.nuchematica.printer.PrintPlanner
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.printer.PrinterActivationEvent
import com.nubasu.nuchematica.printer.PrinterSettings
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import com.nubasu.nuchematica.printer.SchematicPrinter
import com.nubasu.nuchematica.printer.executablePlanUnits
import com.nubasu.nuchematica.printer.schematicWorldBoundingBox
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.BlockStateEquivalence
import com.nubasu.nuchematica.schematic.MissingBlockHolder
import com.nubasu.nuchematica.schematic.SchematicHolder
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.schematic.reader.WorldEditSchematicReader
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.world.Difficulty
import net.minecraft.world.level.DataPackConfig
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.io.File
import java.util.LinkedHashMap
import java.util.OptionalLong
import kotlin.math.max

@Mod.EventBusSubscriber(
    modid = Nuchematica.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = [Dist.CLIENT],
)
public object AutomodeForgeE2e {
    private enum class Phase {
        BOOT,
        WAIT_WORLD,
        WAIT_COMMANDS,
        WAIT_MODEL,
        RUNNING,
        TERMINAL,
    }

    private val logger = LogUtils.getLogger()
    private val enabled: Boolean = System.getProperty("nuchematica.e2e.enabled") == "true"
    private val scenarioName: String = System.getProperty("nuchematica.e2e.scenario", "smoke")
    private val fixtureFile: File? = System.getProperty("nuchematica.e2e.fixture")?.let(::File)
    private val worldName: String = System.getProperty("nuchematica.e2e.world", "nuchematica-e2e")
    private val resumeExistingWorld: Boolean = System.getProperty("nuchematica.e2e.resume") == "true"
    private val resultFile: File? = System.getProperty("nuchematica.e2e.result")?.let(::File)
    private var phase: Phase = Phase.BOOT
    private var clientTicks: Int = 0
    private var phaseStartedTick: Int = 0
    private var runStartedTick: Int = 0
    private var content: SchematicCache = SchematicCache(emptyMap(), emptyMap())
    private var sourceWorld: Map<BlockPos, BlockState> = emptyMap()
    private var expectedWorld: Map<BlockPos, BlockState> = emptyMap()
    private var sourceBounds: Pair<BlockPos, BlockPos>? = null
    private var platformProbe: BlockPos = BlockPos(0, 120, 0)
    private var playerStart: Vec3 = Vec3(0.5, 121.0, 0.5)
    private var bodyCollisionTicks: Int = 0
    private var currentCruiseStationaryTicks: Int = 0
    private var maxCruiseStationaryTicks: Int = 0
    private var currentHoldTicks: Int = 0
    private var maxHoldTicks: Int = 0
    private var currentFenceHoldTicks: Int = 0
    private var maxFenceHoldTicks: Int = 0
    private var previousPlayerPosition: Vec3? = null

    private var lastProgressMatched: Int = 0
    private var lastProgressTick: Int = 0

    @JvmStatic
    @SubscribeEvent
    public fun onClientTick(event: TickEvent.ClientTickEvent): Unit {
        if (!enabled || event.phase != TickEvent.Phase.END || phase == Phase.TERMINAL) return
        clientTicks++
        try {
            when (phase) {
                Phase.BOOT -> tickBoot()
                Phase.WAIT_WORLD -> tickWaitWorld()
                Phase.WAIT_COMMANDS -> tickWaitCommands()
                Phase.WAIT_MODEL -> tickWaitModel()
                Phase.RUNNING -> tickRunning()
                Phase.TERMINAL -> Unit
            }
        } catch (failure: Throwable) {
            logger.error("NUCHEMATICA_FORGE_E2E uncaught failure", failure)
            finish("FAIL", "${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    private fun tickBoot(): Unit {
        if (clientTicks < BOOT_DELAY_TICKS) return
        val minecraft = Minecraft.getInstance()
        if (minecraft.level != null) {
            finish("FAIL", "unexpected world was already loaded")
            return
        }
        minecraft.options.pauseOnLostFocus = false
        writeEvidence("RUNNING", if (resumeExistingWorld) "loading integrated world" else "creating integrated world")
        phase = Phase.WAIT_WORLD
        phaseStartedTick = clientTicks
        if (resumeExistingWorld) {
            minecraft.loadLevel(worldName)
            return
        }
        val registries = RegistryAccess.builtinCopy()
        val levelSettings = LevelSettings(
            worldName,
            GameType.CREATIVE,
            false,
            Difficulty.PEACEFUL,
            true,
            GameRules(),
            DataPackConfig.DEFAULT,
        )
        val worldSettings = WorldGenSettings.makeDefault(registries)
            .withSeed(false, OptionalLong.of(WORLD_SEED))
        minecraft.createLevel(worldName, levelSettings, registries, worldSettings)
    }

    private fun tickWaitWorld(): Unit {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val gameMode = minecraft.gameMode
        if (minecraft.level == null || player == null || gameMode == null) {
            failAfter(WORLD_READY_TIMEOUT_TICKS, "integrated world did not become ready")
            return
        }
        if (!gameMode.playerMode.isCreative) {
            finish("FAIL", "integrated player is not creative")
            return
        }
        player.chat("/gamerule doMobSpawning false")
        player.chat("/gamerule doDaylightCycle false")
        player.chat("/gamerule randomTickSpeed 0")
        content = buildFixture()
        val localBounds = requireNotNull(boundsOf(content.blocks.keys)) { "fixture has no blocks" }
        val worldMin = SCHEMATIC_BASE.offset(localBounds.first)
        val worldMax = SCHEMATIC_BASE.offset(localBounds.second)
        sourceBounds = worldMin to worldMax
        val platformY = worldMin.y - 1
        if (resumeExistingWorld) {
            phase = Phase.WAIT_COMMANDS
            phaseStartedTick = clientTicks
            return
        }
        val setupMin = BlockPos(worldMin.x - SETUP_PADDING, worldMin.y, worldMin.z - SETUP_PADDING)
        val setupMax = BlockPos(worldMax.x + SETUP_PADDING, worldMax.y + CLEAR_ABOVE, worldMax.z + SETUP_PADDING)
        for (command in fillCommands(setupMin, setupMax, "minecraft:air")) player.chat(command)
        player.chat(
            "/fill ${setupMin.x} $platformY ${setupMin.z} ${setupMax.x} $platformY ${setupMax.z} minecraft:stone",
        )
        platformProbe = BlockPos(worldMin.x, platformY, worldMin.z)
        playerStart = Vec3(worldMin.x - 5.5, worldMin.y.toDouble(), worldMin.z - 5.5)
        player.chat("/tp @s ${playerStart.x} ${playerStart.y} ${playerStart.z}")
        phase = Phase.WAIT_COMMANDS
        phaseStartedTick = clientTicks
    }

    private fun tickWaitCommands(): Unit {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            finish("FAIL", "world unloaded during setup")
            return
        }
        val platformReady = resumeExistingWorld || level.getBlockState(platformProbe).`is`(Blocks.STONE)
        val playerReady = resumeExistingWorld || player.position().distanceToSqr(playerStart) <= PLAYER_SETUP_DISTANCE_SQUARED
        if (!platformReady || !playerReady) {
            failAfter(COMMAND_TIMEOUT_TICKS, "setup commands were not acknowledged")
            return
        }

        SchematicHolder.schematicCache = content
        SchematicHolder.renderingBlocks = content
        val localBounds = requireNotNull(boundsOf(content.blocks.keys))
        SchematicHolder.schematicSize = Vector3(
            localBounds.second.x - localBounds.first.x,
            localBounds.second.y - localBounds.first.y,
            localBounds.second.z - localBounds.first.z,
        )
        RenderSettingHolder.renderSettings = RenderSettings(automode = true)
        PrinterSettingsHolder.printerSettings = scenarioSettings()
        SchematicRenderManager.initialize()
        SchematicRenderManager.updateInitialPosition(Direction.EAST, Vec3.atLowerCornerOf(SCHEMATIC_BASE))
        sourceWorld = content.blocks.mapKeys { (local, _) -> SchematicRenderManager.localBlockToWorld(local) }
        phase = Phase.WAIT_MODEL
        phaseStartedTick = clientTicks
    }

    private fun tickWaitModel(): Unit {
        val minecraft = Minecraft.getInstance()
        val modelReady = PrintWorldModel.status() == PrintWorldModel.Status.READY
        val missingReady = !MissingBlockHolder.isInitializing() && (
            resumeExistingWorld || MissingBlockHolder.missingCount() == sourceWorld.size
            )
        if (!modelReady || !missingReady || minecraft.screen != null) {
            failAfter(MODEL_TIMEOUT_TICKS, "production world model did not settle")
            return
        }
        expectedWorld = buildExpectedPlanTargets(assumeSchematicCellsAir = resumeExistingWorld)
        if (
            scenarioName == FANTASY_SCENARIO &&
            !resumeExistingWorld &&
            expectedWorld.size != FANTASY_EXPECTED_TARGETS
        ) {
            finish("FAIL", "Fantasy planner produced ${expectedWorld.size} targets, expected $FANTASY_EXPECTED_TARGETS")
            return
        }
        val activation = SchematicPrinter.toggleRequested(isCreative = true)
        if (activation != PrinterActivationEvent.ENABLED) {
            finish("FAIL", "printer activation returned $activation")
            return
        }
        SchematicMover.toggleRequested()
        val moverState = SchematicMover.latestStatus?.state
        if (moverState != MoverState.TAKEOFF) {
            finish("FAIL", "mover activation returned $moverState")
            return
        }
        phase = Phase.RUNNING
        phaseStartedTick = clientTicks
        runStartedTick = clientTicks
        lastProgressTick = clientTicks
        lastProgressMatched = matchedTargets()
        previousPlayerPosition = Minecraft.getInstance().player?.position()
        logger.info(
            "NUCHEMATICA_FORGE_E2E started scenario={} world={} resume={} source={} targets={}",
            scenarioName,
            worldName,
            resumeExistingWorld,
            sourceWorld.size,
            expectedWorld.size,
        )
    }

    private fun tickRunning(): Unit {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            finish("FAIL", "world unloaded while automode was active")
            return
        }
        val status = SchematicMover.latestStatus
        val moverState = status?.state
        val playerPosition = player.position()
        val movedSquared = previousPlayerPosition?.distanceToSqr(playerPosition) ?: Double.POSITIVE_INFINITY
        previousPlayerPosition = playerPosition

        if (!level.noCollision(player, player.boundingBox.deflate(COLLISION_EPSILON))) {
            bodyCollisionTicks++
        }
        currentCruiseStationaryTicks = if (
            moverState == MoverState.CRUISE && movedSquared < STATIONARY_DISTANCE_SQUARED
        ) {
            currentCruiseStationaryTicks + 1
        } else {
            0
        }
        maxCruiseStationaryTicks = max(maxCruiseStationaryTicks, currentCruiseStationaryTicks)
        currentHoldTicks = if (moverState == MoverState.HOLD) currentHoldTicks + 1 else 0
        maxHoldTicks = max(maxHoldTicks, currentHoldTicks)
        val belowPlayer = BlockPos(player.x, player.y - 0.05, player.z)
        val holdingOnFence = moverState == MoverState.HOLD && level.getBlockState(belowPlayer).block is net.minecraft.world.level.block.FenceBlock
        currentFenceHoldTicks = if (holdingOnFence) currentFenceHoldTicks + 1 else 0
        maxFenceHoldTicks = max(maxFenceHoldTicks, currentFenceHoldTicks)
        sampleProgress()

        when {
            moverState == MoverState.ABORTED -> finish("FAIL", "mover aborted: ${status.abortReason}")
            bodyCollisionTicks > 0 -> finish("FAIL", "player body intersected a block")
            currentCruiseStationaryTicks > MAX_CRUISE_STATIONARY_TICKS ->
                finish("FAIL", "mover stayed stationary in CRUISE")
            currentHoldTicks > MAX_HOLD_TICKS ->
                finish("FAIL", "mover stayed in HOLD too long: ${incompleteDiagnostics()}")
            currentFenceHoldTicks > MAX_FENCE_HOLD_TICKS -> finish("FAIL", "mover stayed on a fence too long")
            clientTicks - lastProgressTick > noProgressTimeoutTicks() ->
                finish(
                    "FAIL",
                    "no planned-target progress for ${clientTicks - lastProgressTick} ticks: " +
                        incompleteDiagnostics(),
                )
            clientTicks - runStartedTick > runTimeoutTicks() -> finish("FAIL", "automode timed out")
            moverState == MoverState.COMPLETE -> verifyCompletedWorld()
        }
    }

    private fun verifyCompletedWorld(): Unit {
        val level = Minecraft.getInstance().level
        if (level == null) {
            finish("FAIL", "world missing during final verification")
            return
        }
        val missing = expectedWorld.entries.filterNot { (pos, expected) ->
            BlockStateEquivalence.matches(expected, level.getBlockState(pos))
        }
        val orphanScaffolds = mutableListOf<BlockPos>()
        val bounds = requireNotNull(sourceBounds)
        for (x in bounds.first.x - 4..bounds.second.x + 4) {
            for (y in bounds.first.y - 2..bounds.second.y + 4) {
                for (z in bounds.first.z - 4..bounds.second.z + 4) {
                    val pos = BlockPos(x, y, z)
                    if (level.getBlockState(pos).`is`(Blocks.SLIME_BLOCK)) orphanScaffolds += pos
                }
            }
        }
        if (missing.isNotEmpty()) {
            val kinds = missing
                .groupingBy { (pos, expected) ->
                    "$expected -> ${level.getBlockState(pos)}"
                }
                .eachCount()
            val diagnostics = missing.take(8).joinToString { (pos, expected) ->
                "$pos expected=$expected actual=${level.getBlockState(pos)}"
            }
            finish("FAIL", "missing targets (${missing.size}; kinds=$kinds): $diagnostics")
            return
        }
        if (orphanScaffolds.isNotEmpty()) {
            finish("FAIL", "orphan scaffolds: ${orphanScaffolds.take(8)}")
            return
        }
        finish("PASS", "all production client/network assertions passed")
    }

    private fun buildFixture(): SchematicCache {
        if (scenarioName == FANTASY_SCENARIO) return buildFantasyFixture()
        require(scenarioName == SMOKE_SCENARIO) { "unsupported E2E scenario: $scenarioName" }
        val blocks = LinkedHashMap<BlockPos, BlockState>()
        for (x in 0..2) {
            for (z in 0..2) {
                if (x != 1 || z != 1) blocks[BlockPos(x, 0, z)] = Blocks.OAK_PLANKS.defaultBlockState()
            }
        }
        for ((x, z) in listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2)) {
            blocks[BlockPos(x, 1, z)] = Blocks.OAK_FENCE.defaultBlockState()
            blocks[BlockPos(x, 2, z)] = Blocks.OAK_SLAB.defaultBlockState()
        }
        blocks[BlockPos(1, 2, 0)] = stair(Direction.SOUTH)
        blocks[BlockPos(1, 2, 2)] = stair(Direction.NORTH)
        blocks[BlockPos(0, 2, 1)] = stair(Direction.EAST)
        blocks[BlockPos(2, 2, 1)] = stair(Direction.WEST)
        blocks[BlockPos(1, 2, 1)] = Blocks.OAK_PLANKS.defaultBlockState()
        blocks[BlockPos(1, 1, 1)] = Blocks.GLOWSTONE.defaultBlockState()
        blocks[BlockPos(1, 3, 1)] = Blocks.PISTON.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.NORTH)
        blocks[BlockPos(1, 4, 1)] = Blocks.OAK_LOG.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
        return SchematicCache(blocks, emptyMap())
    }

    private fun buildFantasyFixture(): SchematicCache {
        val file = requireNotNull(fixtureFile) { "nuchematica.e2e.fixture is required for Fantasy" }
        require(file.isFile) { "Fantasy fixture is missing: ${file.absolutePath}" }
        val clipboard = WorldEditSchematicReader.read(SchematicFormatDetector.readRootTag(file))
        val blocks = LinkedHashMap<BlockPos, BlockState>()
        val blockEntities = LinkedHashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity>()
        for (index in clipboard.position.indices) {
            val state = clipboard.block[index]
            if (state.isAir) continue
            val pos = clipboard.position[index].immutable()
            blocks[pos] = state
            clipboard.tileEntity.getOrNull(index)?.let { entity -> blockEntities[pos] = entity }
        }
        return SchematicCache(blocks, blockEntities)
    }

    private fun scenarioSettings(): PrinterSettings {
        return if (scenarioName == FANTASY_SCENARIO) {
            PrinterSettings(
                attemptsPerTick = 1,
                placementIntervalTicks = 3,
                reach = 4.0,
                facePlacement = true,
                placeWaterloggedDry = true,
                substituteLookalikes = true,
                planFirstMode = true,
            )
        } else {
            PrinterSettings(
                attemptsPerTick = 8,
                placementIntervalTicks = 1,
                reach = 4.0,
                facePlacement = true,
                placeWaterloggedDry = true,
                substituteLookalikes = false,
                planFirstMode = true,
            )
        }
    }

    private fun buildExpectedPlanTargets(assumeSchematicCellsAir: Boolean = false): Map<BlockPos, BlockState> {
        val settings = PrinterSettingsHolder.printerSettings
        val behavior = PlacementBehaviorSettings(
            substituteLookalikes = settings.substituteLookalikes,
            placeWaterloggedDry = settings.placeWaterloggedDry,
        )
        val worldBounds = requireNotNull(
            schematicWorldBoundingBox(content.blocks.keys, SchematicRenderManager::localBlockToWorld, margin = 2),
        )
        val bounds: (BlockPos) -> Boolean = { pos ->
            pos.x in worldBounds.first.x..worldBounds.second.x &&
                pos.y in worldBounds.first.y..worldBounds.second.y &&
                pos.z in worldBounds.first.z..worldBounds.second.z
        }
        val worldStateAt: (BlockPos) -> BlockState = if (assumeSchematicCellsAir) {
            { pos -> if (pos in sourceWorld) Blocks.AIR.defaultBlockState() else PrintWorldModel.stateAt(pos) }
        } else {
            PrintWorldModel::stateAt
        }
        val plan = PrintPlanner.plan(
            sourceWorld.entries.map { (pos, state) -> pos to state },
            worldStateAt,
            PrintPlanParams(bounds = bounds, behavior = behavior),
        )
        val targets = LinkedHashMap<BlockPos, BlockState>()
        for (unit in executablePlanUnits(plan)) {
            for (action in unit.actions) {
                if (action is PlanAction.PlaceTarget) targets[action.pos] = action.expected
            }
        }
        logger.info(
            "NUCHEMATICA_FORGE_E2E plan scenario={} direct={} scaffold={} reserved={} excluded={} unreachable={}",
            scenarioName,
            plan.report.directCount,
            plan.report.scaffoldCount,
            plan.report.reservedCount,
            plan.report.excludedCategoryCount + plan.report.excludedOccupiedCount + plan.report.excludedFallingCount,
            plan.report.unreachableCount,
        )
        return targets
    }

    private fun boundsOf(positions: Collection<BlockPos>): Pair<BlockPos, BlockPos>? {
        if (positions.isEmpty()) return null
        return BlockPos(
            positions.minOf { pos -> pos.x },
            positions.minOf { pos -> pos.y },
            positions.minOf { pos -> pos.z },
        ) to BlockPos(
            positions.maxOf { pos -> pos.x },
            positions.maxOf { pos -> pos.y },
            positions.maxOf { pos -> pos.z },
        )
    }

    private fun fillCommands(min: BlockPos, max: BlockPos, block: String): List<String> {
        val horizontalArea = (max.x - min.x + 1L) * (max.z - min.z + 1L)
        val layersPerCommand = (MAX_FILL_BLOCKS / horizontalArea).coerceAtLeast(1L).toInt()
        return buildList {
            var fromY = min.y
            while (fromY <= max.y) {
                val toY = minOf(max.y, fromY + layersPerCommand - 1)
                add("/fill ${min.x} $fromY ${min.z} ${max.x} $toY ${max.z} $block")
                fromY = toY + 1
            }
        }
    }

    private fun sampleProgress(): Unit {
        val runTicks = clientTicks - runStartedTick
        if (runTicks % PROGRESS_SAMPLE_INTERVAL_TICKS != 0) return
        val matched = matchedTargets()
        if (matched > lastProgressMatched) {
            lastProgressMatched = matched
            lastProgressTick = clientTicks
        }
        if (runTicks % PROGRESS_LOG_INTERVAL_TICKS == 0) {
            val status = SchematicMover.latestStatus
            val printer = SchematicPrinter.latestStatus
            logger.info(
                "NUCHEMATICA_FORGE_E2E progress scenario={} ticks={} matched={}/{} mover={} printerRemaining={} phase={} pos={} target={}",
                scenarioName,
                runTicks,
                matched,
                expectedWorld.size,
                status?.state,
                printer?.remaining,
                printer?.planPhase,
                Minecraft.getInstance().player?.position(),
                status?.target,
            )
        }
    }

    private fun runTimeoutTicks(): Int = if (scenarioName == FANTASY_SCENARIO) FANTASY_RUN_TIMEOUT_TICKS else RUN_TIMEOUT_TICKS

    private fun noProgressTimeoutTicks(): Int =
        if (scenarioName == FANTASY_SCENARIO) FANTASY_NO_PROGRESS_TIMEOUT_TICKS else RUN_TIMEOUT_TICKS

    private fun stair(direction: Direction): BlockState {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, direction)
    }

    private fun failAfter(timeoutTicks: Int, reason: String): Unit {
        if (clientTicks - phaseStartedTick > timeoutTicks) finish("FAIL", reason)
    }

    private fun finish(status: String, reason: String): Unit {
        if (phase == Phase.TERMINAL) return
        phase = Phase.TERMINAL
        writeEvidence(status, reason)
        logger.info(
            "NUCHEMATICA_FORGE_E2E status={} scenario={} reason={} ticks={} matched={}/{} bodyCollisions={} " +
                "maxCruiseStationary={} maxHold={} maxFenceHold={}",
            status,
            scenarioName,
            reason,
            (clientTicks - runStartedTick).coerceAtLeast(0),
            matchedTargets(),
            expectedWorld.size,
            bodyCollisionTicks,
            maxCruiseStationaryTicks,
            maxHoldTicks,
            maxFenceHoldTicks,
        )
        Minecraft.getInstance().stop()
    }

    private fun writeEvidence(status: String, reason: String): Unit {
        val file = resultFile ?: return
        file.parentFile?.mkdirs()
        val moverStatus = SchematicMover.latestStatus
        val printerStatus = SchematicPrinter.latestStatus
        val playerPosition = Minecraft.getInstance().player?.position()
        val firstIncomplete = firstIncompleteLayer()
        val json = buildString {
            append('{')
            append("\"status\":\"").append(jsonEscape(status)).append("\",")
            append("\"reason\":\"").append(jsonEscape(reason)).append("\",")
            append("\"scenario\":\"").append(jsonEscape(scenarioName)).append("\",")
            append("\"world\":\"").append(jsonEscape(worldName)).append("\",")
            append("\"ticks\":").append((clientTicks - runStartedTick).coerceAtLeast(0)).append(',')
            append("\"sourceTargets\":").append(sourceWorld.size).append(',')
            append("\"matchedTargets\":").append(matchedTargets()).append(',')
            append("\"totalTargets\":").append(expectedWorld.size).append(',')
            append("\"mover\":\"").append(moverStatus?.state ?: "NONE").append("\",")
            append("\"abortReason\":\"").append(moverStatus?.abortReason ?: "NONE").append("\",")
            append("\"printerRemaining\":").append(printerStatus?.remaining ?: -1).append(',')
            append("\"printerPlaced\":").append(printerStatus?.placed ?: -1).append(',')
            append("\"printerPhase\":\"").append(jsonEscape(printerStatus?.planPhase ?: "NONE")).append("\",")
            append("\"player\":").append(vecJson(playerPosition)).append(',')
            append("\"movementTarget\":").append(vecJson(moverStatus?.target)).append(',')
            append("\"firstIncompleteY\":").append(firstIncomplete?.first ?: -1).append(',')
            append("\"firstIncompleteMatched\":").append(firstIncomplete?.second ?: 0).append(',')
            append("\"firstIncompleteTotal\":").append(firstIncomplete?.third ?: 0).append(',')
            append("\"noProgressTicks\":").append((clientTicks - lastProgressTick).coerceAtLeast(0)).append(',')
            append("\"bodyCollisionTicks\":").append(bodyCollisionTicks).append(',')
            append("\"maxCruiseStationaryTicks\":").append(maxCruiseStationaryTicks).append(',')
            append("\"maxHoldTicks\":").append(maxHoldTicks).append(',')
            append("\"maxFenceHoldTicks\":").append(maxFenceHoldTicks)
            append('}')
        }
        file.writeText(json)
    }

    private fun matchedTargets(): Int {
        val level = Minecraft.getInstance().level ?: return 0
        return expectedWorld.count { (pos, expected) ->
            BlockStateEquivalence.matches(expected, level.getBlockState(pos))
        }
    }

    private fun firstIncompleteLayer(): Triple<Int, Int, Int>? {
        val level = Minecraft.getInstance().level ?: return null
        for ((y, entries) in expectedWorld.entries.groupBy { (pos, _) -> pos.y }.toSortedMap()) {
            val matched = entries.count { (pos, expected) ->
                BlockStateEquivalence.matches(expected, level.getBlockState(pos))
            }
            if (matched < entries.size) return Triple(y, matched, entries.size)
        }
        return null
    }

    private fun incompleteDiagnostics(): String {
        val level = Minecraft.getInstance().level ?: return "world unavailable"
        val missing = expectedWorld.entries.filterNot { (pos, expected) ->
            BlockStateEquivalence.matches(expected, level.getBlockState(pos))
        }
        if (missing.isEmpty()) return "no live mismatches"
        return missing.take(4).joinToString(separator = "; ") { (pos, expected) ->
            val neighbors = Direction.values().joinToString(separator = ",") { direction ->
                "$direction=${level.getBlockState(pos.relative(direction))}"
            }
            "$pos expected=$expected actual=${level.getBlockState(pos)} neighbors=[$neighbors]"
        }
    }

    private fun vecJson(value: Vec3?): String {
        if (value == null) return "null"
        return "[${value.x},${value.y},${value.z}]"
    }

    private fun jsonEscape(value: Any): String {
        return value.toString()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
    }

    private const val BOOT_DELAY_TICKS: Int = 5
    private const val WORLD_READY_TIMEOUT_TICKS: Int = 1_200
    private const val COMMAND_TIMEOUT_TICKS: Int = 400
    private const val MODEL_TIMEOUT_TICKS: Int = 400
    private const val RUN_TIMEOUT_TICKS: Int = 6_000
    private const val FANTASY_RUN_TIMEOUT_TICKS: Int = 42_000
    private const val FANTASY_NO_PROGRESS_TIMEOUT_TICKS: Int = 2_400
    private const val MAX_CRUISE_STATIONARY_TICKS: Int = 200
    private const val MAX_HOLD_TICKS: Int = 400
    private const val MAX_FENCE_HOLD_TICKS: Int = 100
    private const val PROGRESS_SAMPLE_INTERVAL_TICKS: Int = 20
    private const val PROGRESS_LOG_INTERVAL_TICKS: Int = 200
    private const val MAX_FILL_BLOCKS: Long = 32_768L
    private const val SETUP_PADDING: Int = 6
    private const val CLEAR_ABOVE: Int = 4
    private const val SMOKE_SCENARIO: String = "smoke"
    private const val FANTASY_SCENARIO: String = "fantasy"
    private const val FANTASY_EXPECTED_TARGETS: Int = 3_072
    private const val WORLD_SEED: Long = 20_260_824L
    private const val COLLISION_EPSILON: Double = 1.0e-5
    private const val STATIONARY_DISTANCE_SQUARED: Double = 1.0e-6
    private const val PLAYER_SETUP_DISTANCE_SQUARED: Double = 4.0
    private val SCHEMATIC_BASE: BlockPos = BlockPos(5, 121, 5)
}
