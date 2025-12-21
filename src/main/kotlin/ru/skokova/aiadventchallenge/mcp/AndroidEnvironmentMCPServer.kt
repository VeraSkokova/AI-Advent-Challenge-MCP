package ru.skokova.aiadventchallenge.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.utils.AdbManager

/**
 * MCP сервер для управления Android эмулятором и приложениями
 * Позволяет запускать эмулятор, устанавливать и запускать APK через ADB
 */
class AndroidEnvironmentMCPServer(private val adbManager: AdbManager) {
    private val logger = LoggerFactory.getLogger(AndroidEnvironmentMCPServer::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    
    /**
     * Проверка что значение недействительно или placeholder
     */
    private fun isInvalidOrPlaceholder(value: String?): Boolean {
        return value == null || 
               value.isEmpty() || 
               value == "null" || 
               value.contains("your.package") || 
               value.contains("your.activity")
    }
    
    /**
     * Список доступных инструментов
     */
    fun getToolsList(): List<ToolInfo> = listOf(
        ToolInfo(
            name = "check_adb",
            description = "Check if ADB is available and working",
            parameters = emptyList()
        ),
        ToolInfo(
            name = "list_devices",
            description = "List all connected Android devices and emulators",
            parameters = emptyList()
        ),
        ToolInfo(
            name = "start_emulator",
            description = "Start an Android emulator by AVD name",
            parameters = listOf("avdName")
        ),
        ToolInfo(
            name = "wait_for_device",
            description = "Wait until device/emulator is fully booted and ready",
            parameters = listOf("timeout")
        ),
        ToolInfo(
            name = "install_apk",
            description = "Install an APK file on the connected device. Returns package name and launch activity.",
            parameters = listOf("apkPath", "reinstall")
        ),
        ToolInfo(
            name = "start_app",
            description = "Start an installed Android application. If packageName is not provided, uses last installed APK info.",
            parameters = listOf("packageName", "activityName")
        ),
        ToolInfo(
            name = "get_logcat",
            description = "Get recent logcat entries for a specific package",
            parameters = listOf("packageName", "lines")
        )
    )
    
    /**
     * Выполнение инструмента
     */
    suspend fun executeTool(toolName: String, params: Map<String, Any>): String {
        logger.info("🔧 Executing Android tool: $toolName")
        
        return try {
            when (toolName) {
                "check_adb" -> checkAdb()
                "list_devices" -> listDevices()
                "start_emulator" -> startEmulator(params)
                "wait_for_device" -> waitForDevice(params)
                "install_apk" -> installApk(params)
                "start_app" -> startApp(params)
                "get_logcat" -> getLogcat(params)
                else -> {
                    logger.error("❌ Unknown tool: $toolName")
                    buildJsonError("Unknown tool: $toolName", mapOf("available_tools" to getToolsList().map { it.name }))
                }
            }
        } catch (e: Exception) {
            logger.error("❌ Error executing $toolName", e)
            buildJsonError("Execution failed: ${e.message}", mapOf("tool" to toolName))
        }
    }
    
    /**
     * Helper для построения JSON с ошибкой
     */
    private fun buildJsonError(message: String, extra: Map<String, Any> = emptyMap()): String {
        val errorMap = mutableMapOf<String, String>("error" to message)
        extra.forEach { (k, v) -> 
            errorMap[k] = when (v) {
                is List<*> -> v.joinToString(", ")
                else -> v.toString()
            }
        }
        return json.encodeToString(errorMap)
    }
    
    /**
     * Helper для построения успешного JSON ответа
     */
    private fun buildJsonSuccess(data: Map<String, String>): String {
        return json.encodeToString(data)
    }
    
    /**
     * check_adb: Проверка доступности ADB
     */
    private fun checkAdb(): String {
        val result = adbManager.checkAdb()
        
        return if (result.success) {
            buildJsonSuccess(mapOf(
                "status" to "success",
                "message" to "ADB is available",
                "version" to result.output
            ))
        } else {
            buildJsonError("ADB not found", mapOf(
                "error" to result.error,
                "hint" to "Please install Android SDK and set ANDROID_HOME environment variable"
            ))
        }
    }
    
    /**
     * list_devices: Список подключенных устройств
     */
    private fun listDevices(): String {
        val devices = adbManager.listDevices()
        
        val devicesJson = devices.map { 
            "\"${it.serialNumber}\": \"${it.state}\""
        }.joinToString(", ", "{", "}")
        
        return buildJsonSuccess(mapOf(
            "status" to "success",
            "count" to devices.size.toString(),
            "devices" to devicesJson
        ))
    }
    
    /**
     * start_emulator: Запуск эмулятора
     */
    private fun startEmulator(params: Map<String, Any>): String {
        val avdName = params["avdName"]?.toString() 
            ?: return buildJsonError("Missing required parameter: avdName")
        
        val result = adbManager.startEmulator(avdName)
        
        return if (result.success) {
            buildJsonSuccess(mapOf(
                "status" to "success",
                "message" to "Emulator started",
                "avdName" to avdName,
                "output" to result.output,
                "note" to "Use wait_for_device to ensure the emulator is fully booted"
            ))
        } else {
            buildJsonError("Failed to start emulator", mapOf(
                "avdName" to avdName,
                "error" to result.error
            ))
        }
    }
    
    /**
     * wait_for_device: Ожидание готовности устройства
     */
    private fun waitForDevice(params: Map<String, Any>): String {
        val timeout = when (val t = params["timeout"]) {
            is Number -> t.toInt()
            is String -> t.toIntOrNull() ?: 300
            else -> 300
        }
        
        logger.info("⏳ Waiting for device (timeout: ${timeout}s)")
        val result = adbManager.waitForDevice(timeout)
        
        return if (result.success) {
            buildJsonSuccess(mapOf(
                "status" to "success",
                "message" to "Device is ready",
                "output" to result.output
            ))
        } else {
            buildJsonError("Device not ready", mapOf(
                "error" to result.error,
                "timeout" to timeout.toString()
            ))
        }
    }
    
    /**
     * install_apk: Установка APK
     */
    private fun installApk(params: Map<String, Any>): String {
        val apkPath = params["apkPath"]?.toString() 
            ?: return buildJsonError("Missing required parameter: apkPath")
        
        val reinstall = when (val r = params["reinstall"]) {
            is Boolean -> r
            is String -> r.lowercase() in listOf("true", "yes", "1")
            else -> true
        }
        
        logger.info("📦 Installing APK: $apkPath (reinstall: $reinstall)")
        val result = adbManager.installApk(apkPath, reinstall)
        
        return if (result.success) {
            // Получаем информацию о APK
            val apkInfo = adbManager.getLastInstalledApkInfo()
            
            val responseMap = mutableMapOf(
                "status" to "success",
                "message" to "APK installed successfully",
                "apkPath" to apkPath,
                "output" to result.output
            )
            
            if (apkInfo != null) {
                responseMap["packageName"] = apkInfo.packageName
                if (apkInfo.launchActivity != null) {
                    responseMap["launchActivity"] = apkInfo.launchActivity
                }
                responseMap["note"] = "Use start_app with packageName='${apkInfo.packageName}' to launch the app"
            }
            
            buildJsonSuccess(responseMap)
        } else {
            buildJsonError("Failed to install APK", mapOf(
                "apkPath" to apkPath,
                "error" to result.error,
                "output" to result.output
            ))
        }
    }
    
    /**
     * start_app: Запуск приложения
     */
    private fun startApp(params: Map<String, Any>): String {
        var packageName = params["packageName"]?.toString()
        var activityName = params["activityName"]?.toString()
        
        // Если packageName не указан или placeholder/null, используем инфо из последнего установленного APK
        if (isInvalidOrPlaceholder(packageName)) {
            val apkInfo = adbManager.getLastInstalledApkInfo()
            if (apkInfo != null) {
                logger.info("💾 Using last installed APK info: ${apkInfo.packageName}")
                packageName = apkInfo.packageName
                if (isInvalidOrPlaceholder(activityName)) {
                    activityName = apkInfo.launchActivity
                }
            } else {
                return buildJsonError(
                    "Missing required parameter: packageName",
                    mapOf("hint" to "No previously installed APK info found. Install an APK first or provide packageName explicitly.")
                )
            }
        }
        
        if (isInvalidOrPlaceholder(activityName)) {
            return buildJsonError(
                "Missing required parameter: activityName",
                mapOf("hint" to "Could not determine activity name. Please provide it explicitly.")
            )
        }
        
        logger.info("🚀 Starting app: $packageName/$activityName")
        val result = adbManager.startApp(packageName!!, activityName!!)
        
        return if (result.success) {
            buildJsonSuccess(mapOf(
                "status" to "success",
                "message" to "App started successfully",
                "packageName" to packageName,
                "activityName" to activityName,
                "output" to result.output
            ))
        } else {
            buildJsonError("Failed to start app", mapOf(
                "packageName" to packageName,
                "activityName" to activityName,
                "error" to result.error,
                "output" to result.output
            ))
        }
    }
    
    /**
     * get_logcat: Получение логов
     */
    private fun getLogcat(params: Map<String, Any>): String {
        val packageName = params["packageName"]?.toString() 
            ?: return buildJsonError("Missing required parameter: packageName")
        
        val lines = when (val l = params["lines"]) {
            is Number -> l.toInt()
            is String -> l.toIntOrNull() ?: 50
            else -> 50
        }
        
        logger.info("📋 Getting logcat for: $packageName (last $lines lines)")
        val result = adbManager.getLogcat(packageName, lines)
        
        return if (result.success) {
            buildJsonSuccess(mapOf(
                "status" to "success",
                "packageName" to packageName,
                "lines" to lines.toString(),
                "logs" to result.output
            ))
        } else {
            buildJsonError("Failed to get logcat", mapOf(
                "packageName" to packageName,
                "error" to result.error
            ))
        }
    }
}
