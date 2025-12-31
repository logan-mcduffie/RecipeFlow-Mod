plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    api("com.google.code.gson:gson:${property("gson_version")}")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "RecipeFlow Core",
            "Implementation-Version" to project.version
        )
    }
}
