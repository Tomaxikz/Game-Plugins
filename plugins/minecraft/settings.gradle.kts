rootProject.name = "playermanager-bridge-minecraft"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "neoforged" }
        maven("https://maven.minecraftforge.net/") { name = "minecraftforge" }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigot" }
    }
}

// Worker A (this change set)
include("core")
include("bukkit")
include("universal")

// ---- Placeholders for modules added by other workers (uncomment when the directory exists) ----
// Worker C: proxies
include("proxy")
include("velocity")
include("bungeecord")
// Worker B: mod loaders (build against :core; `common` holds shared mod-loader glue)
include("common")
include("fabric")
include("fabric:v26")
include("neoforge")
include("neoforge:v26")
include("forge")
include("forge:v26")
