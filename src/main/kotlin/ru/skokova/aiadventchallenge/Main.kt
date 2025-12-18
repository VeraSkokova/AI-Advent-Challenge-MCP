package ru.skokova.aiadventchallenge

import kotlinx.coroutines.runBlocking
import ru.skokova.aiadventchallenge.ai.YandexAIAgent
import ru.skokova.aiadventchallenge.ai.YandexGPTClient
import ru.skokova.aiadventchallenge.coincap.CoinCapClient
import ru.skokova.aiadventchallenge.mcp.*
import ru.skokova.aiadventchallenge.storage.ReminderStorage
import ru.skokova.aiadventchallenge.utils.loadProperties
import java.io.File
import java.util.Properties
import java.util.Scanner

fun main() = runBlocking {
    // 0. Загружаем local.properties (если есть)
    val properties = loadProperties()

    // 1. Загрузка конфигурации (через правильный helper)
    // Для COINCAP_API_KEY используем try/catch или отдельную логику,
    // если он опциональный, но по твоей логике getEnvOrProperty кидает exception.
    // Если он обязателен - вызываем так же. Если нет - обрабатываем исключение.
    val yandexKey = getEnvOrProperty("YANDEX_API_KEY", properties)
    val folderId = getEnvOrProperty("YANDEX_FOLDER_ID", properties)

    val coinCapKey = try {
        getEnvOrProperty("COINCAP_API_KEY", properties)
    } catch (e: Exception) {
        null // Если ключа нет, продолжаем без него (или падает, если критично)
    }

    // 2. Инициализация
    val coinCapClient = CoinCapClient(apiKey = coinCapKey)
    val storage = ReminderStorage(File("reminders.json"))
    val yandexGPTClient = YandexGPTClient(yandexKey, folderId)

    // 3. Серверы
    val reminderServer = ReminderMCPServer(storage)
    val cryptoServer = CryptoCurrencyMCPServer(coinCapClient)
    val summarizationServer = SummarizationMCPServer()
    val filesystemClient = FilesystemMCPClient()

    // 4. Агент
    val agent = YandexAIAgent(
        apiKey = yandexKey,
        folderId = folderId,
        reminderMcpServer = reminderServer,
        cryptoCurrencyMcpServer = cryptoServer,
        summarizationMcpServer = summarizationServer,
        filesystemClient = filesystemClient,
        yandexGPTClient = yandexGPTClient
    )

    println("🚀 AI Agent ready! (CoinCap Key: ${if (coinCapKey != null) "Yes" else "No"})")
    val scanner = Scanner(System.`in`)
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

// Твой правильный метод (добавлен прямо сюда для надежности)
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
