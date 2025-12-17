package ru.skokova.aiadventchallenge.scheduler

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.notifications.NotificationService
import ru.skokova.aiadventchallenge.storage.ReminderStorage

/**
 * Планировщик напоминаний - работает 24/7 в фоновой корутине
 *
 * Проверяет каждую минуту: reminder.nextExecution <= currentTime
 * Когда время наступило → вызывает AI Agent с reminder.command
 *
 * @property reminderStorage Хранилище напоминаний
 * @property aiAgent AI-агент для выполнения команд
 * @property notificationService Сервис уведомлений
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
        logger.info("ℹ️ AI Agent integration enabled")
        
        while (true) {
            try {
                val now = System.currentTimeMillis()
                val dueReminders = reminderStorage.getDueReminders(now)
                
                if (dueReminders.isNotEmpty()) {
                    logger.info("📋 Found ${dueReminders.size} reminder(s) to execute")
                }
                
                dueReminders.forEach { reminder ->
                    logger.info("⏰ Executing reminder: ${reminder.title}")
                    logger.info("💬 Command: ${reminder.command}")
                    
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
                        
                        logger.info("✅ Reminder '${reminder.title}' completed successfully")
                        logger.info("📝 Summary: ${agentResponse.summary}")
                        logger.info("🔧 Tools used: ${agentResponse.toolCalls.map { it.toolName }}")
                        
                    } catch (e: Exception) {
                        logger.error("❌ Error executing reminder '${reminder.title}'", e)
                        
                        // Отправляем notification об ошибке
                        notificationService.notify(
                            title = "⚠️ Ошибка: ${reminder.title}",
                            message = "Не удалось выполнить: ${e.message}"
                        )
                    } finally {
                        // Обновляем время выполнения и рассчитываем следующее
                        reminderStorage.updateExecution(reminder.id, now)
                        logger.info("🔄 Next execution scheduled for reminder: ${reminder.id.take(8)}")
                    }
                }
            } catch (e: Exception) {
                logger.error("💥 Scheduler error (will retry in 60 seconds)", e)
            }
            
            // Пауза 60 секунд перед следующей проверкой
            delay(60_000)
        }
    }
}
