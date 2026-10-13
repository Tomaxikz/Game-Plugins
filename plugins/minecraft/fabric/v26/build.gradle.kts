plugins {
    id("net.fabricmc.fabric-loom") version "1.18.4"
}

// Minecraft 26.x is unobfuscated: no mappings, no remapping. One jar for the whole range that was tested.
extra["pmLoader"] = "fabric"
extra["pmMcRange"] = "26.1-26.3"
extra["pmGameVersions"] = ">=26.1 <=26.3"
extra["pmJava"] = 25
apply(from = rootProject.file("common/gradle/mod-release.gradle.kts"))

val minecraftVersion = "26.3"
val loaderVersion = "0.19.5"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    "bundled"(project(":common"))
    testImplementation(libs.junit.jupiter)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

sourceSets.main {
    java.srcDirs("../../common/src/mc/java", "../../common/src/mc-26/java", "../src/main/java")
    resources.srcDirs("../src/main/resources", "../../common/src/mc-26/resources", layout.buildDirectory.dir("generated/pm-version"))
}

tasks.processResources {
    dependsOn(tasks.named("writeVersionResource"))
    val props = mapOf("version" to project.version.toString(), "mcRange" to ">=26.1 <=26.3", "javaMin" to "25")
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

@Suppress("UNCHECKED_CAST")
val pmConfigureJar = project.extra["pmConfigureJar"] as (Jar) -> Unit

tasks.jar {
    pmConfigureJar(this)
    manifest {
        attributes("Implementation-Title" to "Player Manager Bridge (Minecraft Fabric)", "Implementation-Version" to project.version)
    }
}
