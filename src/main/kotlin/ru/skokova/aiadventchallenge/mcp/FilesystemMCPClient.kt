package ru.skokova.aiadventchallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.File

class FilesystemMCPClient(
    private val command: String = "npx",
    private val args: List<String> = listOf("-y", "@modelcontextprotocol/server-filesystem", "./mcp-output")
) {
    private val logger = LoggerFactory.getLogger(FilesystemMCPClient::class.java)
    private var client: Client? = null
    private var process: Process? = null

    suspend fun connect() {
        if (client != null) return
        File("./mcp-output").mkdirs()

        try {
            // Определяем команду в зависимости от ОС
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val fullCommand = if (isWindows) {
                // На Windows используем cmd.exe для поиска npx в PATH
                listOf("cmd.exe", "/c", command) + args
            } else {
                // На Linux/macOS просто вызываем npx
                listOf(command) + args
            }

            logger.info("🚀 Starting Filesystem Server with: ${fullCommand.joinToString(" ")}")

            val pb = ProcessBuilder(fullCommand)
            process = pb.start()

            val inputStream = process!!.inputStream ?: throw RuntimeException("Process InputStream is null")
            val outputStream = process!!.outputStream ?: throw RuntimeException("Process OutputStream is null")

            val transport = StdioClientTransport(
                input = inputStream.asSource().buffered(),
                output = outputStream.asSink().buffered()
            )

            client = Client(
                clientInfo = Implementation(name = "fs-client", version = "1.0.0")
            ).apply {
                connect(transport)
                logger.info("✅ Connected to Filesystem MCP server")
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to connect to Filesystem Server", e)
            throw RuntimeException("Cannot start Filesystem MCP Server. Make sure Node.js and npx are installed and in PATH", e)
        }
    }

    suspend fun listTools(): List<ToolInfo> {
        val c = client ?: run { connect(); client!! }
        val result = c.listTools()
        return result.tools.map { tool ->
            ToolInfo(
                name = tool.name,
                description = tool.description ?: "",
                parameters = tool.inputSchema?.properties?.keys?.toList() ?: emptyList()
            )
        }
    }

    suspend fun callTool(name: String, params: Map<String, Any>): String {
        val c = client ?: run { connect(); client!! }

        val result = c.callTool(name, params)

        if (result == null) {
            return "Error: Empty result from tool $name"
        }

        if (result.isError == true) {
            val errorText = result.content
                .filterIsInstance<TextContent>()
                .joinToString { it.text ?: "" }
            return "Error executing $name: $errorText"
        }

        return result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text ?: "" }
    }

    suspend fun shutdown() {
        try {
            client?.close()
        } catch (_: Exception) {
            // ignore
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
            // ignore
        }
        logger.info("✅ Filesystem Client shutdown")
    }
}