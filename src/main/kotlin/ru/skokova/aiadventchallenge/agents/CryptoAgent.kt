package ru.skokova.aiadventchallenge.agents

import ru.skokova.aiadventchallenge.api.Message
import ru.skokova.aiadventchallenge.api.YandexGptClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.skokova.aiadventchallenge.utils.getEnvOrProperty
import ru.skokova.aiadventchallenge.utils.loadProperties
import java.io.File

fun main(): Unit = runBlocking {
    try {
        println("🕵️ Crypto Agent Starting (Stdio Mode)...")

        // 1. Загрузка конфига
        val props = loadProperties()
        val yandexApiKey = getEnvOrProperty("YANDEX_GPT_API_KEY", props)
        val folderId = getEnvOrProperty("YANDEX_GPT_FOLDER_ID", props)

        // 2. Проверка JAR файла
        val jarPath = "build/libs/ai-advent-mcp-1.0-SNAPSHOT-all.jar"
        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            throw RuntimeException("Server JAR not found at ${jarFile.absolutePath}. Run './gradlew shadowJar' first.")
        }

        // 3. Запуск Сервера (как подпроцесс)
        val javaCmd = System.getProperty("java.home") + "/bin/java"
        val command = listOf(javaCmd, "-jar", jarFile.absolutePath)

        println("🚀 Launching Server...")
        val pb = ProcessBuilder(command)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT) // Ошибки сервера видим в консоли
        // pb.redirectOutput НЕ ТРОГАЕМ (через него идет JSON-RPC)

        val process = pb.start()

        try {
            // 4. Подключение по Stdio
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered()
            )

            val mcpClient = Client(
                clientInfo = Implementation(name = "CryptoAgent", version = "1.0")
            )

            println("🔄 Connecting to MCP...")
            mcpClient.connect(transport)
            println("✅ Connected!")

            // 5. Получение списка инструментов
            val tools = mcpClient.listTools()
            println("🛠 Available Tools: ${tools.tools.joinToString { it.name }}")

            // 6. Подготовка YandexGPT
            val toolsDescription = tools.tools.joinToString("\n") { tool ->
                "- ${tool.name}: ${tool.description}"
            }

            val systemPrompt = """
            Ты — крипто-ассистент. Инструменты:
            $toolsDescription
            
            ЕСЛИ нужен инструмент: верни JSON {"tool": "name", "asset": "value"}.
            В поле "asset" используй полное название на английском (например: bitcoin, ethereum), а не тикер (BTC).
            ИНАЧЕ: отвечай текстом.
        """.trimIndent()

            val gptClient = YandexGptClient(yandexApiKey, folderId)
            val history = mutableListOf(Message("system", systemPrompt))

            // 7. Чат-цикл
            println("\n💬 Введите запрос (например: 'Цена биткоина?'):")
            val userQuery = readlnOrNull() ?: return@runBlocking

            history.add(Message("user", userQuery))

            println("🤖 Думаю...")
            val llmResponse = gptClient.chat(history, jsonObject = true)
            println("LLM Raw: $llmResponse")
            System.out.flush()

            val jsonStartIndex = llmResponse.indexOf('{')
            System.out.flush()
            val jsonEndIndex = llmResponse.lastIndexOf('}')
            System.out.flush()

            if (jsonStartIndex != -1 && jsonEndIndex != -1 && jsonEndIndex > jsonStartIndex) {
                val potentialJson = llmResponse.substring(jsonStartIndex, jsonEndIndex + 1)

                // 8. Обработка ответа (Tool Call)
                if (potentialJson.contains("get_crypto_price")) {
                    println("⚙️ Calling Tool...")

                    try {
                        // Парсим JSON от LLM
                        val json = Json.parseToJsonElement(potentialJson) as JsonObject
                        val asset = json["asset"]?.jsonPrimitive?.content?.lowercase() ?: "bitcoin"

                        // Вызов MCP
                        val args = buildJsonObject { put("asset", asset) }
                        val result = mcpClient.callTool(
                            CallToolRequest(
                                name = "get_crypto_price",
                                arguments = args
                            )
                        )

                        // Извлекаем результат
                        // В SDK 0.7.4 CallToolResult.content может быть List<ToolContent>
                        val contentList = (result as? io.modelcontextprotocol.kotlin.sdk.CallToolResult)?.content
                            ?: emptyList()

                        val toolOutput = contentList.firstOrNull()?.let {
                            (it as? TextContent)?.text
                        } ?: "No data"

                        println("🔧 Tool Output: $toolOutput")

                        // Финальный ответ LLM
                        //history.add(Message("assistant", llmResponse))
                        history.add(Message("assistant", "Мне нужно использовать инструмент get_crypto_price, чтобы узнать цену $asset."))
                        //history.add(Message("user", "Tool result: $toolOutput. Final answer?"))
                        history.add(Message("user", """
                            SYSTEM: Инструмент вернул данные: "$toolOutput".
                            Задача: Теперь ответь пользователю на его вопрос, используя эти данные. Пиши просто текст.
                        """.trimIndent()))

                        val finalRes = gptClient.chat(history, jsonObject = false)
                        println("\n🤖 Ответ: $finalRes")


                    } catch (e: Exception) {
                        println("❌ Tool Error: ${e.message}")
                    }
                } else {
                    println("\n🤖 Ответ: $llmResponse")
                }
            } else {
                println("JSON НЕ НАЙДЕН! start=$jsonStartIndex, end=$jsonEndIndex")
                println("Raw output: $llmResponse")
            }

        } catch (e: Exception) {
            println("❌ Agent Error: ${e.message}")
            e.printStackTrace()
        } finally {
            process.destroy()
            println("👋 Server stopped")
        }
    } catch (e: Exception) {
        println("Error")
        e.printStackTrace()
    }
}