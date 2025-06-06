import java.time.LocalDateTime
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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

project.group = "com.nubasu.nuchematica"
project.version = "1.0-SNAPSHOT"
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

val processResources by tasks.getting(Copy::class) {
    // this will ensure that this task is redone when the versions change.
    inputs.property("version", project.version)

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // replace stuff in mcmod.info, nothing else
    from(sourceSets.main.get().resources.srcDirs) {
        include("mcmod.info")

        // replace version and mcversion
        expand(mapOf(
            "version" to project.version,
            "mcversion" to "1.12.2"
        ))
    }

    // copy everything else, thats not the mcmod.info
    from(sourceSets.main.get().resources.srcDirs) {
        exclude("mcmod.info")
    }
}

// workaround for userdev bug
val copyResourceToClasses by tasks.creating(Copy::class) {
    tasks.classes.get().dependsOn(this)
    dependsOn(tasks.processResources)
    onlyIf { gradle.taskGraph.hasTask(tasks.getByName("prepareRuns")) }

    //into("$buildDir/classes/java/main")
    // if you write @Mod class in kotlin, please use code below
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
            "Implementation-Timestamp" to LocalDateTime.now(),
            "Automatic-Module-Name" to "com.nubasu.nuchematica",
        )
    }
}
tasks.jar.get().finalizedBy("reobfJar")

val shadowModJar by tasks.creating(ShadowJar::class) {
    dependsOn("reobfJar")

    val basePkg = "com.nubasu.nuchematica.libs"
    // add also in FixRtmDevEnvironmentOnlyCorePlugin
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