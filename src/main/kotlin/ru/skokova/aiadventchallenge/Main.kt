package ru.skokova.aiadventchallenge

import ru.skokova.aiadventchallenge.mcp.DeveloperAssistantMCPServer
import ru.skokova.aiadventchallenge.mcp.FilesystemMCPClient
import ru.skokova.aiadventchallenge.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.git.GitClient
import ru.skokova.aiadventchallenge.rag.services.IndexService
import ru.skokova.aiadventchallenge.rag.services.SearchService
import kotlinx.coroutines.runBlocking
import java.io.File
import org.slf4j.LoggerFactory

fun main() = runBlocking {
    val logger = LoggerFactory.getLogger("Main")
    val projectRoot = File(".")
    
    // 1. Initialize Components
    val gitClient = GitClient(projectRoot)
    val indexService = IndexService()
    val searchService = SearchService()
    
    val mcpServer = DeveloperAssistantMCPServer(
        indexService = indexService,
        searchService = searchService,
        gitClient = gitClient,
        projectRoot = projectRoot
    )
    
    // Assuming YandexAIAgent takes a system prompt in constructor or methods
    // NOTE: This assumes YandexAIAgent is compatible. If not, we might need to adjust instantiation.
    // Based on previous files, it seems to take API key from env or similar.
    val aiAgent = YandexAIAgent() 

    logger.info("🤖 AI Code Review Assistant Started")

    // 2. Get Diff (MCP Tool)
    logger.info("📄 Fetching git diff...")
    val diff = mcpServer.executeTool("get_pr_diff", emptyMap())
    
    if (diff.startsWith("No changes")) {
        println("✅ No changes to review.")
        return@runBlocking
    }
    
    logger.info("🔍 Diff size: ${diff.length} chars")

    // 3. Extract Keywords & Get RAG Context
    // Simple keyword extraction: find distinct words starting with capital letters or ending with .kt
    val keywords = Regex("""\b([A-Z][a-zA-Z0-9]+)\b|\b([a-zA-Z0-9]+\.kt)\b""")
        .findAll(diff)
        .map { it.value }
        .distinct()
        .take(5) // Take top 5 keywords to avoid spamming RAG
        .joinToString(" ")
        
    logger.info("📚 Searching RAG context for keywords: $keywords")
    val ragContext = if (keywords.isNotEmpty()) {
         mcpServer.executeTool("ask_project_docs", mapOf("query" to "Review rules and architecture for: $keywords"))
    } else {
        "No specific RAG context found."
    }

    // 4. Construct Prompt
    val prompt = """
        You are a Senior Kotlin Developer doing a Code Review.
        
        ### Context from Project Docs (RAG):
        $ragContext
        
        ### Git Diff to Review:
        ```diff
        $diff
        ```
        
        Analyze the code for bugs, architectural issues, and style violations.
        Provide the output in Markdown format.
    """.trimIndent()

    // 5. Generate Review
    logger.info("🧠 Generating review...")
    val review = aiAgent.generateContent(
        systemPrompt = "You are an expert code reviewer. Be constructive and concise.",
        userPrompt = prompt
    )

    // 6. Output
    println("\n" + "=" * 20 + " AI Code Review " + "=" * 20)
    println(review)
    println("=" * 56)
}
