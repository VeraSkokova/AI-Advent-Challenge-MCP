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
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    val logger = LoggerFactory.getLogger("ReviewPipeline")
    val projectRoot = File(".")

    // 0. Configuration & Environment
    val props = loadProperties()
    val apiKey = try {
        getEnvOrProperty("YANDEX_API_KEY", props)
    } catch (e: Exception) {
        logger.error("❌ Configuration error: ${e.message}")
        return@runBlocking
    }
    val folderId = getEnvOrProperty("YANDEX_FOLDER_ID", props)

    // 1. Initialize Core Services
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
    
    // 2. Parse Command (Pipeline Entry Point)
    val command = args.firstOrNull() ?: "review" // Default action
    
    logger.info("🚀 Starting Pipeline Action: $command")
    
    when (command) {
        "index" -> {
            logger.info("📚 Building RAG Index from docs/ ...")
            val index = indexService.createIndex(File(projectRoot, "docs").absolutePath)
            indexService.saveIndex(index)
            logger.info("✅ Indexing complete. Saved ${index.chunks.size} chunks.")
        }
        "review" -> {
            executeReviewPipeline(logger, mcpServer, gptClient)
        }
        else -> {
            logger.error("Unknown command: $command. Available: 'index', 'review'")
            exitProcess(1)
        }
    }
}

suspend fun executeReviewPipeline(
    logger: org.slf4j.Logger,
    mcpServer: DeveloperAssistantMCPServer,
    gptClient: YandexGPTClient
) {
    // Pipeline Step 1: Get Changes
    logger.info("🔹 [Step 1] Fetching git diff via MCP...")
    val diff = mcpServer.executeTool("get_pr_diff", emptyMap())
    
    if (diff.startsWith("No changes") || diff.isBlank()) {
        logger.info("✅ No changes detected. Pipeline finished.")
        return
    }
    logger.info("   Diff found: ${diff.length} chars")

    // Pipeline Step 2: Context Retrieval (RAG)
    // We specifically look for policy violations keywords in diff to match docs
    val keywords = listOf("println", "System.out", "TODO", "catch", "Exception", "key", "token", "password")
    val diffKeywords = keywords.filter { diff.contains(it) }.joinToString(" ")
    
    val query = if (diffKeywords.isNotEmpty()) {
        "Find coding standards and rules about: $diffKeywords"
    } else {
        "General coding standards and best practices"
    }
    
    logger.info("🔹 [Step 2] Querying RAG Knowledge Base...")
    logger.info("   Query: '$query'")
    
    val ragContext = mcpServer.executeTool("ask_project_docs", mapOf("query" to query))
    
    // Pipeline Step 3: Analysis (LLM)
    logger.info("🔹 [Step 3] Running AI Analysis...")
    
    val systemPrompt = """
        You are an AI Code Reviewer enforcing strict project policies.
        
        YOUR GOAL:
        Analyze the provided Git Diff against the provided Documentation Context.
        
        STRICT REQUIREMENT:
        If you find a violation of a rule found in the Context:
        1. Quote the rule ID and Description.
        2. Cite the source document name.
        3. Point to the specific line in the diff.
        
        Format:
        ## 🚨 Violations Found
        
        ### [Rule ID] Rule Name
        **Source:** `Filename.md`
        **Violation:** Description of what is wrong.
        **Fix:**
        ```kotlin
        // Correct code
        ```
        
        If no strict violations are found, provide a general "Best Practices" review.
    """.trimIndent()

    val userPrompt = """
        ### 📚 Documentation Context (Ground Truth):
        $ragContext
        
        ### 📝 Code Changes (Git Diff):
        ```diff
        $diff
        ```
        
        Analyze now.
    """.trimIndent()

    val review = gptClient.chat(systemPrompt, userPrompt, model = "yandexgpt")

    // Pipeline Step 4: Report
    println("\n" + "=".repeat(20) + " AI Review Report " + "=".repeat(20))
    println(review)
    println("=".repeat(58))
}
