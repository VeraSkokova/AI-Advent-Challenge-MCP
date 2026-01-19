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
        val downloadFile = File(tempDir, "artifact.zip")
        
        logger.info("⬇️ Downloading artifact to ${downloadFile.absolutePath}...")
        gitHubClient.downloadArtifact(artifact.archive_download_url, downloadFile)

        // 4. Unzip and Deploy
        val deployDir = File("deploy") // Local deploy folder in project root
        deployDir.deleteRecursively()
        deployDir.mkdirs()

        logger.info("📂 Unzipping outer artifact to ${deployDir.absolutePath}...")
        unzip(downloadFile, deployDir)

        // Handle nested ZIPs (Gradle distZip inside GitHub artifact zip)
        val nestedZip = deployDir.walkTopDown().find { it.extension == "zip" }
        if (nestedZip != null) {
            logger.info("📦 Found nested distribution archive: ${nestedZip.name}. Unzipping...")
            unzip(nestedZip, deployDir)
            nestedZip.delete() // Cleanup zip
        }
        
        // Debug: List files
        val fileList = deployDir.walkTopDown().map { it.relativeTo(deployDir).path }.joinToString(", ")
        logger.info("📂 Files in deploy dir: $fileList")
        
        // Find the bin script (recursive search)
        // Ищем файл, который:
        // 1. Находится в папке bin/
        // 2. Не имеет расширения .bat (для Unix) или имеет .bat (для Windows)
        // Для кросс-платформенности ищем оба, но приоритет отдаем тому, что подходит под ОС
        
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val scriptName = if (isWindows) "ai-advent-mcp.bat" else "ai-advent-mcp"
        
        val binScript = deployDir.walkTopDown().find { 
            it.name == scriptName && it.parentFile.name == "bin" 
        }
        
        if (binScript == null) {
            return "❌ Could not find executable script '$scriptName' in artifact. Files found: ${fileList.take(200)}..."
        }
        
        binScript.setExecutable(true)

        // 5. Start Server Process
        logger.info("🚀 Starting deployed server using ${binScript.absolutePath}...")
        try {
            val logFile = File("deploy/server.log")
            
            val processBuilder = ProcessBuilder(binScript.absolutePath, "server")
                .redirectOutput(logFile)
                .redirectError(logFile)
            
            // Если Windows, нужно запускать через cmd /c, иначе может не подхватить bat
            // Но обычно ProcessBuilder умеет запускать bat напрямую.
            
            val process = processBuilder.start()
                
            return """
                ✅ Deployment Successful!
                - Branch: $branch
                - Run ID: $runId
                - Artifact: ${artifact.name}
                - Deployed to: ${deployDir.absolutePath}
                - Server PID: ${process.pid()}
                - Logs: ${logFile.absolutePath}
                - Executable: ${binScript.name}
                
                The server is now running on localhost:8080 (check logs if not).
                Try: curl http://localhost:8080/
            """.trimIndent()
        } catch (e: Exception) {
            logger.error("Failed to start process", e)
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
