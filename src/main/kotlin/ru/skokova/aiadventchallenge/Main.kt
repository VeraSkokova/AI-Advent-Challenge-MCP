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

suspend fun main() {
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
        mcpServer = mcpServer.server,
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
    
    // CLI интерфейс
    println("\nCommands: add | list | test <command> | exit\n")
    
    while (true) {
        print("> ")
        val input = readLine() ?: break
        
        when {
            input == "exit" -> {
                logger.info("Shutting down...")
                schedulerJob.cancel()
                break
            }
            
            input == "list" -> {
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
            
            input.startsWith("test ") -> {
                val command = input.removePrefix("test ")
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
            
            input.startsWith("add ") -> {
                // Формат: add "Title" "Command" "0 9 * * *"
                val parts = input.removePrefix("add ")
                    .split("\" \"")
                    .map { it.trim('"') }
                
                if (parts.size == 3) {
                    val reminder = Reminder(
                        title = parts[0],
                        command = parts[1],
                        cronExpression = parts[2],
                        nextExecution = CronParser.calculateNext(parts[2], System.currentTimeMillis())
                    )
                    runBlocking { storage.save(reminder) }
                    println("✅ Reminder added: ${reminder.id.take(8)} - ${reminder.title}")
                } else {
                    println("Usage: add \"Title\" \"Command\" \"Cron\"")
                    println("Example: add \"Crypto Check\" \"Проверь курсы BTC и ETH\" \"0 * * * *\"")
                }
            }
            
            else -> {
                println("Unknown command. Try: add, list, test, exit")
            }
        }
    }
    
    logger.info("Server stopped")
}
