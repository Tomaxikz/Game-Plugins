plugins { base }

description = "NeoForge bridge (server side only, vanilla clients can join). Shared sources live in src/; one ModDevGradle project per Minecraft generation (v26, ...)."

// `./gradlew :neoforge:build` builds every NeoForge variant jar into build/release/.
tasks.named("build") {
    subprojects.forEach { dependsOn("${it.path}:build") }
}
