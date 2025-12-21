package ru.skokova.aiadventchallenge.utils

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Data class для хранения конфигурации Android окружения
 */
@Serializable
data class AndroidConfig(
    val apkPath: String,
    val packageName: String,
    val activityName: String,
    val avdName: String
)

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
 * Менеджер для работы с ADB и Android эмулятором
 * Поддерживает Windows, Linux и macOS
 */
class AdbManager {
    private val logger = LoggerFactory.getLogger(AdbManager::class.java)
    
    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac")
    private val isLinux = osName.contains("nux")
    
    private val androidHome = findAndroidHome()
    private val adbPath = findAdbPath()
    private val emulatorPath = findEmulatorPath()
    
    init {
        logger.info("🔧 AdbManager initialized")
        logger.info("📱 OS: $osName")
        logger.info("📂 ANDROID_HOME: ${androidHome ?: "Not found"}")
        logger.info("🔨 ADB path: ${adbPath ?: "Not found"}")
        logger.info("📲 Emulator path: ${emulatorPath ?: "Not found"}")
    }
    
    /**
     * Поиск ANDROID_HOME
     */
    private fun findAndroidHome(): String? {
        // 1. Проверяем переменную окружения
        val envHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (envHome != null && File(envHome).exists()) {
            return envHome
        }
        
        // 2. Стандартные пути для разных ОС
        val defaultPaths = when {
            isWindows -> listOf(
                "${System.getenv("LOCALAPPDATA")}\\Android\\Sdk",
                "${System.getProperty("user.home")}\\AppData\\Local\\Android\\Sdk"
            )
            isMac -> listOf(
                "${System.getProperty("user.home")}/Library/Android/sdk"
            )
            isLinux -> listOf(
                "${System.getProperty("user.home")}/Android/Sdk",
                "/opt/android-sdk"
            )
            else -> emptyList()
        }
        
        return defaultPaths.firstOrNull { File(it).exists() }
    }
    
    /**
     * Поиск пути к adb
     */
    private fun findAdbPath(): String? {
        val adbExecutable = if (isWindows) "adb.exe" else "adb"
        
        // 1. Проверяем ANDROID_HOME/platform-tools
        if (androidHome != null) {
            val adbInSdk = File(androidHome, "platform-tools/$adbExecutable")
            if (adbInSdk.exists()) {
                return adbInSdk.absolutePath
            }
        }
        
        // 2. Проверяем PATH
        try {
            val result = executeCommandSimple(listOf(adbExecutable, "version"))
            if (result.success) {
                return adbExecutable // Доступен в PATH
            }
        } catch (e: Exception) {
            logger.debug("ADB not found in PATH")
        }
        
        return null
    }
    
    /**
     * Поиск пути к emulator
     */
    private fun findEmulatorPath(): String? {
        val emulatorExecutable = if (isWindows) "emulator.exe" else "emulator"
        
        // 1. Проверяем ANDROID_HOME/emulator
        if (androidHome != null) {
            val emulatorInSdk = File(androidHome, "emulator/$emulatorExecutable")
            if (emulatorInSdk.exists()) {
                return emulatorInSdk.absolutePath
            }
            // Старые версии SDK
            val emulatorInTools = File(androidHome, "tools/$emulatorExecutable")
            if (emulatorInTools.exists()) {
                return emulatorInTools.absolutePath
            }
        }
        
        // 2. Проверяем PATH
        try {
            val result = executeCommandSimple(listOf(emulatorExecutable, "-version"))
            if (result.success) {
                return emulatorExecutable
            }
        } catch (e: Exception) {
            logger.debug("Emulator not found in PATH")
        }
        
        return null
    }
    
    /**
     * Выполнение команды с логированием
     */
    private fun executeCommandSimple(command: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            CommandResult(
                success = exitCode == 0,
                output = output,
                error = if (exitCode != 0) output else ""
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Выполнение команды с подробным логированием
     */
    fun executeCommand(command: List<String>, timeoutSeconds: Long = 30): CommandResult {
        logger.info("🔧 Executing command: ${command.joinToString(" ")}")
        
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            // Читаем stdout
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val outputThread = Thread {
                outputReader.forEachLine { line ->
                    output.appendLine(line)
                    logger.debug("📤 $line")
                }
            }
            outputThread.start()
            
            // Читаем stderr
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val errorThread = Thread {
                errorReader.forEachLine { line ->
                    error.appendLine(line)
                    logger.debug("⚠️ $line")
                }
            }
            errorThread.start()
            
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                logger.error("❌ Command timeout after $timeoutSeconds seconds")
                return CommandResult(
                    success = false,
                    output = output.toString(),
                    error = "Timeout after $timeoutSeconds seconds"
                )
            }
            
            outputThread.join()
            errorThread.join()
            
            val exitCode = process.exitValue()
            val success = exitCode == 0
            
            if (success) {
                logger.info("✅ Command succeeded")
            } else {
                logger.error("❌ Command failed with exit code: $exitCode")
            }
            
            CommandResult(
                success = success,
                output = output.toString().trim(),
                error = error.toString().trim()
            )
        } catch (e: Exception) {
            logger.error("❌ Command execution error: ${e.message}")
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Проверка доступности ADB
     */
    fun checkAdb(): CommandResult {
        if (adbPath == null) {
            return CommandResult(
                success = false,
                output = "",
                error = "ADB not found. Please install Android SDK and set ANDROID_HOME environment variable."
            )
        }
        
        return executeCommand(listOf(adbPath, "version"))
    }
    
    /**
     * Список подключенных устройств
     */
    fun listDevices(): List<Device> {
        if (adbPath == null) {
            logger.error("❌ ADB not found")
            return emptyList()
        }
        
        val result = executeCommand(listOf(adbPath, "devices"))
        if (!result.success) {
            return emptyList()
        }
        
        // Парсим вывод adb devices
        return result.output.lines()
            .drop(1) // Пропускаем заголовок "List of devices attached"
            .filter { it.isNotBlank() && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    Device(parts[0], parts[1])
                } else null
            }
    }
    
    /**
     * Запуск эмулятора
     */
    fun startEmulator(avdName: String): CommandResult {
        if (emulatorPath == null) {
            return CommandResult(
                success = false,
                output = "",
                error = "Emulator not found. Please install Android SDK."
            )
        }
        
        logger.info("🚀 Starting emulator: $avdName")
        
        // Запускаем эмулятор асинхронно
        try {
            val command = listOf(emulatorPath, "-avd", avdName, "-no-snapshot-load")
            logger.info("🔧 Command: ${command.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(command)
            
            // Для Windows добавляем специальные флаги
            if (isWindows) {
                processBuilder.environment()["PATH"] = 
                    "${androidHome}\\emulator;${androidHome}\\platform-tools;${System.getenv("PATH")}"
            }
            
            val process = processBuilder.start()
            
            logger.info("✅ Emulator process started with PID: ${process.pid()}")
            
            return CommandResult(
                success = true,
                output = "Emulator started with PID: ${process.pid()}. Waiting for device...",
                error = ""
            )
        } catch (e: Exception) {
            logger.error("❌ Failed to start emulator", e)
            return CommandResult(
                success = false,
                output = "",
                error = "Failed to start emulator: ${e.message}"
            )
        }
    }
    
    /**
     * Ожидание готовности устройства
     */
    fun waitForDevice(timeoutSeconds: Int = 180): CommandResult {
        if (adbPath == null) {
            return CommandResult(
                success = false,
                output = "",
                error = "ADB not found"
            )
        }
        
        logger.info("⏳ Waiting for device (timeout: ${timeoutSeconds}s)...")
        
        // Сначала ждем подключения устройства
        val waitResult = executeCommand(
            listOf(adbPath, "wait-for-device"),
            timeoutSeconds = timeoutSeconds.toLong()
        )
        
        if (!waitResult.success) {
            return waitResult
        }
        
        // Теперь ждем полной загрузки системы
        val startTime = System.currentTimeMillis()
        val maxWaitMillis = timeoutSeconds * 1000L
        
        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            val bootResult = executeCommand(
                listOf(adbPath, "shell", "getprop", "sys.boot_completed"),
                timeoutSeconds = 10
            )
            
            if (bootResult.success && bootResult.output.trim() == "1") {
                logger.info("✅ Device is ready!")
                return CommandResult(
                    success = true,
                    output = "Device is ready",
                    error = ""
                )
            }
            
            Thread.sleep(5000) // Ждем 5 секунд перед следующей проверкой
            logger.info("⏳ Still waiting for boot completion...")
        }
        
        return CommandResult(
            success = false,
            output = "",
            error = "Device boot timeout after $timeoutSeconds seconds"
        )
    }
    
    /**
     * Установка APK
     */
    fun installApk(apkPath: String, reinstall: Boolean = true): CommandResult {
        if (adbPath == null) {
            return CommandResult(
                success = false,
                output = "",
                error = "ADB not found"
            )
        }
        
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            return CommandResult(
                success = false,
                output = "",
                error = "APK file not found: $apkPath"
            )
        }
        
        logger.info("📦 Installing APK: $apkPath")
        
        val command = if (reinstall) {
            listOf(adbPath, "install", "-r", apkPath)
        } else {
            listOf(adbPath, "install", apkPath)
        }
        
        val result = executeCommand(command, timeoutSeconds = 120)
        
        // Проверяем успешность установки
        if (result.success && result.output.contains("Success", ignoreCase = true)) {
            logger.info("✅ APK installed successfully")
            return result.copy(
                success = true,
                output = "APK installed successfully"
            )
        } else {
            logger.error("❌ APK installation failed")
            return result.copy(success = false)
        }
    }
    
    /**
     * Запуск приложения
     */
    fun startApp(packageName: String, activityName: String): CommandResult {
        if (adbPath == null) {
            return CommandResult(
                success = false,
                output = "",
                error = "ADB not found"
            )
        }
        
        // Формируем полное имя Activity
        val fullActivityName = if (activityName.startsWith(".")) {
            "$packageName$activityName"
        } else if (activityName.contains(".")) {
            activityName
        } else {
            "$packageName.$activityName"
        }
        
        logger.info("🚀 Starting app: $packageName/$fullActivityName")
        
        val result = executeCommand(
            listOf(
                adbPath, "shell", "am", "start",
                "-n", "$packageName/$fullActivityName"
            )
        )
        
        if (result.success && (result.output.contains("Starting") || result.output.contains("Started"))) {
            logger.info("✅ App started successfully")
            return result.copy(
                success = true,
                output = "App $packageName started successfully"
            )
        } else {
            logger.error("❌ Failed to start app")
            return result
        }
    }
    
    /**
     * Получение логов приложения
     */
    fun getLogcat(packageName: String, lines: Int = 50): CommandResult {
        if (adbPath == null) {
            return CommandResult(
                success = false,
                output = "",
                error = "ADB not found"
            )
        }
        
        logger.info("📋 Getting logcat for: $packageName (last $lines lines)")
        
        // Используем разные команды для разных ОС
        val command = if (isWindows) {
            // На Windows используем PowerShell для фильтрации
            listOf(
                "powershell", "-Command",
                "$adbPath logcat -d | Select-String -Pattern '$packageName' | Select-Object -Last $lines"
            )
        } else {
            // На Unix-подобных системах используем grep и tail
            listOf(
                "sh", "-c",
                "$adbPath logcat -d | grep '$packageName' | tail -n $lines"
            )
        }
        
        return executeCommand(command, timeoutSeconds = 30)
    }
}
