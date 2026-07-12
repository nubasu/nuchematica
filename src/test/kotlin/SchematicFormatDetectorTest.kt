import com.nubasu.nuchematica.schematic.reader.DetectedSchematicFormat
import com.nubasu.nuchematica.schematic.reader.SchematicFormatDetector
import com.nubasu.nuchematica.tag.CompoundTag
import com.nubasu.nuchematica.tag.IntTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

public class SchematicFormatDetectorTest {
    private fun schematicFile(name: String): File =
        File(javaClass.getResource("test_schematic/$name")!!.toURI())

    private fun detect(name: String): DetectedSchematicFormat? =
        SchematicFormatDetector.detect(SchematicFormatDetector.readRootTag(schematicFile(name)))

    @Test
    public fun detectsLegacyWorldEditFromRawNbtFile() {
        assertEquals(DetectedSchematicFormat.WORLD_EDIT, detect("0_a.schematic"))
        assertEquals(DetectedSchematicFormat.WORLD_EDIT, detect("ACACIA1.schematic"))
    }

    @Test
    public fun detectsSpongeV2FromGzippedSchemFile() {
        assertEquals(DetectedSchematicFormat.SPONGE_V2, detect("test.schem"))
        assertEquals(DetectedSchematicFormat.SPONGE_V2, detect("test_v2.schem"))
    }

    @Test
    public fun detectsSpongeV3FromGzippedSchemFile() {
        assertEquals(DetectedSchematicFormat.SPONGE_V3, detect("test_v3.schem"))
    }

    @Test
    public fun detectsSpongeV1FromVersionTag() {
        val tag = CompoundTag(mapOf("Schematic" to CompoundTag(mapOf("Version" to IntTag(1)))))
        assertEquals(DetectedSchematicFormat.SPONGE_V1, SchematicFormatDetector.detect(tag))
    }

    @Test
    public fun returnsNullForUnknownStructure() {
        assertNull(SchematicFormatDetector.detect(CompoundTag(emptyMap())))
    }
}
