package ru.skokova.aiadventchallenge.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import ru.skokova.aiadventchallenge.mcp.tools.CoinCapTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.lang.System

// Явно указываем Unit, чтобы убрать warning
fun main(): Unit = runBlocking {
    val toolLogic = CoinCapTool()

    // 1. Создаем Сервер
    val server = Server(
        serverInfo = Implementation(name = "CryptoServer", version = "1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ).apply {
        addTool(
            name = "get_crypto_price",
            description = "Получить текущую цену криптовалюты",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("asset") {
                        put("type", "string")
                        put("description", "Название актива (bitcoin, ethereum)")
                    }
                },
                required = listOf("asset")
            )
        ) { request ->
            // Логируем в stderr! stdout занят JSON-RPC
            System.err.println("🔧 Tool called: ${request.name}")

            try {
                val asset = request.arguments["asset"]?.jsonPrimitive?.content ?: "bitcoin"
                val resultText = toolLogic.getCryptoPrice(asset)

                CallToolResult(content = listOf(TextContent(text = resultText)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message}")),
                    isError = true
                )
            }
        }
    }

    // 2. Запускаем транспорт (Stdio)
    // ВАЖНО: Используем System.in/out с адаптерами kotlinx-io

    val stdInSource = System.`in`.asSource().buffered()
    val stdOutSink = System.out.asSink().buffered()

    val transport = StdioServerTransport(
        inputStream = stdInSource,
        outputStream = stdOutSink
    )

    System.err.println("🚀 MCP Server running on Stdio...")

    // Подключаемся и держим соединение
    server.connect(transport)

    System.err.println("💤 Waiting for requests...")

    // Чтобы main не завершился, используем CompletableDeferred, который никогда не завершится
    val waiter = CompletableDeferred<Unit>()
    waiter.await()

    System.err.println("Server stopped.")
}
