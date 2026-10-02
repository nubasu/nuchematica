import com.nubasu.nuchematica.io.NbtReader
import com.nubasu.nuchematica.io.NbtWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

public class NbtWriterTest {

    @Test
    public fun writtenTagReadsBackIdentically(@TempDir tempDir: File) {
        val inputStream = DataInputStream(
            BufferedInputStream(javaClass.getResourceAsStream("test_schematic/0_a.schematic")!!)
        )
        val expected = inputStream.use { NbtReader(it).readCompoundTag() }

        val outputFile = File(tempDir, "round_trip.schematic")
        DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use {
            NbtWriter(it).writeTagPayload(expected)
        }

        val actual = DataInputStream(BufferedInputStream(FileInputStream(outputFile))).use {
            NbtReader(it).readCompoundTag()
        }

        assertEquals(expected.toString(), actual.toString())
    }
}
