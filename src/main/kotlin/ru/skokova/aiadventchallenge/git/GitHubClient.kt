package ru.skokova.aiadventchallenge.git

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

class GitHubClient(private val token: String?) {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)
    private val client = HttpClient(CIO)

    /**
     * Fetches the raw diff of a Pull Request.
     * url example: https://api.github.com/repos/owner/repo/pulls/1
     */
    suspend fun getPullRequestDiff(owner: String, repo: String, prNumber: Int): String {
        val url = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber"
        logger.info("Fetching PR Diff from: $url")

        try {
            val response = client.get(url) {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
                // Important: Request diff format specifically
                header("Accept", "application/vnd.github.v3.diff")
                header("X-GitHub-Api-Version", "2022-11-28")
            }

            if (response.status != HttpStatusCode.OK) {
                throw RuntimeException("GitHub API Error: ${response.status} - ${response.bodyAsText()}")
            }

            return response.bodyAsText()
        } catch (e: Exception) {
            logger.error("Failed to fetch PR diff", e)
            return "Error: ${e.message}"
        }
    }
}
