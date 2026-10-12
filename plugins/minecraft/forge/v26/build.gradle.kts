plugins {
    `java-library`
    id("net.minecraftforge.gradle") version "[7.0.17,8)"
}

// Forge 26.x runs with the unobfuscated Mojang names. One jar for the whole tested range.
extra["pmLoader"] = "forge"
extra["pmMcRange"] = "26.1-26.3"
extra["pmGameVersions"] = ">=26.1 <=26.3"
extra["pmJava"] = 25
apply(from = rootProject.file("common/gradle/mod-release.gradle.kts"))

val forgeVersion = "26.3-66.0.10"

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$forgeVersion"))
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
    resources.srcDirs("../src/main/resources", layout.buildDirectory.dir("generated/pm-version"))
}

tasks.processResources {
    dependsOn(tasks.named("writeVersionResource"))
    val props = mapOf("version" to project.version.toString(), "mcRange" to "[26.1,26.4)", "forgeMin" to "66")
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") { expand(props) }
}

@Suppress("UNCHECKED_CAST")
val pmConfigureJar = project.extra["pmConfigureJar"] as (Jar) -> Unit

tasks.jar {
    pmConfigureJar(this)
    manifest {
        attributes("Implementation-Title" to "Player Manager Bridge (Minecraft Forge)", "Implementation-Version" to project.version)
    }
}
