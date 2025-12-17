package ru.skokova.aiadventchallenge.day13.scheduler

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.day13.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.day13.notifications.NotificationService
import ru.skokova.aiadventchallenge.day13.storage.ReminderStorage

/**
 * Планировщик напоминаний - работает 24/7 в фоновой корутине
 * Проверяет задачи каждую минуту и вызывает AI-агента для выполнения команд
 */
class ReminderScheduler(
    private val reminderStorage: ReminderStorage,
    private val aiAgent: YandexAIAgent,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(ReminderScheduler::class.java)
    
    /**
     * Основной метод: запускается при старте приложения и работает бесконечно
     * Проверяет задачи каждую минуту
     */
    suspend fun start() {
        logger.info("🕐 Reminder Scheduler started (checking every 60 seconds)")
        logger.info("🤖 AI Agent integration enabled")
        
        while (true) {
            try {
                val now = System.currentTimeMillis()
                val dueReminders = reminderStorage.getDueReminders(now)
                
                if (dueReminders.isNotEmpty()) {
                    logger.info("📋 Found ${dueReminders.size} reminders to execute")
                }
                
                dueReminders.forEach { reminder ->
                    logger.info("⏰ Executing reminder: ${reminder.title}")
                    
                    try {
                        // ═══════════════════════════════════════════════
                        // ОСНОВНАЯ ЛОГИКА: Передаём команду AI-агенту
                        // ═══════════════════════════════════════════════
                        val agentResponse = aiAgent.executeCommand(reminder.command)
                        
                        // AI-агент уже выполнил:
                        // 1. Проанализировал команду через YandexGPT
                        // 2. Вызвал нужные MCP tools
                        // 3. Собрал результаты
                        // 4. Сгенерировал человекочитаемый summary
                        
                        // Отправляем notification с результатом
                        notificationService.notify(
                            title = reminder.title,
                            message = agentResponse.summary
                        )
                        
                        logger.info("✅ Reminder '${reminder.title}' completed")
                        logger.info("   Summary: ${agentResponse.summary}")
                        logger.info("   Tools used: ${agentResponse.toolCalls.map { it.toolName }}")
                        
                    } catch (e: Exception) {
                        logger.error("❌ Error executing reminder '${reminder.title}'", e)
                        notificationService.notify(
                            title = "Ошибка: ${reminder.title}",
                            message = "Не удалось выполнить: ${e.message}"
                        )
                    } finally {
                        // Обновляем время выполнения
                        reminderStorage.updateExecution(reminder.id, now)
                    }
                }
            } catch (e: Exception) {
                logger.error("💥 Scheduler error", e)
            }
            
            // Ждём 60 секунд
            delay(60_000)
        }
    }
}