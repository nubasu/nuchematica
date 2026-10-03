package com.nubasu.nuchematica.printer

import io.mockk.every
import io.mockk.mockk
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

public class PrintWorldModelTest {
    private lateinit var mockedMinecraft: Minecraft
    private var previousMinecraftInstance: Any? = null

    @BeforeEach
    public fun installMockedMinecraft(): Unit {
        mockedMinecraft = mockk(relaxed = true)
        val instanceField = Minecraft::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        previousMinecraftInstance = instanceField.get(null)
        instanceField.set(null, mockedMinecraft)
    }

    @AfterEach
    public fun tearDown(): Unit {
        PrintWorldModel.cancel()
        val instanceField = Minecraft::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, previousMinecraftInstance)
    }

    @Test
    public fun captureIsBudgetedPerTickAndReportsProgress(): Unit {
        assertEquals(50_000, PrintWorldModel.CAPTURE_CELLS_PER_TICK)
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()

        val localPositions = listOf(BlockPos(0, 0, 0), BlockPos(46, 45, 15))
        val started = PrintWorldModel.ensureCapture(
            level = level,
            contentIdentity = Any(),
            transformRevision = 0L,
            localPositions = localPositions,
            localToWorld = { it },
        )
        assertEquals(PrintWorldModel.Status.CAPTURING, started)

        val progress = mutableListOf<Int>()
        val firstResult = PrintWorldModel.pump(level) { percent -> progress.add(percent) }
        assertEquals(PrintWorldModel.Status.CAPTURING, firstResult) {
            "50,000 of 51,000 cells must not complete the capture"
        }
        assertEquals(listOf(80), progress) { "50000/51000 = 98% must report the 80% bucket" }

        val finalResult = PrintWorldModel.pump(level) { percent -> progress.add(percent) }
        assertEquals(PrintWorldModel.Status.READY, finalResult)
        assertEquals(listOf(80), progress) { "100% must never be reported: $progress" }
    }

    @Test
    public fun cancelMidCaptureResetsToIdleAndDiscardsProgress(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        val localPositions = listOf(BlockPos(0, 0, 0), BlockPos(46, 45, 15))
        PrintWorldModel.ensureCapture(level, Any(), 0L, localPositions) { it }
        PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.CAPTURING, PrintWorldModel.status())

        PrintWorldModel.cancel()
        assertEquals(PrintWorldModel.Status.IDLE, PrintWorldModel.status())

        val liveMarker = Blocks.GOLD_BLOCK.defaultBlockState()
        val liveLevel = mockClientLevel()
        every { liveLevel.getBlockState(any()) } returns liveMarker
        withMockedLevel(liveLevel) {
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(1, 1, 1)))
        }
    }

    @Test
    public fun stateAtIsExactOnEveryAabbCornerAndFallsBackLiveOneStepOutsideEachFace(): Unit {
        val contentCorners = listOf(BlockPos(5, 5, 5), BlockPos(7, 7, 7))
        val captured = Blocks.STONE.defaultBlockState()
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns captured

        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) {
            status = PrintWorldModel.pump(level) { }
        }
        assertEquals(PrintWorldModel.Status.READY, status)

        val liveMarker = Blocks.GOLD_BLOCK.defaultBlockState()
        val liveLevel = mockClientLevel()
        every { liveLevel.getBlockState(any()) } returns liveMarker

        withMockedLevel(liveLevel) {
            for (x in intArrayOf(3, 9)) {
                for (y in intArrayOf(3, 9)) {
                    for (z in intArrayOf(3, 9)) {
                        assertEquals(
                            captured,
                            PrintWorldModel.stateAt(BlockPos(x, y, z)),
                            "corner ($x,$y,$z) must read the captured value",
                        )
                    }
                }
            }
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(2, 5, 5)), "one step outside -X face")
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(10, 5, 5)), "one step outside +X face")
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(5, 2, 5)), "one step outside -Y face")
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(5, 10, 5)), "one step outside +Y face")
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(5, 5, 2)), "one step outside -Z face")
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(5, 5, 10)), "one step outside +Z face")
        }
    }

    @Test
    public fun recordWriteOnlyMutatesInsideAReadyRegionAndIsReflectedByStateAt(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        val contentCorners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))
        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.READY, status)

        val inside = BlockPos(1, 1, 1)
        val newState = Blocks.OAK_PLANKS.defaultBlockState()
        PrintWorldModel.recordWrite(inside, newState)
        assertEquals(newState, PrintWorldModel.stateAt(inside))

        PrintWorldModel.recordWrite(BlockPos(1000, 1000, 1000), newState)
        assertEquals(newState, PrintWorldModel.stateAt(inside))
    }

    @Test
    public fun recordWriteBeforeReadyIsANoOp(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        val bigEnoughToStayCapturing = listOf(BlockPos(0, 0, 0), BlockPos(45, 45, 95))
        PrintWorldModel.ensureCapture(level, Any(), 0L, bigEnoughToStayCapturing) { it }
        assertEquals(PrintWorldModel.Status.CAPTURING, PrintWorldModel.status())

        PrintWorldModel.recordWrite(BlockPos(1, 1, 1), Blocks.OAK_PLANKS.defaultBlockState())
        assertEquals(PrintWorldModel.Status.CAPTURING, PrintWorldModel.status())
    }

    @Test
    public fun writeRevisionBumpsOnInRegionRecordWriteIncludingSameValueStores(): Unit {
        val level = mockClientLevel()
        val stone = Blocks.STONE.defaultBlockState()
        every { level.getBlockState(any()) } returns stone
        val contentCorners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))
        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.READY, status)

        val inside = BlockPos(1, 1, 1)
        val baseline = PrintWorldModel.writeRevision()

        PrintWorldModel.recordWrite(inside, stone)
        assertEquals(baseline + 1, PrintWorldModel.writeRevision())

        PrintWorldModel.recordWrite(inside, Blocks.OAK_PLANKS.defaultBlockState())
        assertEquals(baseline + 2, PrintWorldModel.writeRevision())
    }

    @Test
    public fun writeRevisionBumpsOnCancelAndResetButNotOnNoOpRecordWrite(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()

        val bigEnoughToStayCapturing = listOf(BlockPos(0, 0, 0), BlockPos(45, 45, 95))
        PrintWorldModel.ensureCapture(level, Any(), 0L, bigEnoughToStayCapturing) { it }
        val captureBaseline = PrintWorldModel.writeRevision()
        PrintWorldModel.recordWrite(BlockPos(1, 1, 1), Blocks.OAK_PLANKS.defaultBlockState())
        assertEquals(captureBaseline, PrintWorldModel.writeRevision())

        PrintWorldModel.cancel()
        assertEquals(captureBaseline + 1, PrintWorldModel.writeRevision())

        val contentCorners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))
        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.READY, status)
        val readyBaseline = PrintWorldModel.writeRevision()
        PrintWorldModel.recordWrite(BlockPos(1000, 1000, 1000), Blocks.OAK_PLANKS.defaultBlockState())
        assertEquals(readyBaseline, PrintWorldModel.writeRevision())
    }

    @Test
    public fun pumpPlanSnapshotSplitsAcrossMultipleCallsWhenRegionExceedsBudget(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        val contentCorners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))
        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.READY, status)

        assertTrue(PrintWorldModel.beginPlanSnapshot())
        val first = PrintWorldModel.pumpPlanSnapshot(100)
        assertEquals(PrintWorldModel.PlanSnapshotPump.InProgress, first) {
            "343 cells at 100/call must not complete on the first call"
        }

        var pump: PrintWorldModel.PlanSnapshotPump = first
        var calls = 1
        while (pump is PrintWorldModel.PlanSnapshotPump.InProgress) {
            pump = PrintWorldModel.pumpPlanSnapshot(100)
            calls++
        }
        assertTrue(pump is PrintWorldModel.PlanSnapshotPump.Complete)
        assertTrue(calls > 1, "343 cells at 100/call must take more than one pumpPlanSnapshot call")
    }

    @Test
    public fun pumpPlanSnapshotRestartsWhenARecordWriteLandsMidCopy(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        val contentCorners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))
        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.READY, status)

        assertTrue(PrintWorldModel.beginPlanSnapshot())
        val partial = PrintWorldModel.pumpPlanSnapshot(50)
        assertEquals(PrintWorldModel.PlanSnapshotPump.InProgress, partial)

        val written = BlockPos(-2, -2, -2)
        val newState = Blocks.OAK_PLANKS.defaultBlockState()
        PrintWorldModel.recordWrite(written, newState)
        val expectedRevision = PrintWorldModel.writeRevision()

        var pump: PrintWorldModel.PlanSnapshotPump = PrintWorldModel.pumpPlanSnapshot(50)
        while (pump is PrintWorldModel.PlanSnapshotPump.InProgress) {
            pump = PrintWorldModel.pumpPlanSnapshot(50)
        }
        val complete = pump as PrintWorldModel.PlanSnapshotPump.Complete
        assertEquals(expectedRevision, complete.writeRevision)
        assertEquals(newState, complete.snapshot.stateAt(written))
    }

    @Test
    public fun planWorldSnapshotThrowsOutsideRegionAndStaysIsolatedFromLaterLiveWrites(): Unit {
        val level = mockClientLevel()
        val stone = Blocks.STONE.defaultBlockState()
        every { level.getBlockState(any()) } returns stone
        val contentCorners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))
        PrintWorldModel.ensureCapture(level, Any(), 0L, contentCorners) { it }
        var status = PrintWorldModel.status()
        while (status == PrintWorldModel.Status.CAPTURING) status = PrintWorldModel.pump(level) { }
        assertEquals(PrintWorldModel.Status.READY, status)

        assertTrue(PrintWorldModel.beginPlanSnapshot())
        var pump: PrintWorldModel.PlanSnapshotPump = PrintWorldModel.pumpPlanSnapshot(1000)
        while (pump is PrintWorldModel.PlanSnapshotPump.InProgress) {
            pump = PrintWorldModel.pumpPlanSnapshot(1000)
        }
        val snapshot = (pump as PrintWorldModel.PlanSnapshotPump.Complete).snapshot

        val inside = BlockPos(1, 1, 1)
        assertEquals(stone, snapshot.stateAt(inside))

        PrintWorldModel.recordWrite(inside, Blocks.OAK_PLANKS.defaultBlockState())
        assertEquals(stone, snapshot.stateAt(inside))

        assertThrows(IllegalStateException::class.java) { snapshot.stateAt(BlockPos(1000, 1000, 1000)) }
    }

    @Test
    public fun ensureCaptureReusesTheSameIdentityAndRestartsOnlyWhenItChanges(): Unit {
        var reads = 0
        val level = mockClientLevel()
        every { level.getBlockState(any()) } answers {
            reads++
            Blocks.STONE.defaultBlockState()
        }
        val content = Any()
        val corners = listOf(BlockPos(0, 0, 0), BlockPos(2, 2, 2))

        val started = PrintWorldModel.ensureCapture(level, content, 0L, corners) { it }
        assertEquals(PrintWorldModel.Status.CAPTURING, started)
        assertEquals(PrintWorldModel.Status.READY, PrintWorldModel.pump(level) { })
        val readsAfterFirstCapture = reads
        assertTrue(readsAfterFirstCapture > 0)

        val reused = PrintWorldModel.ensureCapture(level, content, 0L, corners) { it }
        assertEquals(PrintWorldModel.Status.READY, reused)
        assertEquals(readsAfterFirstCapture, reads)

        val restarted = PrintWorldModel.ensureCapture(level, content, 1L, corners) { it }
        assertEquals(PrintWorldModel.Status.CAPTURING, restarted)
        PrintWorldModel.pump(level) { }
        assertTrue(reads > readsAfterFirstCapture)
    }

    @Test
    public fun ensureCaptureRefusesRegionsAboveTheCellCapWithoutAllocatingAndStaysLive(): Unit {
        val level = mockClientLevel()
        every { level.getBlockState(any()) } returns Blocks.STONE.defaultBlockState()
        val corners = listOf(BlockPos(0, 0, 0), BlockPos(499, 499, 499))

        val status = PrintWorldModel.ensureCapture(level, Any(), 0L, corners) { it }
        assertEquals(PrintWorldModel.Status.REFUSED, status)

        val liveMarker = Blocks.GOLD_BLOCK.defaultBlockState()
        val liveLevel = mockClientLevel()
        every { liveLevel.getBlockState(any()) } returns liveMarker
        withMockedLevel(liveLevel) {
            assertEquals(liveMarker, PrintWorldModel.stateAt(BlockPos(1, 1, 1)))
        }
    }

    @Test
    public fun ensureCaptureWithNoContentIsRefusedTrivially(): Unit {
        val level = mockClientLevel()
        val status = PrintWorldModel.ensureCapture(level, Any(), 0L, emptyList()) { it }
        assertEquals(PrintWorldModel.Status.REFUSED, status)
    }

    private fun withMockedLevel(level: ClientLevel, block: () -> Unit): Unit {
        val levelField = Minecraft::class.java.getField("level")
        levelField.set(mockedMinecraft, level)
        try {
            block()
        } finally {
            levelField.set(mockedMinecraft, null)
        }
    }

    private fun mockClientLevel(): ClientLevel {
        return mockk()
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
