package ru.skokova.aiadventchallenge.mcp

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.shared.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.shared.TextContent
import kotlinx.coroutines.runBlocking
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
 * Предоставляет 5 tools:
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
                tools = ServerCapabilities.Tool
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
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title" to mapOf(
                        "type" to "string",
                        "description" to "Название напоминания"
                    ),
                    "command" to mapOf(
                        "type" to "string",
                        "description" to "Команда для AI-агента при выполнении"
                    ),
                    "cronExpression" to mapOf(
                        "type" to "string",
                        "description" to "Cron выражение (например: '0 9 * * *' для 9:00)"
                    )
                ),
                "required" to listOf("title", "command", "cronExpression")
            )
        ) { request ->
            try {
                val title = request.arguments["title"] as String
                val command = request.arguments["command"] as String
                val cron = request.arguments["cronExpression"] as String
                
                // Валидируем cron выражение
                if (!CronParser.isValid(cron)) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "⚠️ Невалидное cron выражение: $cron")),
                        isError = true
                    )
                }
                
                val nextExec = CronParser.calculateNext(cron, System.currentTimeMillis())
                    ?: throw IllegalArgumentException("Invalid cron expression: $cron")
                
                val reminder = Reminder(
                    title = title,
                    command = command,
                    cronExpression = cron,
                    nextExecution = nextExec
                )
                
                runBlocking { storage.save(reminder) }
                logger.info("✓ Created reminder: ${reminder.id.take(8)} - $title")
                
                CallToolResult(
                    content = listOf(
                        TextContent(
                            text = "✓ Напоминание '${title}' создано (ID: ${reminder.id.take(8)})"
                        )
                    )
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
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "status" to mapOf(
                        "type" to "string",
                        "enum" to listOf("active", "all"),
                        "description" to "Фильтр по статусу"
                    )
                )
            )
        ) { request ->
            try {
                val status = request.arguments["status"] as? String ?: "active"
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
                    } ?: "не запланировано"
                    
                    "• ${reminder.title} (${reminder.cronExpression}) - следующее: $nextTime"
                }
                
                CallToolResult(
                    content = listOf(
                        TextContent(text = "Напоминания ($status):\n$list")
                    )
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
        // TOOL 3: Удаление напоминания
        // ═══════════════════════════════════════════════════════════════
        addTool(
            name = "remove_reminder",
            description = "Удалить напоминание по ID",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf(
                        "type" to "string",
                        "description" to "ID напоминания"
                    )
                ),
                "required" to listOf("id")
            )
        ) { request ->
            try {
                val id = request.arguments["id"] as String
                runBlocking { storage.remove(id) }
                logger.info("✓ Removed reminder: ${id.take(8)}")
                
                CallToolResult(
                    content = listOf(TextContent(text = "✓ Напоминание удалено"))
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
            description = "Получить статистику выполнения задач",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ) { request ->
            try {
                val reminders = runBlocking { storage.loadAll() }
                val active = reminders.count { it.enabled }
                val total = reminders.size
                val executed = reminders.count { it.lastExecuted != null }
                
                val stats = """
                    📊 Статистика:
                    • Всего напоминаний: $total
                    • Активных: $active
                    • Выполнено хотя бы раз: $executed
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
        // TOOL 5: Проверка курсов криптовалют
        // ═══════════════════════════════════════════════════════════════
        addTool(
            name = "check_crypto_rates",
            description = "Получить текущие курсы криптовалют",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "coins" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "Список монет: bitcoin, ethereum и т.д."
                    )
                ),
                "required" to listOf("coins")
            )
        ) { request ->
            try {
                @Suppress("UNCHECKED_CAST")
                val coins = (request.arguments["coins"] as List<*>).map { it.toString() }
                
                val rates = runBlocking { coinCapClient.getRates(coins) }
                
                if (rates.isEmpty()) {
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent(text = "⚠️ Не удалось получить курсы для указанных монет")
                        )
                    )
                }
                
                val formatted = rates.entries.joinToString("\n") { (coin, price) ->
                    "• ${coin.replaceFirstChar { it.uppercase() }}: $${String.format("%.2f", price)}"
                }
                
                CallToolResult(
                    content = listOf(
                        TextContent(text = "💰 Курсы криптовалют:\n$formatted")
                    )
                )
            } catch (e: Exception) {
                logger.error("Error in check_crypto_rates", e)
                CallToolResult(
                    content = listOf(TextContent(text = "Ошибка: ${e.message}")),
                    isError = true
                )
            }
        }
        
        logger.info("✓ All 5 MCP tools registered successfully")
    }
}
