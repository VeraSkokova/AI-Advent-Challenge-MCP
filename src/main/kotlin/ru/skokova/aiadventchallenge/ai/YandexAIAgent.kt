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
    private val developerAssistantMcpServer: DeveloperAssistantMCPServer,
    private val yandexGPTClient: YandexGPTClient
) {
    private val logger = LoggerFactory.getLogger(YandexAIAgent::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        prettyPrint = false
        coerceInputValues = true
    }

    // Tools that are purely informational but we WANT the LLM to process their output
    // instead of returning raw tool output directly to the user.
    private val purelyInformationalTools = setOf(
        "list_devices",
        "check_adb",
        "get_logcat",
        "list_reminders",
        "get_stats",
        "help_overview",
        "ask_project_docs",
        "git_status",
        "git_diff_list",
        "git_current_branch"
    )

    private val actionKeywords = setOf(
        "запусти", "запуск", "start",
        "установи", "install",
        "удали", "remove", "delete",
        "создай", "create",
        "обнови", "update"
    )

    suspend fun executeCommand(command: String): AgentResponse {
        logger.info("🤖 Processing command: $command")

        val isActionQuery = actionKeywords.any { command.lowercase().contains(it) }
        val mentionsApk = command.lowercase().contains("apk") ||
            command.lowercase().contains("прилож") ||
            command.lowercase().contains("установ")

        val systemPrompt = buildSystemPrompt()
        val executedCalls = mutableListOf<ToolCall>()
        val rawResults = mutableMapOf<String, Any>()

        val rNames = reminderMcpServer.getToolsList().map { it.name }.toSet()
        val cNames = cryptoCurrencyMcpServer.getToolsList().map { it.name }.toSet()
        val sNames = summarizationMcpServer.getToolsList().map { it.name }.toSet()
        val fNames = filesystemClient.listTools().map { it.name }.toSet()
        val aNames = androidEnvironmentMcpServer.getToolsList().map { it.name }.toSet()
        val dNames = developerAssistantMcpServer.getToolsList().map { it.name }.toSet()

        var currentContext = command
        var iterationCount = 0
        val maxIterations = 10

        while (iterationCount < maxIterations) {
            iterationCount++
            logger.info("📍 Iteration $iterationCount")

            val contextMessage = buildContextMessage(command, currentContext, executedCalls, mentionsApk)

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
                        in dNames -> developerAssistantMcpServer.executeTool(call.toolName, call.parameters)
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

                // Не прерываем пайплайн для informational tools: даем LLM шанс обработать вывод на следующей итерации.
                if (!isActionQuery && call.toolName in purelyInformationalTools && result.startsWith("Error")) {
                    return AgentResponse("Произошла ошибка при получении информации: $result", executedCalls, rawResults)
                }

                // Ранний выход для Android сценариев оставляем
                if (!mentionsApk &&
                    call.toolName == "start_emulator" &&
                    result.contains("\"status\": \"success\"")) {
                    logger.info("✅ Emulator started successfully and no APK mentioned. Stopping pipeline.")
                    return AgentResponse(
                        """Эмулятор успешно запущен!

                        Примечание: Эмулятор загружается в фоне. Это может занять 2-3 минуты. Проверьте список устройств позже.""",
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
            Формат ответа СТРОГО JSON: {"tools": [{"name": "...", "params": {...}}]}
            
            Примеры:
            - "Запусти эмулятор Pixel_5" -> {"tools": [{"name": "start_emulator", "params": {"avdName": "Pixel_5"}}]}
            - "Установи APK из /path/app.apk" -> {"tools": [{"name": "install_apk", "params": {"apkPath": "/path/app.apk", "reinstall": true}}]}
            - "/help" (без аргументов) -> {"tools": [{"name": "help_overview", "params": {}}]}
            - "/help как добавить MCP tool" -> {"tools": [{"name": "ask_project_docs", "params": {"query": "как добавить MCP tool"}}]}
            
            ТВОЯ ЦЕЛЬ: Определить первый шаг.
            ВЕРНИ ТОЛЬКО JSON (без ``` и без пояснений).
            """.trimIndent()
        }

        val lastResult = previousResults.last()

        if (lastResult.toolName == "write_file" && !lastResult.result.startsWith("Error")) {
            return "STOP_SUCCESS"
        }

        if (lastResult.toolName == "start_app" && lastResult.result.contains("success")) {
            return "STOP_SUCCESS"
        }

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
            
            ТВОЯ ЦЕЛЬ:
            1. Если последний инструмент вернул контекст (например ask_project_docs/help_overview/git_status) -> СФОРМУЛИРУЙ финальный ответ пользователю на основе этих данных.
               - Ответ должен быть обычным текстом (НЕ JSON).
               - Укажи источники: перечисли файлы (sourceFile) из найденных чанков.
            2. Если нужно продолжить цепочку -> верни JSON строго в формате {"tools": [{"name": "...", "params": {...}}]}.
            
            ЕСЛИ ГОТОВ ОТВЕТИТЬ ПОЛЬЗОВАТЕЛЮ - пиши текст ответа.
            ЕСЛИ НУЖЕН ИНСТРУМЕНТ - пиши JSON.
        """.trimIndent()
    }

    private suspend fun buildSystemPrompt(): String {
        val allTools = mutableListOf<ToolInfo>()
        allTools.addAll(reminderMcpServer.getToolsList())
        allTools.addAll(cryptoCurrencyMcpServer.getToolsList())
        allTools.addAll(summarizationMcpServer.getToolsList())
        allTools.addAll(filesystemClient.listTools())
        allTools.addAll(androidEnvironmentMcpServer.getToolsList())
        allTools.addAll(developerAssistantMcpServer.getToolsList())

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
            2. Формат ответа для вызова инструмента - строго JSON: {"tools": [{"name": "...", "params": {...}}]}
            3. НЕ используй Markdown блоки (```)
            4. Если инструмент вернул ошибку, НЕ продолжай!
            5. При записи файлов используй папку "mcp-output/".
            
            КОМАНДА /help:
            - Если пользователь пишет просто "/help" без аргументов -> вызови "help_overview" (без параметров)
            - Если пользователь пишет "/help <вопрос>" -> вызови "ask_project_docs" с параметром query=<вопрос>
            
            ВАЖНО (RAG):
            - Инструмент ask_project_docs возвращает найденные чанки кода/доков.
            - После получения чанков СФОРМУЛИРУЙ человеческий ответ и обязательно укажи источники (список файлов), которые использовались.
            
            ANDROID РАБОЧИЙ ПРОЦЕСС:
            1. start_emulator -> запускает эмулятор
            2. wait_for_device -> ЖДЕТ полной загрузки (параметры {})
            3. install_apk -> устанавливает APK (ВСЕГДА reinstall: true)
            4. start_app -> запускает приложение (СТРОГО параметры {}, данные берутся из APK)
        """.trimIndent()
    }

    private fun parseToolCalls(response: String): List<ToolCall> {
        // Поддерживаем оба формата, потому что LLM иногда присылает:
        // 1) {"tools": [{...}]}
        // 2) {"name": "tool", "params": {...}}
        // и иногда оборачивает это в ```...

        val objStart = response.indexOf('{')
        val arrStart = response.indexOf('[')

        val startIndex = when {
            objStart == -1 && arrStart == -1 -> return emptyList()
            objStart == -1 -> arrStart
            arrStart == -1 -> objStart
            else -> minOf(objStart, arrStart)
        }

        val jsonString = when (response[startIndex]) {
            '{' -> {
                val end = response.lastIndexOf('}')
                if (end <= startIndex) return emptyList()
                response.substring(startIndex, end + 1)
            }
            '[' -> {
                val end = response.lastIndexOf(']')
                if (end <= startIndex) return emptyList()
                response.substring(startIndex, end + 1)
            }
            else -> return emptyList()
        }

        return try {
            val element = json.parseToJsonElement(jsonString)
            when (element) {
                is JsonObject -> {
                    val toolsArray = element["tools"]?.jsonArray
                    when {
                        !toolsArray.isNullOrEmpty() -> toolsArray.mapNotNull { parseSingleToolObject(it) }
                        element["name"] != null -> listOfNotNull(parseSingleToolObject(element))
                        else -> emptyList()
                    }
                }
                is JsonArray -> element.mapNotNull { parseSingleToolObject(it) }
                else -> emptyList()
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to parse JSON: $jsonString. Error: ${e.message}")
            emptyList()
        }
    }

    private fun parseSingleToolObject(toolElement: JsonElement): ToolCall? {
        if (toolElement !is JsonObject) return null

        val name = toolElement["name"]?.jsonPrimitive?.content ?: return null
        val paramsElement = toolElement["params"]

        val cleanParams: Map<String, Any> = when (paramsElement) {
            is JsonObject -> paramsElement.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> if (value.isString) value.content else value.toString()
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

        return ToolCall(name, cleanParams, "")
    }
}
