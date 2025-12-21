package ru.skokova.aiadventchallenge.utils

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Результат выполнения команды
 */
@Serializable
data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String = ""
)

/**
 * Информация об устройстве
 */
@Serializable
data class Device(
    val serialNumber: String,
    val state: String
)

/**
 * Информация об APK
 */
data class ApkInfo(
    val packageName: String,
    val launchActivity: String?
)

/**
 * Менеджер для работы с ADB и Android эмулятором
 * Поддерживает Windows, Linux и macOS
 */
class AdbManager {
    private val logger = LoggerFactory.getLogger(AdbManager::class.java)

    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")

    private val androidHome = findAndroidHome()
    private val adbPath = findAdbPath()
    private val emulatorPath = findEmulatorPath()
    private val aaptPath = findAaptPath()

    // Кэш для хранения информации о последнем установленном APK
    private var lastInstalledApkInfo: ApkInfo? = null

    init {
        logger.info("🔧 AdbManager initialized. OS: $osName")
        if (androidHome == null) logger.warn("⚠️ ANDROID_HOME not found")
        if (adbPath == null) logger.warn("⚠️ ADB not found")
        if (emulatorPath == null) logger.warn("⚠️ Emulator not found")
        if (aaptPath == null) logger.warn("⚠️ AAPT not found")
    }

    fun getLastInstalledApkInfo(): ApkInfo? = lastInstalledApkInfo

    private fun findAndroidHome(): String? {
        val envHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (envHome != null && File(envHome).exists()) return envHome

        val defaultPaths = when {
            isWindows -> listOf(
                "${System.getenv("LOCALAPPDATA")}\\Android\\Sdk",
                "${System.getProperty("user.home")}\\AppData\\Local\\Android\\Sdk"
            )
            osName.contains("mac") -> listOf("${System.getProperty("user.home")}/Library/Android/sdk")
            osName.contains("nux") -> listOf("${System.getProperty("user.home")}/Android/Sdk", "/opt/android-sdk")
            else -> emptyList()
        }
        return defaultPaths.firstOrNull { File(it).exists() }
    }

    private fun findAdbPath(): String? {
        val adbExecutable = if (isWindows) "adb.exe" else "adb"
        if (androidHome != null) {
            val adbInSdk = File(androidHome, "platform-tools/$adbExecutable")
            if (adbInSdk.exists()) return adbInSdk.absolutePath
        }
        try {
            if (executeCommandSimple(listOf(adbExecutable, "version")).success) return adbExecutable
        } catch (e: Exception) { /* Not in PATH */ }
        return null
    }

    private fun findEmulatorPath(): String? {
        val emulatorExecutable = if (isWindows) "emulator.exe" else "emulator"
        if (androidHome != null) {
            val emulatorInSdk = File(androidHome, "emulator/$emulatorExecutable")
            if (emulatorInSdk.exists()) return emulatorInSdk.absolutePath
            val emulatorInTools = File(androidHome, "tools/$emulatorExecutable")
            if (emulatorInTools.exists()) return emulatorInTools.absolutePath
        }
        try {
            if (executeCommandSimple(listOf(emulatorExecutable, "-version")).success) return emulatorExecutable
        } catch (e: Exception) { /* Not in PATH */ }
        return null
    }

    private fun findAaptPath(): String? {
        val aaptExecutable = if (isWindows) "aapt.exe" else "aapt"
        if (androidHome != null) {
            val buildToolsDir = File(androidHome, "build-tools")
            if (buildToolsDir.exists()) {
                buildToolsDir.listFiles()?.sortedByDescending { it.name }?.forEach { versionDir ->
                    val aaptFile = File(versionDir, aaptExecutable)
                    if (aaptFile.exists()) return aaptFile.absolutePath
                }
            }
        }
        return null
    }

    // Этот метод должен быть public, так как используется в AndroidEnvironmentMCPServer через installApk
    // Но сам он там напрямую не вызывается, но логика инкапсулирована тут.
    // Однако, checkAdb, listDevices, getLogcat - точно вызываются извне.

    fun checkAdb(): CommandResult {
        if (adbPath == null) return CommandResult(false, "", "ADB not found")
        return executeCommand(listOf(adbPath, "version"))
    }

    fun listDevices(): List<Device> {
        if (adbPath == null) return emptyList()
        val result = executeCommand(listOf(adbPath, "devices"))
        if (!result.success) return emptyList()
        return result.output.lines().drop(1).mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) Device(parts[0], parts[1]) else null
        }
    }

    fun getLogcat(packageName: String, lines: Int = 50): CommandResult {
        if (adbPath == null) return CommandResult(false, "", "ADB not found")

        val command = if (isWindows) {
            listOf("powershell", "-Command", "$adbPath logcat -d | Select-String -Pattern '$packageName' | Select-Object -Last $lines")
        } else {
            listOf("sh", "-c", "$adbPath logcat -d | grep '$packageName' | tail -n $lines")
        }
        return executeCommand(command, 30)
    }

    private fun extractApkInfo(apkPath: String): ApkInfo? {
        if (aaptPath == null) {
            logger.warn("⚠️ AAPT not found, cannot extract APK info.")
            return null
        }
        try {
            val result = executeCommandSimple(listOf(aaptPath, "dump", "badging", apkPath))
            if (!result.success) return null

            var packageName: String? = null
            var launchActivity: String? = null

            result.output.lines().forEach { line ->
                if (line.startsWith("package: name=")) {
                    packageName = line.substringAfter("name='").substringBefore("'")
                } else if (line.startsWith("launchable-activity: name=")) {
                    launchActivity = line.substringAfter("name='").substringBefore("'")
                }
            }

            return if (packageName != null) ApkInfo(packageName!!, launchActivity) else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun executeCommandSimple(command: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            process.waitFor(5, TimeUnit.SECONDS)
            val exitCode = process.exitValue()
            CommandResult(exitCode == 0, output, if (exitCode != 0) output else "")
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "Unknown error")
        }
    }

    fun executeCommand(command: List<String>, timeoutSeconds: Long = 60): CommandResult {
        // Оставляем лог только для важных команд запуска/установки
        val isImportant = command.any { it.contains("install") || it.contains("am start") }
        if (isImportant) logger.info("🔧 Executing: ${command.joinToString(" ")}")

        return try {
            val process = ProcessBuilder(command).start()
            val output = StringBuilder()
            val error = StringBuilder()
            val outputThread = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { start() }
            val errorThread = Thread { process.errorStream.bufferedReader().forEachLine { error.appendLine(it) } }.apply { start() }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return CommandResult(false, output.toString(), "Timeout after $timeoutSeconds seconds")
            }

            outputThread.join()
            errorThread.join()

            val exitCode = process.exitValue()
            if (isImportant) {
                if (exitCode == 0) logger.info("✅ Command succeeded")
                else logger.error("❌ Command failed with exit code: $exitCode")
            }

            CommandResult(exitCode == 0, output.toString().trim(), error.toString().trim())
        } catch (e: Exception) {
            if (isImportant) logger.error("❌ Command execution error: ${e.message}")
            CommandResult(false, "", e.message ?: "Unknown error")
        }
    }

    private fun cleanupEmulatorProcesses() {
        logger.info("🧹 Cleaning up old emulator processes...")
        try {
            if (isWindows) {
                executeCommandSimple(listOf("taskkill", "/F", "/IM", "emulator.exe", "/T"))
                executeCommandSimple(listOf("taskkill", "/F", "/IM", "qemu-system-x86_64.exe", "/T"))
            } else {
                executeCommandSimple(listOf("pkill", "-9", "-f", "emulator"))
                executeCommandSimple(listOf("pkill", "-9", "-f", "qemu-system"))
            }
            if (adbPath != null) {
                executeCommandSimple(listOf(adbPath, "kill-server"))
                executeCommandSimple(listOf(adbPath, "start-server"))
            }
            val avdDir = File("${System.getProperty("user.home")}/.android/avd")
            if (avdDir.exists()) {
                avdDir.walk().filter { it.name.endsWith(".lock") }.forEach { it.delete() }
            }
            logger.info("✅ Cleanup completed.")
        } catch (e: Exception) {
            logger.warn("⚠️ Cleanup finished with a warning: ${e.message}")
        }
    }

    fun startEmulator(avdName: String): CommandResult {
        if (emulatorPath == null) return CommandResult(false, "", "Emulator not found.")
        cleanupEmulatorProcesses()
        logger.info("🚀 Starting emulator: $avdName")
        try {
            val command = if (isWindows) {
                listOf("cmd", "/c", "start", "/B", "\"\"", emulatorPath, "-avd", avdName, "-no-snapshot-load")
            } else {
                listOf("nohup", emulatorPath, "-avd", avdName, "-no-snapshot-load", "&")
            }
            val processBuilder = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)

            if (isWindows && androidHome != null) {
                processBuilder.environment()["PATH"] = "${androidHome}\\emulator;${androidHome}\\platform-tools;${System.getenv("PATH")}"
            }
            processBuilder.start()
            Thread.sleep(3000)
            return CommandResult(true, "Emulator launch command sent. Waiting for device to come online...")
        } catch (e: Exception) {
            logger.error("❌ Failed to start emulator", e)
            return CommandResult(false, "", "Failed to start emulator: ${e.message}")
        }
    }

    fun waitForDevice(timeoutSeconds: Int = 300): CommandResult {
        if (adbPath == null) return CommandResult(false, "", "ADB not found")
        logger.info("⏳ Waiting for device (timeout: ${timeoutSeconds}s)...")
        val waitResult = executeCommand(listOf(adbPath, "wait-for-device"), timeoutSeconds.toLong())
        if (!waitResult.success) return waitResult

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            val bootResult = executeCommand(listOf(adbPath, "shell", "getprop", "sys.boot_completed"), 10)
            if (bootResult.success && bootResult.output.trim() == "1") {
                logger.info("✅ Device is ready!")
                return CommandResult(true, "Device is ready")
            }
            Thread.sleep(5000)
        }
        return CommandResult(false, "", "Device boot timeout after $timeoutSeconds seconds")
    }

    fun installApk(apkPath: String, reinstall: Boolean = true): CommandResult {
        if (adbPath == null) return CommandResult(false, "", "ADB not found")
        if (!File(apkPath).exists()) return CommandResult(false, "", "APK file not found: $apkPath")

        val apkInfo = extractApkInfo(apkPath)
        if (apkInfo != null) lastInstalledApkInfo = apkInfo

        logger.info("📦 Installing APK: $apkPath")
        val command = mutableListOf(adbPath, "install").apply { if (reinstall) add("-r"); add(apkPath) }
        val result = executeCommand(command, 120)

        return if (result.success && result.output.contains("Success", ignoreCase = true)) {
            result.copy(output = "APK installed successfully. Package: ${apkInfo?.packageName ?: "unknown"}")
        } else {
            result
        }
    }

    fun startApp(packageName: String, activityName: String): CommandResult {
        if (adbPath == null) return CommandResult(false, "", "ADB not found")

        val fullActivityName = if (activityName.startsWith(".")) "$packageName$activityName" else activityName

        logger.info("🚀 Starting app: $packageName/$fullActivityName")
        val result = executeCommand(listOf(adbPath, "shell", "am", "start", "-n", "$packageName/$fullActivityName"))

        return if (result.success && (result.output.contains("Starting") || result.output.contains("Started"))) {
            result.copy(output = "App $packageName started successfully")
        } else {
            result
        }
    }
}
