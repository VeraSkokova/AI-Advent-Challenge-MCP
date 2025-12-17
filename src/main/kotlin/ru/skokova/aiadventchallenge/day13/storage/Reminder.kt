package ru.skokova.aiadventchallenge.day13.storage

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Напоминание с cron-расписанием
 * 
 * @property id Уникальный идентификатор
 * @property title Название для notification
 * @property command Команда для AI-агента при выполнении
 * @property cronExpression Cron-выражение (например, "0 9 * * *" для 9:00 каждый день)
 * @property createdAt Время создания
 * @property lastExecuted Время последнего выполнения
 * @property nextExecution Время следующего выполнения (рассчитывается из cron)
 * @property enabled Активно ли напоминание
 * @property metadata Дополнительные метаданные
 */
@Serializable
data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
    val cronExpression: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastExecuted: Long? = null,
    val nextExecution: Long? = null,
    val enabled: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)