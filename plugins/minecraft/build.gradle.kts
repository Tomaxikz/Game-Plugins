plugins {
    base
    alias(libs.plugins.shadow) apply false
}

fun readVersion(): String {
    System.getenv("BRIDGE_VERSION")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    var dir: File? = rootDir
    while (dir != null) {
        val f = File(dir, "VERSION")
        if (f.isFile) return f.readText().trim()
        dir = dir.parentFile
    }
    return "0.0.0-dev"
}

allprojects {
    group = "dev.playermanager.bridge"
    version = readVersion()
}

// Conventions shared by every Java module (core, bukkit, proxies, mod-loader common code).
// Mod-loader modules that need a newer Java release override `options.release` themselves.
subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(8)
            options.compilerArgs.addAll(listOf("-Xlint:-options"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
        }
    }
}

// `./gradlew buildAll` builds every release jar into build/release/ (named per releases.md section 3).
// Worker B: when you add fabric/neoforge/forge modules, add `dependsOn(":fabric:build")` etc. below (or guard with findProject).
tasks.register("buildAll") {
    group = "build"
    description = "Builds every release jar into build/release/ (universal now; mod loaders when added)."
    dependsOn(":universal:build")
    // Loader projects may be parents (e.g. :fabric) of per-version projects (:fabric:v26); build them all.
    listOf("fabric", "neoforge", "forge").forEach { n ->
        findProject(":$n")?.let { p -> p.allprojects.forEach { sub -> dependsOn(sub.tasks.matching { it.name == "build" }) } }
    }
}
