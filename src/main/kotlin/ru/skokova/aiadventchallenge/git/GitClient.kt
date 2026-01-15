package ru.skokova.aiadventchallenge.git

import java.io.File
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class GitClient(private val workingDir: File) {
    private val logger = LoggerFactory.getLogger(GitClient::class.java)

    fun status(): String {
        return runCommand("git", "status")
    }

    fun currentBranch(): String {
        return runCommand("git", "rev-parse", "--abbrev-ref", "HEAD").trim()
    }

    fun diffFiles(): List<String> {
        val output = runCommand("git", "diff", "--name-only")
        return if (output.isBlank()) emptyList() else output.lines().filter { it.isNotBlank() }
    }
    
    fun getDiffContent(): String {
        // Limit diff size to avoid blowing up context
        val diff = runCommand("git", "diff")
        return if (diff.length > 20000) {
            diff.take(20000) + "\n... (Diff truncated)"
        } else {
            diff
        }
    }
    
    fun getRemoteUrl(): String {
        val url = runCommand("git", "config", "--get", "remote.origin.url").trim()
        // Convert SSH to HTTPS if needed for parsing
        // git@github.com:User/Repo.git -> https://github.com/User/Repo
        if (url.startsWith("git@")) {
            return url.replace(":", "/").replace("git@", "https://").removeSuffix(".git")
        }
        return url.removeSuffix(".git")
    }

    private fun runCommand(vararg args: String): String {
        return try {
            val process = ProcessBuilder(*args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            logger.error("Git command failed: ${args.joinToString(" ")}", e)
            "Error: ${e.message}"
        }
    }
}
