plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.spongepowered.gradle.vanilla") version "0.2.1"
}

val coroutinesVersion: String by project
val serializationVersion: String by project

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

// Tests in src/test run from :forge on the Forge classpath; some depend on Forge-patched Minecraft behavior.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:${serializationVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${serializationVersion}")
}

// src/test is compiled and run by :forge, so this module has no test sources of its own.
kotlin.sourceSets.named("test") {
    kotlin.setSrcDirs(emptyList<File>())
    resources.setSrcDirs(emptyList<File>())
}

tasks.compileKotlin {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xexplicit-api=warning", "-Xjvm-default=all")
    }
}
