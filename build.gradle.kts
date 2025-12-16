plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("application")
    id("com.github.johnrengelman.shadow") version "8.1.1" // Плагин Shadow
}

group = "ru.skokova.aiadventchallenge"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Client
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-cio:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-server-netty:3.0.0")
    implementation("io.ktor:ktor-server-sse:3.0.0") // Для Server-Sent Events

    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Логирование Ktor (опционально)
    implementation("io.ktor:ktor-client-logging:3.0.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ru.skokova.aiadventchallenge.mcp.server.CryptoMcpServerKt")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}