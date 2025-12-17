plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("application")
}

group = "ru.skokova.aiadventchallenge"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.4")
    
    // Ktor Client для Yandex GPT API
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-client-logging:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    
    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Cron Utils для парсинга cron выражений
    implementation("com.cronutils:cron-utils:9.2.1")
    
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ru.skokova.aiadventchallenge.day13.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}