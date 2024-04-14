import com.nubasu.nuchematica.io.NbtReader
import net.minecraft.util.FastBufferedInputStream
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.FileInputStream

public class NbtReaderTest {

    @Test
    public fun testReadCompoundTag() {
        val path = javaClass.getResource("test_schematic/0_a.schematic").path
        val inputStream = DataInputStream(FastBufferedInputStream(FileInputStream(path)))

        val reader = NbtReader(inputStream)
        reader.readCompoundTag()
    }
}