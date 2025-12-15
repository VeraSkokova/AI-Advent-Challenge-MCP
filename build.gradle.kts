plugins {
    kotlin("jvm") version "2.2.20"
    id("application")
}

group = "ru.skokova.aiadventchallenge"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Client
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")

    // Логирование Ktor (опционально)
    implementation("io.ktor:ktor-client-logging:2.3.7")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ru.skokova.aiadventchallenge.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}