import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.LocalDateTime

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.minecraftforge.gradle") version "5.1.+"

    `maven-publish`
    eclipse
    idea
}

val mcVersion: String by project
val forgeVersion: String by project
val kotlinVersion: String by project
val coroutinesVersion: String by project
val serializationVersion: String by project

val group = "com.nubasu.nuchematica"
val version = "1.0-SNAPSHOT"
val archivesBaseName = "nuchematica"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}
jarJar.enable()

minecraft {
    mappings("official", mcVersion)
//    copyIdeResources.set(true)

    runs {
        runs {
            create("client") {
                workingDirectory(project.file("run"))

//                ideaModule = "${project.parent!!.name}.${project.name}.test"

                property("forge.logging.markers", "SCAN,LOADING,CORE")
                property("forge.logging.console.level", "debug")

                mods {
                    create("nuchematica") {
                        source(sourceSets.main.get())
                    }
                }
            }


            create("server") {
                workingDirectory(project.file("run/server"))

//                ideaModule = "${project.parent!!.name}.${project.name}.test"

                property("forge.logging.markers", "SCAN,LOADING,CORE")
                property("forge.logging.console.level", "debug")

                mods {
                    create("nuchematica") {
                        source(sourceSets.main.get())
                    }
                }
            }

            create("gameTestServer") {
                workingDirectory(project.file("run/server"))

//                ideaModule = "${project.parent!!.name}.${project.name}.test"

                property("forge.logging.markers", "SCAN,LOADING,CORE")
                property("forge.logging.console.level", "warn")
                property("forge.enabledGameTestNamespaces", "kfflibtest")

                mods {
                    create("nuchematica") {
                        source(sourceSets.main.get())
                    }
                }

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
repositories {
    maven("https://maven.enginehub.org/repo/")
}
dependencies {
    minecraft("net.minecraftforge:forge:1.19.4-45.1.0")

    api("org.jetbrains.kotlin:kotlin-reflect:${kotlin.coreLibrariesVersion}")
    api("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
    api("org.jetbrains.kotlin:kotlin-stdlib-common:${kotlin.coreLibrariesVersion}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${coroutinesVersion}")
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:${serializationVersion}")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:${serializationVersion}")

}

tasks {
    withType<Jar> {
        manifest {
            attributes(
                "Specification-Title" to "nuchematica",
                "Specification-Vendor" to "Forge",
                "Specification-Version" to "1",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "nubasu.com",
                "Implementation-Timestamp" to LocalDateTime.now(),
                "Automatic-Module-Name" to "com.nubasu.nuchematica",
            )
        }
    }

    withType<KotlinCompile> {
        kotlinOptions.freeCompilerArgs = listOf("-Xexplicit-api=warning", "-Xjvm-default=all")
    }
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
