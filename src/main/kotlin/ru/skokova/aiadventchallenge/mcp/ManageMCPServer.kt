package ru.skokova.aiadventchallenge.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.skokova.aiadventchallenge.git.GitHubClient

class ManageMCPServer(
    private val gitHubClient: GitHubClient,
    private val owner: String,
    private val repo: String
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo(
                name = "get_project_status",
                description = "Get current project status: list of open issues and pull requests.",
                parameters = listOf()
            ),
            ToolInfo(
                name = "create_task",
                description = "Create a new task (Issue) in GitHub. Use this when the user asks to add a task.",
                parameters = listOf("title", "description", "priority")
            )
        )
    }

    suspend fun executeTool(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "get_project_status" -> {
                val issues = gitHubClient.listIssues(owner, repo)
                val prsJson = gitHubClient.listPullRequests(owner, repo)
                
                // Parse PRs just to count them or get basic info, here we simplify
                val prsCount = Regex("\"number\"").findAll(prsJson).count()
                
                val statusReport = buildString {
                    append("📊 Project Status for $owner/$repo\n")
                    append("--------------------------------\n")
                    append("Open PRs: $prsCount\n")
                    append("Open Issues: ${issues.size}\n\n")
                    
                    issues.forEach { issue ->
                        val labels = issue.labels.joinToString { it.name }
                        append("- [#${issue.number}] ${issue.title} (Labels: $labels)\n")
                    }
                }
                statusReport
            }
            "create_task" -> {
                val title = params["title"] as? String ?: return "Error: title required"
                val description = params["description"] as? String ?: ""
                val priority = params["priority"] as? String ?: "medium"
                
                // Map priority to labels
                val labels = mutableListOf("ai-task")
                when (priority.lowercase()) {
                    "high", "critical" -> labels.add("priority:high")
                    "low" -> labels.add("priority:low")
                    else -> labels.add("priority:medium")
                }
                
                gitHubClient.createIssue(owner, repo, title, description, labels)
            }
            else -> "Error: Unknown tool $toolName"
        }
    }
}
