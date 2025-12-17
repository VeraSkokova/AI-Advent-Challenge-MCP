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
    println("\nCommands: add | list | test <command> | exit\n")
    
    val reader = System.`in`.bufferedReader()
    
    while (true) {
        try {
            print("> ")
            System.out.flush() // Важно для вывода приглашения
            
            val input = reader.readLine()
            
            // Проверяем на null (Ctrl+D / EOF)
            if (input == null) {
                logger.info("EOF detected, shutting down...")
                break
            }
            
            // Пропускаем пустые строки
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
                    val reminders = storage.loadAll()
                    if (reminders.isEmpty()) {
                        println("No reminders")
                    } else {
                        reminders.forEach { r ->
                            val next = r.nextExecution?.let {
                                CronParser.formatTimestamp(it)
                            } ?: "?"
                            println("  ${r.id.take(8)}: ${r.title} (${r.cronExpression}) → $next")
                        }
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
                    // Формат: add "Title" "Command" "0 9 * * *"
                    val parts = input.trim().removePrefix("add ")
                        .split("\" \"")
                        .map { it.trim('"') }
                    
                    if (parts.size == 3) {
                        val reminder = Reminder(
                            title = parts[0],
                            command = parts[1],
                            cronExpression = parts[2],
                            nextExecution = CronParser.calculateNext(parts[2], System.currentTimeMillis())
                        )
                        storage.save(reminder)
                        println("✅ Reminder added: ${reminder.id.take(8)} - ${reminder.title}")
                    } else {
                        println("Usage: add \"Title\" \"Command\" \"Cron\"")
                        println("Example: add \"Крипто\" \"Проверь BTC и ETH\" \"0 * * * *\"")
                    }
                }
                
                else -> {
                    println("Unknown command. Try: add, list, test, exit")
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing command", e)
            println("Error: ${e.message}")
        }
    }
    
    logger.info("Server stopped")
}
