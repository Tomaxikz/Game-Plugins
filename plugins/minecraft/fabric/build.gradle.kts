plugins { base }

description = "Fabric bridge (server side only, no Fabric API). Shared sources live in src/; one Loom project per Minecraft generation (v26, ...)."

// `./gradlew :fabric:build` builds every Fabric variant jar into build/release/.
tasks.named("build") {
    subprojects.forEach { dependsOn("${it.path}:build") }
}
