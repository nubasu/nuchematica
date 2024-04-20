import com.nubasu.nuchematica.io.NbtReader
import com.nubasu.nuchematica.tag.CompoundTag
import net.minecraft.util.FastBufferedInputStream
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.FileInputStream

public class NbtReaderTest {

    @Test
    public fun testReadCompoundTag() {
        val path = javaClass.getResource("test_schematic/_natura_town.schematic").path
        val inputStream = DataInputStream(FastBufferedInputStream(FileInputStream(path)))

        val reader = NbtReader(inputStream)
        val tag = reader.readCompoundTag()

    }
}