package ru.skokova.aiadventchallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.coincap.CoinCapClient
import ru.skokova.aiadventchallenge.scheduler.CronParser
import ru.skokova.aiadventchallenge.storage.Reminder
import ru.skokova.aiadventchallenge.storage.ReminderStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MCP Server для управления напоминаниями с интеграцией CoinCap
 */
class ReminderMCPServer(
    private val storage: ReminderStorage,
    private val coinCapClient: CoinCapClient
) {
    private val logger = LoggerFactory.getLogger(ReminderMCPServer::class.java)
    
    // Внутренний реестр tools для AI Agent
    private val toolRegistry = mutableMapOf<String, suspend (Map<String, Any>) -> String>()
    
    val server: Server = Server(
        serverInfo = Implementation(name = "reminder-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ).apply {
        registerAllTools()
    }
    
    /**
     * Получить список доступных tools для AI Agent
     */
    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo("add_reminder", "Создать напоминание", listOf("title", "command", "cronExpression")),
            ToolInfo("list_reminders", "Список напоминаний", listOf("status")),
            ToolInfo("remove_reminder", "Удалить напоминание", listOf("id")),
            ToolInfo("get_stats", "Статистика", emptyList()),
            ToolInfo("check_crypto_rates", "Курсы криптовалют", listOf("coins"))
        )
    }
    
    /**
     * Вызвать tool напрямую (для AI Agent)
     */
    suspend fun executeTool(name: String, params: Map<String, Any>): String {
        val handler = toolRegistry[name] ?: return "Tool not found: $name"
        return try {
            handler(params)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    private fun Server.registerAllTools() {
        // ════════════════════════════════════════════════════════════════
        // TOOL 1: Добавление напоминания
        // ════════════════════════════════════════════════════════════════
        addTool(
            name = "add_reminder",
            description = "Создать новое напоминание с cron-расписанием",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название напоминания")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Команда для AI-агента")
                    }
                    putJsonObject("cronExpression") {
                        put("type", "string")
                        put("description", "Cron выражение")
                    }
                },
                required = listOf("title", "command", "cronExpression")
            )
        ) { request ->
            val params = mapOf(
                "title" to (request.arguments["title"]?.jsonPrimitive?.content ?: ""),
                "command" to (request.arguments["command"]?.jsonPrimitive?.content ?: ""),
                "cronExpression" to (request.arguments["cronExpression"]?.jsonPrimitive?.content ?: "")
            )
            val result = runBlocking { executeTool("add_reminder", params) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }
        
        toolRegistry["add_reminder"] = { params ->
            val title = params["title"] as? String ?: ""
            val command = params["command"] as? String ?: ""
            val cron = params["cronExpression"] as? String ?: ""
            
            if (!CronParser.isValid(cron)) {
                "⚠️ Невалидное cron: $cron"
            } else {
                val nextExec = CronParser.calculateNext(cron, System.currentTimeMillis())
                    ?: throw IllegalArgumentException("Invalid cron: $cron")
                
                val reminder = Reminder(
                    title = title,
                    command = command,
                    cronExpression = cron,
                    nextExecution = nextExec
                )
                
                storage.save(reminder)
                logger.info("✓ Created: ${reminder.id.take(8)} - $title")
                "✓ Напоминание '$title' создано"
            }
        }
        
        // ════════════════════════════════════════════════════════════════
        // TOOL 2: Список напоминаний
        // ════════════════════════════════════════════════════════════════
        addTool(
            name = "list_reminders",
            description = "Получить список напоминаний",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("status") {
                        put("type", "string")
                        put("description", "Фильтр: active или all")
                    }
                },
                required = listOf()
            )
        ) { request ->
            val params = mapOf(
                "status" to (request.arguments["status"]?.jsonPrimitive?.content ?: "active")
            )
            val result = runBlocking { executeTool("list_reminders", params) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }
        
        toolRegistry["list_reminders"] = { params ->
            val status = params["status"] as? String ?: "active"
            val reminders = when (status) {
                "active" -> storage.loadAll().filter { it.enabled }
                else -> storage.loadAll()
            }
            
            if (reminders.isEmpty()) {
                "Нет напоминаний"
            } else {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                reminders.joinToString("\n") { reminder ->
                    val nextTime = reminder.nextExecution?.let { 
                        Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .format(formatter)
                    } ?: "н/а"
                    "• ${reminder.title} (${reminder.cronExpression}) → $nextTime"
                }
            }
        }
        
        // ════════════════════════════════════════════════════════════════
        // TOOL 3: Удаление
        // ════════════════════════════════════════════════════════════════
        addTool(
            name = "remove_reminder",
            description = "Удалить напоминание",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "ID напоминания")
                    }
                },
                required = listOf("id")
            )
        ) { request ->
            val params = mapOf("id" to (request.arguments["id"]?.jsonPrimitive?.content ?: ""))
            val result = runBlocking { executeTool("remove_reminder", params) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }
        
        toolRegistry["remove_reminder"] = { params ->
            val id = params["id"] as? String ?: ""
            storage.remove(id)
            logger.info("✓ Removed: ${id.take(8)}")
            "✓ Удалено"
        }
        
        // ════════════════════════════════════════════════════════════════
        // TOOL 4: Статистика
        // ════════════════════════════════════════════════════════════════
        addTool(
            name = "get_stats",
            description = "Статистика выполнения",
            inputSchema = Tool.Input(
                properties = buildJsonObject { },
                required = listOf()
            )
        ) { _ ->
            val result = runBlocking { executeTool("get_stats", emptyMap()) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }
        
        toolRegistry["get_stats"] = { _ ->
            val reminders = storage.loadAll()
            val active = reminders.count { it.enabled }
            val total = reminders.size
            val executed = reminders.count { it.lastExecuted != null }
            
            """
                📊 Статистика:
                • Всего: $total
                • Активных: $active
                • Выполнено: $executed
            """.trimIndent()
        }
        
        // ════════════════════════════════════════════════════════════════
        // TOOL 5: Криптокурсы
        // ════════════════════════════════════════════════════════════════
        addTool(
            name = "check_crypto_rates",
            description = "Курсы криптовалют",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("coins") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put("description", "Список монет")
                    }
                },
                required = listOf("coins")
            )
        ) { request ->
            val coinsArray = request.arguments["coins"]?.jsonArray
            val coins = coinsArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val params = mapOf("coins" to coins)
            val result = runBlocking { executeTool("check_crypto_rates", params) }
            CallToolResult(content = listOf(TextContent(text = result)))
        }
        
        toolRegistry["check_crypto_rates"] = { params ->
            @Suppress("UNCHECKED_CAST")
            val coins = params["coins"] as? List<String> ?: emptyList()
            
            val rates = coinCapClient.getRates(coins)
            
            if (rates.isEmpty()) {
                "⚠️ Не удалось получить курсы"
            } else {
                val formatted = rates.entries.joinToString("\n") { (coin, price) ->
                    "• ${coin.replaceFirstChar { it.uppercase() }}: $${String.format("%.2f", price)}"
                }
                "💰 Курсы:\n$formatted"
            }
        }
        
        logger.info("✓ All 5 MCP tools registered")
    }
}

data class ToolInfo(
    val name: String,
    val description: String,
    val parameters: List<String>
)
