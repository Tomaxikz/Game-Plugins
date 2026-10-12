plugins {
    `java-library`
}

description = "Velocity proxy bridge. Compiled against the Velocity 4.x API, which requires Java 25 (class file 69)."

// Velocity's API needs a Java 25 toolchain; the bridge code itself stays plain Java.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":proxy"))
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") { expand("version" to project.version) }
}
