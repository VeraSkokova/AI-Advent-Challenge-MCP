package ru.skokova.aiadventchallenge.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.mcp.*

data class AgentResponse(
    val summary: String,
    val toolCalls: List<ToolCall>,
    val rawResults: Map<String, Any> = emptyMap()
)

data class ToolCall(
    val toolName: String,
    val parameters: Map<String, Any>,
    val result: String
)

// Wrapper классы оставлены для совместимости, но логика парсинга теперь работает напрямую с JsonElement
@Serializable
data class ToolCallsWrapper(val tools: List<ToolCallData> = emptyList())
@Serializable
data class ToolCallData(val name: String, val params: Map<String, JsonElement>)

class YandexAIAgent(
    private val apiKey: String,
    private val folderId: String,
    private val reminderMcpServer: ReminderMCPServer,
    private val cryptoCurrencyMcpServer: CryptoCurrencyMCPServer,
    private val summarizationMcpServer: SummarizationMCPServer,
    private val filesystemClient: FilesystemMCPClient,
    private val androidEnvironmentMcpServer: AndroidEnvironmentMCPServer,
    private val yandexGPTClient: YandexGPTClient
) {
    private val logger = LoggerFactory.getLogger(YandexAIAgent::class.java)

    // Максимально мягкая конфигурация JSON для парсинга ответов LLM
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        prettyPrint = false
        coerceInputValues = true
    }
    
    // Список инструментов, которые являются чисто информационными (не меняют состояние системы)
    private val purelyInformationalTools = setOf(
        "list_devices",
        "check_adb",
        "get_logcat",
        "list_reminders",
        "get_stats"
    )
    
    // Список ключевых слов действий в запросах пользователя
    private val actionKeywords = setOf(
        "запусти", "запуск", "start",
        "установи", "install",
        "удали", "remove", "delete",
        "создай", "create",
        "обнови", "update"
    )

    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")
        
        // Определяем, является ли запрос действием или информационным
        val isActionQuery = actionKeywords.any { command.lowercase().contains(it) }
        
        // Проверяем, упоминается ли в запросе APK или приложение
        val mentionsApk = command.lowercase().contains("apk") || 
                         command.lowercase().contains("прилож") ||
                         command.lowercase().contains("установ")

        val systemPrompt = buildSystemPrompt()
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()

        // Списки инструментов для маршрутизации
        val rNames = reminderMcpServer.getToolsList().map { it.name }.toSet()
        val cNames = cryptoCurrencyMcpServer.getToolsList().map { it.name }.toSet()
        val sNames = summarizationMcpServer.getToolsList().map { it.name }.toSet()
        val fNames = filesystemClient.listTools().map { it.name }.toSet()
        val aNames = androidEnvironmentMcpServer.getToolsList().map { it.name }.toSet()

        var currentContext = command
        var iterationCount = 0
        val maxIterations = 10

        while (iterationCount < maxIterations) {
            iterationCount++
            logger.info("📍 Iteration $iterationCount")

            val contextMessage = buildContextMessage(command, currentContext, executedCalls, mentionsApk)

            // Если buildContextMessage вернул спец-сигнал об успехе, завершаем работу
            if (contextMessage == "STOP_SUCCESS") {
                logger.info("✅ Pipeline finished successfully via context check")
                return AgentResponse("Готово! Эмулятор запущен.", executedCalls, rawResults)
            }

            val response = yandexGPTClient.chat(systemPrompt, contextMessage)
            logger.info("📋 LLM Response: ${response.take(300)}...")

            val toolCalls = parseToolCalls(response)

            // Если инструментов нет, значит LLM вернула финальный текстовый ответ
            if (toolCalls.isEmpty()) {
                logger.info("✅ LLM вернула финальный ответ")
                return AgentResponse(response, executedCalls, rawResults)
            }

            for (call in toolCalls) {
                logger.info("🔧 Executing tool: ${call.toolName} with params: ${call.parameters}")

                val result = try {
                    when (call.toolName) {
                        in rNames -> reminderMcpServer.executeTool(call.toolName, call.parameters)
                        in cNames -> cryptoCurrencyMcpServer.executeTool(call.toolName, call.parameters)
                        in sNames -> summarizationMcpServer.executeTool(call.toolName, call.parameters)
                        in fNames -> filesystemClient.callTool(call.toolName, call.parameters)
                        in aNames -> androidEnvironmentMcpServer.executeTool(call.toolName, call.parameters)
                        else -> "Error: Tool not found '${call.toolName}'"
                    }
                } catch (e: Exception) {
                    logger.error("Error executing ${call.toolName}", e)
                    "Error: ${e.message}"
                }

                logger.info("✅ Result: ${result.take(150)}...")

                val toolCallWithResult = call.copy(result = result)
                executedCalls.add(toolCallWithResult)
                rawResults[call.toolName] = result
                currentContext = result
                
                // Ранний выход ТОЛЬКО для чисто информационных запросов
                if (!isActionQuery && 
                    call.toolName in purelyInformationalTools && 
                    result.contains("\"status\": \"success\"")) {
                    logger.info("✅ Informational tool '${call.toolName}' completed successfully. Stopping pipeline.")
                    return AgentResponse(
                        "Запрос выполнен успешно. Результат:\n$result",
                        executedCalls,
                        rawResults
                    )
                }
                
                // Ранний выход после успешного запуска эмулятора, если APK не упоминается
                if (!mentionsApk && 
                    call.toolName == "start_emulator" && 
                    result.contains("\"status\": \"success\"")) {
                    logger.info("✅ Emulator started successfully and no APK mentioned. Stopping pipeline.")
                    return AgentResponse(
                        "Эмулятор успешно запущен!\n\nПримечание: Эмулятор загружается в фоне. Это может занять 2-3 минуты. Проверьте список устройств позже.",
                        executedCalls,
                        rawResults
                    )
                }
            }
        }

        return AgentResponse(
            "Выполнено максимальное количество итераций ($maxIterations). Результаты собраны.",
            executedCalls,
            rawResults
        )
    }

    private fun buildContextMessage(
        originalCommand: String, 
        currentContext: String, 
        previousResults: List<ToolCall>,
        mentionsApk: Boolean
    ): String {
        if (previousResults.isEmpty()) {
            return """
            ЗАДАЧА ПОЛЬЗОВАТЕЛЯ: "$originalCommand"
            
            ВАЖНО: Извлекай параметры из текста задачи!
            Примеры:
            - "Запусти эмулятор Pixel_5" -> {"name": "start_emulator", "params": {"avdName": "Pixel_5"}}
            - "Установи APK из /path/app.apk" -> {"name": "install_apk", "params": {"apkPath": "/path/app.apk"}}
            
            ТВОЯ ЦЕЛЬ: Определить первый шаг.
            ВЕРНИ JSON с одним инструментом.
            """.trimIndent()
        }

        val lastResult = previousResults.last()

        // Условие раннего выхода: если файл успешно записан
        if (lastResult.toolName == "write_file" && !lastResult.result.startsWith("Error")) {
            return "STOP_SUCCESS"
        }
        
        // Условие раннего выхода: если приложение успешно запущено
        if (lastResult.toolName == "start_app" && lastResult.result.contains("success")) {
            return "STOP_SUCCESS"
        }
        
        // Условие раннего выхода: эмулятор запущен и APK не упоминается
        if (!mentionsApk && lastResult.toolName == "start_emulator" && lastResult.result.contains("success")) {
            return "STOP_SUCCESS"
        }

        val history = previousResults.joinToString("\n") { "✔ ${it.toolName}: Выполнено" }

        return """
            ИСХОДНАЯ ЗАДАЧА: "$originalCommand"
            
            ИСТОРИЯ ВЫПОЛНЕНИЯ:
            $history
            
            ⬇️ РЕЗУЛЬТАТ ПОСЛЕДНЕГО ШАГА (${lastResult.toolName}):
            ${lastResult.result}
            
            ТВОЯ ЦЕЛЬ: Вызвать следующий инструмент.
            
            ВАЖНЫЕ ПРАВИЛА:
            1. ВСЕГДА извлекай параметры из ИСХОДНОЙ ЗАДАЧИ.
            2. Если последний инструмент вернул ошибку, НЕ продолжай!
            3. Для crypto -> summarize -> write_file: копируй данные между шагами.
            4. Для Android: start_emulator -> (остановись если нет APK в задаче)
            
            ВЕРНИ ТОЛЬКО JSON с инструментом и параметрами.
        """.trimIndent()
    }

    private suspend fun buildSystemPrompt(): String {
        val allTools = mutableListOf<ToolInfo>()
        allTools.addAll(reminderMcpServer.getToolsList())
        allTools.addAll(cryptoCurrencyMcpServer.getToolsList())
        allTools.addAll(summarizationMcpServer.getToolsList())
        allTools.addAll(filesystemClient.listTools())
        allTools.addAll(androidEnvironmentMcpServer.getToolsList())

        val toolsDescription = allTools.joinToString("\n") { tool ->
            val paramsDesc = if (tool.parameters.isEmpty()) "no params" else tool.parameters.joinToString(", ")
            "- ${tool.name}: ${tool.description} (params: $paramsDesc)"
        }

        return """
            Ты AI-агент. Твоя задача - выполнять цепочку действий для решения задачи пользователя.
            
            ДОСТУПНЫЕ ИНСТРУМЕНТЫ:
            $toolsDescription
            
            ПРАВИЛА:
            1. ВСЕГДА извлекай параметры из текста задачи пользователя!
            2. Формат ответа - строго JSON: {"tools": [{"name": "...", "params": {...}}]}
            3. НЕ используй Markdown блоки (```)
            4. Если инструмент вернул ошибку, НЕ продолжай!
            5. При записи файлов используй папку "mcp-output/".
        """.trimIndent()
    }

    private fun parseToolCalls(response: String): List<ToolCall> {
        // Надежный способ извлечения JSON: ищем первую { и последнюю }
        val jsonStartIndex = response.indexOf('{')
        val jsonEndIndex = response.lastIndexOf('}')

        // Если скобок нет или порядок нарушен — считаем это просто текстом
        if (jsonStartIndex == -1 || jsonEndIndex == -1 || jsonEndIndex <= jsonStartIndex) {
            return emptyList()
        }

        val jsonString = response.substring(jsonStartIndex, jsonEndIndex + 1)

        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val toolsArray = root["tools"]?.jsonArray

            if (toolsArray.isNullOrEmpty()) return emptyList()

            toolsArray.mapNotNull { toolElement ->
                if (toolElement !is JsonObject) return@mapNotNull null

                val name = toolElement["name"]?.jsonPrimitive?.content ?: "unknown"
                val paramsElement = toolElement["params"]

                // Преобразуем параметры в Map<String, Any>, корректно обрабатывая вложенные JSON объекты
                val cleanParams: Map<String, Any> = when (paramsElement) {
                    is JsonObject -> paramsElement.mapValues { (_, value) ->
                        when (value) {
                            is JsonPrimitive -> if (value.isString) value.content else value.toString()
                            // Если параметр - это объект (например, data), превращаем его обратно в строку
                            is JsonObject -> value.toString()
                            is JsonArray -> {
                                if (value.all { it is JsonPrimitive && it.isString }) {
                                    value.map { (it as JsonPrimitive).content }
                                } else {
                                    value.toString()
                                }
                            }
                            else -> value.toString()
                        }
                    }
                    else -> emptyMap()
                }

                ToolCall(name, cleanParams, "")
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to parse JSON: $jsonString. Error: ${e.message}")
            emptyList()
        }
    }
}
