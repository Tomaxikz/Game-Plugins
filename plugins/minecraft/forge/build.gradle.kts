plugins { base }

description = "Forge bridge (server side only, vanilla clients can join). Shared sources live in src/; one ForgeGradle project per Minecraft generation (v26, ...)."

// `./gradlew :forge:build` builds every Forge variant jar into build/release/.
tasks.named("build") {
    subprojects.forEach { dependsOn("${it.path}:build") }
}
