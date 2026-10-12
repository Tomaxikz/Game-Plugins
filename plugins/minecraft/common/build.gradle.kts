plugins {
    `java-library`
}

description = "Shared code of the Fabric / NeoForge / Forge bridges. src/main = pure Java (no Minecraft classes, unit tested here); " +
    "src/mc and src/mc-* = Minecraft-touching sources that every loader variant compiles against its own Minecraft jar (see NOTES-section.md)."

dependencies {
    api(project(":core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
