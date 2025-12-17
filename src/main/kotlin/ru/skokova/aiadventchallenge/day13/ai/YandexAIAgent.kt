package ru.skokova.aiadventchallenge.day13.ai

import io.modelcontextprotocol.kotlin.sdk.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Результат выполнения команды AI-агентом
 */
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
    val tools: List<ToolCallData>
)

@Serializable
data class ToolCallData(
    val name: String,
    val params: Map<String, JsonElement>
)

/**
 * AI-агент на базе YandexGPT - центральный компонент системы
 * Получает команду, выполняет её через MCP tools, генерирует summary
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
     * Основной метод: получает команду, выполняет её через MCP tools,
     * генерирует summary для пользователя
     */
    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")
        
        // ШАГ 1: Получаем список tools и строим system prompt
        val availableTools = getAvailableTools()
        val systemPrompt = buildSystemPrompt(availableTools)
        
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
        
        logger.info("🔧 Executing ${toolCalls.size} tool calls")
        
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
     * Выполнение MCP tool через Server
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
     * Получение списка доступных tools
     */
    private fun getAvailableTools(): List<Tool> {
        return listOf(
            Tool(
                name = "add_reminder",
                description = "Создать новое напоминание",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string"),
                        "command" to mapOf("type" to "string"),
                        "cronExpression" to mapOf("type" to "string")
                    )
                )
            ),
            Tool(
                name = "list_reminders",
                description = "Показать список напоминаний",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "status" to mapOf(
                            "type" to "string",
                            "enum" to listOf("active", "all")
                        )
                    )
                )
            ),
            Tool(
                name = "remove_reminder",
                description = "Удалить напоминание",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string")
                    )
                )
            ),
            Tool(
                name = "get_stats",
                description = "Получить статистику выполнения",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            )
        )
    }
    
    /**
     * Генерация system prompt с описанием доступных tools
     */
    private fun buildSystemPrompt(tools: List<Tool>): String {
        val toolDescriptions = tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description}"
        }
        
        return """
            Ты - AI ассистент для управления напоминаниями и задачами.
            
            У тебя есть доступ к следующим инструментам (MCP tools):
            $toolDescriptions
            
            Когда пользователь даёт команду:
            1. Проанализируй, какие инструменты нужны
            2. Сформируй JSON с вызовами в формате:
            ```json
            {"tools": [{"name": "tool_name", "params": {"key": "value"}}]}
            ```
            3. Я выполню эти инструменты и верну результаты
            4. Ты создашь краткую сводку для пользователя
            
            Примеры:
            - "Покажи все задачи" → {"tools": [{"name": "list_reminders", "params": {}}]}
            - "Покажи статистику" → {"tools": [{"name": "get_stats", "params": {}}]}
            
            ВАЖНО: Если запрашиваешь tools, отвечай ТОЛЬКО JSON блоком, без лишнего текста.
        """.trimIndent()
    }
    
    /**
     * Парсинг tool calls из ответа YandexGPT
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
                        is JsonPrimitive -> value.content
                        is JsonArray -> value.map { (it as? JsonPrimitive)?.content ?: it.toString() }
                        else -> value.toString()
                    }
                }
                ToolCall(toolData.name, params, "")
            }
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to parse tool calls: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Форматирование результатов для контекста
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