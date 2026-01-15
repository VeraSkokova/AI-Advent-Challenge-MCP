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
                
                val prsCount = Regex("\"number\"").findAll(prsJson).count()
                
                // GitHub API returns PRs as Issues too. We need to filter them out if possible, 
                // or just be aware. Ideally, check if 'pull_request' field exists in issue json, 
                // but our simple DTO might not have it. 
                // Let's rely on the fact that we list PRs separately.
                // A better way is to filter issues that are NOT PRs if DTO supported it.
                // For now, let's just clearly label them in the output.
                
                val statusReport = buildString {
                    append("📊 Project Status for $owner/$repo\n")
                    append("--------------------------------\n")
                    append("Open PRs (Count): $prsCount\n")
                    append("Open Issues (All items including PRs): ${issues.size}\n\n")
                    
                    issues.forEach { issue ->
                        // Simple heuristic: if html_url contains "pull", it's a PR
                        val type = if (issue.html_url.contains("/pull/")) "[PR]" else "[ISSUE]"
                        val labels = if (issue.labels.isEmpty()) "No labels" else issue.labels.joinToString { it.name }
                        append("- $type [#${issue.number}] ${issue.title} (Labels: $labels)\n")
                    }
                }
                statusReport
            }
            "create_task" -> {
                val title = params["title"] as? String ?: return "Error: title required"
                val description = params["description"] as? String ?: ""
                val priority = params["priority"] as? String ?: "medium"
                
                val labels = mutableListOf("ai-task")
                when (priority.lowercase()) {
                    "high", "critical", "высокий" -> labels.add("priority:high")
                    "low", "низкий" -> labels.add("priority:low")
                    else -> labels.add("priority:medium")
                }
                
                gitHubClient.createIssue(owner, repo, title, description, labels)
            }
            else -> "Error: Unknown tool $toolName"
        }
    }
}
