package ru.skokova.aiadventchallenge.mcp

import kotlinx.serialization.Serializable
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
    
    @Serializable
    data class ToolInfo(
        val name: String,
        val description: String,
        val parameters: Map<String, String>
    )
    
    /**
     * Список доступных инструментов
     */
    fun getToolsList(): List<ToolInfo> = listOf(
        ToolInfo(
            name = "check_adb",
            description = "Check if ADB is available and working",
            parameters = emptyMap()
        ),
        ToolInfo(
            name = "list_devices",
            description = "List all connected Android devices and emulators",
            parameters = emptyMap()
        ),
        ToolInfo(
            name = "start_emulator",
            description = "Start an Android emulator by AVD name",
            parameters = mapOf(
                "avdName" to "string (required) - Name of the AVD to start (e.g., Pixel_5_API_34)"
            )
        ),
        ToolInfo(
            name = "wait_for_device",
            description = "Wait until device/emulator is fully booted and ready",
            parameters = mapOf(
                "timeout" to "int (optional) - Timeout in seconds (default: 180)"
            )
        ),
        ToolInfo(
            name = "install_apk",
            description = "Install an APK file on the connected device",
            parameters = mapOf(
                "apkPath" to "string (required) - Absolute path to the APK file",
                "reinstall" to "boolean (optional) - Allow reinstall without clearing data (default: true)"
            )
        ),
        ToolInfo(
            name = "start_app",
            description = "Start an installed Android application",
            parameters = mapOf(
                "packageName" to "string (required) - Package name (e.g., com.example.app)",
                "activityName" to "string (required) - Activity name (e.g., .MainActivity)"
            )
        ),
        ToolInfo(
            name = "get_logcat",
            description = "Get recent logcat entries for a specific package",
            parameters = mapOf(
                "packageName" to "string (required) - Package name to filter logs",
                "lines" to "int (optional) - Number of lines to retrieve (default: 50)"
            )
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
                    json.encodeToString(
                        mapOf(
                            "error" to "Unknown tool: $toolName",
                            "available_tools" to getToolsList().map { it.name }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("❌ Error executing $toolName", e)
            json.encodeToString(
                mapOf(
                    "error" to "Execution failed: ${e.message}",
                    "tool" to toolName
                )
            )
        }
    }
    
    /**
     * check_adb: Проверка доступности ADB
     */
    private fun checkAdb(): String {
        val result = adbManager.checkAdb()
        
        return if (result.success) {
            json.encodeToString(
                mapOf(
                    "status" to "success",
                    "message" to "ADB is available",
                    "version" to result.output
                )
            )
        } else {
            json.encodeToString(
                mapOf(
                    "status" to "error",
                    "message" to "ADB not found",
                    "error" to result.error,
                    "hint" to "Please install Android SDK and set ANDROID_HOME environment variable"
                )
            )
        }
    }
    
    /**
     * list_devices: Список подключенных устройств
     */
    private fun listDevices(): String {
        val devices = adbManager.listDevices()
        
        return json.encodeToString(
            mapOf(
                "status" to "success",
                "count" to devices.size,
                "devices" to devices.map {
                    mapOf(
                        "serial" to it.serialNumber,
                        "state" to it.state
                    )
                }
            )
        )
    }
    
    /**
     * start_emulator: Запуск эмулятора
     */
    private fun startEmulator(params: Map<String, Any>): String {
        val avdName = params["avdName"]?.toString() 
            ?: return json.encodeToString(
                mapOf("error" to "Missing required parameter: avdName")
            )
        
        val result = adbManager.startEmulator(avdName)
        
        return if (result.success) {
            json.encodeToString(
                mapOf(
                    "status" to "success",
                    "message" to "Emulator started",
                    "avdName" to avdName,
                    "output" to result.output,
                    "note" to "Use wait_for_device to ensure the emulator is fully booted"
                )
            )
        } else {
            json.encodeToString(
                mapOf(
                    "status" to "error",
                    "message" to "Failed to start emulator",
                    "avdName" to avdName,
                    "error" to result.error
                )
            )
        }
    }
    
    /**
     * wait_for_device: Ожидание готовности устройства
     */
    private fun waitForDevice(params: Map<String, Any>): String {
        val timeout = when (val t = params["timeout"]) {
            is Number -> t.toInt()
            is String -> t.toIntOrNull() ?: 180
            else -> 180
        }
        
        logger.info("⏳ Waiting for device (timeout: ${timeout}s)")
        val result = adbManager.waitForDevice(timeout)
        
        return if (result.success) {
            json.encodeToString(
                mapOf(
                    "status" to "success",
                    "message" to "Device is ready",
                    "output" to result.output
                )
            )
        } else {
            json.encodeToString(
                mapOf(
                    "status" to "error",
                    "message" to "Device not ready",
                    "error" to result.error,
                    "timeout" to timeout
                )
            )
        }
    }
    
    /**
     * install_apk: Установка APK
     */
    private fun installApk(params: Map<String, Any>): String {
        val apkPath = params["apkPath"]?.toString() 
            ?: return json.encodeToString(
                mapOf("error" to "Missing required parameter: apkPath")
            )
        
        val reinstall = when (val r = params["reinstall"]) {
            is Boolean -> r
            is String -> r.lowercase() in listOf("true", "yes", "1")
            else -> true // По умолчанию разрешаем reinstall
        }
        
        logger.info("📦 Installing APK: $apkPath (reinstall: $reinstall)")
        val result = adbManager.installApk(apkPath, reinstall)
        
        return if (result.success) {
            json.encodeToString(
                mapOf(
                    "status" to "success",
                    "message" to "APK installed successfully",
                    "apkPath" to apkPath,
                    "output" to result.output
                )
            )
        } else {
            json.encodeToString(
                mapOf(
                    "status" to "error",
                    "message" to "Failed to install APK",
                    "apkPath" to apkPath,
                    "error" to result.error,
                    "output" to result.output
                )
            )
        }
    }
    
    /**
     * start_app: Запуск приложения
     */
    private fun startApp(params: Map<String, Any>): String {
        val packageName = params["packageName"]?.toString() 
            ?: return json.encodeToString(
                mapOf("error" to "Missing required parameter: packageName")
            )
        
        val activityName = params["activityName"]?.toString() 
            ?: return json.encodeToString(
                mapOf("error" to "Missing required parameter: activityName")
            )
        
        logger.info("🚀 Starting app: $packageName/$activityName")
        val result = adbManager.startApp(packageName, activityName)
        
        return if (result.success) {
            json.encodeToString(
                mapOf(
                    "status" to "success",
                    "message" to "App started successfully",
                    "packageName" to packageName,
                    "activityName" to activityName,
                    "output" to result.output
                )
            )
        } else {
            json.encodeToString(
                mapOf(
                    "status" to "error",
                    "message" to "Failed to start app",
                    "packageName" to packageName,
                    "activityName" to activityName,
                    "error" to result.error,
                    "output" to result.output
                )
            )
        }
    }
    
    /**
     * get_logcat: Получение логов
     */
    private fun getLogcat(params: Map<String, Any>): String {
        val packageName = params["packageName"]?.toString() 
            ?: return json.encodeToString(
                mapOf("error" to "Missing required parameter: packageName")
            )
        
        val lines = when (val l = params["lines"]) {
            is Number -> l.toInt()
            is String -> l.toIntOrNull() ?: 50
            else -> 50
        }
        
        logger.info("📋 Getting logcat for: $packageName (last $lines lines)")
        val result = adbManager.getLogcat(packageName, lines)
        
        return if (result.success) {
            json.encodeToString(
                mapOf(
                    "status" to "success",
                    "packageName" to packageName,
                    "lines" to lines,
                    "logs" to result.output
                )
            )
        } else {
            json.encodeToString(
                mapOf(
                    "status" to "error",
                    "message" to "Failed to get logcat",
                    "packageName" to packageName,
                    "error" to result.error
                )
            )
        }
    }
}
