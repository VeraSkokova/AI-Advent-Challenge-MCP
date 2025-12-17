package ru.skokova.aiadventchallenge.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser as CronUtilsParser
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

/**
 * Парсер cron-выражений и вычисление следующего времени выполнения
 */
object CronParser {
    private val parser = CronUtilsParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    )
    private val logger = LoggerFactory.getLogger(CronParser::class.java)
    
    /**
     * Вычисляет следующее время выполнения на основе cron expression
     *
     * @param cronExpression Cron выражение (Unix-формат: "0 9 * * *" для 9:00 каждый день)
     * @param fromTime Время от которого считать (обычно System.currentTimeMillis())
     * @return Timestamp следующего выполнения или null если невалидный cron
     */
    fun calculateNext(cronExpression: String, fromTime: Long): Long? {
        return try {
            val cron = parser.parse(cronExpression)
            cron.validate() // Валидация cron выражения
            
            val executionTime = ExecutionTime.forCron(cron)
            val zonedDateTime = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(fromTime),
                java.time.ZoneId.systemDefault()
            )
            
            executionTime.nextExecution(zonedDateTime)
                .map { it.toInstant().toEpochMilli() }
                .orElse(null)
                
        } catch (e: Exception) {
            logger.error("Invalid cron expression: '$cronExpression'", e)
            null
        }
    }
    
    /**
     * Проверяет валидность cron выражения
     *
     * @param cronExpression Cron выражение
     * @return true если валидно, false иначе
     */
    fun isValid(cronExpression: String): Boolean {
        return try {
            val cron = parser.parse(cronExpression)
            cron.validate()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Форматирование timestamp в читаемый вид
     */
    fun formatTimestamp(timestamp: Long): String {
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        val zonedDateTime = instant.atZone(java.time.ZoneId.systemDefault())
        return zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}
