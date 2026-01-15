package ru.skokova.aiadventchallenge.git

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import io.ktor.client.plugins.contentnegotiation.*

@Serializable
data class GitHubIssue(
    val number: Int,
    val title: String,
    val state: String,
    val html_url: String,
    val body: String?,
    val labels: List<GitHubLabel>
)

@Serializable
data class GitHubLabel(val name: String)

@Serializable
data class CreateIssueRequest(val title: String, val body: String?, val labels: List<String>)

class GitHubClient(private val token: String?) {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun checkToken() {
        if (token.isNullOrBlank()) {
            throw IllegalStateException("GITHUB_TOKEN is missing. Please set it in local.properties or ENV.")
        }
    }

    suspend fun getPullRequestDiff(owner: String, repo: String, prNumber: Int): String {
        val url = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber"
        return fetchText(url, "application/vnd.github.v3.diff")
    }

    suspend fun listIssues(owner: String, repo: String, state: String = "open"): List<GitHubIssue> {
        checkToken()
        val url = "https://api.github.com/repos/$owner/$repo/issues?state=$state"
        logger.info("Fetching issues from: $url")
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
        }
        return if (response.status == HttpStatusCode.OK) {
             Json { ignoreUnknownKeys = true }.decodeFromString(response.bodyAsText())
        } else {
            logger.error("Failed to list issues: ${response.status}")
            emptyList()
        }
    }

    suspend fun listPullRequests(owner: String, repo: String, state: String = "open"): String {
        checkToken()
        val url = "https://api.github.com/repos/$owner/$repo/pulls?state=$state"
        logger.info("Fetching PRs from: $url")
        val response = client.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
        }
        return response.bodyAsText() // Return raw JSON string for simplicity in summarization
    }

    suspend fun createIssue(owner: String, repo: String, title: String, body: String, labels: List<String>): String {
        checkToken()
        val url = "https://api.github.com/repos/$owner/$repo/issues"
        logger.info("Creating issue '$title' in $owner/$repo")
        
        val requestBody = CreateIssueRequest(title, body, labels)
        
        val response = client.post(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        
        return if (response.status == HttpStatusCode.Created) {
            val jsonResp = Json { ignoreUnknownKeys = true }.parseToJsonElement(response.bodyAsText()).jsonObject
            "Success: Issue created #${jsonResp["number"]} - ${jsonResp["html_url"]}"
        } else {
            "Error creating issue: ${response.status} - ${response.bodyAsText()}"
        }
    }

    private suspend fun fetchText(url: String, acceptHeader: String): String {
        try {
            val response = client.get(url) {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
                header("Accept", acceptHeader)
                header("X-GitHub-Api-Version", "2022-11-28")
            }

            if (response.status != HttpStatusCode.OK) {
                throw RuntimeException("GitHub API Error: ${response.status} - ${response.bodyAsText()}")
            }
            return response.bodyAsText()
        } catch (e: Exception) {
            logger.error("Failed to fetch from $url", e)
            return "Error: ${e.message}"
        }
    }
}
