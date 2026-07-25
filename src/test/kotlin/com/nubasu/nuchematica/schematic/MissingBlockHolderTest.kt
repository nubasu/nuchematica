package com.nubasu.nuchematica.schematic

import com.nubasu.nuchematica.common.SchematicCache
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.printer.PrintWorldModel
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

public class MissingBlockHolderTest {
    private lateinit var previousRenderingBlocks: SchematicCache
    private lateinit var previousSchematicSize: Vector3
    private lateinit var previousDirection: Direction
    private lateinit var previousPosition: Vec3

    @BeforeEach
    public fun setUp(): Unit {
        previousRenderingBlocks = SchematicHolder.renderingBlocks
        previousSchematicSize = SchematicHolder.schematicSize
        previousDirection = SchematicRenderManager.initialDirection
        previousPosition = SchematicRenderManager.initialPosition
        MissingBlockHolder.airPos.clear()
        MissingBlockHolder.blockPos.clear()
        SchematicHolder.schematicSize = Vector3.ZERO
        SchematicRenderManager.initialDirection = Direction.EAST
        SchematicRenderManager.initialPosition = Vec3.ZERO
        SchematicRenderManager.setOffset(Vec3.ZERO)
        SchematicRenderManager.setRotation(0f, Vec3.ZERO)
    }

    @AfterEach
    public fun tearDown(): Unit {
        MissingBlockHolder.airPos.clear()
        MissingBlockHolder.blockPos.clear()
        SchematicHolder.renderingBlocks = previousRenderingBlocks
        SchematicHolder.schematicSize = previousSchematicSize
        SchematicRenderManager.initialDirection = previousDirection
        SchematicRenderManager.initialPosition = previousPosition
        SchematicRenderManager.setOffset(Vec3.ZERO)
        SchematicRenderManager.setRotation(0f, Vec3.ZERO)
    }

    @Test
    public fun placedAndRemovedReportEveryMissingSatisfiedAndWrongTransition(): Unit {
        val localPos = BlockPos(2, 3, 4)
        val outsidePos = BlockPos(99, 3, 4)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        MissingBlockHolder.airPos += localPos

        val missingToSatisfied = MissingBlockHolder.placed(localPos, expected)
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = true, satisfied = true),
            missingToSatisfied,
        )
        assertTrue(MissingBlockHolder.airPos.isEmpty())
        assertTrue(MissingBlockHolder.blockPos.isEmpty())

        val satisfiedToMissing = MissingBlockHolder.removed(localPos)
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = true, satisfied = false),
            satisfiedToMissing,
        )
        assertEquals(listOf(localPos), MissingBlockHolder.airPos)

        val wrongPlacement = MissingBlockHolder.placed(localPos, Blocks.DIRT.defaultBlockState())
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = false, satisfied = false),
            wrongPlacement,
        )
        assertTrue(MissingBlockHolder.airPos.isEmpty())
        assertEquals(listOf(localPos), MissingBlockHolder.blockPos)

        val wrongBreak = MissingBlockHolder.removed(localPos)
        assertEquals(
            MissingBlockChange(localPos, overlayChanged = true, satisfiedChanged = false, satisfied = false),
            wrongBreak,
        )
        assertEquals(listOf(localPos), MissingBlockHolder.airPos)
        assertTrue(MissingBlockHolder.blockPos.isEmpty())

        assertNull(MissingBlockHolder.placed(outsidePos, expected))
        assertNull(MissingBlockHolder.removed(outsidePos))
        assertEquals(listOf(localPos), MissingBlockHolder.airPos)
        assertTrue(MissingBlockHolder.blockPos.isEmpty())
    }

    @Test
    public fun equivalentPlacedLeavesAreSatisfied(): Unit {
        val localPos = BlockPos.ZERO
        val naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, false)
            .setValue(BlockStateProperties.DISTANCE, 7)
        val placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
            .setValue(BlockStateProperties.DISTANCE, 1)
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to naturalLeaves), emptyMap())
        MissingBlockHolder.airPos += localPos

        val change = MissingBlockHolder.placed(localPos, placedLeaves)

        assertTrue(change?.satisfied == true)
        assertTrue(MissingBlockHolder.airPos.isEmpty())
        assertTrue(MissingBlockHolder.blockPos.isEmpty())
    }

    @Test
    public fun repeatedWrongPlacementIsANoopAndSatisfiedSnapshotIsIndependent(): Unit {
        val satisfiedPos = BlockPos.ZERO
        val wrongPos = BlockPos(1, 0, 0)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(
            mapOf(satisfiedPos to expected, wrongPos to expected),
            emptyMap(),
        )
        MissingBlockHolder.blockPos += wrongPos

        val snapshot = MissingBlockHolder.satisfiedPositions()
        val unchanged = MissingBlockHolder.placed(wrongPos, Blocks.DIRT.defaultBlockState())

        assertEquals(setOf(satisfiedPos), snapshot)
        assertEquals(
            MissingBlockChange(wrongPos, overlayChanged = false, satisfiedChanged = false, satisfied = false),
            unchanged,
        )
        assertFalse(wrongPos in snapshot)

        MissingBlockHolder.removed(satisfiedPos)
        assertEquals(setOf(satisfiedPos), snapshot)
        assertTrue(MissingBlockHolder.satisfiedPositions().isEmpty())
    }

    @Test
    public fun rotatedOffsetWorldCoordinateMatchesTheFormerManualMapping(): Unit {
        val localPos = BlockPos(2, 1, 3)
        val expectedWorldPos = BlockPos(15, 20, 36)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.schematicSize = Vector3(4, 2, 7)
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        SchematicRenderManager.initialDirection = Direction.EAST
        SchematicRenderManager.initialPosition = Vec3(10.0, 20.0, 30.0)
        SchematicRenderManager.setOffset(Vec3(2.0, -1.0, 4.0))
        SchematicRenderManager.setRotation(DirectionSetting.CLOCKWISE_90)
        MissingBlockHolder.airPos += localPos

        assertEquals(expectedWorldPos, SchematicRenderManager.localBlockToWorld(localPos))
        assertEquals(localPos, SchematicRenderManager.worldBlockToLocal(expectedWorldPos))
        assertEquals(localPos, MissingBlockHolder.placed(expectedWorldPos, expected)?.localPos)
    }

    @Test
    public fun revisionChangesOnlyWhenApplyStatusChangesMissingState(): Unit {
        val localPos = BlockPos.ZERO
        val outsidePos = BlockPos(10, 0, 0)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        val initialRevision = MissingBlockHolder.missingSnapshot().revision

        assertNull(MissingBlockHolder.removed(outsidePos))
        assertEquals(initialRevision, MissingBlockHolder.missingSnapshot().revision)

        MissingBlockHolder.removed(localPos)
        val firstMissing = MissingBlockHolder.missingSnapshot()
        assertEquals(initialRevision + 1L, firstMissing.revision)
        assertEquals(listOf(localPos), firstMissing.missingLocal)

        MissingBlockHolder.removed(localPos)
        assertEquals(firstMissing.revision, MissingBlockHolder.missingSnapshot().revision)

        MissingBlockHolder.placed(localPos, Blocks.DIRT.defaultBlockState())
        val wrongBlock = MissingBlockHolder.missingSnapshot()
        assertEquals(firstMissing.revision + 1L, wrongBlock.revision)

        MissingBlockHolder.placed(localPos, Blocks.DIRT.defaultBlockState())
        assertEquals(wrongBlock.revision, MissingBlockHolder.missingSnapshot().revision)

        MissingBlockHolder.placed(localPos, expected)
        assertEquals(wrongBlock.revision + 1L, MissingBlockHolder.missingSnapshot().revision)
        assertEquals(listOf(localPos), firstMissing.missingLocal)
        assertThrows(UnsupportedOperationException::class.java) {
            (firstMissing.missingLocal as MutableList<BlockPos>).add(outsidePos)
        }
    }

    @Test
    public fun initializeIncrementsRevisionOnlyWhenScanResultChanges(): Unit {
        val localPos = BlockPos.ZERO
        val expected = Blocks.STONE.defaultBlockState()
        var actualState = Blocks.AIR.defaultBlockState()
        val level = mockk<ClientLevel>()
        every { level.getBlockState(any()) } answers { actualState }
        val minecraft = mockk<Minecraft>(relaxed = true)
        val instanceField = Minecraft::class.java.getDeclaredField("instance")
        val levelField = Minecraft::class.java.getField("level")
        instanceField.isAccessible = true
        val previousMinecraft = instanceField.get(null)
        levelField.set(minecraft, level)
        instanceField.set(null, minecraft)
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(localPos to expected), emptyMap())
        val initialRevision = MissingBlockHolder.missingSnapshot().revision

        try {
            MissingBlockHolder.initialize()
            val firstScan = MissingBlockHolder.missingSnapshot()
            assertEquals(initialRevision + 1L, firstScan.revision)
            assertEquals(listOf(localPos), firstScan.missingLocal)

            MissingBlockHolder.initialize()
            assertEquals(firstScan.revision, MissingBlockHolder.missingSnapshot().revision)

            actualState = expected
            MissingBlockHolder.initialize()
            val satisfiedScan = MissingBlockHolder.missingSnapshot()
            assertEquals(firstScan.revision + 1L, satisfiedScan.revision)
            assertTrue(satisfiedScan.missingLocal.isEmpty())
        } finally {
            instanceField.set(null, previousMinecraft)
        }
    }

    // initialize() must consume a READY PrintWorldModel capture (array lookups) and
    // classify positions IDENTICALLY to what a direct live scan of the same world
    // states would have produced -- three positions covering all three outcomes
    // (satisfied / air-missing / wrong-block-missing), captured via a mocked
    // ClientLevel exactly like a real budgeted capture would read them.
    @Test
    public fun initializeConsumesAReadyModelAndClassifiesEquivalentlyToALiveScan(): Unit {
        val satisfiedLocal = BlockPos(0, 0, 0)
        val airMissingLocal = BlockPos(1, 0, 0)
        val blockMissingLocal = BlockPos(2, 0, 0)
        val expectedSatisfied = Blocks.STONE.defaultBlockState()
        val expectedAirMissing = Blocks.DIRT.defaultBlockState()
        val expectedBlockMissing = Blocks.OAK_PLANKS.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(
            mapOf(
                satisfiedLocal to expectedSatisfied,
                airMissingLocal to expectedAirMissing,
                blockMissingLocal to expectedBlockMissing,
            ),
            emptyMap(),
        )

        // The default @BeforeEach transform (initialPosition=ZERO, offset=ZERO,
        // rotation=0, direction=EAST) makes SchematicRenderManager.localBlockToWorld an
        // identity mapping (matches every other test in this file that compares
        // localPos directly against MissingBlockHolder.airPos/blockPos), so the world
        // states below are keyed by the same BlockPos as the local positions.
        val worldStates = mapOf(
            satisfiedLocal to expectedSatisfied,
            airMissingLocal to Blocks.AIR.defaultBlockState(),
            blockMissingLocal to Blocks.COBBLESTONE.defaultBlockState(),
        )
        val level = mockk<ClientLevel>()
        // Stubbed defensively: an unstubbed mockk<ClientLevel>() falls through to an
        // expensive kotlin-reflect member walk on every invocation MockK's debug logger
        // describes (via the mock's own intercepted toString()) -- see
        // PrintWorldModelTest.mockClientLevel's doc for the full root-cause trace.
        every { level.toString() } returns "ClientLevel(mock)"
        every { level.getBlockState(any()) } answers {
            worldStates[firstArg<BlockPos>()] ?: Blocks.AIR.defaultBlockState()
        }

        var status = PrintWorldModel.ensureCapture(
            level = level,
            contentIdentity = Any(),
            transformRevision = 0L,
            localPositions = worldStates.keys,
            localToWorld = { it },
        )
        while (status == PrintWorldModel.Status.CAPTURING) {
            status = PrintWorldModel.pump(level)
        }
        assertEquals(PrintWorldModel.Status.READY, status)

        try {
            MissingBlockHolder.initialize()

            assertTrue(satisfiedLocal !in MissingBlockHolder.airPos && satisfiedLocal !in MissingBlockHolder.blockPos)
            assertEquals(listOf(airMissingLocal), MissingBlockHolder.airPos)
            assertEquals(listOf(blockMissingLocal), MissingBlockHolder.blockPos)
        } finally {
            // Every test that stands up a capture must leave PrintWorldModel back at
            // IDLE -- it is a real singleton shared with every other test class in this
            // JVM (see PrintWorldModelTest's own class doc for the same discipline).
            PrintWorldModel.cancel()
        }
    }

    // hasMissing()/missingCount() are O(1) accessors for the toggle-time/HUD checks
    // that only ever needed a presence/count test, not the full missing list a
    // missingSnapshot() call used to have to copy to answer them.
    @Test
    public fun hasMissingAndMissingCountReflectTheCombinedAirAndBlockPositions(): Unit {
        assertFalse(MissingBlockHolder.hasMissing())
        assertEquals(0, MissingBlockHolder.missingCount())

        MissingBlockHolder.airPos += BlockPos(0, 0, 0)
        assertTrue(MissingBlockHolder.hasMissing())
        assertEquals(1, MissingBlockHolder.missingCount())

        MissingBlockHolder.blockPos += BlockPos(1, 0, 0)
        assertTrue(MissingBlockHolder.hasMissing())
        assertEquals(2, MissingBlockHolder.missingCount())

        MissingBlockHolder.airPos.clear()
        assertTrue(MissingBlockHolder.hasMissing())
        assertEquals(1, MissingBlockHolder.missingCount())

        MissingBlockHolder.blockPos.clear()
        assertFalse(MissingBlockHolder.hasMissing())
        assertEquals(0, MissingBlockHolder.missingCount())
    }

    // Op-counter/budget structural test: applyStatus (placed()/removed()'s shared path)
    // does `localPos in airPos`/`blockPos` plus `.add()`/`.remove()` on EVERY call -- on
    // a plain ArrayList that is an O(n) linear scan each time, so N sequential
    // placements against an N-entry missing set cost O(n^2) overall. At Fantasy's ~3k
    // scale that is unnoticeable; at 0_all's ~3.5M scale it makes the whole print
    // catastrophically slow. Structural proof (not a fragile microsecond timing check):
    // airPos/blockPos must reject duplicate membership the way Set semantics do (a
    // plain ArrayList would happily accept a second copy, since applyStatus's own
    // `if (!wasAirMissing)` guard is the only thing that prevents that -- a caller
    // mutating the list directly, as this test does, bypasses it entirely). Combined
    // with a generously-bounded wall-clock budget over a realistic large-N pass (O(1)
    // finishes in well under a second; an O(n^2) ArrayList shape would take
    // single-digit-plus seconds at this N on any reasonable machine -- see
    // PrintWorldModel.CAPTURE_CELLS_PER_TICK for why this N matches the codebase's own
    // "realistic large schematic" scale).
    @Test
    public fun airPosAndBlockPosStayDuplicateFreeAndPlacedRemovedStayFastAtCaptureScale(): Unit {
        MissingBlockHolder.airPos += BlockPos(0, 0, 0)
        MissingBlockHolder.airPos += BlockPos(0, 0, 0)
        assertEquals(1, MissingBlockHolder.airPos.size) {
            "airPos must be duplicate-free (Set semantics), not silently accept a second copy"
        }

        val total = 20_000
        val expected = Blocks.STONE.defaultBlockState()
        val positions = (0 until total).map { i -> BlockPos(i, 0, 0) }
        SchematicHolder.renderingBlocks = SchematicCache(positions.associateWith { expected }, emptyMap())

        val elapsedNanos = kotlin.system.measureNanoTime {
            for (pos in positions) MissingBlockHolder.placed(pos, Blocks.AIR.defaultBlockState())
            assertEquals(total, MissingBlockHolder.airPos.size)
            for (pos in positions) MissingBlockHolder.placed(pos, expected)
            assertTrue(MissingBlockHolder.airPos.isEmpty())
        }
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        assertTrue(elapsedSeconds < BUDGET_SCALE_TIME_LIMIT_SECONDS) {
            "placed() across $total positions took ${elapsedSeconds}s -- a plain " +
                "ArrayList's O(n) contains/remove per call (O(n^2) overall) would take " +
                "far longer than the ${BUDGET_SCALE_TIME_LIMIT_SECONDS}s budget at this scale"
        }
    }

    // initialize()'s classification of the content map must itself be budgeted: an
    // unbudgeted one-shot pass over 0_all's 3.5M-entry content would itself be a
    // freeze (separate from, and in addition to, the PrintWorldModel capture budget).
    // A content map bigger than PrintWorldModel.CAPTURE_CELLS_PER_TICK must not finish classifying
    // inside a single initialize()/pump() call, per-call work must stay bounded by that
    // same budget regardless of total content size, isInitializing() must report the
    // in-progress state truthfully throughout, and blockPos/airPos must show nothing (not
    // a half-built result) until the pass actually completes -- then the final result and
    // the single revision bump must match what one unbudgeted pass would have produced.
    //
    // Volume note: total stays just over ONE budget tick (not e.g. two full ticks), same
    // discipline as PrintWorldModelTest's own capture-budgeting tests and for the same
    // documented reason (see PrintWorldModelTest.mockClientLevel's root-cause note) --
    // every mocked ClientLevel.getBlockState call pays kotlin-reflect's per-invocation
    // member-resolution cost uncached, so a larger "cleaner" volume here turns a
    // millisecond budgeting check into a test-suite-wide OOM risk for no added coverage.
    @Test
    public fun initializeIsBudgetedAcrossPumpCallsAndConvergesToTheSameResultAsOneShot(): Unit {
        val budget = PrintWorldModel.CAPTURE_CELLS_PER_TICK
        val total = budget + 1
        val positions = (0 until total).map { i -> BlockPos(i, 0, 0) }
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(positions.associateWith { expected }, emptyMap())
        val level = mockk<ClientLevel>()
        every { level.getBlockState(any()) } returns Blocks.AIR.defaultBlockState()
        val minecraft = mockk<Minecraft>(relaxed = true)
        val instanceField = Minecraft::class.java.getDeclaredField("instance")
        val levelField = Minecraft::class.java.getField("level")
        instanceField.isAccessible = true
        val previousMinecraft = instanceField.get(null)
        levelField.set(minecraft, level)
        instanceField.set(null, minecraft)
        val initialRevision = MissingBlockHolder.missingSnapshot().revision

        try {
            MissingBlockHolder.initialize()
            assertTrue(MissingBlockHolder.isInitializing()) {
                "a content map larger than the per-tick budget must not finish in one call"
            }
            assertTrue(MissingBlockHolder.airPos.isEmpty()) { "in-progress work must stay invisible" }
            assertTrue(MissingBlockHolder.blockPos.isEmpty())
            assertEquals(initialRevision, MissingBlockHolder.missingSnapshot().revision)

            // One more budget round (the 1 leftover position) finishes the pass.
            assertTrue(MissingBlockHolder.pump())
            assertFalse(MissingBlockHolder.isInitializing())
            assertEquals(total, MissingBlockHolder.airPos.size)
            assertTrue(MissingBlockHolder.blockPos.isEmpty())
            assertEquals(initialRevision + 1L, MissingBlockHolder.missingSnapshot().revision)

            // A further pump() once the pass is done is a no-op, not a restart.
            assertTrue(MissingBlockHolder.pump())
            assertEquals(initialRevision + 1L, MissingBlockHolder.missingSnapshot().revision)
        } finally {
            instanceField.set(null, previousMinecraft)
        }
    }

    // changesSince is the pull API LayerGateEligibleMissingCache/MoverMissingWorldCache
    // replay instead of rescanning the whole missing set on every revision bump.
    @Test
    public fun changesSinceReturnsExactlyTheChangesAfterTheGivenRevisionInOrder(): Unit {
        val first = BlockPos(0, 0, 0)
        val second = BlockPos(1, 0, 0)
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(
            mapOf(first to expected, second to expected),
            emptyMap(),
        )
        val baseRevision = MissingBlockHolder.missingSnapshot().revision

        assertEquals(emptyList<MissingBlockChange>(), MissingBlockHolder.changesSince(baseRevision))

        val firstChange = requireNotNull(MissingBlockHolder.removed(first))
        val secondChange = requireNotNull(MissingBlockHolder.removed(second))

        assertEquals(listOf(firstChange, secondChange), MissingBlockHolder.changesSince(baseRevision))
        assertEquals(listOf(secondChange), MissingBlockHolder.changesSince(baseRevision + 1L))
        assertEquals(emptyList<MissingBlockChange>(), MissingBlockHolder.changesSince(baseRevision + 2L))
    }

    @Test
    public fun changesSinceSkipsNoOpApplyStatusCallsThatDoNotBumpRevision(): Unit {
        val pos = BlockPos.ZERO
        val expected = Blocks.STONE.defaultBlockState()
        SchematicHolder.renderingBlocks = SchematicCache(mapOf(pos to expected), emptyMap())
        val baseRevision = MissingBlockHolder.missingSnapshot().revision

        // Repeating the exact same wrong placement twice: the first call changes the
        // overlay (revision bumps once), the second is a no-op (matches the
        // sticky-demotion reasoning already covered elsewhere in this suite).
        MissingBlockHolder.placed(pos, Blocks.DIRT.defaultBlockState())
        val unchanged = MissingBlockHolder.placed(pos, Blocks.DIRT.defaultBlockState())
        assertFalse(unchanged?.overlayChanged == true)

        assertEquals(1, MissingBlockHolder.changesSince(baseRevision)?.size)
    }

    // A gap wider than the retained log (or one that predates the last initialize()) must
    // signal "no", not silently return a partial/incorrect list -- callers fall back to a
    // full rebuild from missingSnapshot() in that case.
    @Test
    public fun changesSinceReturnsNullWhenTheRequestedRevisionIsNoLongerRetained(): Unit {
        val expected = Blocks.STONE.defaultBlockState()
        val positions = (0 until 10).map { i -> BlockPos(i, 0, 0) }
        SchematicHolder.renderingBlocks = SchematicCache(
            positions.associateWith { expected },
            emptyMap(),
        )
        val baseRevision = MissingBlockHolder.missingSnapshot().revision
        for (pos in positions) {
            MissingBlockHolder.removed(pos)
        }

        // Every one of the 10 removals above is still within the log's retained window,
        // so the exact revision right before them all resolves correctly.
        assertEquals(10, MissingBlockHolder.changesSince(baseRevision)?.size)

        // A revision from before this test's own genesis can never be retained.
        assertNull(MissingBlockHolder.changesSince(baseRevision - 1_000_000L))
    }

    public companion object {
        // Generous on purpose -- only meant to separate O(1) (well under a
        // second at this N) from an O(n^2) ArrayList shape (which would take far
        // longer), not to pin an exact performance target.
        private const val BUDGET_SCALE_TIME_LIMIT_SECONDS: Double = 5.0

        @BeforeAll
        @JvmStatic
        public fun bootstrapMinecraft(): Unit {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
