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

@Serializable
data class ToolCallsWrapper(
    val tools: List<ToolCallData> = emptyList()
)

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
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")

        val systemPrompt = buildSystemPrompt()
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()

        // Собираем списки доступных инструментов для роутинга
        val rNames = reminderMcpServer.getToolsList().map { it.name }.toSet()
        val cNames = cryptoCurrencyMcpServer.getToolsList().map { it.name }.toSet()
        val sNames = summarizationMcpServer.getToolsList().map { it.name }.toSet()
        val fNames = filesystemClient.listTools().map { it.name }.toSet()

        // Итеративный цикл
        var currentContext = command
        var iterationCount = 0
        val maxIterations = 10

        while (iterationCount < maxIterations) {
            iterationCount++
            logger.info("📍 Iteration $iterationCount")

            // Строим контекст для LLM
            val contextMessage = buildContextMessage(currentContext, executedCalls)

            // Запрашиваем следующее действие
            val response = yandexGPTClient.chat(systemPrompt, contextMessage)
            logger.info("📝 LLM Response: ${response.take(300)}...")

            // Парсим ответ
            val toolCalls = parseToolCalls(response)

            if (toolCalls.isEmpty()) {
                logger.info("✅ LLM вернула финальный ответ (инструментов нет)")
                return AgentResponse(response, executedCalls, rawResults)
            }

            // Выполняем полученные инструменты (обычно 1)
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

                // Обновляем контекст для следующей итерации
                currentContext = result
            }
        }

        // Если достигли максимума итераций
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
            
            ВЕРНИ JSON С ОДНИМ (!) ИНСТРУМЕНТОМ. Ничего больше. Только один.
            
            Формат: {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["Bitcoin", "Ethereum"]}}]}
            
            Какой инструмент вызвать ПЕРВЫМ?
            """.trimIndent()
        } else {
            val lastResult = previousResults.last()
            val toolsSoFar = previousResults.joinToString("\n") { "  ${previousResults.indexOf(it) + 1}. ${it.toolName}" }
            """
            Задача пользователя: "$userCommand"
            
            Выполнено:
            $toolsSoFar
            
            Результат последнего инструмента (${previousResults.last().toolName}):
            ${lastResult.result.take(250)}
            
            ТЕПЕРЬ:
            - Если нужен ещё один инструмент, верни JSON с ОДНИМ инструментом: 
              {"tools": [{"name": "...", "params": {...}}]}
            - Если задача завершена, ответь ТОЛЬКО текстом (без JSON). Просто опиши результат.
            
            ВАЖНО: Верни только ОДИН инструмент за раз. Не несколько.
            """.trimIndent()
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val allTools = reminderMcpServer.getToolsList() +
                cryptoCurrencyMcpServer.getToolsList() +
                summarizationMcpServer.getToolsList() +
                filesystemClient.listTools()

        return """
            Ты AI-агент, выполняющий задачи пошагово.
            
            ИНСТРУМЕНТЫ:
            ${allTools.joinToString("\n") { "- ${it.name}: ${it.description}" }}
            
            ПАЙПЛАЙН для криптовалют:
            ШАГ 1: check_crypto_rates → получить курсы
            ШАГ 2: summarize_data → красиво отформатировать
            ШАГ 3: write_file → сохранить результат
            
            ОБЯЗАТЕЛЬНЫЕ ПРАВИЛА:
            1. Возвращай JSON в формате: {"tools": [{"name": "...", "params": {...}}]}
            2. ОДНОГО инструмента за раз. Никогда не несколько в одном запросе.
            3. НИКОГДА не оборачивай JSON в `````` блоки
            4. Передавай полный результат предыдущего инструмента как параметр следующему
            5. Когда всё готово, ответь текстом (без JSON)
        """.trimIndent()
    }

    private fun parseToolCalls(response: String): List<ToolCall> {
        val jsonStartIndex = response.indexOf('{')
        val jsonEndIndex = response.lastIndexOf('}')

        // Если нет JSON — это текстовый ответ
        if (jsonStartIndex == -1 || jsonEndIndex == -1 || jsonEndIndex <= jsonStartIndex) {
            logger.info("📄 No JSON found in response")
            return emptyList()
        }

        var jsonString = response.substring(jsonStartIndex, jsonEndIndex + 1)
        logger.debug("🔍 Extracted JSON: $jsonString")

        return try {
            val wrapper = json.decodeFromString<ToolCallsWrapper>(jsonString)

            if (wrapper.tools.isEmpty()) {
                logger.info("📄 JSON parsed but tools list is empty")
                return emptyList()
            }

            wrapper.tools.map { toolData ->
                val cleanParams = toolData.params.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> {
                            if (value.isString) value.content else value.toString()
                        }
                        is JsonArray -> {
                            value.map {
                                if (it is JsonPrimitive && it.isString) it.content else it.toString()
                            }
                        }
                        else -> value.toString()
                    }
                }
                ToolCall(toolData.name, cleanParams, "")
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to parse JSON: $jsonString. Error: ${e.message}")
            emptyList()
        }
    }
}
