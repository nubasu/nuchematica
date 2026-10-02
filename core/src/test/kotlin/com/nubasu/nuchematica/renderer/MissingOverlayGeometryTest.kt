package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import com.nubasu.nuchematica.utils.BaseRender
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteOrder

public class MissingOverlayGeometryTest {

    @Test
    public fun fixtureBoundariesSelectExpectedFacesAndQuadCounts(): Unit {
        for (fixture in fixtures()) {
            val schematicBlocks = fixture.expectedFaces.keys.associateWith { Unit }
            val builder = BufferBuilder(1024)
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)

            for ((pos, expectedFaces) in fixture.expectedFaces) {
                val actualFaces = selectMissingOverlayFaces(pos, schematicBlocks)
                assertEquals(expectedFaces, actualFaces, "${fixture.name} faces at $pos")
                BaseRender.drawVisibleFacesCubeWithBuffer(
                    builder,
                    PoseStack(),
                    Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
                    GREEN,
                    actualFaces,
                )
            }

            builder.end()
            val drawState = builder.popNextBuffer().first
            val expectedQuads = fixture.expectedFaces.values.sumOf(Set<Direction>::size)
            assertEquals(expectedQuads * 4, drawState.vertexCount(), "${fixture.name} vertex count")
            assertEquals(expectedQuads * 6, drawState.indexCount(), "${fixture.name} index count")
        }
    }

    @Test
    public fun allFaceWindingsPointTowardTheirExteriorCameras(): Unit {
        val actualNormals = Direction.values().associateWith { direction ->
            val vertices = verticesFor(direction)
            val edgeA = vertices[1].subtract(vertices[0])
            val edgeB = vertices[2].subtract(vertices[0])
            edgeA.cross(edgeB)
        }
        val expectedNormals = Direction.values().associateWith { direction ->
            Vec3(
                direction.stepX.toDouble(),
                direction.stepY.toDouble(),
                direction.stepZ.toDouble(),
            )
        }

        assertEquals(expectedNormals, actualNormals)
    }

    private fun verticesFor(direction: Direction): List<Vec3> {
        val builder = BufferBuilder(256)
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        BaseRender.drawVisibleFacesCubeWithBuffer(
            builder,
            PoseStack(),
            Vec3.ZERO,
            GREEN,
            setOf(direction),
        )
        builder.end()

        val rendered = builder.popNextBuffer()
        val drawState = rendered.first
        val buffer = rendered.second
        // Not every Minecraft distribution sets the popped slice to the native byte order.
        buffer.order(ByteOrder.nativeOrder())
        val vertexSize = drawState.format().vertexSize
        return List(drawState.vertexCount()) { index ->
            val offset = index * vertexSize
            Vec3(
                buffer.getFloat(offset).toDouble(),
                buffer.getFloat(offset + Float.SIZE_BYTES).toDouble(),
                buffer.getFloat(offset + 2 * Float.SIZE_BYTES).toDouble(),
            )
        }
    }

    private fun fixtures(): List<Fixture> {
        val origin = BlockPos.ZERO
        val allFaces = Direction.values().toSet()
        val horizontalEast = origin.east()
        val verticalUp = origin.above()

        val cube = buildMap {
            for (x in 0..1) {
                for (y in 0..1) {
                    for (z in 0..1) {
                        val faces = buildSet {
                            if (x == 0) add(Direction.WEST)
                            if (x == 1) add(Direction.EAST)
                            if (y == 0) add(Direction.DOWN)
                            if (y == 1) add(Direction.UP)
                            if (z == 0) add(Direction.NORTH)
                            if (z == 1) add(Direction.SOUTH)
                        }
                        put(BlockPos(x, y, z), faces)
                    }
                }
            }
        }

        val asymmetric = mapOf(
            origin to setOf(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST),
            origin.east() to allFaces - Direction.WEST,
            origin.above() to setOf(Direction.UP, Direction.NORTH, Direction.WEST, Direction.EAST),
            origin.above().south() to allFaces - Direction.NORTH,
        )

        return listOf(
            Fixture("isolated", mapOf(origin to allFaces)),
            Fixture(
                "horizontal pair",
                mapOf(
                    origin to allFaces - Direction.EAST,
                    horizontalEast to allFaces - Direction.WEST,
                ),
            ),
            Fixture(
                "vertical pair",
                mapOf(
                    origin to allFaces - Direction.UP,
                    verticalUp to allFaces - Direction.DOWN,
                ),
            ),
            Fixture("2x2x2", cube),
            Fixture("asymmetric with top", asymmetric),
        )
    }

    private data class Fixture(
        val name: String,
        val expectedFaces: Map<BlockPos, Set<Direction>>,
    )

    private companion object {
        private val GREEN = com.mojang.math.Vector3f(0f, 1f, 0f)
    }
}
