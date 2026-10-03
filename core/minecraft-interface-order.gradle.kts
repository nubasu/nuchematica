// Invariant: on the vanilla Minecraft 1.18.2 jar, 6 classes (BlockableEventLoop, ProcessorMailbox,
// MinecraftServer, Direction$Axis, LootItemFunction, LootItemCondition) list their interfaces in a
// different order than their generic Signature attribute. MockK's inline mocking retransforms a class from
// the Signature order, and the JVM rejects that as a changed interface list ("class redefinition failed:
// attempted to change superclass or interfaces"), which breaks mocking of Minecraft (BlockableEventLoop
// is one of its superclasses). A newer ByteBuddy does not change this (1.18.14 fails identically).
// The task below rewrites only the interfaces table of those classes into Signature order, leaving every
// other byte untouched, and the result precedes the Minecraft jar on the :core:test runtime classpath only.
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Erased internal names of the superinterfaces listed in a class Signature attribute, or null for shapes
// this reader does not handle.
fun signatureInterfaceNames(signature: String): List<String>? {
    var pos = 0
    if (signature.startsWith("<")) {
        var depth = 0
        do {
            when (signature[pos++]) {
                '<' -> depth++
                '>' -> depth--
            }
        } while (depth > 0)
    }
    val types = mutableListOf<String>()
    while (pos < signature.length) {
        if (signature[pos] != 'L') return null
        val start = ++pos
        var depth = 0
        var nameEnd = -1
        while (depth > 0 || signature[pos] != ';') {
            when (signature[pos]) {
                '<' -> {
                    if (depth == 0) nameEnd = pos
                    depth++
                }
                '>' -> depth--
                '.' -> if (depth == 0) return null
            }
            pos++
        }
        types.add(signature.substring(start, if (nameEnd >= 0) nameEnd else pos))
        pos++
    }
    return types.drop(1)
}

// Returns the class file with its interfaces table permuted into the order of its generic Signature
// attribute, or null when the two already agree. Only the table entries move; every other byte is kept.
fun withSignatureInterfaceOrder(classBytes: ByteArray): ByteArray? {
    val buf = ByteBuffer.wrap(classBytes)
    fun u2(): Int = buf.short.toInt() and 0xFFFF
    fun skip(count: Int) {
        buf.position(buf.position() + count)
    }
    fun skipAttribute() {
        skip(2)
        skip(buf.int)
    }
    buf.position(8)
    val poolSize = u2()
    val utf8 = arrayOfNulls<String>(poolSize)
    val classNameSlot = IntArray(poolSize)
    var slot = 1
    while (slot < poolSize) {
        when (val tag = buf.get().toInt()) {
            1 -> {
                val bytes = ByteArray(u2())
                buf.get(bytes)
                utf8[slot] = String(bytes, Charsets.ISO_8859_1)
            }
            7 -> classNameSlot[slot] = u2()
            8, 16, 19, 20 -> skip(2)
            15 -> skip(3)
            3, 4, 9, 10, 11, 12, 17, 18 -> skip(4)
            5, 6 -> {
                skip(8)
                slot++
            }
            else -> error("Unknown constant pool tag $tag")
        }
        slot++
    }
    skip(6)
    val interfaceCount = u2()
    val interfacesStart = buf.position()
    val interfaceSlots = IntArray(interfaceCount) { u2() }
    if (interfaceCount < 2) return null
    repeat(2) {
        repeat(u2()) {
            skip(6)
            repeat(u2()) { skipAttribute() }
        }
    }
    var signature: String? = null
    repeat(u2()) {
        val name = utf8[u2()]
        val length = buf.int
        if (name == "Signature") signature = utf8[buf.getShort(buf.position()).toInt() and 0xFFFF]
        skip(length)
    }
    val wanted = signatureInterfaceNames(signature ?: return null) ?: return null
    val present = interfaceSlots.map { interfaceSlot -> utf8[classNameSlot[interfaceSlot]]!! }
    if (wanted == present || wanted.sorted() != present.sorted()) return null
    val fixed = classBytes.copyOf()
    val out = ByteBuffer.wrap(fixed)
    wanted.forEachIndexed { index, name ->
        out.putShort(interfacesStart + 2 * index, interfaceSlots[present.indexOf(name)].toShort())
    }
    return fixed
}

val minecraftJar = configurations.named("testRuntimeClasspath").map { classpath ->
    classpath.filter { file -> file.name.startsWith("joined") }
}
val interfaceOrderJar = layout.buildDirectory.file("minecraft-interface-order/classes.jar")
val normalizeMinecraftInterfaceOrder by tasks.registering {
    inputs.files(minecraftJar)
    outputs.file(interfaceOrderJar)
    doLast {
        val target = interfaceOrderJar.get().asFile
        target.parentFile.mkdirs()
        ZipOutputStream(target.outputStream()).use { out ->
            minecraftJar.get().forEach { source ->
                ZipFile(source).use { zip ->
                    for (entry in zip.entries()) {
                        if (!entry.name.endsWith(".class")) continue
                        val fixed = withSignatureInterfaceOrder(zip.getInputStream(entry).readBytes()) ?: continue
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(fixed)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}

tasks.named<Test>("test") {
    classpath = files(normalizeMinecraftInterfaceOrder) + classpath
}
