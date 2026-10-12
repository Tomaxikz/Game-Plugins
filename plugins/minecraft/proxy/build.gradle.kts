plugins {
    `java-library`
}

description = "Proxy-independent platform shared by the Velocity and BungeeCord bridges (ProxyPlatform, ProxyHost). Java 8 bytecode, no proxy API."

dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<JavaCompile>("compileTestJava") { options.release.set(17) }
