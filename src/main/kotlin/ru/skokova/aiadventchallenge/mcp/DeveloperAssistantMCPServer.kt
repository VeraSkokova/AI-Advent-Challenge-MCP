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
                logger.warn("RAG index not found at $indexFile. Use /help to trigger initial indexing or run 'rebuild_rag_index' tool.")
            } else {
                logger.info("✅ RAG index loaded. Chunks: ${cachedIndex?.chunks?.size}")
            }
        }
    }

    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo(
                name = "help_overview",
                description = "Показать базовую информацию о проекте: краткое описание из README, текущую ветку git и количество изменённых файлов. Используй когда пользователь пишет просто '/help' без аргументов.",
                parameters = listOf()
            ),
            ToolInfo(
                name = "ask_project_docs",
                description = "Поиск информации в документации проекта и коде через RAG. Используй для вопросов 'как работает...', 'где находится...', 'как добавить...', '/help <вопрос>'",
                parameters = listOf("query")
            ),
            ToolInfo(
                name = "rebuild_rag_index",
                description = "Полное пересканирование проекта (docs/, README.md, src/**/*.kt) и пересоздание RAG индекса. Запускай если файлы проекта изменились.",
                parameters = listOf()
            ),
            ToolInfo(
                name = "git_status",
                description = "Показать полный статус git репозитория (текущая ветка и список изменений)",
                parameters = listOf()
            ),
            ToolInfo(
                name = "git_diff_list",
                description = "Получить список только имён изменённых файлов",
                parameters = listOf()
            ),
            ToolInfo(
                name = "git_current_branch",
                description = "Узнать название текущей git ветки",
                parameters = listOf()
            )
        )
    }

    suspend fun executeTool(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "help_overview" -> {
                handleHelpOverview()
            }
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

    private fun handleHelpOverview(): String {
        val sb = StringBuilder()
        
        // 1. Краткое описание проекта из README
        val readme = File(projectRoot, "README.md")
        if (readme.exists()) {
            val readmeText = readme.readText()
            // Берём первые 500 символов или до первого двойного переноса строки
            val summary = readmeText.take(500).substringBefore("\n\n").trim()
            sb.append("📘 Project Overview:\n$summary\n\n")
        } else {
            sb.append("📘 Project Overview: README.md not found\n\n")
        }
        
        // 2. Текущая ветка
        val branch = gitClient.currentBranch()
        sb.append("🌿 Current Git Branch: $branch\n")
        
        // 3. Количество изменённых файлов
        val changedFiles = gitClient.diffFiles()
        sb.append("📝 Changed Files: ${changedFiles.size}")
        if (changedFiles.isNotEmpty()) {
            sb.append("\n   Files: ${changedFiles.joinToString(", ")}")
        }
        
        sb.append("\n\n💡 Tip: Use '/help <question>' to search project documentation and code.")
        
        return sb.toString()
    }

    private suspend fun handleRagSearch(query: String): String {
        // Если индекс не загружен, пытаемся создать его автоматически
        if (cachedIndex == null) {
            logger.info("Index not loaded, attempting to build index automatically...")
            val buildResult = rebuildIndex()
            if (buildResult.startsWith("Error")) {
                return "$buildResult\n\nCannot search without index. Please fix the error and try again."
            }
        }
        
        val index = cachedIndex ?: return "Error: Index still not available after rebuild attempt."
        
        val results = searchService.search(query, index, topK = 3)
        if (results.isEmpty()) return "No relevant information found in project documentation for query: '$query'"

        val sb = StringBuilder()
        sb.append("🔍 Found ${results.size} relevant context(s) for: '$query'\n\n")
        results.forEachIndexed { idx, result ->
            sb.append("[${idx + 1}] 📄 ${result.chunk.metadata.sourceFile} (similarity: %.2f)\n".format(result.similarity))
            sb.append("```\n${result.chunk.text.take(300)}")
            if (result.chunk.text.length > 300) sb.append("...")
            sb.append("\n```\n\n")
        }
        return sb.toString()
    }

    private suspend fun rebuildIndex(): String {
        return try {
            logger.info("🔄 Rebuilding RAG index...")
            val newIndex = indexService.createIndex(projectRoot.absolutePath)
            indexService.saveIndex(newIndex, indexFile)
            cachedIndex = newIndex
            logger.info("✅ Index rebuilt successfully")
            "Success: Index rebuilt. Total chunks: ${newIndex.chunks.size}"
        } catch (e: Exception) {
            logger.error("❌ Failed to rebuild index", e)
            "Error rebuilding index: ${e.message}"
        }
    }
}