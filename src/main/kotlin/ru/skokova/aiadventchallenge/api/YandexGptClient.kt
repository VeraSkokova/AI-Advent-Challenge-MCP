package ru.skokova.aiadventchallenge.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.ktor.serialization.kotlinx.json.* // Самый важный
import io.ktor.client.plugins.contentnegotiation.*

@Serializable
data class YandexRequest(
    val modelUri: String,
    val completionOptions: CompletionOptions,
    val messages: List<Message>,
    val jsonObject: Boolean = false
)

@Serializable
data class CompletionOptions(
    val stream: Boolean = false,
    val temperature: Double = 0.3,
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

class YandexGptClient(
    private val apiKey: String,
    private val folderId: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun chat(messages: List<Message>, jsonObject: Boolean = false): String {
        val request = YandexRequest(
            modelUri = "gpt://$folderId/yandexgpt/latest", // Или yandexgpt/latest
            completionOptions = CompletionOptions(),
            messages = messages,
            jsonObject = jsonObject
        )

        val response: YandexResponse = client.post("https://llm.api.cloud.yandex.net/foundationModels/v1/completion") {
            header("Authorization", "Api-Key $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        return response.result.alternatives.first().message.text
    }
}
