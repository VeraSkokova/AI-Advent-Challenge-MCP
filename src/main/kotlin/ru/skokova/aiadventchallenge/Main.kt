package ru.skokova.aiadventchallenge

import kotlinx.coroutines.runBlocking
import ru.skokova.aiadventchallenge.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.ai.YandexGPTClient
import ru.skokova.aiadventchallenge.coincap.CoinCapClient
import ru.skokova.aiadventchallenge.mcp.*
import ru.skokova.aiadventchallenge.storage.ReminderStorage
import ru.skokova.aiadventchallenge.utils.AdbManager
import ru.skokova.aiadventchallenge.utils.AndroidConfig
import ru.skokova.aiadventchallenge.utils.loadProperties
import java.io.File
import java.util.Properties
import java.util.Scanner

fun main() = runBlocking {
    // 0. Загружаем local.properties (если есть)
    val properties = loadProperties()

    // 1. Загрузка конфигурации
    val yandexKey = getEnvOrProperty("YANDEX_API_KEY", properties)
    val folderId = getEnvOrProperty("YANDEX_FOLDER_ID", properties)

    val coinCapKey = try {
        getEnvOrProperty("COINCAP_API_KEY", properties)
    } catch (e: Exception) {
        null
    }

    // 2. Инициализация клиентов и хранилищ
    val coinCapClient = CoinCapClient(apiKey = coinCapKey)
    val storage = ReminderStorage(File("reminders.json"))
    val yandexGPTClient = YandexGPTClient(yandexKey, folderId)

    // 3. Инициализация MCP серверов
    val reminderServer = ReminderMCPServer(storage)
    val cryptoServer = CryptoCurrencyMCPServer(coinCapClient)
    val summarizationServer = SummarizationMCPServer()
    val filesystemClient = FilesystemMCPClient()
    
    // 4. Day 15: Android Environment MCP Server
    val adbManager = AdbManager()
    val androidEnvServer = AndroidEnvironmentMCPServer(adbManager)
    
    // 5. Интерактивный сбор конфигурации для Android
    println("\n" + "=".repeat(60))
    println("🤖 Day 15: Android Environment Setup")
    println("=".repeat(60))
    println()
    println("📱 Этот режим позволяет автоматизировать работу с Android эмулятором.")
    println("Вы можете запускать эмуляторы, устанавливать и тестировать APK.")
    println()
    print("❓ Хотите настроить Android окружение? (y/n): ")
    
    val scanner = Scanner(System.`in`)
    val setupAndroid = scanner.nextLine().trim().lowercase() in listOf("y", "yes", "д", "да")
    
    val androidConfig = if (setupAndroid) {
        collectAndroidConfig(scanner)
    } else {
        println("✅ Android окружение не настроено. Вы можете сделать это позже.")
        null
    }

    // 6. Агент с поддержкой всех серверов
    val agent = YandexAIAgent(
        apiKey = yandexKey,
        folderId = folderId,
        reminderMcpServer = reminderServer,
        cryptoCurrencyMcpServer = cryptoServer,
        summarizationMcpServer = summarizationServer,
        filesystemClient = filesystemClient,
        androidEnvironmentMcpServer = androidEnvServer,
        yandexGPTClient = yandexGPTClient
    )

    // 7. Интерактивный режим
    println("\n" + "=".repeat(60))
    println("🚀 AI Agent ready!")
    println("=".repeat(60))
    println("CoinCap API: ${if (coinCapKey != null) "✅ Connected" else "❌ Not configured"}")
    println("Android Environment: ${if (setupAndroid) "✅ Configured" else "❌ Not configured"}")
    if (androidConfig != null) {
        println("\n📱 Android Config:")
        println("  APK: ${androidConfig.apkPath}")
        println("  Package: ${androidConfig.packageName}")
        println("  Activity: ${androidConfig.activityName}")
        println("  AVD: ${androidConfig.avdName}")
    }
    println("\n💬 Type 'exit' to quit")
    println("=".repeat(60))
    
    while (true) {
        print("\n> ")
        if (!scanner.hasNextLine()) break
        val input = scanner.nextLine()
        if (input.lowercase() == "exit") break
        try {
            val response = agent.executeCommand(input)
            println("\n🤖 ${response.summary}")
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
        }
    }
    filesystemClient.shutdown()
}

/**
 * Интерактивный сбор конфигурации для Android окружения
 */
fun collectAndroidConfig(scanner: Scanner): AndroidConfig {
    println()
    println("🔧 Настройка Android Environment")
    println("-".repeat(60))
    
    print("\n📱 Введите путь к APK файлу: ")
    val apkPath = scanner.nextLine().trim()
    
    print("📦 Введите package name (например, com.example.app): ")
    val packageName = scanner.nextLine().trim()
    
    print("🎯 Введите main activity (например, .MainActivity): ")
    val activityName = scanner.nextLine().trim()
    
    print("📲 Введите имя AVD (например, Pixel_5_API_34): ")
    val avdName = scanner.nextLine().trim()
    
    println()
    println("✅ Конфигурация сохранена!")
    println("-".repeat(60))
    
    return AndroidConfig(
        apkPath = apkPath,
        packageName = packageName,
        activityName = activityName,
        avdName = avdName
    )
}

/**
 * Получение значения из ENV или properties файла
 */
fun getEnvOrProperty(key: String, properties: Properties?): String {
    // 1. Ищем в ENV (приоритет)
    val envValue = System.getenv(key)
    if (!envValue.isNullOrBlank()) return envValue

    // 2. Ищем в properties файле
    val propValue = properties?.getProperty(key)
    if (!propValue.isNullOrBlank()) return propValue

    // 3. Падаем, если не нашли
    throw IllegalStateException("Missing configuration: $key. Please set it in ENV or local.properties")
}
