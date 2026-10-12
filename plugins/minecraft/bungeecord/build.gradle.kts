plugins {
    `java-library`
}

description = "BungeeCord / Waterfall proxy bridge. Compiled against the BungeeCord 1.21 API (Java 8 bytecode)."

dependencies {
    implementation(project(":core"))
    implementation(project(":proxy"))
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("bungee.yml") { expand("version" to project.version) }
}

tasks.named<JavaCompile>("compileTestJava") { options.release.set(17) }
