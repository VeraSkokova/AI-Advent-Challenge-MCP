package ru.skokova.aiadventchallenge.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.mcp.ReminderMCPServer

/**
 * Ответ AI-агента
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
 * AI-агент на базе YandexGPT
 */
class YandexAIAgent(
    private val apiKey: String,
    private val folderId: String,
    private val mcpServer: ReminderMCPServer,
    private val yandexGPTClient: YandexGPTClient
) {
    private val logger = LoggerFactory.getLogger(YandexAIAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing: $command")
        
        // ШАГ 1: Получаем tools динамически
        val availableTools = mcpServer.getToolsList()
        logger.info("📋 Tools: ${availableTools.map { it.name }}")
        
        // ШАГ 2: System prompt
        val systemPrompt = buildSystemPrompt(availableTools)
        
        // ШАГ 3: YandexGPT
        val firstResponse = yandexGPTClient.chat(systemPrompt, command)
        logger.info("📝 Response: ${firstResponse.take(150)}...")
        
        // ШАГ 4: Парсим tool calls
        val toolCalls = parseToolCalls(firstResponse)
        
        if (toolCalls.isEmpty()) {
            logger.info("ℹ️ No tools parsed - returning raw response")
            return AgentResponse(firstResponse, emptyList())
        }
        
        logger.info("🔧 Executing ${toolCalls.size} tool(s)")
        
        // ШАГ 5: Выполняем tools
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()
        
        for (call in toolCalls) {
            try {
                logger.info("  → ${call.toolName}")
                val result = mcpServer.executeTool(call.toolName, call.parameters)
                executedCalls.add(call.copy(result = result))
                rawResults[call.toolName] = result
                logger.info("  ✓ Done")
            } catch (e: Exception) {
                logger.error("  ✗ Failed", e)
                executedCalls.add(call.copy(result = "Error: ${e.message}"))
            }
        }
        
        // ШАГ 6: Summary
        val resultsContext = buildResultsContext(executedCalls)
        val summaryPrompt = """
            Команда: "$command"
            Результаты:
            $resultsContext
            
            Создай краткую сводку (2-3 предложения) с эмодзи.
        """.trimIndent()
        
        val summary = yandexGPTClient.chat(
            "Ты ассистент, создающий сводки.",
            summaryPrompt
        )
        
        logger.info("✅ Summary: $summary")
        return AgentResponse(summary, executedCalls, rawResults)
    }
    
    private fun buildSystemPrompt(tools: List<ru.skokova.aiadventchallenge.mcp.ToolInfo>): String {
        val toolDescriptions = if (tools.isNotEmpty()) {
            tools.joinToString("\n") { tool ->
                val params = tool.parameters.joinToString(", ")
                "- ${tool.name}: ${tool.description}\n  Параметры: $params"
            }
        } else {
            "(Нет инструментов)"
        }
        
        return """
            Ты - AI ассистент.
            
            Доступные инструменты:
            $toolDescriptions
            
            Когда пользователь даёт команду:
            1. Определи, какие инструменты нужны
            2. Верни JSON БЕЗ markdown:
            {"tools": [{"name": "tool_name", "params": {"key": "value"}}]}
            
            Примеры:
            - "Проверь BTC и ETH" → {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["bitcoin", "ethereum"]}}]}
            - "Покажи задачи" → {"tools": [{"name": "list_reminders", "params": {"status": "active"}}]}
            
            ВАЖНО: Отвечай ТОЛЬКО чистым JSON, БЕЗ ```json и других обёрток.
        """.trimIndent()
    }
    
    private fun parseToolCalls(response: String): List<ToolCall> {
        // Удаляем markdown обёртки и лишние пробелы
        var cleanedResponse = response
            .replace("```json", "")
            .replace("```", "")
            .trim()
        
        // Ищем JSON объект с "tools"
        val jsonPattern = Regex("""\{[\s\S]*?"tools"[\s\S]*?\]""", RegexOption.MULTILINE)
        val match = jsonPattern.find(cleanedResponse)
        
        if (match == null) {
            logger.warn("No JSON found in response: $cleanedResponse")
            return emptyList()
        }
        
        // Найдём закрывающую скобку
        val startIdx = match.range.first
        var openBraces = 0
        var endIdx = startIdx
        
        for (i in startIdx until cleanedResponse.length) {
            when (cleanedResponse[i]) {
                '{' -> openBraces++
                '}' -> {
                    openBraces--
                    if (openBraces == 0) {
                        endIdx = i + 1
                        break
                    }
                }
            }
        }
        
        val jsonString = cleanedResponse.substring(startIdx, endIdx)
        logger.debug("Extracted JSON: $jsonString")
        
        return try {
            val parsed = json.decodeFromString<ToolCallsWrapper>(jsonString)
            logger.info("✓ Parsed ${parsed.tools.size} tool call(s)")
            
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
            logger.error("JSON parse failed: ${e.message}")
            logger.error("Attempted to parse: $jsonString")
            emptyList()
        }
    }
    
    private fun buildResultsContext(calls: List<ToolCall>): String {
        return calls.joinToString("\n\n") { call ->
            """
            Tool: ${call.toolName}
            Params: ${call.parameters}
            Result: ${call.result}
            """.trimIndent()
        }
    }
}
