package ru.skokova.aiadventchallenge.day13.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser as CronUtilsParser
import java.time.ZonedDateTime

/**
 * Парсер cron-выражений и расчет следующего времени выполнения
 */
object CronParser {
    private val parser = CronUtilsParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    )
    
    /**
     * Вычисляет следующее время выполнения на основе cron expression
     * 
     * @param cronExpression - "0 9 * * *" для 9:00 каждый день
     * @param fromTime - время от которого считать (обычно System.currentTimeMillis())
     * @return timestamp следующего выполнения или null если невалидный cron
     */
    fun calculateNext(cronExpression: String, fromTime: Long): Long? {
        return try {
            val cron = parser.parse(cronExpression)
            val executionTime = ExecutionTime.forCron(cron)
            val zonedDateTime = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(fromTime),
                java.time.ZoneId.systemDefault()
            )
            
            executionTime.nextExecution(zonedDateTime)
                .map { it.toInstant().toEpochMilli() }
                .orElse(null)
        } catch (e: Exception) {
            System.err.println("Невалидное cron-выражение: $cronExpression - ${e.message}")
            null
        }
    }
    
    /**
     * Проверяет валидность cron-выражения
     */
    fun isValid(cronExpression: String): Boolean {
        return try {
            parser.parse(cronExpression)
            true
        } catch (e: Exception) {
            false
        }
    }
}