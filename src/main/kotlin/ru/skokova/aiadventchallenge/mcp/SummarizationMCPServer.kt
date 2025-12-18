package ru.skokova.aiadventchallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class SummarizationMCPServer {
    private val logger = LoggerFactory.getLogger(SummarizationMCPServer::class.java)
    private val toolRegistry = mutableMapOf<String, suspend (Map<String, Any>) -> String>()

    val server: Server = Server(
        serverInfo = Implementation(name = "summarization-server", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
    ).apply { registerAllTools() }

    fun getToolsList(): List<ToolInfo> {
        return listOf(ToolInfo("summarize_data", "Форматировать данные", listOf("data_type", "data")))
    }

    suspend fun executeTool(name: String, params: Map<String, Any>): String {
        val handler = toolRegistry[name] ?: return "Tool not found: $name"
        return try { handler(params) } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun Server.registerAllTools() {
        addTool(
            name = "summarize_data",
            description = "Форматировать и суммаризировать данные",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("data_type") { put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("crypto_rates")); add(JsonPrimitive("text")) }) }
                    putJsonObject("data") { put("type", "string") }
                },
                required = listOf("data_type", "data")
            )
        ) { request ->
            val dataType = request.arguments["data_type"]?.jsonPrimitive?.content ?: "unknown"
            val dataRaw = request.arguments["data"]?.jsonPrimitive?.content ?: ""
            val result = runBlocking { executeTool("summarize_data", mapOf("data_type" to dataType, "data" to dataRaw)) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }

        toolRegistry["summarize_data"] = { params ->
            val dataType = params["data_type"] as? String ?: "unknown"
            val dataRaw = params["data"] as? String ?: ""
            when (dataType) {
                "crypto_rates" -> formatCryptoRates(dataRaw)
                "text" -> dataRaw.ifBlank { "Нет текста" }
                else -> dataRaw
            }
        }
        logger.info("✓ SummarizationMCPServer initialized")
    }

    private fun formatCryptoRates(jsonString: String): String {
        return try {
            val json = Json.parseToJsonElement(jsonString).jsonObject
            val rates = json["rates"]?.jsonObject ?: JsonObject(emptyMap())
            val timestamp = json["timestamp"]?.jsonPrimitive?.content ?: "unknown"

            buildString {
                appendLine("💰 CRYPTOCURRENCY RATES SUMMARY 💰")
                appendLine("═══════════════════════════════════")
                rates.forEach { (coin, price) ->
                    val p = price.jsonPrimitive.doubleOrNull ?: 0.0
                    appendLine("📊 ${coin.replaceFirstChar { it.uppercase() }}: $${String.format("%.2f", p)}")
                }
                appendLine("\n⏰ Timestamp: $timestamp")
                appendLine("═══════════════════════════════════")
            }
        } catch (e: Exception) { "Error parsing JSON: ${e.message}" }
    }
}
