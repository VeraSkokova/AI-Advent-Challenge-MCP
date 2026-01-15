package ru.skokova.aiadventchallenge

import ru.skokova.aiadventchallenge.mcp.DeveloperAssistantMCPServer
import ru.skokova.aiadventchallenge.mcp.SupportMCPServer
import ru.skokova.aiadventchallenge.mcp.ManageMCPServer
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
    
    // Auto-detect repo for management
    val remoteUrl = try { gitClient.getRemoteUrl() } catch (e: Exception) { "" }
    val (repoOwner, repoName) = if (remoteUrl.contains("github.com")) {
        val parts = remoteUrl.split("github.com/").last().split("/")
        parts[0] to parts[1]
    } else {
        "VeraSkokova" to "AI-Advent-Challenge-MCP" // Fallback
    }
    
    val manageMcpServer = ManageMCPServer(githubClient, repoOwner, repoName)
    
    // 2. Parse Command
    val command = args.firstOrNull() ?: "review" 
    
    when {
        command == "index" -> {
            logger.info("📚 Building RAG Index...")
            val paths = listOf(File(projectRoot, "docs").absolutePath, File(projectRoot, "src/main/kotlin").absolutePath)
            val index = indexService.createIndex(paths)
            indexService.saveIndex(index)
            logger.info("✅ Indexing complete.")
        }
        command == "review" -> {
             val diff = mcpServer.executeTool("get_pr_diff", emptyMap())
             executeReviewAnalysis(logger, diff, mcpServer, gptClient)
        }
        command.startsWith("review_pr") -> {
            val url = args.getOrNull(1) ?: return@runBlocking logger.error("Usage: review_pr <url>")
            val regex = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
            val match = regex.find(url) ?: return@runBlocking logger.error("Invalid URL")
            val (owner, repo, prNum) = match.destructured
            val diff = mcpServer.executeTool("fetch_github_pr_diff", mapOf("owner" to owner, "repo" to repo, "pr_number" to prNum.toInt()))
            executeReviewAnalysis(logger, diff, mcpServer, gptClient)
        }
        command == "support" -> {
            val userId = args.getOrNull(1)
            runSupportChat(userId, supportMcpServer, mcpServer, gptClient, logger)
        }
        command == "manage" -> {
            logger.info("Starting Manager for $repoOwner/$repoName")
            runManageChat(manageMcpServer, mcpServer, gptClient, logger)
        }
        else -> {
            logger.error("Unknown command: $command.")
            exitProcess(1)
        }
    }
}

suspend fun runManageChat(
    manageMcp: ManageMCPServer,
    devMcp: DeveloperAssistantMCPServer,
    gptClient: YandexGPTClient,
    logger: org.slf4j.Logger
) {
    val scanner = Scanner(System.`in`)
    println("\n🚀 AI Team Manager is ready!")
    println("I can help you with Project Status, Tasks, and Priorities.")
    println("Ask me anything! (e.g. 'What is the project status?', 'Create a task for bugfix')\n")

    // Context buffer to keep track of conversation/tools
    var conversationContext = ""

    while (true) {
        print("👔 Manager: ")
        val input = scanner.nextLine().trim()
        if (input.lowercase() == "exit") break
        if (input.isBlank()) continue

        // ReAct Loop Prompt
        val systemPrompt = """
            You are an AI Team Lead and Project Manager.
            You have access to the following TOOLS:
            
            1. get_project_status
               - Description: Get list of open issues and PRs.
               - Parameters: None
            
            2. ask_project_docs
               - Description: Search documentation for rules/policies (RAG).
               - Parameters: query (string)
            
            3. create_task
               - Description: Create a GitHub Issue.
               - Parameters: title (string), description (string), priority (string: low/medium/high/critical)
            
            INSTRUCTIONS:
            - Analyze the user request.
            - If you need information, CALL A TOOL by outputting JSON: {"tool": "tool_name", "params": {...}}
            - If you have enough info, answer the user normally (text).
            - If the user asks to create a task, CALL the create_task tool.
            
            Current Conversation Context:
            $conversationContext
        """.trimIndent()

        // 1. LLM Decision
        val llmResponse = gptClient.chat(systemPrompt, input, model = "yandexgpt")
        
        // 2. Check for Tool Call
        if (llmResponse.trim().startsWith("{") && llmResponse.contains("\"tool\"")) {
            try {
                // Basic parsing
                val toolNameMatch = Regex(""""tool":\s*"(.*?)"""").find(llmResponse)
                val toolName = toolNameMatch?.groupValues?.get(1)
                
                if (toolName != null) {
                    println("⚙️ Executing tool: $toolName...")
                    
                    val result = when (toolName) {
                        "get_project_status" -> manageMcp.executeTool("get_project_status", emptyMap())
                        "ask_project_docs" -> {
                             val queryMatch = Regex(""""query":\s*"(.*?)"""").find(llmResponse)
                             val query = queryMatch?.groupValues?.get(1) ?: input
                             devMcp.executeTool("ask_project_docs", mapOf("query" to query))
                        }
                        "create_task" -> {
                             val titleMatch = Regex(""""title":\s*"(.*?)"""").find(llmResponse)
                             val descMatch = Regex(""""description":\s*"(.*?)"""").find(llmResponse)
                             val prioMatch = Regex(""""priority":\s*"(.*?)"""").find(llmResponse)
                             
                             manageMcp.executeTool("create_task", mapOf(
                                "title" to (titleMatch?.groupValues?.get(1) ?: "New Task"),
                                "description" to (descMatch?.groupValues?.get(1) ?: ""),
                                "priority" to (prioMatch?.groupValues?.get(1) ?: "medium")
                             ))
                        }
                        else -> "Error: Unknown tool"
                    }
                    
                    println("✅ Tool Result:\n$result\n")
                    
                    // 3. Final Answer Generation (with tool result)
                    val finalPrompt = """
                        The user asked: "$input"
                        You executed tool '$toolName' and got this result:
                        $result
                        
                        Now provide a helpful, natural response to the user summarizing this result.
                    """.trimIndent()
                    
                    val finalResponse = gptClient.chat(systemPrompt, finalPrompt, model = "yandexgpt")
                    println("🤖 Bot: $finalResponse\n")
                    
                    // Update context (optional, simple version)
                    conversationContext += "\nUser: $input\nTool: $toolName\nResult: $result\n"
                }
            } catch (e: Exception) {
                println("❌ Error executing tool plan: ${e.message}")
                println("Raw: $llmResponse")
            }
        } else {
            // Direct answer (no tool needed)
            println("🤖 Bot: $llmResponse\n")
            conversationContext += "\nUser: $input\nBot: $llmResponse\n"
        }
    }
}

// ... existing runSupportChat and executeReviewAnalysis ...
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
    while (userId.isNullOrBlank()) {
        print("👤 Please enter User ID (e.g. user_dev): ")
        userId = scanner.nextLine().trim()
    }

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

    while (true) {
        print("🧑 $userId: ")
        val input = scanner.nextLine().trim()
        if (input.lowercase() == "exit") break
        if (input.isBlank()) continue

        val ragContext = devMcp.executeTool("ask_project_docs", mapOf("query" to input))
        
        if (ragContext.startsWith("No relevant information")) {
             logger.info("⚠️ RAG found nothing for: '$input'")
        } else {
             logger.info("✅ RAG Context found (first 100 chars): ${ragContext.take(100)}...")
        }

        val systemPrompt = """
            You are a helpful Technical Support Agent for the 'AI Reviewer' tool.
            USER CONTEXT (CRM): $userDetails
            TICKET HISTORY: $userHistory
            KNOWLEDGE BASE (RAG): $ragContext
            INSTRUCTIONS: Answer using RAG. Personalize using CRM.
        """.trimIndent()

        val response = gptClient.chat(systemPrompt, input, model = "yandexgpt")
        println("🤖 Bot: $response\n")
    }
}

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

    val keywords = listOf("println", "System.out", "TODO", "catch", "Exception", "key", "token", "password")
    val diffKeywords = keywords.filter { diff.contains(it) }.joinToString(" ")
    val classNames = Regex("""class\s+([A-Z][a-zA-Z0-9]+)""").findAll(diff).map { it.groupValues[1] }.joinToString(" ")
    val query = buildString {
        if (diffKeywords.isNotEmpty()) append("Find coding standards and rules about: $diffKeywords. ")
        if (classNames.isNotEmpty()) append("Find existing code related to: $classNames. ")
        if (isEmpty()) append("General coding standards and best practices")
    }
    val ragContext = mcpServer.executeTool("ask_project_docs", mapOf("query" to query))
    
    val systemPrompt = """
        You are an AI Code Reviewer enforcing strict project policies.
        STRICT REQUIREMENT: Cite source document AND line numbers. Quote Rule ID.
        Format: ## 🚨 Violations Found ...
    """.trimIndent()

    val userPrompt = """
        ### 📚 Documentation & Code Context (RAG):
        $ragContext
        ### 📝 Code Changes (Git Diff):
        ```diff
        $diff
        ```
        Analyze now.
    """.trimIndent()

    val review = gptClient.chat(systemPrompt, userPrompt, model = "yandexgpt")
    println("\n" + "=".repeat(20) + " AI Review Report " + "=".repeat(20))
    println(review)
    println("=".repeat(58))
}
