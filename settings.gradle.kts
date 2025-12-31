pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "recipeflow-mod"

include("core")
include("forge-1.20.1")
