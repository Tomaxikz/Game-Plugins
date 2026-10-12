plugins {
    `java-library`
}

description = "Paper / Spigot / Folia bridge. Compiled against the Spigot 1.13.2 API with --release 8; newer APIs are reached reflectively."

dependencies {
    api(project(":core"))
    compileOnly(libs.spigot.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.spigot.api)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.named<JavaCompile>("compileTestJava") { options.release.set(17) }
