package ru.skokova.aiadventchallenge.ai

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Ответ AI-агента после выполнения команды
 */
data class AgentResponse(
    val summary: String,
    val toolCalls: List<ToolCall>,
    val rawResults: Map<String, Any> = emptyMap()
)

/**
 * Информация о вызове tool
 */
data class ToolCall(
    val toolName: String,
    val parameters: Map<String, Any>,
    val result: String
)

@Serializable
data class ToolCallsWrapper(
    val tools: List<ToolCallData>
)

@Serializable
data class ToolCallData(
    val name: String,
    val params: Map<String, JsonElement>
)

/**
 * AI-агент на базе YandexGPT для выполнения команд через MCP tools
 *
 * Workflow:
 * 1. Получает текстовую команду (например: "Проверь курсы BTC и ETH")
 * 2. Отправляет в YandexGPT с описанием доступных tools
 * 3. Парсит JSON ответ с tool calls
 * 4. Выполняет tools через MCP Server
 * 5. Отправляет результаты обратно в YandexGPT для генерации summary
 * 6. Возвращает human-readable summary для notification
 */
class YandexAIAgent(
    private val apiKey: String,
    private val folderId: String,
    private val mcpServer: Server,
    private val yandexGPTClient: YandexGPTClient
) {
    private val logger = LoggerFactory.getLogger(YandexAIAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Выполнить команду пользователя
     */
    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")
        
        // ШАГ 1: Строим system prompt с описанием tools
        val systemPrompt = buildSystemPrompt()
        
        // ШАГ 2: Отправляем команду в YandexGPT
        val firstResponse = yandexGPTClient.chat(
            systemPrompt = systemPrompt,
            userMessage = command
        )
        
        logger.info("📝 YandexGPT response: ${firstResponse.take(200)}...")
        
        // ШАГ 3: Парсим tool calls
        val toolCalls = parseToolCalls(firstResponse)
        
        if (toolCalls.isEmpty()) {
            logger.info("ℹ️ No tools requested, returning direct response")
            return AgentResponse(
                summary = firstResponse,
                toolCalls = emptyList()
            )
        }
        
        logger.info("🔧 Executing ${toolCalls.size} tool call(s)")
        
        // ШАГ 4: Выполняем все tools
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()
        
        for (call in toolCalls) {
            try {
                logger.info("  → Calling tool: ${call.toolName}")
                val result = executeTool(call.toolName, call.parameters)
                executedCalls.add(call.copy(result = result))
                rawResults[call.toolName] = result
                logger.info("  ✓ Tool ${call.toolName} completed")
            } catch (e: Exception) {
                logger.error("  ✗ Tool ${call.toolName} failed", e)
                executedCalls.add(call.copy(result = "Error: ${e.message}"))
            }
        }
        
        // ШАГ 5: Генерируем summary через YandexGPT
        val resultsContext = buildResultsContext(executedCalls)
        val summaryPrompt = """
            Ты выполнил команду пользователя: "$command"
            
            Результаты выполнения инструментов:
            $resultsContext
            
            Сформулируй краткую сводку (2-3 предложения) для уведомления пользователю.
            Будь конкретным и полезным. Используй эмодзи для наглядности.
            Отвечай ТОЛЬКО текстом сводки, без вступлений.
        """.trimIndent()
        
        val summary = yandexGPTClient.chat(
            systemPrompt = "Ты ассистент, который создаёт краткие сводки для уведомлений.",
            userMessage = summaryPrompt
        )
        
        logger.info("✅ Summary generated: $summary")
        
        return AgentResponse(
            summary = summary,
            toolCalls = executedCalls,
            rawResults = rawResults
        )
    }
    
    /**
     * Выполнить MCP tool
     */
    private suspend fun executeTool(toolName: String, parameters: Map<String, Any>): String {
        val request = CallToolRequest(
            method = "tools/call",
            params = CallToolRequestParams(
                name = toolName,
                arguments = parameters
            )
        )
        
        val result = mcpServer.callTool(request)
        
        return when {
            result.isError == true -> "Error: ${result.content.firstOrNull()?.text}"
            else -> result.content.firstOrNull()?.text ?: "No result"
        }
    }
    
    /**
     * Построить system prompt с описанием доступных tools
     */
    private fun buildSystemPrompt(): String {
        return """
            Ты - AI ассистент для управления напоминаниями и задачами.
            
            У тебя есть доступ к следующим инструментам (MCP tools):
            - add_reminder: Создать новое напоминание с cron-расписанием
              Параметры: title (string), command (string), cronExpression (string)
            - list_reminders: Получить список напоминаний
              Параметры: status ("active" или "all")
            - remove_reminder: Удалить напоминание по ID
              Параметры: id (string)
            - get_stats: Получить статистику выполнения задач
              Параметры: нет
            - check_crypto_rates: Получить текущие курсы криптовалют
              Параметры: coins (array of strings, например ["bitcoin", "ethereum"])
            
            Когда пользователь даёт команду:
            1. Проанализируй, какие инструменты нужны
            2. Сформируй JSON с вызовами в формате:
            ```json
            {"tools": [{"name": "tool_name", "params": {"key": "value"}}]}
            ```
            3. Я выполню эти инструменты и верну результаты
            4. Ты создашь краткую сводку для пользователя
            
            Примеры:
            - "Проверь курсы BTC и ETH" → {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["bitcoin", "ethereum"]}}]}
            - "Покажи все задачи" → {"tools": [{"name": "list_reminders", "params": {"status": "active"}}]}
            - "Статистика" → {"tools": [{"name": "get_stats", "params": {}}]}
            
            ВАЖНО: Если запрашиваешь tools, отвечай ТОЛЬКО JSON блоком в markdown (```json), без лишнего текста.
        """.trimIndent()
    }
    
    /**
     * Парсить tool calls из ответа YandexGPT
     */
    private fun parseToolCalls(response: String): List<ToolCall> {
        // Ищем JSON блок в ```json ... ``` или прямой JSON
        val jsonMatch = Regex("""```json\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
            .find(response)
            ?: Regex("""(\{"tools":\s*\[.*?\]\})""", RegexOption.DOT_MATCHES_ALL)
                .find(response)
            ?: return emptyList()
        
        return try {
            val parsed = json.decodeFromString<ToolCallsWrapper>(jsonMatch.groupValues[1])
            parsed.tools.map { toolData ->
                val params = toolData.params.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> value.contentOrNull ?: value.toString()
                        is JsonArray -> value.map { 
                            (it as? JsonPrimitive)?.contentOrNull ?: it.toString() 
                        }
                        else -> value.toString()
                    }
                }
                ToolCall(toolData.name, params, "")
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse tool calls: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Построить контекст с результатами для генерации summary
     */
    private fun buildResultsContext(calls: List<ToolCall>): String {
        return calls.joinToString("\n\n") { call ->
            """
            Инструмент: ${call.toolName}
            Параметры: ${call.parameters}
            Результат: ${call.result}
            """.trimIndent()
        }
    }
}
