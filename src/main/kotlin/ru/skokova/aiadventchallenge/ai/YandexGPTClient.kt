package ru.skokova.aiadventchallenge.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class CompletionRequest(
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
    val text: String? = ""
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

class YandexGPTClient(
    private val apiKey: String,
    private val folderId: String
) {
    private val logger = LoggerFactory.getLogger(YandexGPTClient::class.java)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                coerceInputValues = true 
                prettyPrint = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
        }
    }

    suspend fun chat(systemPrompt: String, userPrompt: String, model: String = "yandexgpt"): String {
        return chat(
            messages = listOf(
                Message(role = "system", text = systemPrompt),
                Message(role = "user", text = userPrompt)
            ),
            model = model
        )
    }

    suspend fun chat(messages: List<Message>, model: String = "yandexgpt"): String {
        val modelUri = "gpt://$folderId/$model/latest"
        val url = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

        val requestBody = CompletionRequest(
            modelUri = modelUri,
            completionOptions = CompletionOptions(),
            messages = messages
        )

        return try {
            val response: YandexResponse = client.post(url) {
                header("Authorization", "Api-Key $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            val text = response.result.alternatives.firstOrNull()?.message?.text
            if (text.isNullOrBlank()) {
                logger.warn("Received empty or null text from YandexGPT")
                "..." 
            } else {
                text
            }
        } catch (e: Exception) {
            logger.error("YandexGPT API error", e)
            "Error: ${e.message}"
        }
    }
}
