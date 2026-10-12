plugins {
    `java-library`
}

description = "Velocity proxy bridge. Built with a Java 25 compiler (the Velocity 4.x API jar is class file 69) but emits Java 8 bytecode."

// javac 25 is needed to read the Velocity 4.x API jar (class file 69). The output must stay Java 8 bytecode:
// Paper 1.20.5+ remaps every class in a plugin jar with an ASM that rejects class file 69, so Java 25 classes in
// the universal jar would stop the whole plugin from loading on Paper (seen on Paper 1.21.4).
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    // velocity-api's Gradle metadata says JVM 25; it is compileOnly and Velocity itself runs on 25, so accept it.
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
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
