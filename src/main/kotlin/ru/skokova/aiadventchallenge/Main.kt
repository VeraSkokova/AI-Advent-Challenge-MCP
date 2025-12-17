package ru.skokova.aiadventchallenge

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.ai.YandexGPTClient
import ru.skokova.aiadventchallenge.coincap.CoinCapClient
import ru.skokova.aiadventchallenge.mcp.ReminderMCPServer
import ru.skokova.aiadventchallenge.notifications.NotificationService
import ru.skokova.aiadventchallenge.scheduler.CronParser
import ru.skokova.aiadventchallenge.scheduler.ReminderScheduler
import ru.skokova.aiadventchallenge.storage.Reminder
import ru.skokova.aiadventchallenge.storage.ReminderStorage
import ru.skokova.aiadventchallenge.utils.getEnvOrProperty
import ru.skokova.aiadventchallenge.utils.loadProperties

private val logger = LoggerFactory.getLogger("Main")

fun main() = runBlocking {
    logger.info("═══════════════════════════════════════════")
    logger.info("   Reminder MCP Server with AI Agent      ")
    logger.info("═══════════════════════════════════════════")
    
    // Загружаем конфигурацию из ENV или local.properties
    val properties = loadProperties()
    
    val apiKey = getEnvOrProperty("YANDEX_API_KEY", properties)
    val folderId = getEnvOrProperty("YANDEX_FOLDER_ID", properties)
    val coinCapKey = try {
        getEnvOrProperty("COINCAP_API_KEY", properties)
    } catch (e: Exception) {
        logger.warn("COINCAP_API_KEY not set, using CoinCap without auth (limited rate)")
        null
    }
    
    // Инициализация компонентов
    val storage = ReminderStorage()
    val yandexGPTClient = YandexGPTClient(apiKey, folderId)
    val coinCapClient = CoinCapClient(coinCapKey)
    
    val mcpServer = ReminderMCPServer(storage, coinCapClient)
    
    val aiAgent = YandexAIAgent(
        apiKey = apiKey,
        folderId = folderId,
        mcpServer = mcpServer,
        yandexGPTClient = yandexGPTClient
    )
    
    val notificationService = NotificationService()
    val scheduler = ReminderScheduler(storage, aiAgent, notificationService)
    
    // Запуск scheduler в фоне
    val schedulerJob = GlobalScope.launch {
        scheduler.start()
    }
    
    logger.info("🚀 Scheduler running in background (24/7)")
    logger.info("💬 CLI interface ready")
    logger.info("═══════════════════════════════════════════")
    
    // CLI интерфейс с BufferedReader
    println("\nCommands:")
    println("  add \"Title\" \"Command\" \"Cron\"  - Добавить напоминание")
    println("  list                          - Список напоминаний")
    println("  test <command>                - Тест AI агента")
    println("  exit                          - Выход\n")
    
    val reader = System.`in`.bufferedReader()
    
    while (true) {
        try {
            print("> ")
            System.out.flush()
            
            val input = reader.readLine()
            
            if (input == null) {
                logger.info("EOF detected, shutting down...")
                break
            }
            
            if (input.isBlank()) {
                continue
            }
            
            when {
                input.trim() == "exit" -> {
                    logger.info("Shutting down...")
                    schedulerJob.cancel()
                    break
                }
                
                input.trim() == "list" -> {
                    val reminders = runBlocking { storage.loadAll() }
                    if (reminders.isEmpty()) {
                        println("❌ No reminders")
                    } else {
                        println("\n📝 Reminders (${reminders.size}):")
                        reminders.forEach { r ->
                            val next = r.nextExecution?.let {
                                CronParser.formatTimestamp(it)
                            } ?: "?"
                            val status = if (r.enabled) "✅" else "❌"
                            println("  $status ${r.id.take(8)}: ${r.title}")
                            println("     Cron: ${r.cronExpression}")
                            println("     Next: $next")
                            if (r.lastExecuted != null) {
                                println("     Last: ${CronParser.formatTimestamp(r.lastExecuted!!)}")
                            }
                        }
                        println()
                    }
                }
                
                input.trim().startsWith("test ") -> {
                    val command = input.trim().removePrefix("test ")
                    println("\n🔄 Executing: $command")
                    try {
                        val response = aiAgent.executeCommand(command)
                        println("\n✅ Summary: ${response.summary}")
                        println("🔧 Tools used: ${response.toolCalls.map { it.toolName }}")
                    } catch (e: Exception) {
                        println("\n❌ Error: ${e.message}")
                        e.printStackTrace()
                    }
                }
                
                input.trim().startsWith("add ") -> {
                    try {
                        // Формат: add "Title" "Command" "0 9 * * *"
                        val parts = input.trim().removePrefix("add ")
                            .split("\" \"")
                            .map { it.trim('"') }
                        
                        logger.debug("🔍 Parsed parts: $parts (size=${parts.size})")
                        
                        if (parts.size != 3) {
                            println("❌ Invalid format. Use: add \"Title\" \"Command\" \"Cron\"")
                            println("💡 Example: add \"Крипто\" \"Проверь BTC и ETH\" \"*/1 * * * *\"")
                            continue
                        }
                        
                        val title = parts[0]
                        val command = parts[1]
                        val cronExpr = parts[2]
                        
                        // Валидация cron
                        if (!CronParser.isValid(cronExpr)) {
                            println("❌ Invalid cron expression: $cronExpr")
                            println("💡 Examples: */1 * * * * (every minute), 0 * * * * (hourly)")
                            continue
                        }
                        
                        val nextExec = CronParser.calculateNext(cronExpr, System.currentTimeMillis())
                        
                        val reminder = Reminder(
                            title = title,
                            command = command,
                            cronExpression = cronExpr,
                            nextExecution = nextExec
                        )
                        
                        runBlocking { storage.save(reminder) }
                        
                        println("✅ Reminder added: ${reminder.id.take(8)} - ${reminder.title}")
                        if (nextExec != null) {
                            println("⏰ Next execution: ${CronParser.formatTimestamp(nextExec)}")
                        }
                    } catch (e: Exception) {
                        println("❌ Error adding reminder: ${e.message}")
                        logger.error("Add reminder failed", e)
                    }
                }
                
                else -> {
                    println("❌ Unknown command. Try: add, list, test, exit")
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing command", e)
            println("❌ Error: ${e.message}")
        }
    }
    
    logger.info("Server stopped")
}
