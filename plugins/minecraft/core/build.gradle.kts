plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Platform-independent Player Manager bridge core (protocol, transport, subscriptions, items, assets). Java 8 bytecode, zero dependencies."

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.junixsocket.core) // exercises the optional junixsocket transport used on Java < 16
}

java {
    withSourcesJar()
}

// Tests and fixtures use Java 17 APIs (JDK Unix domain sockets) while main stays Java 8 bytecode.
// Other modules can reuse the protocol client and the in-memory platform:
//   testImplementation(testFixtures(project(":core")))
tasks.named<JavaCompile>("compileTestJava") { options.release.set(17) }
tasks.named<JavaCompile>("compileTestFixturesJava") { options.release.set(17) }
