import com.nubasu.nuchematica.io.NbtReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.DataInputStream

public class NbtReaderTest {

    @Test
    public fun readsRootCompoundFromLegacySchematicFile() {
        val inputStream = DataInputStream(
            BufferedInputStream(javaClass.getResourceAsStream("test_schematic/0_a.schematic")!!)
        )

        val tag = inputStream.use { NbtReader(it).readCompoundTag() }

        assertEquals(listOf("Schematic"), tag.value.keys.toList())
    }
}
