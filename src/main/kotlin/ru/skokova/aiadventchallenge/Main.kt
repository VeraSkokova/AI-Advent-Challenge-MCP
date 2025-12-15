package ru.skokova.aiadventchallenge

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered


fun main() {
    runBlocking {
        println("🎄 AI Advent Challenge - Day 11")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        // Используем полный путь к cmd, чтобы избежать проблем
        val command = if (isWindows) {
            listOf("cmd.exe", "/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", ".")
        } else {
            listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", ".")
        }

        val pb = ProcessBuilder(command)
        // ВАЖНО: Не наследуем Error поток, если боимся мусора в output,
        // но для дебага пока оставим. Если будет падать JSON parse error - убери redirectError
        //pb.redirectError(ProcessBuilder.Redirect.INHERIT)

        val process = pb.start()

        try {
            // --- РЕШЕНИЕ ПРОБЛЕМЫ С NULL ---

            // Явно проверяем, что потоки создались
            val inputStream = process.inputStream ?: throw RuntimeException("Process InputStream is null")
            val outputStream = process.outputStream ?: throw RuntimeException("Process OutputStream is null")

            // Используем явные импорты из kotlinx.io
            // Если asSource() продолжает сбоить, попробуй этот "грязный" хак:
            // Создаем анонимный RawSource вручную (это крайняя мера, но сработает)

            val source = inputStream.asSource().buffered()
            val sink = outputStream.asSink().buffered()

            val transport = StdioClientTransport(
                input = source,
                output = sink
            )

            // -------------------------------

            val client = Client(
                clientInfo = Implementation(name = "KotlinAdventClient", version = "1.0.0")
            )

            println("🔄 Connecting...")
            client.connect(transport)
            println("✅ Connected!")

            val tools = client.listTools()
            println("\n🛠 Tools found: ${tools.tools.size}")
            tools.tools.forEach { println("- ${it.name}: ${it.description ?: ""}") }

        } catch (e: Exception) {
            println("❌ ERROR: ${e}")
            e.printStackTrace()
        } finally {
            process.destroy()
        }
    }
}