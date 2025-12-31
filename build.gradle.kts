plugins {
    java
    idea
}

allprojects {
    group = property("mod_group") as String
    version = property("mod_version") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        withSourcesJar()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
