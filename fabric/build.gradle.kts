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

loom {
    accessWidenerPath.set(rootProject.file("core/src/main/resources/nuchematica.accesswidener"))
    mixin {
        defaultRefmapName.set("nuchematica.refmap.json")
    }
    runs {
        named("client") {
            runDir("../runs/fabric")
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
