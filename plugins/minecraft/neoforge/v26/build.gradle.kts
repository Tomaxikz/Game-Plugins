plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

// NeoForge 26.x runs with the unobfuscated Mojang names. One jar for the whole tested range.
extra["pmLoader"] = "neoforge"
extra["pmMcRange"] = "26.1-26.3"
extra["pmGameVersions"] = ">=26.1 <=26.3"
extra["pmJava"] = 25
apply(from = rootProject.file("common/gradle/mod-release.gradle.kts"))

val neoVersion = "26.3.0.76"

neoForge {
    version = neoVersion
}

dependencies {
    "bundled"(project(":common"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

sourceSets.main {
    java.srcDirs("../../common/src/mc/java", "../../common/src/mc-26/java", "../src/main/java", "src/main/java")
    resources.srcDirs("../src/main/resources", "../../common/src/mc-26/resources", layout.buildDirectory.dir("generated/pm-version"))
}

tasks.processResources {
    dependsOn(tasks.named("writeVersionResource"))
    val props = mapOf("version" to project.version.toString(), "mcRange" to "[26.1,26.4)", "neoMin" to "26.1.0.0")
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

@Suppress("UNCHECKED_CAST")
val pmConfigureJar = project.extra["pmConfigureJar"] as (Jar) -> Unit

tasks.jar {
    pmConfigureJar(this)
    manifest {
        attributes("Implementation-Title" to "Player Manager Bridge (Minecraft NeoForge)", "Implementation-Version" to project.version)
    }
}
