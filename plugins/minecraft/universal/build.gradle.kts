import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

description = "ONE jar for Paper, Spigot, Folia, Velocity and BungeeCord (releases.md section 3)."

// Proxy modules are added by worker C: when :velocity / :bungeecord exist in settings.gradle.kts they are shaded in
// automatically (their plugin descriptors bungee.yml / velocity-plugin.json come from their own resources).
val proxyModules = listOf("velocity", "bungeecord").mapNotNull { findProject(":$it") }

dependencies {
    implementation(project(":bukkit"))
    proxyModules.forEach { implementation(it) }
}

val platformList = buildList {
    addAll(listOf("paper", "spigot", "folia"))
    if (findProject(":velocity") != null) add("velocity")
    if (findProject(":bungeecord") != null) add("bungeecord")
}

val releaseDir = rootProject.layout.buildDirectory.dir("release")
val jarBaseName = "playermanager-bridge-minecraft-universal-${project.version}"

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("playermanager-bridge-minecraft-universal")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    destinationDirectory.set(releaseDir)
    mergeServiceFiles()
    // Relocate every third-party library that is bundled; our own packages keep their names. Nothing third-party is
    // bundled today (core is dependency free). junixsocket is deliberately NOT bundled: relocating it breaks its JNI
    // symbol names (UnsatisfiedLinkError, verified), so Java < 16 servers use the TCP + token fallback instead.
    exclude("META-INF/versions/*/module-info.class", "module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes(
            "Implementation-Title" to "Player Manager Bridge (Minecraft universal)",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.named<Jar>("jar") { enabled = false }

// Sidecar read by the release manifest generator: <jar>.meta.json
val writeMeta = tasks.register("writeReleaseMeta") {
    val out = releaseDir.map { it.file("$jarBaseName.jar.meta.json") }
    val platforms = platformList
    inputs.property("platforms", platforms)
    inputs.property("version", project.version.toString())
    outputs.file(out)
    doLast {
        val json = """{
  "game": "minecraft_java",
  "platforms": [${platforms.joinToString(", ") { "\"$it\"" }}],
  "game_versions": ">=1.13",
  "java": ">=8",
  "bridge_version": "${project.version}"
}
"""
        out.get().asFile.apply { parentFile.mkdirs(); writeText(json) }
    }
}

tasks.named("shadowJar") { finalizedBy(writeMeta) }
tasks.named("assemble") { dependsOn("shadowJar") }
