package com.nubasu.nuchematica.printer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

public class ScaffoldPlannerTest {
    private val target = BlockPos(0, 1, 0)
    private val schematicPositions = setOf(target)

    @Test
    public fun pickTheFirstDirectionOrderNeighborThatQualifies(): Unit {
        val states = Direction.values().associate { direction ->
            target.relative(direction).relative(direction) to Blocks.STONE.defaultBlockState()
        }
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = Blocks.STONE.defaultBlockState(),
            isSchematicPosition = { pos -> pos in schematicPositions },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(target.below(), plan?.cells?.single())
        assertEquals(target, plan?.targetPos)
    }

    @Test
    public fun skipsACandidateThatIsAnExpectedSchematicPosition(): Unit {
        val schematicNeighbor = target.below()
        val supportedCandidate = target.above()
        val states = mapOf(
            schematicNeighbor to Blocks.STONE.defaultBlockState(),
            supportedCandidate.above() to Blocks.STONE.defaultBlockState(),
        )
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = Blocks.STONE.defaultBlockState(),
            isSchematicPosition = { pos -> pos == schematicNeighbor },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(supportedCandidate, plan?.cells?.single())
    }

    @Test
    public fun skipsACandidateThatIsNotReplaceableInTheWorld(): Unit {
        val occupiedCandidate = target.below()
        val replaceableCandidate = target.above()
        val states = mapOf(
            occupiedCandidate to Blocks.COBBLESTONE.defaultBlockState(),
            replaceableCandidate.above() to Blocks.STONE.defaultBlockState(),
        )
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = Blocks.STONE.defaultBlockState(),
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(replaceableCandidate, plan?.cells?.single())
    }

    @Test
    public fun skipsCandidatesInsideThePlayerProtectionColumn(): Unit {
        val playerFeetPos = Vec3(target.below().x + 0.5, target.below().y.toDouble(), target.below().z + 0.5)
        val northCandidate = target.north()
        val states = mapOf(northCandidate.below() to Blocks.STONE.defaultBlockState())
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = Blocks.STONE.defaultBlockState(),
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = playerFeetPos,
        )

        assertEquals(northCandidate, plan?.cells?.single())
    }

    @Test
    public fun skipsACandidateWithNoSupportFaceOfItsOwn(): Unit {
        val supportedCandidate = target.above()
        val states = mapOf(
            supportedCandidate.above() to Blocks.STONE.defaultBlockState(),
        )
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = Blocks.STONE.defaultBlockState(),
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(supportedCandidate, plan?.cells?.single())
    }

    @Test
    public fun returnsNullWhenNoNeighborCellQualifies(): Unit {
        val stateAt: (BlockPos) -> BlockState = { Blocks.AIR.defaultBlockState() }

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = Blocks.STONE.defaultBlockState(),
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertNull(plan)
    }

    @Test
    public fun bottomHalfStairRejectsTheAboveCellButAcceptsASideCell(): Unit {
        val expectedState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.BOTTOM)
        val schematicNeighbor = target.below()
        val aboveCandidate = target.above()
        val northCandidate = target.north()
        val states = mapOf(
            aboveCandidate.above() to Blocks.STONE.defaultBlockState(),
            northCandidate.below() to Blocks.STONE.defaultBlockState(),
        )
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = expectedState,
            isSchematicPosition = { pos -> pos == schematicNeighbor },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(northCandidate, plan?.cells?.single())
    }

    @Test
    public fun bottomHalfStairAcceptsTheBelowCell(): Unit {
        val expectedState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.BOTTOM)
        val belowCandidate = target.below()
        val states = mapOf(belowCandidate.below() to Blocks.STONE.defaultBlockState())
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = expectedState,
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(belowCandidate, plan?.cells?.single())
    }

    @Test
    public fun trapdoorRejectsScaffoldOnWrongHorizontalSupportFace(): Unit {
        val expectedState = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.OPEN, true)
            .setValue(BlockStateProperties.POWERED, true)
        val wrongFaceCandidate = target.north()
        val unavailableNeighbors = Direction.values()
            .map { direction -> target.relative(direction) }
            .filterNot { pos -> pos == wrongFaceCandidate }
            .toSet()
        val stateAt = stateReader(mapOf(wrongFaceCandidate.below() to Blocks.STONE.defaultBlockState()))

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = expectedState,
            isSchematicPosition = { pos -> pos in unavailableNeighbors },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertNull(plan, "the north scaffold exposes SOUTH and cannot place a west-facing trapdoor")
    }

    @Test
    public fun topHalfStairRejectsTheBelowCellButAcceptsASideCell(): Unit {
        val expectedState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP)
        val schematicNeighbor = target.above()
        val belowCandidate = target.below()
        val northCandidate = target.north()
        val states = mapOf(
            belowCandidate.below() to Blocks.STONE.defaultBlockState(),
            northCandidate.below() to Blocks.STONE.defaultBlockState(),
        )
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = expectedState,
            isSchematicPosition = { pos -> pos == schematicNeighbor },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(northCandidate, plan?.cells?.single())
    }

    @Test
    public fun topHalfStairAcceptsTheAboveCell(): Unit {
        val expectedState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP)
        val aboveCandidate = target.above()
        val states = mapOf(aboveCandidate.above() to Blocks.STONE.defaultBlockState())
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = expectedState,
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(aboveCandidate, plan?.cells?.single())
    }

    @Test
    public fun pillarAxisOnlyAcceptsCandidatesAlongTheExpectedAxis(): Unit {
        val expectedState = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)
        val states = Direction.values().associate { direction ->
            target.relative(direction).relative(direction) to Blocks.STONE.defaultBlockState()
        }
        val stateAt = stateReader(states)

        val plan = ScaffoldPlanner.plan(
            targetPos = target,
            expectedState = expectedState,
            isSchematicPosition = { false },
            stateAt = stateAt,
            playerFeetPos = null,
        )

        assertEquals(target.west(), plan?.cells?.single())
    }

    @Test
    public fun scaffoldMaterialIsSlimeBlock(): Unit {
        assertEquals(Blocks.SLIME_BLOCK.defaultBlockState(), SCAFFOLD_BLOCK_STATE)
    }

    private fun stateReader(states: Map<BlockPos, BlockState>): (BlockPos) -> BlockState {
        return { pos -> states[pos] ?: Blocks.AIR.defaultBlockState() }
    }

    @Test
    public fun findOrphanScaffoldsFindsAWorldSlimeBlockTheSchematicDoesNotExpect(): Unit {
        val orphan = BlockPos(0, 1, 0)
        val world = mapOf(orphan to Blocks.SLIME_BLOCK.defaultBlockState())

        val found = findOrphanScaffolds(
            boundsMin = BlockPos(-1, 0, -1),
            boundsMax = BlockPos(1, 2, 1),
            expectedAt = { null },
            stateAt = stateReader(world),
        )

        assertEquals(listOf(orphan), found)
    }

    @Test
    public fun findOrphanScaffoldsExcludesAPositionWhereTheSchematicItselfExpectsSlime(): Unit {
        val userSlime = BlockPos(0, 1, 0)
        val world = mapOf(userSlime to Blocks.SLIME_BLOCK.defaultBlockState())
        val expected = mapOf(userSlime to Blocks.SLIME_BLOCK.defaultBlockState())

        val found = findOrphanScaffolds(
            boundsMin = BlockPos(-1, 0, -1),
            boundsMax = BlockPos(1, 2, 1),
            expectedAt = expected::get,
            stateAt = stateReader(world),
        )

        assertTrue(found.isEmpty()) { "must not sweep a position the schematic itself expects slime at" }
    }

    @Test
    public fun findOrphanScaffoldsIgnoresNonSlimeBlocksRegardlessOfExpectation(): Unit {
        val stonePos = BlockPos(0, 1, 0)
        val world = mapOf(stonePos to Blocks.STONE.defaultBlockState())

        val found = findOrphanScaffolds(
            boundsMin = BlockPos(-1, 0, -1),
            boundsMax = BlockPos(1, 2, 1),
            expectedAt = { null },
            stateAt = stateReader(world),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    public fun findOrphanScaffoldsSkipsTheScanAndReportsWhenTheBoxExceedsTheCap(): Unit {
        var exceeded = false

        val found = findOrphanScaffolds(
            boundsMin = BlockPos(0, 0, 0),
            boundsMax = BlockPos(200, 200, 200),
            expectedAt = { null },
            stateAt = { error("must not scan when the box exceeds the cap") },
            onBoundsExceeded = { exceeded = true },
        )

        assertTrue(found.isEmpty())
        assertTrue(exceeded) { "onBoundsExceeded must fire when the box is over ORPHAN_SWEEP_MAX_CELLS" }
    }

    @Test
    public fun schematicWorldBoundingBoxExpandsTheMappedAabbByTheMargin(): Unit {
        val local = listOf(BlockPos(0, 0, 0), BlockPos(2, 1, 3))
        val localToWorld: (BlockPos) -> BlockPos = { pos -> pos.offset(10, 0, 10) }

        val bounds = schematicWorldBoundingBox(local, localToWorld, margin = 2)

        assertEquals(BlockPos(8, -2, 8), bounds?.first)
        assertEquals(BlockPos(14, 3, 15), bounds?.second)
    }

    @Test
    public fun schematicWorldBoundingBoxIsNullForNoContent(): Unit {
        val bounds = schematicWorldBoundingBox(emptyList(), { it }, margin = 2)

        assertNull(bounds)
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
