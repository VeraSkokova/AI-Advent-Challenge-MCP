package ru.skokova.aiadventchallenge

import kotlinx.coroutines.runBlocking
import ru.skokova.aiadventchallenge.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.ai.YandexGPTClient
import ru.skokova.aiadventchallenge.coincap.CoinCapClient
import ru.skokova.aiadventchallenge.mcp.*
import ru.skokova.aiadventchallenge.storage.ReminderStorage
import ru.skokova.aiadventchallenge.utils.AdbManager
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

    // 5. Агент с поддержкой всех серверов
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

    // 6. Интерактивный режим
    val scanner = Scanner(System.`in`)

    println("\n" + "=".repeat(60))
    println("🚀 AI Agent ready!")
    println("=".repeat(60))
    println("CoinCap API: ${if (coinCapKey != null) "✅ Connected" else "❌ Not configured"}")
    println("Android Environment MCP: ✅ Enabled")
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
