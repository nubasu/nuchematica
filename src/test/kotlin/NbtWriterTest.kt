import com.nubasu.nuchematica.schematic.parser.NbtReader
import com.nubasu.nuchematica.schematic.parser.NbtWriter
import it.unimi.dsi.fastutil.io.FastBufferedInputStream
import it.unimi.dsi.fastutil.io.FastBufferedOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.*


public class NbtWriterTest {
    private val outputPath = "${javaClass.getResource("generated").path}/test_output.schematic"

    @AfterEach
    public fun tearDown() {
        val outputFile = File("${javaClass.getResource("generated").path}/0_a.schematic")

        if (outputFile.exists()) {
            outputFile.delete()
        }
    }

    @Test
    public fun testWriteNbt() {
        val inputPath = javaClass.getResource("test_schematic/0_a.schematic").path
        val inputStream = DataInputStream(FastBufferedInputStream(FileInputStream(inputPath)))

        val expected = NbtReader(inputStream).readCompoundTag()

        val outputStream = DataOutputStream(FastBufferedOutputStream(FileOutputStream(outputPath)))
        NbtWriter(outputStream).writeTagPayload(expected)
        outputStream.flush()

        val outputResult = DataInputStream(FastBufferedInputStream(FileInputStream(outputPath)))

        val actual = NbtReader(outputResult).readCompoundTag()
        assertEquals(expected.toString(), actual.toString())
    }
}