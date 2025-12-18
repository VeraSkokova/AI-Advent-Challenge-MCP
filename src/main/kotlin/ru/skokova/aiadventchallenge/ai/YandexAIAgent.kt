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

// Wrapper классы больше не нужны для парсинга, но оставим для совместимости если нужно
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

    // Максимально мягкая конфигурация JSON
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        prettyPrint = false
        coerceInputValues = true // Пытаться привести типы
    }

    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")

        val systemPrompt = buildSystemPrompt()
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()

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
            val response = yandexGPTClient.chat(systemPrompt, contextMessage)
            logger.info("📝 LLM Response: ${response.take(300)}...")

            val toolCalls = parseToolCalls(response)

            if (toolCalls.isEmpty()) {
                logger.info("✅ LLM вернула финальный ответ (инструментов нет)")
                val cleanResponse = response.replace("``````", "").trim()
                return AgentResponse(cleanResponse, executedCalls, rawResults)
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
        return if (previousResults.isEmpty()) {
            """
            ЗАДАЧА: "$userCommand"
            
            ДЕЙСТВИЕ 1: Верни JSON для первого инструмента.
            Пример: {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["Bitcoin", "Ethereum"]}}]}
            """.trimIndent()
        } else {
            val lastResult = previousResults.last()

            // Формируем историю, но кратко для старых, подробно для последнего
            val history = previousResults.mapIndexed { index, call ->
                if (index == previousResults.lastIndex) {
                    "⬇️ ПОСЛЕДНИЙ РЕЗУЛЬТАТ (${call.toolName}):\n${call.result}" // Полный результат для контекста
                } else {
                    "✔ ${call.toolName}: выполнен"
                }
            }.joinToString("\n")

            """
            ЗАДАЧА: "$userCommand"
            
            ИСТОРИЯ:
            $history
            
            ТВОЯ ЦЕЛЬ: Используя "ПОСЛЕДНИЙ РЕЗУЛЬТАТ", вызови следующий инструмент.
            
            ЕСЛИ ПОСЛЕДНИЙ БЫЛ check_crypto_rates:
            -> Вызови summarize_data.
            -> Параметр "data" должен содержать ВЕСЬ JSON из последнего результата.
            -> Пример: {"tools": [{"name": "summarize_data", "params": {"data_type": "crypto_rates", "data": <ВСТАВЬ_СЮДА_ВЕСЬ_JSON_РЕЗУЛЬТАТ>}}]}
            
            ЕСЛИ ПОСЛЕДНИЙ БЫЛ summarize_data:
            -> Вызови write_file.
            -> Параметр "content" должен быть текстом из последнего результата.
            -> Параметр "path" - имя файла из задачи (например "rates.txt").
            -> Параметр "path" ДОЛЖЕН начинаться с "mcp-output/" (например "mcp-output/rates.txt").
            -> Пример: {"tools": [{"name": "write_file", "params": {"path": "rates.txt", "content": "<ВСТАВЬ_СЮДА_ТЕКСТ_РЕЗУЛЬТАТА>"}}]}
            -> В поле "content" ты ДОЛЖЕН скопировать ВЕСЬ текст из "Результат последнего инструмента" выше.
            -> НЕ пиши плейсхолдеры. Копируй реальные данные.
            
            ЕСЛИ ПОСЛЕДНИЙ БЫЛ write_file:
            -> Задача завершена. Ответь текстом "Файл сохранен."
            
            Верни ТОЛЬКО JSON с инструментом.
            """.trimIndent()
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val allTools = reminderMcpServer.getToolsList() +
                cryptoCurrencyMcpServer.getToolsList() +
                summarizationMcpServer.getToolsList() +
                filesystemClient.listTools()

        return """
            Ты AI-агент. Твоя задача - выполнять цепочку действий, ПЕРЕДАВАЯ ДАННЫЕ между инструментами.
            
            ИНСТРУМЕНТЫ:
            ${allTools.joinToString("\n") { "- ${it.name}: ${it.description} Params: ${it.parameters}" }}
            
            ВАЖНО:
            1. Никогда не вызывай инструмент с пустыми параметрами, если они обязательны.
            2. Ты должен явно копировать данные из вывода предыдущего шага во ввод следующего.
            3. Формат ответа - JSON: {"tools": [{"name": "...", "params": {...}}]}
            4. ВАЖНО: При сохранении файлов ВСЕГДА используй папку "mcp-output/".
               Пример: "path": "mcp-output/rates.txt" (А НЕ просто "rates.txt")
        """.trimIndent()
    }

    private fun parseToolCalls(response: String): List<ToolCall> {
        // ИСПРАВЛЕННЫЙ REGEX!
        val markdownRegex = Regex("``````", RegexOption.DOT_MATCHES_ALL)
        val match = markdownRegex.find(response)

        var jsonString = if (match != null) {
            match.groupValues[1].trim()
        } else {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            if (start != -1 && end != -1 && end > start) {
                response.substring(start, end + 1)
            } else {
                return emptyList()
            }
        }

        jsonString = jsonString.replace("\uFEFF", "")

        return try {
            // Парсим в JsonElement
            val root = json.parseToJsonElement(jsonString).jsonObject
            val toolsArray = root["tools"]?.jsonArray

            if (toolsArray.isNullOrEmpty()) return emptyList()

            toolsArray.mapNotNull { toolElement ->
                if (toolElement !is JsonObject) return@mapNotNull null

                val name = toolElement["name"]?.jsonPrimitive?.content ?: "unknown"
                val paramsElement = toolElement["params"]

                val cleanParams: Map<String, Any> = when (paramsElement) {
                    is JsonObject -> paramsElement.mapValues { (_, value) ->
                        when (value) {
                            is JsonPrimitive -> if (value.isString) value.content else value.toString()
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
