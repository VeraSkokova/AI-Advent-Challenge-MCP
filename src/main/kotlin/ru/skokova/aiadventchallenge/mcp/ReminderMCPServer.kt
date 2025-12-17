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
 *
 * Предоставляет 5 MCP tools:
 * 1. add_reminder - Создать напоминание
 * 2. list_reminders - Показать список
 * 3. remove_reminder - Удалить
 * 4. get_stats - Статистика
 * 5. check_crypto_rates - Курсы криптовалют
 */
class ReminderMCPServer(
    private val storage: ReminderStorage,
    private val coinCapClient: CoinCapClient
) {
    private val logger = LoggerFactory.getLogger(ReminderMCPServer::class.java)
    
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
    
    private fun Server.registerAllTools() {
        // ═══════════════════════════════════════════════════════════════
        // TOOL 1: Добавление напоминания
        // ═══════════════════════════════════════════════════════════════
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
            try {
                val title = request.arguments["title"]?.jsonPrimitive?.content ?: ""
                val command = request.arguments["command"]?.jsonPrimitive?.content ?: ""
                val cron = request.arguments["cronExpression"]?.jsonPrimitive?.content ?: ""
                
                if (!CronParser.isValid(cron)) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "⚠️ Невалидное cron выражение: $cron")),
                        isError = true
                    )
                }
                
                val nextExec = CronParser.calculateNext(cron, System.currentTimeMillis())
                    ?: throw IllegalArgumentException("Invalid cron: $cron")
                
                val reminder = Reminder(
                    title = title,
                    command = command,
                    cronExpression = cron,
                    nextExecution = nextExec
                )
                
                runBlocking { storage.save(reminder) }
                logger.info("✓ Created reminder: ${reminder.id.take(8)} - $title")
                
                CallToolResult(
                    content = listOf(TextContent(text = "✓ Напоминание '$title' создано"))
                )
            } catch (e: Exception) {
                logger.error("Error in add_reminder", e)
                CallToolResult(
                    content = listOf(TextContent(text = "Ошибка: ${e.message}")),
                    isError = true
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // TOOL 2: Список напоминаний
        // ═══════════════════════════════════════════════════════════════
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
            try {
                val status = request.arguments["status"]?.jsonPrimitive?.content ?: "active"
                val reminders = runBlocking {
                    when (status) {
                        "active" -> storage.loadAll().filter { it.enabled }
                        else -> storage.loadAll()
                    }
                }
                
                if (reminders.isEmpty()) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "Нет напоминаний"))
                    )
                }
                
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val list = reminders.joinToString("\n") { reminder ->
                    val nextTime = reminder.nextExecution?.let { 
                        Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .format(formatter)
                    } ?: "н/а"
                    "• ${reminder.title} (${reminder.cronExpression}) → $nextTime"
                }
                
                CallToolResult(
                    content = listOf(TextContent(text = "Напоминания:\n$list"))
                )
            } catch (e: Exception) {
                logger.error("Error in list_reminders", e)
                CallToolResult(
                    content = listOf(TextContent(text = "Ошибка: ${e.message}")),
                    isError = true
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // TOOL 3: Удаление
        // ═══════════════════════════════════════════════════════════════
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
            try {
                val id = request.arguments["id"]?.jsonPrimitive?.content ?: ""
                runBlocking { storage.remove(id) }
                logger.info("✓ Removed: ${id.take(8)}")
                
                CallToolResult(
                    content = listOf(TextContent(text = "✓ Удалено"))
                )
            } catch (e: Exception) {
                logger.error("Error in remove_reminder", e)
                CallToolResult(
                    content = listOf(TextContent(text = "Ошибка: ${e.message}")),
                    isError = true
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // TOOL 4: Статистика
        // ═══════════════════════════════════════════════════════════════
        addTool(
            name = "get_stats",
            description = "Статистика выполнения",
            inputSchema = Tool.Input(
                properties = buildJsonObject { },
                required = listOf()
            )
        ) { _ ->
            try {
                val reminders = runBlocking { storage.loadAll() }
                val active = reminders.count { it.enabled }
                val total = reminders.size
                val executed = reminders.count { it.lastExecuted != null }
                
                val stats = """
                    📊 Статистика:
                    • Всего: $total
                    • Активных: $active
                    • Выполнено: $executed
                """.trimIndent()
                
                CallToolResult(content = listOf(TextContent(text = stats)))
            } catch (e: Exception) {
                logger.error("Error in get_stats", e)
                CallToolResult(
                    content = listOf(TextContent(text = "Ошибка: ${e.message}")),
                    isError = true
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // TOOL 5: Криптокурсы
        // ═══════════════════════════════════════════════════════════════
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
            try {
                val coinsArray = request.arguments["coins"]?.jsonArray
                val coins = coinsArray?.map { it.jsonPrimitive.content } ?: emptyList()
                
                val rates = runBlocking { coinCapClient.getRates(coins) }
                
                if (rates.isEmpty()) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "⚠️ Не удалось получить курсы"))
                    )
                }
                
                val formatted = rates.entries.joinToString("\n") { (coin, price) ->
                    "• ${coin.replaceFirstChar { it.uppercase() }}: $${String.format("%.2f", price)}"
                }
                
                CallToolResult(
                    content = listOf(TextContent(text = "💰 Курсы:\n$formatted"))
                )
            } catch (e: Exception) {
                logger.error("Error in check_crypto_rates", e)
                CallToolResult(
                    content = listOf(TextContent(text = "Ошибка: ${e.message}")),
                    isError = true
                )
            }
        }
        
        logger.info("✓ All 5 MCP tools registered")
    }
}
