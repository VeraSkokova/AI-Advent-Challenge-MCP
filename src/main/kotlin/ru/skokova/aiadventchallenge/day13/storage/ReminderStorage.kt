package ru.skokova.aiadventchallenge.day13.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.skokova.aiadventchallenge.day13.scheduler.CronParser
import java.io.File

/**
 * Хранилище напоминаний с JSON-персистентностью
 * Использует Mutex для thread-safe операций
 */
class ReminderStorage(
    private val storageFile: File = File("data/reminders.json")
) {
    private val mutex = Mutex()
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    init {
        storageFile.parentFile?.mkdirs()
        if (!storageFile.exists()) {
            storageFile.writeText("[]")
        }
    }
    
    /**
     * Сохранить новое напоминание
     */
    suspend fun save(reminder: Reminder) = mutex.withLock {
        val reminders = loadAll().toMutableList()
        reminders.add(reminder)
        storageFile.writeText(json.encodeToString(reminders))
    }
    
    /**
     * Загрузить все напоминания
     */
    suspend fun loadAll(): List<Reminder> = mutex.withLock {
        if (!storageFile.exists()) return emptyList()
        try {
            json.decodeFromString<List<Reminder>>(storageFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Получить напоминания, которые нужно выполнить (nextExecution <= now)
     */
    suspend fun getDueReminders(now: Long): List<Reminder> {
        return loadAll().filter { reminder ->
            reminder.enabled && 
            reminder.nextExecution?.let { it <= now } == true
        }
    }
    
    /**
     * Обновить время выполнения и рассчитать следующее
     */
    suspend fun updateExecution(id: String, executedAt: Long) = mutex.withLock {
        val reminders = loadAll().map { reminder ->
            if (reminder.id == id) {
                reminder.copy(
                    lastExecuted = executedAt,
                    nextExecution = CronParser.calculateNext(
                        reminder.cronExpression,
                        executedAt
                    )
                )
            } else reminder
        }
        storageFile.writeText(json.encodeToString(reminders))
    }
    
    /**
     * Удалить напоминание по ID
     */
    suspend fun remove(id: String) = mutex.withLock {
        val reminders = loadAll().filter { it.id != id }
        storageFile.writeText(json.encodeToString(reminders))
    }
    
    /**
     * Обновить напоминание
     */
    suspend fun update(reminder: Reminder) = mutex.withLock {
        val reminders = loadAll().map { 
            if (it.id == reminder.id) reminder else it 
        }
        storageFile.writeText(json.encodeToString(reminders))
    }
}