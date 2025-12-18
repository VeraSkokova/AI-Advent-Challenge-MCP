package ru.skokova.aiadventchallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.scheduler.CronParser
import ru.skokova.aiadventchallenge.storage.Reminder
import ru.skokova.aiadventchallenge.storage.ReminderStorage

class ReminderMCPServer(
    private val storage: ReminderStorage
) {
    private val logger = LoggerFactory.getLogger(ReminderMCPServer::class.java)
    private val toolRegistry = mutableMapOf<String, suspend (Map<String, Any>) -> String>()

    val server: Server = Server(
        serverInfo = Implementation(name = "reminder-server", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
    ).apply { registerAllTools() }

    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo("add_reminder", "Создать напоминание", listOf("title", "command", "cronExpression")),
            ToolInfo("list_reminders", "Список напоминаний", listOf("status")),
            ToolInfo("remove_reminder", "Удалить напоминание", listOf("id")),
            ToolInfo("get_stats", "Статистика", emptyList())
        )
    }

    suspend fun executeTool(name: String, params: Map<String, Any>): String {
        val handler = toolRegistry[name] ?: return "Tool not found: $name"
        return try { handler(params) } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun Server.registerAllTools() {
        // Tool: Add
        addTool("add_reminder", "Создать напоминание", Tool.Input(
            properties = buildJsonObject {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("command") { put("type", "string") }
                putJsonObject("cronExpression") { put("type", "string") }
            }, required = listOf("title", "command", "cronExpression")
        )) { req ->
            val p = mapOf(
                "title" to (req.arguments["title"]?.jsonPrimitive?.content ?: ""),
                "command" to (req.arguments["command"]?.jsonPrimitive?.content ?: ""),
                "cronExpression" to (req.arguments["cronExpression"]?.jsonPrimitive?.content ?: "")
            )
            CallToolResult(content = listOf(TextContent(text = runBlocking { executeTool("add_reminder", p) })))
        }
        toolRegistry["add_reminder"] = { p ->
            val title = p["title"] as String; val cmd = p["command"] as String; val cron = p["cronExpression"] as String
            if (!CronParser.isValid(cron)) "Invalid cron" else {
                storage.save(Reminder(title = title, command = cmd, cronExpression = cron, nextExecution = CronParser.calculateNext(cron, System.currentTimeMillis())!!))
                "✓ Created: $title"
            }
        }
        // Tool: List
        addTool("list_reminders", "Список", Tool.Input(properties = buildJsonObject { putJsonObject("status") { put("type", "string") } })) { req ->
            val p = mapOf("status" to (req.arguments["status"]?.jsonPrimitive?.content ?: "active"))
            CallToolResult(content = listOf(TextContent(text = runBlocking { executeTool("list_reminders", p) })))
        }
        toolRegistry["list_reminders"] = { p ->
            val list = if (p["status"] == "active") storage.loadAll().filter { it.enabled } else storage.loadAll()
            if (list.isEmpty()) "Нет напоминаний" else list.joinToString("\n") { "• ${it.title}" }
        }
        // Tool: Remove
        addTool("remove_reminder", "Удалить", Tool.Input(properties = buildJsonObject { putJsonObject("id") { put("type", "string") } }, required = listOf("id"))) { req ->
            val p = mapOf("id" to (req.arguments["id"]?.jsonPrimitive?.content ?: ""))
            CallToolResult(content = listOf(TextContent(text = runBlocking { executeTool("remove_reminder", p) })))
        }
        toolRegistry["remove_reminder"] = { p -> storage.remove(p["id"] as String); "✓ Removed" }
        // Tool: Stats
        addTool("get_stats", "Статистика", Tool.Input(properties = buildJsonObject{})) {
            CallToolResult(content = listOf(TextContent(text = runBlocking { executeTool("get_stats", emptyMap()) })))
        }
        toolRegistry["get_stats"] = { "Всего напоминаний: ${storage.loadAll().size}" }
        logger.info("✓ ReminderMCPServer initialized")
    }
}