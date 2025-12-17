package ru.skokova.aiadventchallenge.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.scheduler.CronParser
import java.io.File

/**
 * Thread-safe хранилище напоминаний с JSON персистентностью
 *
 * @property storageFile Файл для сохранения данных (по умолчанию data/reminders.json)
 */
class ReminderStorage(
    private val storageFile: File = File("data/reminders.json")
) {
    private val mutex = Mutex()
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val logger = LoggerFactory.getLogger(ReminderStorage::class.java)
    
    init {
        // Создаём директорию если не существует
        storageFile.parentFile?.mkdirs()
        logger.info("ReminderStorage initialized with file: ${storageFile.absolutePath}")
    }
    
    /**
     * Сохранить новое напоминание
     */
    suspend fun save(reminder: Reminder) {
        logger.debug("🔒 Acquiring lock for save()...")
        mutex.withLock {
            logger.debug("🔓 Lock acquired, loading existing reminders...")
            val reminders = loadAllUnsafe().toMutableList()
            logger.debug("📊 Current reminders count: ${reminders.size}")
            
            reminders.add(reminder)
            logger.debug("➕ Added new reminder, total: ${reminders.size}")
            
            val jsonContent = json.encodeToString(reminders)
            logger.debug("📝 Writing to file: ${storageFile.absolutePath}")
            
            storageFile.writeText(jsonContent)
            logger.info("✓ Reminder saved: ${reminder.id.take(8)} - ${reminder.title}")
            logger.debug("📁 File size: ${storageFile.length()} bytes")
        }
        logger.debug("🔓 Lock released")
    }
    
    /**
     * Загрузить все напоминания
     */
    suspend fun loadAll(): List<Reminder> = mutex.withLock {
        loadAllUnsafe()
    }
    
    /**
     * Загрузить без блокировки (используется внутри mutex.withLock)
     */
    private fun loadAllUnsafe(): List<Reminder> {
        if (!storageFile.exists()) {
            logger.debug("Storage file does not exist, returning empty list")
            return emptyList()
        }
        
        try {
            val content = storageFile.readText()
            if (content.isBlank()) {
                logger.debug("Storage file is empty, returning empty list")
                return emptyList()
            }
            return json.decodeFromString<List<Reminder>>(content)
        } catch (e: Exception) {
            logger.error("Error loading reminders from storage", e)
            return emptyList()
        }
    }
    
    /**
     * Получить напоминания которые нужно выполнить (nextExecution <= now)
     */
    suspend fun getDueReminders(now: Long): List<Reminder> {
        return loadAll().filter { reminder ->
            reminder.enabled && reminder.nextExecution?.let { it <= now } == true
        }
    }
    
    /**
     * Обновить время выполнения и рассчитать следующее
     */
    suspend fun updateExecution(id: String, executedAt: Long) = mutex.withLock {
        val reminders = loadAllUnsafe().map { reminder ->
            if (reminder.id == id) {
                val nextExec = CronParser.calculateNext(
                    reminder.cronExpression,
                    executedAt
                )
                reminder.copy(
                    lastExecuted = executedAt,
                    nextExecution = nextExec
                )
            } else {
                reminder
            }
        }
        storageFile.writeText(json.encodeToString(reminders))
        logger.info("✓ Updated execution time for reminder: ${id.take(8)}")
    }
    
    /**
     * Удалить напоминание по ID
     */
    suspend fun remove(id: String) = mutex.withLock {
        val reminders = loadAllUnsafe().filter { it.id != id }
        storageFile.writeText(json.encodeToString(reminders))
        logger.info("✓ Removed reminder: ${id.take(8)}")
    }
    
    /**
     * Обновить напоминание
     */
    suspend fun update(reminder: Reminder) = mutex.withLock {
        val reminders = loadAllUnsafe().map { 
            if (it.id == reminder.id) reminder else it
        }
        storageFile.writeText(json.encodeToString(reminders))
        logger.info("✓ Updated reminder: ${reminder.id.take(8)} - ${reminder.title}")
    }
}
