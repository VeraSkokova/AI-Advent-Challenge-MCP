package ru.skokova.aiadventchallenge

import ru.skokova.aiadventchallenge.mcp.DeveloperAssistantMCPServer
import ru.skokova.aiadventchallenge.ai.YandexGPTClient
import ru.skokova.aiadventchallenge.git.GitClient
import ru.skokova.aiadventchallenge.git.GitHubClient
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
    // Optional: GITHUB_TOKEN
    val githubToken = System.getenv("GITHUB_TOKEN") ?: props?.getProperty("GITHUB_TOKEN")

    // 1. Initialize Core Services
    val gitClient = GitClient(projectRoot)
    val githubClient = GitHubClient(githubToken)
    val embeddingClient = YandexEmbeddingClient(apiKey, folderId)
    val chunker = TextChunker()
    val indexService = IndexService(embeddingClient, chunker)
    val searchService = SearchService(embeddingClient)
    val gptClient = YandexGPTClient(apiKey, folderId)

    val mcpServer = DeveloperAssistantMCPServer(
        indexService = indexService,
        searchService = searchService,
        gitClient = gitClient,
        gitHubClient = githubClient,
        projectRoot = projectRoot
    )
    
    // 2. Parse Command (Pipeline Entry Point)
    val command = args.firstOrNull() ?: "review" 
    
    logger.info("🚀 Starting Pipeline Action: $command")
    
    when {
        command == "index" -> {
            logger.info("📚 Building RAG Index from docs/ and src/main/kotlin ...")
            val paths = listOf(
                File(projectRoot, "docs").absolutePath,
                File(projectRoot, "src/main/kotlin").absolutePath
            )
            val index = indexService.createIndex(paths)
            indexService.saveIndex(index)
            logger.info("✅ Indexing complete. Saved ${index.chunks.size} chunks.")
        }
        command == "review" -> {
            logger.info("🔹 Fetching LOCAL git diff...")
            val diff = mcpServer.executeTool("get_pr_diff", emptyMap())
            executeReviewAnalysis(logger, diff, mcpServer, gptClient)
        }
        command.startsWith("review_pr") -> {
            // Usage: review_pr https://github.com/owner/repo/pull/123
            val url = args.getOrNull(1)
            if (url == null) {
                logger.error("❌ Usage: review_pr <github_pr_url>")
                return@runBlocking
            }
            
            // Parse URL: https://github.com/owner/repo/pull/123
            val regex = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
            val match = regex.find(url)
            if (match == null) {
                logger.error("❌ Invalid GitHub PR URL format. Expected: https://github.com/owner/repo/pull/123")
                return@runBlocking
            }
            
            val (owner, repo, prNum) = match.destructured
            logger.info("🔹 Fetching REMOTE PR diff for $owner/$repo #$prNum...")
            
            val diff = mcpServer.executeTool("fetch_github_pr_diff", mapOf(
                "owner" to owner,
                "repo" to repo,
                "pr_number" to prNum.toInt()
            ))
            
            executeReviewAnalysis(logger, diff, mcpServer, gptClient)
        }
        else -> {
            logger.error("Unknown command: $command. Available: 'index', 'review', 'review_pr <url>'")
            exitProcess(1)
        }
    }
}

/**
 * Common logic for analyzing code regardless of source (local diff or remote PR)
 */
suspend fun executeReviewAnalysis(
    logger: org.slf4j.Logger,
    diff: String,
    mcpServer: DeveloperAssistantMCPServer,
    gptClient: YandexGPTClient
) {
    if (diff.startsWith("No changes") || diff.isBlank() || diff.startsWith("Error")) {
        logger.info("⚠️ Review aborted. Reason: $diff")
        return
    }
    logger.info("   Diff found: ${diff.length} chars")

    // Pipeline Step 2: Context Retrieval (RAG)
    val keywords = listOf("println", "System.out", "TODO", "catch", "Exception", "key", "token", "password")
    val diffKeywords = keywords.filter { diff.contains(it) }.joinToString(" ")
    
    val classNames = Regex("""class\s+([A-Z][a-zA-Z0-9]+)""")
        .findAll(diff)
        .map { it.groupValues[1] }
        .joinToString(" ")

    val query = buildString {
        if (diffKeywords.isNotEmpty()) append("Find coding standards and rules about: $diffKeywords. ")
        if (classNames.isNotEmpty()) append("Find existing code related to: $classNames. ")
        if (isEmpty()) append("General coding standards and best practices")
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
        1. If you find a violation of a rule found in the Context, you MUST cite the source document AND the line numbers.
        2. The Context provided includes line numbers (e.g. Filename.md:10-15). You MUST include these in your citation.
        3. Quote the specific Rule ID if available (e.g., LOG-001).
        
        Format your response as follows:
        
        ## 🚨 Violations Found
        
        ### [Rule ID] Rule Name
        **Source:** `Filename.md:StartLine-EndLine`
        **Violation:** <Description of what is wrong>
        **Fix:**
        ```kotlin
        // Correct code
        ```
        
        If no strict violations are found, provide a general "Best Practices" review.
    """.trimIndent()

    val userPrompt = """
        ### 📚 Documentation & Code Context (RAG):
        $ragContext
        
        ### 📝 Code Changes (Git Diff):
        ```diff
        $diff
        ```
        
        Analyze now. Remember to cite your sources with line numbers!
    """.trimIndent()

    val review = gptClient.chat(systemPrompt, userPrompt, model = "yandexgpt")

    // Pipeline Step 4: Report
    println("\n" + "=".repeat(20) + " AI Review Report " + "=".repeat(20))
    println(review)
    println("=".repeat(58))
}
