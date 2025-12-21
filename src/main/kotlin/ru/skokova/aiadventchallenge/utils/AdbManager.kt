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
    private val isMac = osName.contains("mac")
    private val isLinux = osName.contains("nux")
    
    private val androidHome = findAndroidHome()
    private val adbPath = findAdbPath()
    private val emulatorPath = findEmulatorPath()
    private val aaptPath = findAaptPath()
    
    // Кэш для хранения информации о последнем установленном APK
    private var lastInstalledApkInfo: ApkInfo? = null
    
    init {
        logger.info("🔧 AdbManager initialized")
        logger.info("📱 OS: $osName")
        logger.info("📂 ANDROID_HOME: ${androidHome ?: "Not found"}")
        logger.info("🔨 ADB path: ${adbPath ?: "Not found"}")
        logger.info("📲 Emulator path: ${emulatorPath ?: "Not found"}")
        logger.info("🔍 AAPT path: ${aaptPath ?: "Not found"}")
    }
    
    /**
     * Получить информацию о последнем установленном APK
     */
    fun getLastInstalledApkInfo(): ApkInfo? = lastInstalledApkInfo
    
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
     * Поиск пути к aapt (Android Asset Packaging Tool)
     */
    private fun findAaptPath(): String? {
        val aaptExecutable = if (isWindows) "aapt.exe" else "aapt"
        
        if (androidHome != null) {
            // Ищем в build-tools
            val buildToolsDir = File(androidHome, "build-tools")
            if (buildToolsDir.exists()) {
                val versions = buildToolsDir.listFiles()?.sortedByDescending { it.name } ?: emptyList()
                for (versionDir in versions) {
                    val aaptFile = File(versionDir, aaptExecutable)
                    if (aaptFile.exists()) {
                        return aaptFile.absolutePath
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Извлечение информации из APK
     */
    fun extractApkInfo(apkPath: String): ApkInfo? {
        if (aaptPath == null) {
            logger.warn("⚠️ AAPT not found, cannot extract APK info")
            return null
        }
        
        try {
            logger.info("🔍 Extracting APK info from: $apkPath")
            
            val result = executeCommandSimple(
                listOf(aaptPath, "dump", "badging", apkPath),
                logOutput = false
            )
            
            if (!result.success) {
                logger.warn("⚠️ Failed to extract APK info: ${result.error}")
                return null
            }
            
            var packageName: String? = null
            var launchActivity: String? = null
            
            result.output.lines().forEach { line ->
                when {
                    line.startsWith("package: name=") -> {
                        // package: name='ru.skokova.chatwithygpt' versionCode='1' versionName='1.0'
                        packageName = line.substringAfter("name='").substringBefore("'")
                    }
                    line.startsWith("launchable-activity: name=") -> {
                        // launchable-activity: name='ru.skokova.chatwithygpt.MainActivity'
                        launchActivity = line.substringAfter("name='").substringBefore("'")
                    }
                }
            }
            
            if (packageName != null) {
                val info = ApkInfo(packageName!!, launchActivity)
                logger.info("✅ Extracted APK info: package=$packageName, activity=$launchActivity")
                return info
            } else {
                logger.warn("⚠️ Could not find package name in APK")
                return null
            }
        } catch (e: Exception) {
            logger.error("❌ Error extracting APK info", e)
            return null
        }
    }
    
    /**
     * Выполнение команды с логированием
     */
    private fun executeCommandSimple(command: List<String>, logOutput: Boolean = false): CommandResult {
        if (logOutput) {
            logger.info("🔧 Executing: ${command.joinToString(" ")}")
        }
        
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor(5, TimeUnit.SECONDS)
            
            if (!exitCode) {
                process.destroyForcibly()
            }
            
            val actualExitCode = process.exitValue()
            
            if (logOutput) {
                logger.info("📤 Exit code: $actualExitCode")
                if (output.isNotBlank()) {
                    logger.info("📤 Output: ${output.take(200)}")
                }
            }
            
            CommandResult(
                success = actualExitCode == 0,
                output = output,
                error = if (actualExitCode != 0) output else ""
            )
        } catch (e: Exception) {
            if (logOutput) {
                logger.warn("⚠️ Command failed: ${e.message}")
            }
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
     * Очистка старых процессов эмулятора перед запуском
     */
    private fun cleanupEmulatorProcesses() {
        logger.info("🧹 Cleaning up old emulator processes...")
        
        try {
            // Шаг 1: Принудительное завершение ВСЕХ процессов эмулятора через taskkill
            if (isWindows) {
                logger.info("🔨 Step 1: Killing emulator.exe processes...")
                val result1 = executeCommandSimple(
                    listOf("taskkill", "/F", "/IM", "emulator.exe", "/T"),
                    logOutput = true
                )
                Thread.sleep(1500)
                
                logger.info("🔨 Step 2: Killing qemu-system-x86_64.exe processes...")
                val result2 = executeCommandSimple(
                    listOf("taskkill", "/F", "/IM", "qemu-system-x86_64.exe", "/T"),
                    logOutput = true
                )
                Thread.sleep(1500)
                
                // Проверяем что процессы убиты
                logger.info("🔍 Step 3: Verifying processes are killed...")
                val checkResult = executeCommandSimple(
                    listOf("powershell", "-Command", 
                        "Get-Process | Where-Object {\$_.ProcessName -like '*emulator*'} | Select-Object ProcessName, Id"),
                    logOutput = true
                )
                
                if (checkResult.output.contains("emulator")) {
                    logger.warn("⚠️ Emulator processes still running!")
                    logger.warn("Output: ${checkResult.output}")
                } else {
                    logger.info("✅ All emulator processes killed")
                }
            } else {
                // Для Unix-подобных систем
                executeCommandSimple(listOf("pkill", "-9", "-f", "emulator"), logOutput = true)
                Thread.sleep(1000)
                executeCommandSimple(listOf("pkill", "-9", "-f", "qemu-system"), logOutput = true)
                Thread.sleep(1000)
            }
            
            // Шаг 2: Перезапуск ADB сервера для очистки всех соединений
            if (adbPath != null) {
                logger.info("🔄 Step 4: Restarting ADB server...")
                executeCommandSimple(listOf(adbPath, "kill-server"), logOutput = true)
                Thread.sleep(1500)
                executeCommandSimple(listOf(adbPath, "start-server"), logOutput = true)
                Thread.sleep(1500)
            }
            
            // Шаг 3: Удаляем lock файлы AVD если они существуют
            logger.info("🗑️ Step 5: Deleting AVD lock files...")
            val userHome = System.getProperty("user.home")
            val avdPath = if (isWindows) {
                "$userHome\\.android\\avd"
            } else {
                "$userHome/.android/avd"
            }
            
            val avdDir = File(avdPath)
            if (avdDir.exists()) {
                var lockFilesDeleted = 0
                avdDir.listFiles()?.forEach { avdFolder ->
                    if (avdFolder.isDirectory) {
                        val lockFiles = avdFolder.listFiles { file -> 
                            file.name.endsWith(".lock") || file.name.contains("lock")
                        }
                        lockFiles?.forEach { lockFile ->
                            try {
                                if (lockFile.delete()) {
                                    lockFilesDeleted++
                                    logger.info("  ✓ Deleted: ${lockFile.name}")
                                }
                            } catch (e: Exception) {
                                logger.debug("  ✗ Could not delete: ${lockFile.name}")
                            }
                        }
                    }
                }
                logger.info("✅ Deleted $lockFilesDeleted lock files")
            } else {
                logger.info("ℹ️ AVD directory not found: $avdPath")
            }
            
            logger.info("✅ Cleanup completed successfully")
        } catch (e: Exception) {
            logger.warn("⚠️ Cleanup warning: ${e.message}")
            e.printStackTrace()
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
        
        // Очистка перед запуском
        cleanupEmulatorProcesses()
        
        logger.info("🚀 Starting emulator: $avdName")
        
        // Запускаем эмулятор асинхронно через cmd /c start (Windows) или nohup (Unix)
        try {
            val command = if (isWindows) {
                // Windows: используем cmd /c start для отвязки процесса
                listOf(
                    "cmd", "/c", "start", 
                    "/B",  // Без создания нового окна консоли
                    "\"\"",  // Пустой заголовок
                    emulatorPath, "-avd", avdName, "-no-snapshot-load"
                )
            } else {
                // Unix: используем nohup для отвязки процесса
                listOf(
                    "nohup", emulatorPath, "-avd", avdName, "-no-snapshot-load", "&"
                )
            }
            
            logger.info("🔧 Command: ${command.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(command)
            
            // Для Windows добавляем специальные флаги
            if (isWindows) {
                processBuilder.environment()["PATH"] = 
                    "${androidHome}\\emulator;${androidHome}\\platform-tools;${System.getenv("PATH")}"
            }
            
            // Перенаправляем вывод в /dev/null чтобы не блокироваться
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
            
            val process = processBuilder.start()
            
            // Ждём немного и проверяем что команда cmd /c start выполнилась
            Thread.sleep(2000)
            
            logger.info("✅ Emulator launch command executed")
            
            // Проверяем что процесс эмулятора действительно запустился
            Thread.sleep(1000)
            val checkResult = if (isWindows) {
                executeCommandSimple(
                    listOf("powershell", "-Command", 
                        "Get-Process | Where-Object {\$_.ProcessName -like '*emulator*'} | Select-Object ProcessName, Id -First 1"),
                    logOutput = false
                )
            } else {
                executeCommandSimple(listOf("pgrep", "-f", "emulator"), logOutput = false)
            }
            
            if (checkResult.output.contains("emulator") || checkResult.output.isNotBlank()) {
                logger.info("✅ Emulator process confirmed running")
                return CommandResult(
                    success = true,
                    output = "Emulator started successfully. Waiting for device...",
                    error = ""
                )
            } else {
                logger.warn("⚠️ Could not confirm emulator process")
                return CommandResult(
                    success = true,
                    output = "Emulator launch command sent. Process may be starting...",
                    error = ""
                )
            }
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
    fun waitForDevice(timeoutSeconds: Int = 300): CommandResult {
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
        
        // Извлекаем информацию из APK перед установкой
        val apkInfo = extractApkInfo(apkPath)
        if (apkInfo != null) {
            lastInstalledApkInfo = apkInfo
            logger.info("💾 Saved APK info for later use: ${apkInfo.packageName}")
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
                output = "APK installed successfully. Package: ${apkInfo?.packageName ?: "unknown"}"
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
