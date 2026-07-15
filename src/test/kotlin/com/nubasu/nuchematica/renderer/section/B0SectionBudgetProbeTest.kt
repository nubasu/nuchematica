package com.nubasu.nuchematica.renderer.section

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import com.nubasu.nuchematica.schematic.reader.DetectedSchematicFormat
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.IntTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.io.File
import kotlin.math.max

public class B0SectionBudgetProbeTest {

    @Test
    public fun inventoryRepresentativeFixtureHeaders(): Unit {
        assumeTrue(System.getenv(BENCHMARK_ENV) == "1", "$BENCHMARK_ENV=1 enables this evidence probe")

        val directory = File("run/schematics")
        for (name in FIXTURES) {
            val file = File(directory, name)
            assumeTrue(file.isFile, "B0 local fixture is unavailable: $name")
            val root = SchematicFormatDetector.readRootTag(file)
            val format = SchematicFormatDetector.detect(root)
            val schematic = when (format) {
                DetectedSchematicFormat.WORLD_EDIT -> root.value["Schematic"] as CompoundTag
                DetectedSchematicFormat.SPONGE_V3 ->
                    ((root.value[""] as CompoundTag).value["Schematic"] as CompoundTag)
                else -> error("unsupported B0 fixture format: $format")
            }
            val width = schematic.getShort("Width").toInt()
            val height = schematic.getShort("Height").toInt()
            val length = schematic.getShort("Length").toInt()
            val dataSize = if (format == DetectedSchematicFormat.WORLD_EDIT) {
                schematic.getByteArray("Blocks").size
            } else {
                (schematic.value["Blocks"] as CompoundTag).getByteArray("Data").size
            }
            println(
                "B0_FIXTURE_HEADER fixture=$name fileBytes=${file.length()} " +
                    "dims=${width}x${height}x$length volume=${width.toLong() * height * length} dataBytes=$dataSize",
            )
        }
    }

    @Test
    public fun measureRepresentativeSectionCaptureAndGeometry(): Unit {
        assumeTrue(System.getenv(BENCHMARK_ENV) == "1", "$BENCHMARK_ENV=1 enables this evidence probe")

        for (fixture in loadFixtures()) {
            val profile = profile(fixture)
            println(profile.asEvidenceLine())
        }
    }

    @Test
    public fun measureRepresentativeOpenGlUploads(): Unit {
        assumeTrue(System.getenv(GL_ENV) == "1", "$GL_ENV=1 enables this evidence probe")

        val profiles = loadFixtures().map(::profile)
        assumeTrue(GLFW.glfwInit(), "GLFW initialization is unavailable")
        var window = 0L
        try {
            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
            window = GLFW.glfwCreateWindow(64, 64, "nuchematica-b0-upload-probe", 0L, 0L)
            assumeTrue(window != 0L, "OpenGL 3.2 context creation is unavailable")
            GLFW.glfwMakeContextCurrent(window)
            GL.createCapabilities()
            RenderSystem.initRenderThread()

            for (profile in profiles) {
                val p95 = measureUpload(profile.p95Quads)
                val peak = measureUpload(profile.peakQuads)
                println(
                    "B0_UPLOAD fixture=${profile.name} " +
                        "p95Quads=${profile.p95Quads} p95Bytes=${profile.p95Bytes} " +
                        "p95UploadUs=${p95.p50Micros}/${p95.p95Micros} " +
                        "peakQuads=${profile.peakQuads} peakBytes=${profile.peakBytes} " +
                    "peakUploadUs=${peak.p50Micros}/${peak.p95Micros}",
                )
            }
            for ((name, quads) in RUNTIME_UPLOAD_SAMPLES) {
                val upload = measureUpload(quads)
                println(
                    "B0_UPLOAD_RUNTIME_SAMPLE name=$name quads=$quads " +
                        "probeBytes=${quads * BYTES_PER_SORTED_QUAD} " +
                        "uploadUs=${upload.p50Micros}/${upload.p95Micros}",
                )
            }
        } finally {
            if (window != 0L) {
                VertexBuffer.unbind()
                GL.setCapabilities(null)
                GLFW.glfwMakeContextCurrent(0L)
                GLFW.glfwDestroyWindow(window)
            }
            GLFW.glfwTerminate()
        }
    }

    private fun profile(fixture: Fixture): SectionProfile {
        val bySection = fixture.cells.groupBy { SectionKey(it.x shr 4, it.y shr 4, it.z shr 4) }
        val sortedSections = bySection.keys.sortedWith(compareBy(SectionKey::x, SectionKey::y, SectionKey::z))
        val first = sortedSections.first()
        val geometryBuilder = BufferBuilder(INITIAL_BUFFER_BYTES)

        repeat(WARMUP_ITERATIONS) {
            capture(first, fixture.cells)
            buildSortedGeometry(geometryBuilder, bySection.getValue(first), fixture.cells)
        }

        val captureTimes = ArrayList<Long>(sortedSections.size)
        val geometryTimes = ArrayList<Long>(sortedSections.size)
        val quads = ArrayList<Int>(sortedSections.size)
        val bytes = ArrayList<Int>(sortedSections.size)
        for (section in sortedSections) {
            var start = System.nanoTime()
            val snapshot = capture(section, fixture.cells)
            captureTimes += System.nanoTime() - start
            assertEquals(SNAPSHOT_EDGE * SNAPSHOT_EDGE * SNAPSHOT_EDGE, snapshot.size)

            start = System.nanoTime()
            val geometry = buildSortedGeometry(geometryBuilder, bySection.getValue(section), fixture.cells)
            geometryTimes += System.nanoTime() - start
            quads += geometry.quads
            bytes += geometry.bytes
        }

        return SectionProfile(
            name = fixture.name,
            width = fixture.width,
            height = fixture.height,
            length = fixture.length,
            blocks = fixture.cells.size,
            sections = sortedSections.size,
            captureP50Micros = percentile(captureTimes, 50) / 1_000,
            captureP95Micros = percentile(captureTimes, 95) / 1_000,
            capturePeakMicros = captureTimes.maxOrNull()!! / 1_000,
            geometryP50Micros = percentile(geometryTimes, 50) / 1_000,
            geometryP95Micros = percentile(geometryTimes, 95) / 1_000,
            geometryPeakMicros = geometryTimes.maxOrNull()!! / 1_000,
            p95Quads = percentile(quads, 95),
            peakQuads = quads.maxOrNull()!!,
            p95Bytes = percentile(bytes, 95),
            peakBytes = bytes.maxOrNull()!!,
            totalBytes = bytes.sumOf(Int::toLong),
        )
    }

    private fun capture(section: SectionKey, cells: Set<Cell>): IntArray {
        val result = IntArray(SNAPSHOT_EDGE * SNAPSHOT_EDGE * SNAPSHOT_EDGE)
        val baseX = section.x * SECTION_EDGE - SNAPSHOT_HALO
        val baseY = section.y * SECTION_EDGE - SNAPSHOT_HALO
        val baseZ = section.z * SECTION_EDGE - SNAPSHOT_HALO
        var index = 0
        for (y in 0 until SNAPSHOT_EDGE) {
            for (z in 0 until SNAPSHOT_EDGE) {
                for (x in 0 until SNAPSHOT_EDGE) {
                    if (Cell(baseX + x, baseY + y, baseZ + z) in cells) {
                        result[index] = PACKED_SAMPLE
                    }
                    index++
                }
            }
        }
        return result
    }

    private fun buildSortedGeometry(
        builder: BufferBuilder,
        sectionCells: List<Cell>,
        allCells: Set<Cell>,
    ): GeometryMeasurement {
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        var quads = 0
        for (cell in sectionCells) {
            for (direction in DIRECTIONS) {
                if (Cell(cell.x + direction.x, cell.y + direction.y, cell.z + direction.z) in allCells) {
                    continue
                }
                addProbeQuad(builder, cell.x.toFloat(), cell.y.toFloat(), cell.z.toFloat())
                quads++
            }
        }
        builder.setQuadSortOrigin(0f, 0f, 0f)
        builder.end()
        val builtBuffer = builder.popNextBuffer()
        val drawState = builtBuffer.first
        val data = builtBuffer.second
        assertEquals(quads * 4, drawState.vertexCount())
        assertTrue(data.remaining() - drawState.bufferSize() in 0..3)
        return GeometryMeasurement(quads, drawState.bufferSize())
    }

    private fun measureUpload(quads: Int): UploadMeasurement {
        if (quads == 0) return UploadMeasurement(0, 0)

        val builder = BufferBuilder(max(INITIAL_BUFFER_BYTES, quads * BYTES_PER_SORTED_QUAD))
        val buffer = VertexBuffer()
        try {
            repeat(UPLOAD_WARMUP_ITERATIONS) {
                fillSortedProbeBuffer(builder, quads)
                buffer.bind()
                buffer.upload(builder)
                VertexBuffer.unbind()
            }
            GL11.glFinish()

            val times = ArrayList<Long>(UPLOAD_ITERATIONS)
            repeat(UPLOAD_ITERATIONS) {
                fillSortedProbeBuffer(builder, quads)
                val start = System.nanoTime()
                buffer.bind()
                buffer.upload(builder)
                VertexBuffer.unbind()
                GL11.glFinish()
                times += System.nanoTime() - start
            }
            return UploadMeasurement(
                p50Micros = percentile(times, 50) / 1_000,
                p95Micros = percentile(times, 95) / 1_000,
            )
        } finally {
            buffer.close()
        }
    }

    private fun fillSortedProbeBuffer(builder: BufferBuilder, quads: Int) {
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        repeat(quads) { index ->
            val x = (index and 15).toFloat()
            val y = (index shr 4 and 15).toFloat()
            val z = (index shr 8).toFloat()
            addProbeQuad(builder, x, y, z)
        }
        builder.setQuadSortOrigin(8f, 8f, 32f)
        builder.end()
    }

    private fun addProbeQuad(builder: BufferBuilder, x: Float, y: Float, z: Float) {
        addProbeVertex(builder, x, y, z, 0f, 0f)
        addProbeVertex(builder, x + 1f, y, z, 1f, 0f)
        addProbeVertex(builder, x + 1f, y + 1f, z, 1f, 1f)
        addProbeVertex(builder, x, y + 1f, z, 0f, 1f)
    }

    private fun addProbeVertex(builder: BufferBuilder, x: Float, y: Float, z: Float, u: Float, v: Float) {
        builder.vertex(x.toDouble(), y.toDouble(), z.toDouble())
            .color(255, 255, 255, 255)
            .uv(u, v)
            .uv2(15, 15)
            .normal(0f, 1f, 0f)
            .endVertex()
    }

    private fun loadFixtures(): List<Fixture> {
        val directory = File("run/schematics")
        val files = FIXTURES.map { name -> File(directory, name) }
        assumeTrue(files.all(File::isFile), "B0 local fixture set is unavailable")
        return files.map(::readFixture)
    }

    private fun readFixture(file: File): Fixture {
        val root = SchematicFormatDetector.readRootTag(file)
        val format = SchematicFormatDetector.detect(root)
        val schematic = when (format) {
            DetectedSchematicFormat.WORLD_EDIT -> root.value["Schematic"] as CompoundTag
            DetectedSchematicFormat.SPONGE_V3 ->
                ((root.value[""] as CompoundTag).value["Schematic"] as CompoundTag)
            else -> error("unsupported B0 fixture format: $format")
        }
        val width = schematic.getShort("Width").toInt()
        val height = schematic.getShort("Height").toInt()
        val length = schematic.getShort("Length").toInt()
        val cells = HashSet<Cell>()
        val blockIds: ByteArray
        val airIds: Set<Int>
        if (format == DetectedSchematicFormat.WORLD_EDIT) {
            blockIds = schematic.getByteArray("Blocks")
            airIds = setOf(0)
        } else {
            val blocks = schematic.value["Blocks"] as CompoundTag
            val palette = blocks.value["Palette"] as CompoundTag
            blockIds = blocks.getByteArray("Data")
            airIds = palette.value
                .filterKeys { it.substringBefore('[') == "minecraft:air" }
                .values
                .map { (it as IntTag).value }
                .toSet()
        }
        assertEquals(width * height * length, blockIds.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                for (z in 0 until length) {
                    val index = (y * length + z) * width + x
                    if ((blockIds[index].toInt() and 0xff) !in airIds) {
                        cells += Cell(x, y, z)
                    }
                }
            }
        }
        return Fixture(file.name, width, height, length, cells)
    }

    private fun <T : Comparable<T>> percentile(values: List<T>, percentile: Int): T {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile) / 100
        return sorted[index]
    }

    private data class Cell(val x: Int, val y: Int, val z: Int)

    private data class SectionKey(val x: Int, val y: Int, val z: Int)

    private data class Direction(val x: Int, val y: Int, val z: Int)

    private data class Fixture(
        val name: String,
        val width: Int,
        val height: Int,
        val length: Int,
        val cells: Set<Cell>,
    )

    private data class GeometryMeasurement(val quads: Int, val bytes: Int)

    private data class UploadMeasurement(val p50Micros: Long, val p95Micros: Long)

    private data class SectionProfile(
        val name: String,
        val width: Int,
        val height: Int,
        val length: Int,
        val blocks: Int,
        val sections: Int,
        val captureP50Micros: Long,
        val captureP95Micros: Long,
        val capturePeakMicros: Long,
        val geometryP50Micros: Long,
        val geometryP95Micros: Long,
        val geometryPeakMicros: Long,
        val p95Quads: Int,
        val peakQuads: Int,
        val p95Bytes: Int,
        val peakBytes: Int,
        val totalBytes: Long,
    ) {
        fun asEvidenceLine(): String {
            val volume = width.toLong() * height * length
            val densityPermille = blocks * 1_000L / volume
            return "B0_SECTION fixture=$name dims=${width}x${height}x$length " +
                "blocks=$blocks densityPermille=$densityPermille sections=$sections " +
                "captureUs=$captureP50Micros/$captureP95Micros/$capturePeakMicros " +
                "geometryUs=$geometryP50Micros/$geometryP95Micros/$geometryPeakMicros " +
                "quads=$p95Quads/$peakQuads bytes=$p95Bytes/$peakBytes totalBytes=$totalBytes"
        }
    }

    private companion object {
        private const val BENCHMARK_ENV = "NUCHEMATICA_B0_BENCHMARK"
        private const val GL_ENV = "NUCHEMATICA_B0_GL"
        private const val SECTION_EDGE = 16
        private const val SNAPSHOT_HALO = 1
        private const val SNAPSHOT_EDGE = SECTION_EDGE + SNAPSHOT_HALO * 2
        private const val PACKED_SAMPLE = 0x00F000F0
        private const val INITIAL_BUFFER_BYTES = 262_144
        private const val BYTES_PER_SORTED_QUAD = 140
        private const val WARMUP_ITERATIONS = 8
        private const val UPLOAD_WARMUP_ITERATIONS = 5
        private const val UPLOAD_ITERATIONS = 25

        private val FIXTURES = listOf(
            "render_matrix.schem",
            "HORRORTREE1.schematic",
            "Church.schematic",
            "nature_town.schematic",
        )

        private val RUNTIME_UPLOAD_SAMPLES = listOf(
            "large-p95" to 4_430,
            "large-peak" to 5_661,
        )

        private val DIRECTIONS = arrayOf(
            Direction(-1, 0, 0),
            Direction(1, 0, 0),
            Direction(0, -1, 0),
            Direction(0, 1, 0),
            Direction(0, 0, -1),
            Direction(0, 0, 1),
        )
    }
}
