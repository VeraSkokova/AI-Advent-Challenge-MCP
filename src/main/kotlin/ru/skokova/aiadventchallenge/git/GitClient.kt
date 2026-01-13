package ru.skokova.aiadventchallenge.git

import java.io.File
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class GitClient(private val workingDir: File = File(".")) {
    private val logger = LoggerFactory.getLogger(GitClient::class.java)
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun status(): String = runCommand("git status --short")
    
    fun currentBranch(): String = runCommand("git rev-parse --abbrev-ref HEAD").trim()
    
    fun diffFiles(): List<String> {
        val output = runCommand("git diff --name-only")
        if (output.isBlank()) return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }
    
    fun getDiffContent(): String {
        return runCommand("git diff")
    }

    private fun runCommand(command: String): String {
        val parts = if (isWindows) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        return try {
            val process = ProcessBuilder(parts)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(10, TimeUnit.SECONDS)) { // Increased timeout for potentially large diffs
                process.destroy()
                throw RuntimeException("Command timed out: $command")
            }

            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                logger.warn("Git command '$command' returned non-zero exit code. Output: $output")
                if (output.isBlank()) return ""
                return "Error: $output" 
            }
            output
        } catch (e: Exception) {
            logger.error("Git execution failed", e)
            "Error executing git command: ${e.message}"
        }
    }
}