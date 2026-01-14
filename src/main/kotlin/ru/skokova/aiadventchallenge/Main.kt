package ru.skokova.aiadventchallenge

import ru.skokova.aiadventchallenge.mcp.DeveloperAssistantMCPServer
import ru.skokova.aiadventchallenge.mcp.SupportMCPServer
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
import java.util.Scanner

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
    val githubToken = System.getenv("GITHUB_TOKEN") ?: props?.getProperty("GITHUB_TOKEN")

    // 1. Initialize Core Services
    val gitClient = GitClient(projectRoot)
    val githubClient = GitHubClient(githubToken)
    val embeddingClient = YandexEmbeddingClient(apiKey, folderId)
    val chunker = TextChunker()
    val indexService = IndexService(embeddingClient, chunker)
    val searchService = SearchService(embeddingClient)
    val gptClient = YandexGPTClient(apiKey, folderId)

    // MCP Servers
    val mcpServer = DeveloperAssistantMCPServer(
        indexService = indexService,
        searchService = searchService,
        gitClient = gitClient,
        gitHubClient = githubClient,
        projectRoot = projectRoot
    )
    val supportMcpServer = SupportMCPServer(File(projectRoot, "crm/users.json"))
    
    // 2. Parse Command
    val command = args.firstOrNull() ?: "review" 
    
    when {
        command == "index" -> {
            logger.info("📚 Building RAG Index...")
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
             // ... existing PR logic ...
            val url = args.getOrNull(1)
            if (url == null) {
                logger.error("❌ Usage: review_pr <github_pr_url>")
                return@runBlocking
            }
            val regex = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
            val match = regex.find(url)
            if (match == null) {
                logger.error("❌ Invalid GitHub PR URL format.")
                return@runBlocking
            }
            val (owner, repo, prNum) = match.destructured
            val diff = mcpServer.executeTool("fetch_github_pr_diff", mapOf("owner" to owner, "repo" to repo, "pr_number" to prNum.toInt()))
            executeReviewAnalysis(logger, diff, mcpServer, gptClient)
        }
        command == "support" -> {
            val userIdArg = args.getOrNull(1)
            runSupportChat(userIdArg, supportMcpServer, mcpServer, gptClient, logger)
        }
        else -> {
            logger.error("Unknown command: $command.")
            exitProcess(1)
        }
    }
}

suspend fun runSupportChat(
    userIdArg: String?,
    supportMcp: SupportMCPServer,
    devMcp: DeveloperAssistantMCPServer,
    gptClient: YandexGPTClient,
    logger: org.slf4j.Logger
) {
    val scanner = Scanner(System.`in`)
    var userId = userIdArg

    println("\n💬 Welcome to AI Support Chat!")
    
    // 1. Identify User
    while (userId.isNullOrBlank()) {
        print("👤 Please enter User ID (e.g. user_dev): ")
        userId = scanner.nextLine().trim()
    }

    // 2. Fetch User Context (CRM)
    logger.info("🔍 Fetching CRM data for: $userId")
    val userDetails = supportMcp.executeTool("get_user_details", mapOf("userId" to userId))
    val userHistory = supportMcp.executeTool("get_user_history", mapOf("userId" to userId))

    if (userDetails.startsWith("Error")) {
        println("❌ $userDetails")
        return
    }

    println("✅ Found user: $userDetails")
    println("📜 Recent History: $userHistory")
    println("\nHow can I help you today? (Type 'exit' to quit)\n")

    // 3. Chat Loop
    while (true) {
        print("🧑 $userId: ")
        val input = scanner.nextLine().trim()
        if (input.lowercase() == "exit") break
        if (input.isBlank()) continue

        // 4. RAG Search
        // Search docs based on user query + technical context from CRM (e.g. OS, IDE)
        val ragContext = devMcp.executeTool("ask_project_docs", mapOf("query" to input))

        // 5. Generate Answer
        val systemPrompt = """
            You are a helpful Technical Support Agent for the 'AI Reviewer' tool.
            
            USER CONTEXT (CRM):
            $userDetails
            
            TICKET HISTORY:
            $userHistory
            
            KNOWLEDGE BASE (RAG):
            $ragContext
            
            INSTRUCTIONS:
            - Answer the user's question using the RAG knowledge base.
            - Use the CRM context to personalize the answer (e.g. mention their OS or Plan).
            - If the user is on a FREE plan and asks for PRO features, gently upsell.
            - Be polite, concise, and helpful.
            - If the RAG context doesn't have the answer, admit it and suggest contacting human support.
        """.trimIndent()

        val response = gptClient.chat(systemPrompt, input, model = "yandexgpt")
        
        println("🤖 Bot: $response\n")
    }
}

// ... existing executeReviewAnalysis function ...
suspend fun executeReviewAnalysis(
    logger: org.slf4j.Logger,
    diff: String,
    mcpServer: DeveloperAssistantMCPServer,
    gptClient: YandexGPTClient
) {
     // ... copied from previous revision to maintain functionality ...
    if (diff.startsWith("No changes") || diff.isBlank() || diff.startsWith("Error")) {
        logger.info("⚠️ Review aborted. Reason: $diff")
        return
    }
    logger.info("   Diff found: ${diff.length} chars")

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
    
    val ragContext = mcpServer.executeTool("ask_project_docs", mapOf("query" to query))
    
    val systemPrompt = """
        You are an AI Code Reviewer enforcing strict project policies.
        STRICT REQUIREMENT:
        1. If you find a violation of a rule found in the Context, you MUST cite the source document AND the line numbers.
        2. Format citations as [Source: filename.md:10-15].
        3. Quote the specific Rule ID if available (e.g., LOG-001).
        
        Format:
        ## 🚨 Violations Found
        ### [Rule ID] Rule Name
        **Source:** `Filename.md:StartLine-EndLine`
        **Violation:** ...
        **Fix:** ...
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
    println("\n" + "=".repeat(20) + " AI Review Report " + "=".repeat(20))
    println(review)
    println("=".repeat(58))
}
