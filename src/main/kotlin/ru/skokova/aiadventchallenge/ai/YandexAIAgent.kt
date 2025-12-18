package ru.skokova.aiadventchallenge.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.mcp.*

data class AgentResponse(
    val summary: String,
    val toolCalls: List<ToolCall>,
    val rawResults: Map<String, Any> = emptyMap()
)

data class ToolCall(
    val toolName: String,
    val parameters: Map<String, Any>,
    val result: String
)

// Wrapper классы оставлены для совместимости, но логика парсинга теперь работает напрямую с JsonElement
@Serializable
data class ToolCallsWrapper(val tools: List<ToolCallData> = emptyList())
@Serializable
data class ToolCallData(val name: String, val params: Map<String, JsonElement>)

class YandexAIAgent(
    private val apiKey: String,
    private val folderId: String,
    private val reminderMcpServer: ReminderMCPServer,
    private val cryptoCurrencyMcpServer: CryptoCurrencyMCPServer,
    private val summarizationMcpServer: SummarizationMCPServer,
    private val filesystemClient: FilesystemMCPClient,
    private val yandexGPTClient: YandexGPTClient
) {
    private val logger = LoggerFactory.getLogger(YandexAIAgent::class.java)

    // Максимально мягкая конфигурация JSON для парсинга ответов LLM
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        prettyPrint = false
        coerceInputValues = true
    }

    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")

        val systemPrompt = buildSystemPrompt()
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()

        // Списки инструментов для маршрутизации
        val rNames = reminderMcpServer.getToolsList().map { it.name }.toSet()
        val cNames = cryptoCurrencyMcpServer.getToolsList().map { it.name }.toSet()
        val sNames = summarizationMcpServer.getToolsList().map { it.name }.toSet()
        val fNames = filesystemClient.listTools().map { it.name }.toSet()

        var currentContext = command
        var iterationCount = 0
        val maxIterations = 10

        while (iterationCount < maxIterations) {
            iterationCount++
            logger.info("📍 Iteration $iterationCount")

            val contextMessage = buildContextMessage(currentContext, executedCalls)

            // Если buildContextMessage вернул спец-сигнал об успехе, завершаем работу
            if (contextMessage == "STOP_SUCCESS") {
                logger.info("✅ Pipeline finished successfully via context check")
                return AgentResponse("Готово! Файл успешно сохранен в папку mcp-output.", executedCalls, rawResults)
            }

            val response = yandexGPTClient.chat(systemPrompt, contextMessage)
            logger.info("📝 LLM Response: ${response.take(300)}...")

            val toolCalls = parseToolCalls(response)

            // Если инструментов нет, значит LLM вернула финальный текстовый ответ
            if (toolCalls.isEmpty()) {
                logger.info("✅ LLM вернула финальный ответ")
                // Возвращаем ответ как есть, не пытаясь вырезать markdown, чтобы не сломать форматирование
                return AgentResponse(response, executedCalls, rawResults)
            }

            for (call in toolCalls) {
                logger.info("🔧 Executing tool: ${call.toolName} with params: ${call.parameters}")

                val result = try {
                    when (call.toolName) {
                        in rNames -> reminderMcpServer.executeTool(call.toolName, call.parameters)
                        in cNames -> cryptoCurrencyMcpServer.executeTool(call.toolName, call.parameters)
                        in sNames -> summarizationMcpServer.executeTool(call.toolName, call.parameters)
                        in fNames -> filesystemClient.callTool(call.toolName, call.parameters)
                        else -> "Error: Tool not found '${call.toolName}'"
                    }
                } catch (e: Exception) {
                    logger.error("Error executing ${call.toolName}", e)
                    "Error: ${e.message}"
                }

                logger.info("✅ Result: ${result.take(150)}...")

                val toolCallWithResult = call.copy(result = result)
                executedCalls.add(toolCallWithResult)
                rawResults[call.toolName] = result
                currentContext = result
            }
        }

        return AgentResponse(
            "Выполнено максимальное количество итераций ($maxIterations). Результаты собраны.",
            executedCalls,
            rawResults
        )
    }

    private fun buildContextMessage(userCommand: String, previousResults: List<ToolCall>): String {
        if (previousResults.isEmpty()) {
            return """
            ЗАДАЧА ПОЛЬЗОВАТЕЛЯ: "$userCommand"
            
            ТВОЯ ЦЕЛЬ: Определить первый шаг.
            ВЕРНИ JSON с одним инструментом.
            Пример формата: {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["Bitcoin"]}}]}
            """.trimIndent()
        }

        val lastResult = previousResults.last()

        // Условие раннего выхода: если файл успешно записан, останавливаемся
        if (lastResult.toolName == "write_file" && !lastResult.result.startsWith("Error")) {
            return "STOP_SUCCESS"
        }

        val history = previousResults.joinToString("\n") { "✔ ${it.toolName}: Выполнено" }

        return """
            ЗАДАЧА: "$userCommand"
            
            ИСТОРИЯ ВЫПОЛНЕНИЯ:
            $history
            
            ⬇️ РЕЗУЛЬТАТ ПОСЛЕДНЕГО ШАГА (${lastResult.toolName}):
            ${lastResult.result}
            
            ТВОЯ ЦЕЛЬ: Вызвать следующий инструмент, используя ЭТОТ результат.
            
            ИНСТРУКЦИИ:
            1. Если это результат check_crypto_rates -> вызови summarize_data. 
               Скопируй ВЕСЬ JSON из результата выше в параметр "data".
            
            2. Если это результат summarize_data -> вызови write_file.
               Скопируй ВЕСЬ текст из результата выше в параметр "content".
               В параметр "path" укажи имя файла (обязательно добавь префикс "mcp-output/").
            
            ВЕРНИ ТОЛЬКО JSON с инструментом.
        """.trimIndent()
    }

    private suspend fun buildSystemPrompt(): String {
        val allTools = reminderMcpServer.getToolsList() +
                cryptoCurrencyMcpServer.getToolsList() +
                summarizationMcpServer.getToolsList() +
                filesystemClient.listTools()

        return """
            Ты AI-агент. Твоя задача - выполнять цепочку действий для решения задачи пользователя.
            
            ДОСТУПНЫЕ ИНСТРУМЕНТЫ:
            ${allTools.joinToString("\n") { "- ${it.name}: ${it.description} (params: ${it.parameters})" }}
            
            ПРАВИЛА:
            1. Всегда передавай вывод одного инструмента на вход следующему (копируй данные целиком).
            2. Формат ответа - строго JSON: {"tools": [{"name": "...", "params": {...}}]}
            3. НЕ используй Markdown блоки (```
            4. При записи файлов всегда используй папку "mcp-output/".
        """.trimIndent()
    }

    private fun parseToolCalls(response: String): List<ToolCall> {
        // Надежный способ извлечения JSON: ищем первую { и последнюю }
        val jsonStartIndex = response.indexOf('{')
        val jsonEndIndex = response.lastIndexOf('}')

        // Если скобок нет или порядок нарушен — считаем это просто текстом
        if (jsonStartIndex == -1 || jsonEndIndex == -1 || jsonEndIndex <= jsonStartIndex) {
            return emptyList()
        }

        val jsonString = response.substring(jsonStartIndex, jsonEndIndex + 1)

        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val toolsArray = root["tools"]?.jsonArray

            if (toolsArray.isNullOrEmpty()) return emptyList()

            toolsArray.mapNotNull { toolElement ->
                if (toolElement !is JsonObject) return@mapNotNull null

                val name = toolElement["name"]?.jsonPrimitive?.content ?: "unknown"
                val paramsElement = toolElement["params"]

                // Преобразуем параметры в Map<String, Any>, корректно обрабатывая вложенные JSON объекты
                val cleanParams: Map<String, Any> = when (paramsElement) {
                    is JsonObject -> paramsElement.mapValues { (_, value) ->
                        when (value) {
                            is JsonPrimitive -> if (value.isString) value.content else value.toString()
                            // Если параметр - это объект (например, data), превращаем его обратно в строку
                            is JsonObject -> value.toString()
                            is JsonArray -> {
                                if (value.all { it is JsonPrimitive && it.isString }) {
                                    value.map { (it as JsonPrimitive).content }
                                } else {
                                    value.toString()
                                }
                            }
                            else -> value.toString()
                        }
                    }
                    else -> emptyMap()
                }

                ToolCall(name, cleanParams, "")
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to parse JSON: $jsonString. Error: ${e.message}")
            emptyList()
        }
    }
}
