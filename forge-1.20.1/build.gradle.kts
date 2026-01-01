plugins {
    id("java")
    alias(libs.plugins.modDevGradle)
}

val mod_id: String by project
val mod_name: String by project
val mod_version: String by project

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

legacyForge {
    version = libs.versions.minecraftForge.get()

    parchment {
        minecraftVersion = libs.versions.minecraft.get()
        mappingsVersion = libs.versions.parchment.get()
    }

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    mods {
        create(mod_id) {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":core").sourceSets.main.get())
        }
    }
}

repositories {
    maven {
        name = "GTCEu Maven"
        url = uri("https://maven.gtceu.com")
    }
    maven {
        name = "LDLib / lowdragmc"
        url = uri("https://maven.firstdark.dev/releases")
    }
    maven {
        name = "FirstDark Snapshots"
        url = uri("https://maven.firstdark.dev/snapshots")
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
        name = "ModMaven"
        url = uri("https://modmaven.dev")
    }
    maven {
        name = "Shedaniel"
        url = uri("https://maven.shedaniel.me/")
    }
    maven {
        name = "Cursemaven"
        url = uri("https://cursemaven.com")
    }
}

dependencies {
    implementation(project(":core"))

    // GTCEu Modern - now compatible in dev with Parchment mappings
    compileOnly("com.gregtechceu.gtceu:gtceu-${libs.versions.minecraft.get()}:${libs.versions.gtceu.get()}")

    // EMI
    compileOnly("dev.emi:emi-forge:${libs.versions.emi.get()}+${libs.versions.minecraft.get()}")

    // JEI
    compileOnly("mezz.jei:jei-${libs.versions.minecraft.get()}-forge:${libs.versions.jei.get()}")

    // WebP support for animated icon export
    implementation("org.sejda.imageio:webp-imageio:${libs.versions.webpImageio.get()}")
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

    // Set the archive base name
    archiveBaseName.set("recipeflow")
}
