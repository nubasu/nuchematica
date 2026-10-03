import java.time.Duration

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("fabric-loom") version "1.7.4"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val mcVersion: String by project
val coroutinesVersion: String by project
val serializationVersion: String by project
val fabricLoaderVersion = "0.16.14"
val fabricApiVersion = "0.77.0+1.18.2"
val fabricE2eSourceSet = sourceSets.create("fabricE2e")
val fabricE2eResultFile = layout.buildDirectory.file("reports/automode-fabric-e2e/result.json")
val fabricE2eWorldName = "nuchematica-e2e-${System.currentTimeMillis()}"
val fabricFantasyE2eResultFile = layout.buildDirectory.file("reports/automode-fabric-fantasy-e2e/result.json")
val fabricFantasyE2eWorldName = providers.gradleProperty("automodeFantasyE2eWorld")
    .getOrElse("nuchematica-fantasy-e2e-${System.currentTimeMillis()}")
val fabricFantasyE2eResume = providers.gradleProperty("automodeFantasyE2eResume").getOrElse("false")

fabricE2eSourceSet.compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
fabricE2eSourceSet.runtimeClasspath += fabricE2eSourceSet.output + sourceSets.main.get().runtimeClasspath

project.group = "com.nubasu.nuchematica"
project.version = "1.0-SNAPSHOT"
base.archivesName.set("nuchematica-fabric")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

// The loader-independent core is compiled as part of this module so Kotlin `internal` stays visible
// between the core and fabric packages.
kotlin.sourceSets.named("main") {
    kotlin.srcDir(rootProject.file("core/src/main/kotlin"))
}
sourceSets.main {
    resources.srcDir(rootProject.file("core/src/main/resources"))
}
// The shared automode E2E harness lives in core and is compiled into the test-only mod, never into the main jar.
kotlin.sourceSets.named("fabricE2e") {
    kotlin.srcDir(rootProject.file("core/src/e2e/kotlin"))
}

loom {
    accessWidenerPath.set(rootProject.file("core/src/main/resources/nuchematica.accesswidener"))
    mixin {
        defaultRefmapName.set("nuchematica.refmap.json")
    }
    runs {
        named("client") {
            runDir("../runs/fabric")
        }
        create("automodeE2eClient") {
            client()
            source(fabricE2eSourceSet)
            runDir("build/fabric-e2e/run")
            programArgs("--width", "854", "--height", "480")

            property("nuchematica.e2e.enabled", "true")
            property("nuchematica.e2e.result", fabricE2eResultFile.get().asFile.absolutePath)
            property("nuchematica.e2e.world", fabricE2eWorldName)
        }
        create("automodeFantasyE2eClient") {
            client()
            source(fabricE2eSourceSet)
            runDir("build/fabric-fantasy-e2e/run")
            programArgs("--width", "854", "--height", "480")

            property("nuchematica.e2e.enabled", "true")
            property("nuchematica.e2e.scenario", "fantasy")
            property("nuchematica.e2e.fixture", rootProject.file("core/src/test/resources/test_schematic/Fantasy_BigHouse1.schematic").absolutePath)
            property("nuchematica.e2e.result", fabricFantasyE2eResultFile.get().asFile.absolutePath)
            property("nuchematica.e2e.world", fabricFantasyE2eWorldName)
            property("nuchematica.e2e.resume", fabricFantasyE2eResume)
        }
    }
    mods {
        create("nuchematica") {
            sourceSet(sourceSets.main.get())
        }
        create("nuchematica_e2e") {
            sourceSet(fabricE2eSourceSet)
        }
    }
}

val shade by configurations.creating
configurations.implementation.get().extendsFrom(shade)

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    shade("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    shade("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    shade("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlin.coreLibrariesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutinesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-serialization-core:${serializationVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-serialization-json:${serializationVersion}")
}

tasks.processResources {
    inputs.property("version", project.version)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.compileKotlin {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xexplicit-api=warning", "-Xjvm-default=all")
    }
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveClassifier.set("dev-shadow")
    mergeServiceFiles()

    val basePkg = "com.nubasu.nuchematica.libs"
    relocate("kotlin.", "$basePkg.kotlin.")
    relocate("kotlinx.", "$basePkg.kotlinx.")
    relocate("io.sigpipe.jbsdiff.", "$basePkg.jbsdiff.")
    relocate("org.intellij.lang.annotations.", "$basePkg.ij_annotations.")
    relocate("org.jetbrains.annotations.", "$basePkg.jb_annotations.")
    relocate("org.apache.commons.compress.", "$basePkg.commons_compress.")
}

tasks.remapJar {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    dependsOn(tasks.shadowJar)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileFabricE2eKotlin") {
    dependsOn(tasks.compileKotlin)
    friendPaths.from(sourceSets.main.get().output.classesDirs)
}

tasks.matching { task -> task.name == "runAutomodeE2eClient" }.configureEach {
    timeout.set(Duration.ofMinutes(8))
    doFirst {
        val result = fabricE2eResultFile.get().asFile
        result.parentFile.mkdirs()
        result.delete()
    }
}

tasks.matching { task -> task.name == "runAutomodeFantasyE2eClient" }.configureEach {
    timeout.set(Duration.ofMinutes(40))
    doFirst {
        val result = fabricFantasyE2eResultFile.get().asFile
        result.parentFile.mkdirs()
        result.delete()
    }
}

val automodeFabricE2e by tasks.registering {
    group = "verification"
    description = "Runs automode in a separate Fabric client/integrated-server process and verifies its JSON result."
    dependsOn("runAutomodeE2eClient")
    doLast {
        val result = fabricE2eResultFile.get().asFile
        check(result.isFile) { "Fabric automode E2E did not write ${result.absolutePath}" }
        val evidence = result.readText()
        check("\"status\":\"PASS\"" in evidence) { "Fabric automode E2E failed: $evidence" }
        println("[automode-fabric-e2e] $evidence")
    }
}

val automodeFantasyFabricE2e by tasks.registering {
    group = "verification"
    description = "Runs the full Fantasy schematic through automode in a separate Fabric client/integrated-server process."
    dependsOn("runAutomodeFantasyE2eClient")
    doLast {
        val result = fabricFantasyE2eResultFile.get().asFile
        check(result.isFile) { "Fabric Fantasy automode E2E did not write ${result.absolutePath}" }
        val evidence = result.readText()
        check("\"status\":\"PASS\"" in evidence) { "Fabric Fantasy automode E2E failed: $evidence" }
        println("[automode-fabric-fantasy-e2e] $evidence")
    }
}
