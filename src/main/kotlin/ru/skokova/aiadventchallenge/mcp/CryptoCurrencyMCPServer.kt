package ru.skokova.aiadventchallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.coincap.CoinCapClient
import java.time.Instant

class CryptoCurrencyMCPServer(
    private val coinCapClient: CoinCapClient
) {
    private val logger = LoggerFactory.getLogger(CryptoCurrencyMCPServer::class.java)
    private val toolRegistry = mutableMapOf<String, suspend (Map<String, Any>) -> String>()

    val server: Server = Server(
        serverInfo = Implementation(name = "crypto-server", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
    ).apply { registerAllTools() }

    fun getToolsList(): List<ToolInfo> {
        return listOf(ToolInfo("check_crypto_rates", "Получить курсы криптовалют", listOf("coins")))
    }

    suspend fun executeTool(name: String, params: Map<String, Any>): String {
        val handler = toolRegistry[name] ?: return "Tool not found: $name"
        return try { handler(params) } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun Server.registerAllTools() {
        addTool(
            name = "check_crypto_rates",
            description = "Получить текущие курсы криптовалют",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("coins") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Список названий монет")
                    }
                },
                required = listOf("coins")
            )
        ) { request ->
            val coinsArray = request.arguments["coins"]?.jsonArray
            val coins = coinsArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val result = runBlocking { executeTool("check_crypto_rates", mapOf("coins" to coins)) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }

        toolRegistry["check_crypto_rates"] = { params ->
            @Suppress("UNCHECKED_CAST")
            val coins = params["coins"] as? List<String> ?: emptyList()
            if (coins.isEmpty()) throw IllegalArgumentException("Список монет пуст")

            val rates = coinCapClient.getRates(coins)
            val timestamp = Instant.now().toString()

            buildJsonObject {
                putJsonObject("rates") {
                    rates.forEach { (coin, price) -> put(coin, price) }
                }
                put("timestamp", timestamp)
            }.toString()
        }
        logger.info("✓ CryptoCurrencyMCPServer initialized")
    }
}
