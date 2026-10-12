import java.security.MessageDigest

plugins {
    java
}

val hytaleBuild: String = providers.gradleProperty("hytale_build").get()
val hytaleChannel: String = providers.gradleProperty("hytale_channel").getOrElse("release")
val bridgeVersion: String = System.getenv("BRIDGE_VERSION")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../../VERSION").readText().trim()

group = "com.playermanager"
version = bridgeVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // Hypixel Studios' public Maven repository (no login). The server artifact is the API.
    // It is a compile-time dependency only: it is never packaged into the bridge jar.
    maven("https://maven.hytale.com/$hytaleChannel") {
        content { includeGroup("com.hypixel.hytale") }
    }
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:$hytaleBuild")
    testImplementation("com.hypixel.hytale:Server:$hytaleBuild")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("manifest.json") {
        expand("version" to bridgeVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("pm.hytaleBuild", hytaleBuild)
}

val releaseDir = layout.buildDirectory.dir("release")
val artifactName = "playermanager-bridge-hytale-$hytaleBuild-$bridgeVersion.jar"

tasks.jar {
    archiveFileName.set(artifactName)
    manifest {
        attributes(
            "Implementation-Version" to bridgeVersion,
            "Specification-Version" to hytaleBuild
        )
    }
}

val releaseJar = tasks.register<Copy>("releaseJar") {
    from(tasks.jar)
    into(releaseDir)
    doLast {
        val f = releaseDir.get().file(artifactName).asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        f.resolveSibling("$artifactName.sha256")
            .writeText(digest.joinToString("") { "%02x".format(it) } + "  " + artifactName + "\n")
    }
}

tasks.build {
    dependsOn(releaseJar)
}

// Conformance harness: runs the real socket server against the fake provider (no Hytale server needed).
tasks.register<JavaExec>("runFakeBridge") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.playermanager.hytale.net.FakeServerMain"
    args(providers.gradleProperty("root").getOrElse(layout.buildDirectory.dir("fake-root").get().asFile.absolutePath))
    dependsOn(tasks.testClasses)
}
