package ru.skokova.aiadventchallenge.ai

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

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
    private val mcpServer: Server,
    private val yandexGPTClient: YandexGPTClient
) {
    private val logger = LoggerFactory.getLogger(YandexAIAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing: $command")
        
        // ШАГ 1: Получаем tools динамически
        val availableTools = try {
            mcpServer.listToolsBlocking()
        } catch (e: Exception) {
            logger.error("Failed to get tools", e)
            emptyList()
        }
        
        logger.info("📋 Tools: ${availableTools.map { it.name }}")
        
        // ШАГ 2: System prompt
        val systemPrompt = buildSystemPrompt(availableTools)
        
        // ШАГ 3: YandexGPT
        val firstResponse = yandexGPTClient.chat(systemPrompt, command)
        logger.info("📝 Response: ${firstResponse.take(150)}...")
        
        // ШАГ 4: Парсим tool calls
        val toolCalls = parseToolCalls(firstResponse)
        
        if (toolCalls.isEmpty()) {
            logger.info("ℹ️ No tools needed")
            return AgentResponse(firstResponse, emptyList())
        }
        
        logger.info("🔧 Executing ${toolCalls.size} tool(s)")
        
        // ШАГ 5: Выполняем tools
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()
        
        for (call in toolCalls) {
            try {
                logger.info("  → ${call.toolName}")
                val result = executeTool(call.toolName, call.parameters)
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
    
    private fun executeTool(toolName: String, parameters: Map<String, Any>): String {
        // Преобразуем Map<String, Any> в JsonObject
        val jsonArgs = buildJsonObject {
            parameters.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    is List<*> -> putJsonArray(key) {
                        value.forEach { item ->
                            when (item) {
                                is String -> add(item)
                                is Number -> add(item)
                                else -> add(item.toString())
                            }
                        }
                    }
                    else -> put(key, value.toString())
                }
            }
        }
        
        val request = CallToolRequest(name = toolName, arguments = jsonArgs)
        val result = mcpServer.callToolBlocking(request)
        
        return when {
            result.isError == true -> "Error: ${result.content.firstOrNull()?.text}"
            else -> result.content.firstOrNull()?.text ?: "No result"
        }
    }
    
    private fun buildSystemPrompt(tools: List<Tool>): String {
        val toolDescriptions = if (tools.isNotEmpty()) {
            tools.joinToString("\n") { tool ->
                val params = (tool.inputSchema?.properties as? JsonObject)?.keys?.joinToString(", ") ?: ""
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
            2. Верни JSON:
            ```json
            {"tools": [{"name": "tool_name", "params": {"key": "value"}}]}
            ```
            
            Примеры:
            - "Проверь BTC и ETH" → {"tools": [{"name": "check_crypto_rates", "params": {"coins": ["bitcoin", "ethereum"]}}]}
            - "Покажи задачи" → {"tools": [{"name": "list_reminders", "params": {"status": "active"}}]}
            
            ВАЖНО: Отвечай ТОЛЬКО JSON блоком ```json.
        """.trimIndent()
    }
    
    private fun parseToolCalls(response: String): List<ToolCall> {
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
            logger.warn("Parse failed: ${e.message}")
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

// Extension для blocking вызовов
private fun Server.listToolsBlocking(): List<Tool> {
    return kotlinx.coroutines.runBlocking { listTools() }
}

private fun Server.callToolBlocking(request: CallToolRequest): io.modelcontextprotocol.kotlin.sdk.CallToolResult {
    return kotlinx.coroutines.runBlocking { callTool(request) }
}
