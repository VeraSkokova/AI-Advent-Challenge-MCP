package ru.skokova.aiadventchallenge.git

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class GitHubClient(private val token: String?) {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(Logging) { level = LogLevel.INFO }
        install(HttpTimeout) { requestTimeoutMillis = 60000 }
    }

    private fun getToken(): String = token ?: throw IllegalStateException("GITHUB_TOKEN not set")

    // --- Existing Issues/PR methods ---
    
    suspend fun listIssues(owner: String, repo: String): List<Issue> {
        return try {
            client.get("https://api.github.com/repos/$owner/$repo/issues?state=open") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
            }.body()
        } catch (e: Exception) { emptyList() }
    }
    
    suspend fun listPullRequests(owner: String, repo: String): String {
        return try {
            client.get("https://api.github.com/repos/$owner/$repo/pulls?state=open") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
            }.bodyAsText()
        } catch (e: Exception) { "[]" }
    }

    suspend fun createIssue(owner: String, repo: String, title: String, body: String, labels: List<String>): String {
        return try {
            val response: Issue = client.post("https://api.github.com/repos/$owner/$repo/issues") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(CreateIssueRequest(title, body, labels))
            }.body()
            "Created Issue #${response.number}: ${response.html_url}"
        } catch (e: Exception) { "Error creating issue: ${e.message}" }
    }

    // --- NEW: Actions API ---

    suspend fun triggerWorkflow(owner: String, repo: String, workflowId: String, ref: String): Boolean {
        return try {
            val response = client.post("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowId/dispatches") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(WorkflowDispatch(ref))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error("Failed to trigger workflow", e)
            false
        }
    }

    suspend fun getLatestRun(owner: String, repo: String, workflowId: String, branch: String): WorkflowRun? {
        return try {
            val response: WorkflowRunsResponse = client.get("https://api.github.com/repos/$owner/$repo/actions/runs") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
                // parameter("event", "workflow_dispatch") // Filter can be tricky if triggered by push too
                parameter("branch", branch)
                parameter("per_page", 1)
            }.body()
            response.workflow_runs.firstOrNull()
        } catch (e: Exception) {
            logger.error("Failed to get runs", e)
            null
        }
    }
    
    suspend fun getRun(owner: String, repo: String, runId: Long): WorkflowRun? {
        return try {
             client.get("https://api.github.com/repos/$owner/$repo/actions/runs/$runId") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
            }.body()
        } catch(e: Exception) { null }
    }

    suspend fun listArtifacts(owner: String, repo: String, runId: Long): List<Artifact> {
        return try {
            val response: ArtifactsResponse = client.get("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts") {
                header("Authorization", "Bearer ${getToken()}")
                header("Accept", "application/vnd.github.v3+json")
            }.body()
            response.artifacts
        } catch (e: Exception) {
            logger.error("Failed to list artifacts", e)
            emptyList()
        }
    }

    suspend fun downloadArtifact(url: String, destination: File) {
        val bytes: ByteArray = client.get(url) {
            header("Authorization", "Bearer ${getToken()}")
        }.body()
        destination.writeBytes(bytes)
    }
}

// DTOs
@Serializable data class Issue(val number: Int, val title: String, val html_url: String, val labels: List<Label>)
@Serializable data class Label(val name: String)
@Serializable data class CreateIssueRequest(val title: String, val body: String, val labels: List<String>)
@Serializable data class WorkflowDispatch(val ref: String)
@Serializable data class WorkflowRunsResponse(val workflow_runs: List<WorkflowRun>)
@Serializable data class WorkflowRun(val id: Long, val status: String, val conclusion: String?, val html_url: String)
@Serializable data class ArtifactsResponse(val artifacts: List<Artifact>)
@Serializable data class Artifact(val id: Long, val name: String, val archive_download_url: String)
