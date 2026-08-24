import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.Test
import java.time.Duration

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.minecraftforge.gradle") version "5.1.+"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    `maven-publish`
    eclipse
    idea
}

val mcVersion: String by project
val forgeVersion: String by project
val kotlinVersion: String by project
val coroutinesVersion: String by project
val serializationVersion: String by project
val mockkVersion: String by project
val forgeE2eSourceSet = sourceSets.create("forgeE2e")
val forgeE2eResultFile = layout.buildDirectory.file("reports/automode-forge-e2e/result.json")
val forgeE2eWorldName = "nuchematica-e2e-${System.currentTimeMillis()}"
val forgeFantasyE2eResultFile = layout.buildDirectory.file("reports/automode-forge-fantasy-e2e/result.json")
val forgeFantasyE2eWorldName = providers.gradleProperty("automodeFantasyE2eWorld")
    .getOrElse("nuchematica-fantasy-e2e-${System.currentTimeMillis()}")
val forgeFantasyE2eResume = providers.gradleProperty("automodeFantasyE2eResume").getOrElse("false")

forgeE2eSourceSet.compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
forgeE2eSourceSet.runtimeClasspath += forgeE2eSourceSet.output + sourceSets.main.get().runtimeClasspath

project.group = "com.nubasu.nuchematica"
project.version = "1.0-SNAPSHOT"
val archivesBaseName = "nuchematica"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}
jarJar.enable()

minecraft {
    accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))
    mappings("official", mcVersion)
    runs {
        runs {
            val clientRun = create("client") {
                workingDirectory(project.file("run"))

                workingDirectory(project.file("run"))
                args("--noCoreSearch")

                property("forge.logging.markers", "SCAN,LOADING,CORE")
                property("forge.logging.console.level", "debug")
                property("legacy.debugClassLoading", "true")

                mods {
                    create("nuchematica") {
                        source(sourceSets.main.get())
                    }
                }
            }
            val automodeE2eRun = create("automodeE2eClient") {
                client(true)
                forceExit(false)
                workingDirectory(project.layout.buildDirectory.dir("forge-e2e/run").get().asFile)
                args("--width", "854", "--height", "480")

                property("forge.logging.console.level", "info")
                property("nuchematica.e2e.enabled", "true")
                property("nuchematica.e2e.result", forgeE2eResultFile.get().asFile.absolutePath)
                property("nuchematica.e2e.world", forgeE2eWorldName)

                mods {
                    create("nuchematica") {
                        source(sourceSets.main.get())
                        source(forgeE2eSourceSet)
                    }
                }
            }
            val automodeFantasyE2eRun = create("automodeFantasyE2eClient") {
                client(true)
                forceExit(false)
                workingDirectory(project.layout.buildDirectory.dir("forge-fantasy-e2e/run").get().asFile)
                args("--width", "854", "--height", "480")

                property("forge.logging.console.level", "info")
                property("nuchematica.e2e.enabled", "true")
                property("nuchematica.e2e.scenario", "fantasy")
                property("nuchematica.e2e.fixture", project.file("src/test/resources/test_schematic/Fantasy_BigHouse1.schematic").absolutePath)
                property("nuchematica.e2e.result", forgeFantasyE2eResultFile.get().asFile.absolutePath)
                property("nuchematica.e2e.world", forgeFantasyE2eWorldName)
                property("nuchematica.e2e.resume", forgeFantasyE2eResume)

                mods {
                    create("nuchematica") {
                        source(sourceSets.main.get())
                        source(forgeE2eSourceSet)
                    }
                }
            }
            project.afterEvaluate {
                // ForgeGradle adds the launch target in its afterEvaluate callback.
                automodeE2eRun.merge(clientRun, false)
                automodeFantasyE2eRun.merge(clientRun, false)
            }
        }
    }
}

configurations {
    runtimeElements {
        setExtendsFrom(emptySet())
    }
    api {
        minecraftLibrary.get().extendsFrom(this)
        minecraftLibrary.get().exclude("org.jetbrains", "annotations")
    }
}

val shade by configurations.creating
configurations.implementation.get().extendsFrom(shade)

repositories {
    maven("https://maven.enginehub.org/repo/")
}
dependencies {
    minecraft("net.minecraftforge:forge:1.18.2-40.3.0")

    shade("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    shade("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    shade("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlin.coreLibrariesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutinesVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-serialization-core:${serializationVersion}")
    shade("org.jetbrains.kotlinx:kotlinx-serialization-json:${serializationVersion}")

    minecraftLibrary("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    minecraftLibrary("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    minecraftLibrary("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlin.coreLibrariesVersion}")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutinesVersion}")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-serialization-core:${serializationVersion}")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-serialization-json:${serializationVersion}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testImplementation("io.mockk:mockk:${mockkVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")

}

tasks.test {
    useJUnitPlatform {
        excludeTags("automode-full")
    }
    // Minecraft and MockK initialization exceed Gradle's default 512 MiB worker heap.
    maxHeapSize = "2g"
}

val processResources by tasks.getting(Copy::class) {
    inputs.property("version", project.version)

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    from(sourceSets.main.get().resources.srcDirs) {
        include("mcmod.info")
        expand(mapOf(
            "version" to project.version,
            "mcversion" to "1.12.2"
        ))
    }

    from(sourceSets.main.get().resources.srcDirs) {
        exclude("mcmod.info")
    }
}

// prepareRuns expects resources beside Kotlin's compiled classes.
val copyResourceToClasses by tasks.creating(Copy::class) {
    tasks.classes.get().dependsOn(this)
    dependsOn(tasks.processResources)
    onlyIf { gradle.taskGraph.hasTask(tasks.getByName("prepareRuns")) }

    into("$buildDir/classes/kotlin/main")
    from(tasks.processResources.get().destinationDir)
}

val jar by tasks.getting(Jar::class) {
    afterEvaluate {
        shade.forEach { dep ->
            from(project.zipTree(dep)) {
                exclude("META-INF", "META-INF/**")
                exclude("LICENSE.txt")
            }
            from(project.zipTree(dep)) {
                include("META-INF/services/**")
            }
        }
    }

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    manifest {
        attributes(
            "Specification-Title" to "nuchematica",
            "Specification-Vendor" to "Forge",
            "Specification-Version" to "1",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "nubasu.com",
            "Automatic-Module-Name" to "com.nubasu.nuchematica",
        )
    }
}
tasks.jar.get().finalizedBy("reobfJar")

val shadowModJar by tasks.creating(ShadowJar::class) {
    dependsOn("reobfJar")

    val basePkg = "com.nubasu.nuchematica.libs"
    relocate("kotlin.", "$basePkg.kotlin.")
    relocate("kotlinx.", "$basePkg.kotlinx.")
    relocate("io.sigpipe.jbsdiff.", "$basePkg.jbsdiff.")
    relocate("org.intellij.lang.annotations.", "$basePkg.ij_annotations.")
    relocate("org.jetbrains.annotations.", "$basePkg.jb_annotations.")
    relocate("org.apache.commons.compress.", "$basePkg.commons_compress.")

    from(provider { zipTree(tasks.jar.get().archiveFile) })
    from(fileTree("src/main/distResources")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    destinationDirectory.set(buildDir.resolve("shadowing"))
    archiveVersion.set("")
    manifest.from(provider {
        zipTree(tasks.jar.get().archiveFile)
            .matching { include("META-INF/MANIFEST.MF") }
            .files.first()
    })
}

val copyShadowedJar by tasks.creating {
    dependsOn(shadowModJar)
    doLast {
        shadowModJar.archiveFile.get().asFile.inputStream().use { src ->
            tasks.jar.get().archiveFile.get().asFile.apply { parentFile.mkdirs() }
                .outputStream()
                .use { dst -> src.copyTo(dst) }
        }
    }
}

tasks.assemble.get().dependsOn(copyShadowedJar)


tasks.compileKotlin {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xexplicit-api=warning", "-Xjvm-default=all")
    }
}

val makeSourceDir by tasks.creating {
    doLast {
        buildDir.resolve("sources/main/java").mkdirs()
    }
}
tasks.compileJava.get().dependsOn(makeSourceDir)

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileForgeE2eKotlin") {
    dependsOn(tasks.compileKotlin)
    friendPaths.from(sourceSets.main.get().output.classesDirs)
}

val automodeSimulation by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs deterministic production-core automode simulations and writes JSON traces/reports."
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("automode-full")
    }
    maxHeapSize = "2g"
}

tasks.matching { task -> task.name == "runAutomodeE2eClient" }.configureEach {
    timeout.set(Duration.ofMinutes(8))
    doFirst {
        val result = forgeE2eResultFile.get().asFile
        result.parentFile.mkdirs()
        result.delete()
    }
}

tasks.matching { task -> task.name == "runAutomodeFantasyE2eClient" }.configureEach {
    timeout.set(Duration.ofMinutes(40))
    doFirst {
        val result = forgeFantasyE2eResultFile.get().asFile
        result.parentFile.mkdirs()
        result.delete()
    }
}

val automodeForgeE2e by tasks.registering {
    group = "verification"
    description = "Runs automode in a separate Forge client/integrated-server process and verifies its JSON result."
    dependsOn("runAutomodeE2eClient")
    doLast {
        val result = forgeE2eResultFile.get().asFile
        check(result.isFile) { "Forge automode E2E did not write ${result.absolutePath}" }
        val evidence = result.readText()
        check("\"status\":\"PASS\"" in evidence) { "Forge automode E2E failed: $evidence" }
        println("[automode-forge-e2e] $evidence")
    }
}

val automodeFantasyForgeE2e by tasks.registering {
    group = "verification"
    description = "Runs the full Fantasy schematic through automode in a separate Forge client/integrated-server process."
    dependsOn("runAutomodeFantasyE2eClient")
    doLast {
        val result = forgeFantasyE2eResultFile.get().asFile
        check(result.isFile) { "Forge Fantasy automode E2E did not write ${result.absolutePath}" }
        val evidence = result.readText()
        check("\"status\":\"PASS\"" in evidence) { "Forge Fantasy automode E2E failed: $evidence" }
        println("[automode-forge-fantasy-e2e] $evidence")
    }
}
