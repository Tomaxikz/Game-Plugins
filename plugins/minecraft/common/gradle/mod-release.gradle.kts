// Applied by every mod-loader variant project (fabric/v26, neoforge/..., forge/...).
//
// Expects these project extra properties (set before `apply(from = ...)`):
//   pmLoader        fabric | neoforge | forge
//   pmMcRange       release-name range, e.g. "26.1-26.3" or "26.3"
//   pmGameVersions  manifest semver range, e.g. ">=26.1 <=26.3"
//   pmJava          minimum Java major, e.g. 25
//   pmDependencies  JSON array text for the sidecar, default "[]"
//
// Provides: configuration `bundled` (classes merged into the mod jar), `pmJarName`, task `writeReleaseMeta` (finalizes the jar).
import java.io.File

val pmLoader = extra["pmLoader"] as String
val pmMcRange = extra["pmMcRange"] as String
val pmGameVersions = extra["pmGameVersions"] as String
val pmJava = extra["pmJava"] as Int
val pmDependencies = if (extra.has("pmDependencies")) extra["pmDependencies"] as String else "[]"

val releaseDir = rootProject.layout.buildDirectory.dir("release")
val jarBaseName = "playermanager-bridge-minecraft-$pmLoader-$pmMcRange-${project.version}"
extra["pmJarName"] = "$jarBaseName.jar"
extra["pmReleaseDir"] = releaseDir

val bundled = configurations.maybeCreate("bundled")
bundled.isCanBeConsumed = false
bundled.isCanBeResolved = true
configurations.named("implementation") { extendsFrom(bundled) }

// Version baked into the jar (read at runtime as /playermanager-bridge-version.txt unless BRIDGE_VERSION is set)
val versionResource = tasks.register("writeVersionResource") {
    val out = layout.buildDirectory.file("generated/pm-version/playermanager-bridge-version.txt")
    inputs.property("version", project.version.toString())
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs(); writeText(project.version.toString() + "\n") } }
}

val writeMeta = tasks.register("writeReleaseMeta") {
    val out = releaseDir.map { it.file("$jarBaseName.jar.meta.json") }
    inputs.property("loader", pmLoader)
    inputs.property("versions", pmGameVersions)
    inputs.property("java", pmJava)
    inputs.property("deps", pmDependencies)
    inputs.property("version", project.version.toString())
    outputs.file(out)
    doLast {
        val json = """{
  "game": "minecraft_java",
  "platforms": ["$pmLoader"],
  "game_versions": "$pmGameVersions",
  "java": ">=$pmJava",
  "dependencies": $pmDependencies,
  "bridge_version": "${project.version}"
}
"""
        out.get().asFile.apply { parentFile.mkdirs(); writeText(json) }
    }
}
extra["pmWriteMeta"] = writeMeta
extra["pmVersionResource"] = versionResource

// Call from the variant's `tasks.jar { }` (or remapJar input): names the jar, merges the bundled classes, writes the sidecar.
extra["pmConfigureJar"] = { jar: Jar ->
    jar.archiveFileName.set("$jarBaseName.jar")
    jar.destinationDirectory.set(releaseDir)
    jar.dependsOn(bundled)
    jar.from(bundled.elements.map { files -> files.map { f -> if (f.asFile.isDirectory) f.asFile else zipTree(f.asFile) } }) {
        exclude("META-INF/MANIFEST.MF", "module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    jar.finalizedBy(writeMeta)
}
