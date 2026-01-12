package ru.skokova.aiadventchallenge.mcp

import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.git.GitClient
import ru.skokova.aiadventchallenge.rag.services.IndexService
import ru.skokova.aiadventchallenge.rag.services.SearchService
import ru.skokova.aiadventchallenge.rag.models.VectorIndex
import java.io.File
import kotlinx.coroutines.runBlocking

class DeveloperAssistantMCPServer(
    private val indexService: IndexService,
    private val searchService: SearchService,
    private val gitClient: GitClient,
    private val projectRoot: File = File(".")
) {
    private val logger = LoggerFactory.getLogger(DeveloperAssistantMCPServer::class.java)
    private var cachedIndex: VectorIndex? = null
    private val indexFile = "rag_index.json"

    init {
        runBlocking {
            cachedIndex = indexService.loadIndex(indexFile)
            if (cachedIndex == null) {
                logger.warn("RAG index not found at $indexFile. Please run 'rebuild_rag_index' tool.")
            } else {
                logger.info("RAG index loaded. Chunks: ${cachedIndex?.chunks?.size}")
            }
        }
    }

    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo(
                name = "ask_project_docs",
                description = "Поиск информации в документации проекта и коде (RAG). Используй это для вопросов 'как работает...', 'где находится...', 'как добавить...'",
                parameters = listOf("query")
            ),
            ToolInfo(
                name = "rebuild_rag_index",
                description = "Полное пересканирование проекта и пересоздание RAG индекса. Запускай если файлы изменились.",
                parameters = listOf()
            ),
            ToolInfo(
                name = "git_status",
                description = "Показать статус git репозитория (текущая ветка и измененные файлы)",
                parameters = listOf()
            ),
            ToolInfo(
                name = "git_diff_list",
                description = "Получить список измененных файлов",
                parameters = listOf()
            ),
            ToolInfo(
                name = "git_current_branch",
                description = "Узнать текущую git ветку",
                parameters = listOf()
            )
        )
    }

    suspend fun executeTool(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "ask_project_docs" -> {
                val query = params["query"] as? String ?: return "Error: 'query' parameter required"
                handleRagSearch(query)
            }
            "rebuild_rag_index" -> {
                rebuildIndex()
            }
            "git_status" -> {
                 val branch = gitClient.currentBranch()
                 val status = gitClient.status()
                 "Branch: $branch\nStatus:\n$status"
            }
            "git_diff_list" -> {
                gitClient.diffFiles().joinToString("\n").ifEmpty { "No changes" }
            }
            "git_current_branch" -> {
                gitClient.currentBranch()
            }
            else -> "Error: Unknown tool $toolName"
        }
    }

    private suspend fun handleRagSearch(query: String): String {
        val index = cachedIndex ?: return "Error: Index not loaded. Run 'rebuild_rag_index' first."
        
        val results = searchService.search(query, index, topK = 3)
        if (results.isEmpty()) return "No relevant information found in project documentation."

        val sb = StringBuilder()
        sb.append("Found relevant context:\n\n")
        results.forEach { result ->
            sb.append("📄 File: ${result.chunk.metadata.sourceFile} (Score: %.2f)\n".format(result.similarity))
            sb.append("```\n${result.chunk.text}\n```\n\n")
        }
        return sb.toString()
    }

    private suspend fun rebuildIndex(): String {
        return try {
            // Сканируем папку docs и src
            val newIndex = indexService.createIndex(projectRoot.absolutePath)
            indexService.saveIndex(newIndex, indexFile)
            cachedIndex = newIndex
            "Success: Index rebuilt. Total chunks: ${newIndex.chunks.size}"
        } catch (e: Exception) {
            logger.error("Failed to rebuild index", e)
            "Error rebuilding index: ${e.message}"
        }
    }
}