package ru.skokova.aiadventchallenge.storage

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Напоминание с командой для AI-агента и cron-расписанием
 *
 * @property id Уникальный идентификатор
 * @property title Название для notification
 * @property command Команда для AI-агента при выполнении (например: "Проверь курсы BTC и ETH")
 * @property cronExpression Cron выражение (например: "0 9 * * *" для 9:00 каждый день)
 * @property createdAt Timestamp создания
 * @property lastExecuted Timestamp последнего выполнения
 * @property nextExecution Timestamp следующего выполнения (рассчитывается из cron)
 * @property enabled Активно ли напоминание
 * @property metadata Дополнительные данные
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
) {
    companion object {
        /**
         * Примеры напоминаний для тестирования
         */
        fun createExamples(): List<Reminder> = listOf(
            Reminder(
                title = "Курс криптовалют",
                command = "Проверь текущие курсы Bitcoin и Ethereum, сравни с открытием дня и напиши динамику",
                cronExpression = "0 * * * *" // каждый час
            ),
            Reminder(
                title = "Утренняя сводка",
                command = "Покажи все активные напоминания на сегодня и статистику выполнений",
                cronExpression = "0 9 * * *" // 9:00 каждый день
            ),
            Reminder(
                title = "Недельная сводка",
                command = "Посчитай сколько задач выполнено за неделю и покажи топ-3 самых частых",
                cronExpression = "0 18 * * 5" // Пятница 18:00
            )
        )
    }
}
