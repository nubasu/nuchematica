pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }

    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

rootProject.name = "nuchematica"

include("core", "forge")
