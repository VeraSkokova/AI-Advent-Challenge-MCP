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
            Задача пользователя: "$userCommand"
            
            ВЕРНИ JSON С ОДНИМ (!) ИНСТРУМЕНТОМ.
            Формат: {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["Bitcoin", "Ethereum"]}}]}
            """.trimIndent()
        } else {
            val lastResult = previousResults.last()
            val toolsSoFar = previousResults.joinToString("\n") { "  ${previousResults.indexOf(it) + 1}. ${it.toolName}" }
            """
            Задача пользователя: "$userCommand"
            Выполнено:
            $toolsSoFar
            Результат последнего:
            ${lastResult.result.take(500)}
            
            Если нужен ещё инструмент, верни JSON: {"tools": [{"name": "...", "params": {...}}]}
            Если готово, ответь текстом.
            """.trimIndent()
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val allTools = reminderMcpServer.getToolsList() +
                cryptoCurrencyMcpServer.getToolsList() +
                summarizationMcpServer.getToolsList() +
                filesystemClient.listTools()

        return """
            Ты AI-агент.
            ИНСТРУМЕНТЫ:
            ${allTools.joinToString("\n") { "- ${it.name}: ${it.description}" }}
            
            ПАЙПЛАЙН:
            1. check_crypto_rates
            2. summarize_data
            3. write_file
            
            ПРАВИЛА:
            1. Возвращай JSON: {"tools": [{"name": "...", "params": {...}}]}
            2. НЕ ИСПОЛЬЗУЙ markdown для JSON.
            3. Params могут быть объектами.
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
