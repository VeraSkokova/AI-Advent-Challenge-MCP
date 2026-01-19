package ru.skokova.aiadventchallenge.mcp

import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.git.GitHubClient
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory

class DeployMCPServer(
    private val gitHubClient: GitHubClient,
    private val owner: String,
    private val repo: String
) {
    private val logger = LoggerFactory.getLogger(DeployMCPServer::class.java)
    private val json = Json { prettyPrint = true }

    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo(
                name = "deploy_to_localhost",
                description = "Triggers GitHub Actions build, waits for artifact, downloads it, and starts the server locally.",
                parameters = listOf("branch")
            )
        )
    }

    suspend fun executeTool(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "deploy_to_localhost" -> {
                val branch = params["branch"] as? String ?: "day24"
                deployToLocalhost(branch)
            }
            else -> "Unknown tool: $toolName"
        }
    }

    private suspend fun deployToLocalhost(branch: String): String {
        val workflowId = "ci.yml"
        
        // 1. Trigger Build
        logger.info("🚀 Triggering workflow '$workflowId' on branch '$branch'...")
        val triggered = gitHubClient.triggerWorkflow(owner, repo, workflowId, branch)
        if (!triggered) return "❌ Failed to trigger workflow."

        logger.info("⏳ Waiting for workflow run to start...")
        delay(10000) // Wait for GH to register the run

        // 2. Poll for Completion
        var runId: Long = -1
        var status = "queued"
        var conclusion: String? = null
        
        // Simple polling loop (max 5 minutes)
        for (i in 1..30) {
            val run = gitHubClient.getLatestRun(owner, repo, workflowId, branch)
            if (run != null) {
                runId = run.id
                status = run.status
                conclusion = run.conclusion
                logger.info("🔄 Run #$runId Status: $status (Attempt $i/30)")
                
                if (status == "completed") break
            }
            delay(10000)
        }

        if (status != "completed" || conclusion != "success") {
            return "❌ Build failed or timed out. Status: $status, Conclusion: $conclusion"
        }

        // 3. Download Artifact
        logger.info("📦 Build successful! Fetching artifacts...")
        val artifacts = gitHubClient.listArtifacts(owner, repo, runId)
        val artifact = artifacts.find { it.name == "mcp-server-release" } 
            ?: return "❌ Artifact 'mcp-server-release' not found."

        val tempDir = createTempDirectory("mcp_deploy").toFile()
        val zipFile = File(tempDir, "release.zip")
        
        logger.info("⬇️ Downloading artifact to ${zipFile.absolutePath}...")
        gitHubClient.downloadArtifact(artifact.archive_download_url, zipFile)

        // 4. Unzip and Deploy
        val deployDir = File("deploy") // Local deploy folder in project root
        deployDir.deleteRecursively()
        deployDir.mkdirs()

        logger.info("📂 Unzipping to ${deployDir.absolutePath}...")
        unzip(zipFile, deployDir)
        
        // Find the bin script (it's nested inside the unzipped folder structure)
        val binScript = deployDir.walkTopDown().find { it.name == "AI-Advent-Challenge-MCP" && !it.name.endsWith(".bat") }
        
        if (binScript == null) {
            return "❌ Could not find executable script in artifact."
        }
        
        binScript.setExecutable(true)

        // 5. Start Server Process
        logger.info("🚀 Starting deployed server...")
        try {
            // Kill previous instance if needed (simplified: relying on OS or user to kill old one, 
            // or just spawning a new one for demo)
            // For a real scenario, we'd track the PID.
            
            val logFile = File("deploy/server.log")
            val process = ProcessBuilder(binScript.absolutePath, "server")
                .redirectOutput(logFile)
                .redirectError(logFile)
                .start()
                
            return """
                ✅ Deployment Successful!
                - Branch: $branch
                - Run ID: $runId
                - Artifact: ${artifact.name}
                - Deployed to: ${deployDir.absolutePath}
                - Server PID: ${process.pid()}
                - Logs: ${logFile.absolutePath}
                
                The server is now running on localhost:8080 (check logs if not).
            """.trimIndent()
        } catch (e: Exception) {
            return "❌ Failed to start server process: ${e.message}"
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val newFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile.mkdirs()
                    newFile.outputStream().use { fos -> zis.copyTo(fos) }
                }
            }
        }
    }
}
