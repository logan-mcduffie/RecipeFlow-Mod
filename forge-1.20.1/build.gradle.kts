plugins {
    id("net.minecraftforge.gradle") version "6.0.+"
    id("org.spongepowered.mixin") version "0.7.+"
}

val minecraft_version: String by project
val forge_version: String by project
val mapping_channel: String by project
val mapping_version: String by project
val mod_id: String by project
val mod_name: String by project
val mod_version: String by project

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

minecraft {
    mappings(mapping_channel, mapping_version)

    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")

            mods {
                create(mod_id) {
                    source(sourceSets.main.get())
                    source(project(":core").sourceSets.main.get())
                }
            }
        }

        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")

            mods {
                create(mod_id) {
                    source(sourceSets.main.get())
                    source(project(":core").sourceSets.main.get())
                }
            }
        }
    }
}

repositories {
    maven {
        name = "GTCEu Maven"
        url = uri("https://maven.gtceu.com")
    }
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    maven {
        name = "TerraformersMC"
        url = uri("https://maven.terraformersmc.com/releases/")
    }
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
    }
    maven {
        name = "Jared's Maven"
        url = uri("https://maven.blamejared.com/")
    }
    maven {
        name = "tterrag maven"
        url = uri("https://maven.tterrag.com/")
    }
    maven {
        name = "FirstDark Snapshots"
        url = uri("https://maven.firstdark.dev/snapshots")
    }
    maven {
        name = "ModMaven"
        url = uri("https://modmaven.dev")
    }
    maven {
        name = "Cursemaven"
        url = uri("https://cursemaven.com")
    }
    maven {
        name = "Shedaniel"
        url = uri("https://maven.shedaniel.me/")
    }
}

mixin {
    add(sourceSets.main.get(), "recipeflow.refmap.json")
    config("recipeflow.mixins.json")
}

dependencies {
    minecraft("net.minecraftforge:forge:${minecraft_version}-${forge_version}")

    implementation(project(":core"))

    // Mixin annotation processor
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")

    // GTCEu Modern - compileOnly (can't run in dev due to SRG mapping conflicts)
    compileOnly("com.gregtechceu.gtceu:gtceu-${minecraft_version}:${property("gtceu_version")}")

    // EMI - compileOnly
    compileOnly("dev.emi:emi-forge:${property("emi_version")}+${minecraft_version}")

    // JEI - compileOnly (can't run in dev due to SRG mapping conflicts)
    compileOnly("mezz.jei:jei-${minecraft_version}-forge:15.20.0.121")

    // WebP support for animated icon export
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes(
            "Specification-Title" to mod_name,
            "Specification-Vendor" to "RecipeFlow",
            "Specification-Version" to "1",
            "Implementation-Title" to mod_name,
            "Implementation-Version" to mod_version,
            "Implementation-Vendor" to "RecipeFlow"
        )
    }
    // Include core classes in the jar
    from(project(":core").sourceSets.main.get().output)

    // Set the archive base name to something more descriptive
    archiveBaseName.set("recipeflow")
}

// reobfJar modifies the jar in libs/ in place, so after build,
// build/libs/recipeflow-1.0.0.jar IS the reobfuscated production jar
tasks.named("build") {
    dependsOn("reobfJar")
}

// Disable jarJar - we don't need it
tasks.named<net.minecraftforge.gradle.userdev.tasks.JarJar>("jarJar") {
    enabled = false
}
