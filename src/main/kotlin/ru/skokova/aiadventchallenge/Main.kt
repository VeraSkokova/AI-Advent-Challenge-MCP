package ru.skokova.aiadventchallenge

import ru.skokova.aiadventchallenge.mcp.DeveloperAssistantMCPServer
import ru.skokova.aiadventchallenge.ai.YandexGPTClient
import ru.skokova.aiadventchallenge.git.GitClient
import ru.skokova.aiadventchallenge.rag.client.YandexEmbeddingClient
import ru.skokova.aiadventchallenge.rag.services.IndexService
import ru.skokova.aiadventchallenge.rag.services.SearchService
import ru.skokova.aiadventchallenge.rag.services.TextChunker
import ru.skokova.aiadventchallenge.utils.getEnvOrProperty
import ru.skokova.aiadventchallenge.utils.loadProperties
import kotlinx.coroutines.runBlocking
import java.io.File
import org.slf4j.LoggerFactory

fun main() = runBlocking {
    val logger = LoggerFactory.getLogger("Main")
    val projectRoot = File(".")

    // 0. Environment / local.properties Check
    val props = loadProperties()
    val apiKey = getEnvOrProperty("YANDEX_API_KEY", props)
    val folderId = getEnvOrProperty("YANDEX_FOLDER_ID", props)

    // 1. Initialize Components
    val gitClient = GitClient(projectRoot)
    val embeddingClient = YandexEmbeddingClient(apiKey, folderId)
    val chunker = TextChunker()

    val indexService = IndexService(embeddingClient, chunker)
    val searchService = SearchService(embeddingClient)
    val gptClient = YandexGPTClient(apiKey, folderId)

    val mcpServer = DeveloperAssistantMCPServer(
        indexService = indexService,
        searchService = searchService,
        gitClient = gitClient,
        projectRoot = projectRoot
    )

    logger.info("🤖 AI Code Review Assistant Started")

    // 2. Get Diff (MCP Tool)
    logger.info("📄 Fetching git diff...")
    val diff = mcpServer.executeTool("get_pr_diff", emptyMap())

    if (diff.startsWith("No changes") || diff.isBlank()) {
        println("✅ No changes to review.")
        return@runBlocking
    }

    logger.info("🔍 Diff size: ${diff.length} chars")

    // 3. Extract Keywords & Get RAG Context
    val keywords = Regex("""\b([A-Z][a-zA-Z0-9]+)\b|\b([a-zA-Z0-9]+\.kt)\b""")
        .findAll(diff)
        .map { it.value }
        .distinct()
        .filter { it.length > 3 }
        .take(5)
        .joinToString(" ")

    logger.info("📚 Searching RAG context for keywords: $keywords")
    val ragContext = if (keywords.isNotEmpty()) {
        mcpServer.executeTool(
            "ask_project_docs",
            mapOf("query" to "Review rules and architecture related to: $keywords")
        )
    } else {
        "No specific keywords found for RAG context."
    }

    // 4. Construct Prompt
    val systemPrompt = """
        Ты - Senior Kotlin Developer и техлид проекта.
        Твоя задача: провести Code Review изменений в репозитории.

        Используй следующие критерии:
        1. Clean Architecture и SOLID.
        2. Kotlin Code Conventions (naming, null-safety).
        3. Обработка ошибок (try-catch, Result, logging).
        4. Отсутствие hardcoded значений.
        5. Читаемость и поддерживаемость.

        Формат ответа (Markdown):
        ## Summary
        Краткое описание изменений (1-2 предложения).

        ## Code Review
        Список замечаний. Для каждого замечания укажи:
        - 🔴 Critical / 🟡 Warning / 🟢 Info
        - Файл (если понятно из диффа)
        - Суть проблемы
        - Пример улучшения (код)

        ## Verdict
        APPROVE / REQUEST CHANGES
    """.trimIndent()

    val userPrompt = """
        ### Context from Documentation/Codebase (RAG):
        $ragContext

        ### Changes (Git Diff):
        ```diff
        $diff
        ```

        Review this code.
    """.trimIndent()

    // 5. Generate Review
    logger.info("🧠 Generating review with YandexGPT...")
    val review = gptClient.chat(systemPrompt, userPrompt, model = "yandexgpt")

    // 6. Output
    println("\n" + "=".repeat(20) + " AI Code Review " + "=".repeat(20))
    println(review)
    println("=".repeat(56))
}
