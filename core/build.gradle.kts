plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.spongepowered.gradle.vanilla") version "0.2.1"
}

val coroutinesVersion: String by project
val serializationVersion: String by project
val mockkVersion: String by project

project.group = "com.nubasu.nuchematica"
project.version = "1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

minecraft {
    version("1.18.2")
    accessWideners(file("src/main/resources/nuchematica.accesswidener"))
}

repositories {
    mavenCentral()
}

// Vanilla Minecraft resolves LWJGL without its native libraries, so tests that reach MemoryTracker
// (and thereby MemoryUtil) need them on the test runtime classpath. The version is the one VanillaGradle
// resolves for the Minecraft version above.
val lwjglVersion = "3.2.2"
val lwjglModules = listOf("lwjgl", "lwjgl-glfw", "lwjgl-jemalloc", "lwjgl-openal", "lwjgl-opengl", "lwjgl-stb", "lwjgl-tinyfd")
val lwjglNatives = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("win") -> "natives-windows"
        os.contains("mac") || os.contains("darwin") -> "natives-macos"
        else -> "natives-linux"
    }
}

// The suite in src/test runs here on vanilla Minecraft and from :forge on the Forge classpath.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:${serializationVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${serializationVersion}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testImplementation("io.mockk:mockk:${mockkVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    lwjglModules.forEach { module -> testRuntimeOnly("org.lwjgl:$module:$lwjglVersion:$lwjglNatives") }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("automode-full")
    }
    // Minecraft and MockK initialization exceed Gradle's default 512 MiB worker heap.
    maxHeapSize = "2g"
}

// MockK cannot retransform some vanilla Minecraft classes unless their interfaces table order is normalized.
apply(from = "minecraft-interface-order.gradle.kts")

tasks.compileKotlin {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xexplicit-api=warning", "-Xjvm-default=all")
    }
}
