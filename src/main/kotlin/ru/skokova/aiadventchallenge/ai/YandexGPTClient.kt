package ru.skokova.aiadventchallenge.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class YandexRequest(
    val modelUri: String,
    val completionOptions: CompletionOptions,
    val messages: List<Message>
)

@Serializable
data class CompletionOptions(
    val stream: Boolean = false,
    val temperature: Double = 0.6,
    val maxTokens: String = "2000"
)

@Serializable
data class Message(
    val role: String,
    val text: String
)

@Serializable
data class YandexResponse(
    val result: Result
)

@Serializable
data class Result(
    val alternatives: List<Alternative>
)

@Serializable
data class Alternative(
    val message: Message,
    val status: String
)

/**
 * HTTP клиент для работы с Yandex Cloud GPT API
 *
 * @property apiKey API ключ для доступа к Yandex Cloud
 * @property folderId ID каталога в Yandex Cloud
 */
class YandexGPTClient(
    private val apiKey: String,
    private val folderId: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    private val logger = LoggerFactory.getLogger(YandexGPTClient::class.java)
    
    /**
     * Отправить сообщение в YandexGPT
     *
     * @param systemPrompt Системная инструкция
     * @param userMessage Сообщение пользователя
     * @param model Модель: yandexgpt-lite (быстрая) или yandexgpt (точная)
     * @return Текстовый ответ модели
     */
    suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        model: String = "yandexgpt-lite"
    ): String {
        val messages = listOf(
            Message(role = "system", text = systemPrompt),
            Message(role = "user", text = userMessage)
        )
        
        val request = YandexRequest(
            modelUri = "gpt://$folderId/$model/latest",
            completionOptions = CompletionOptions(),
            messages = messages
        )
        
        return try {
            val response: YandexResponse = client.post(
                "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
            ) {
                header("Authorization", "Api-Key $apiKey")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
            
            response.result.alternatives.first().message.text
        } catch (e: Exception) {
            logger.error("YandexGPT API error", e)
            throw e
        }
    }
    
    /**
     * Отправить список сообщений в YandexGPT
     */
    suspend fun chat(messages: List<Message>, model: String = "yandexgpt-lite"): String {
        val request = YandexRequest(
            modelUri = "gpt://$folderId/$model/latest",
            completionOptions = CompletionOptions(),
            messages = messages
        )
        
        return try {
            val response: YandexResponse = client.post(
                "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
            ) {
                header("Authorization", "Api-Key $apiKey")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
            
            response.result.alternatives.first().message.text
        } catch (e: Exception) {
            logger.error("YandexGPT API error", e)
            throw e
        }
    }
}
