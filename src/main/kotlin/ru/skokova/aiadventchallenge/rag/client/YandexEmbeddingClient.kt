package ru.skokova.aiadventchallenge.rag.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.rag.models.EmbeddingRequest
import ru.skokova.aiadventchallenge.rag.models.EmbeddingResponse

class YandexEmbeddingClient(
    private val apiKey: String,
    private val folderId: String
) {
    private val logger = LoggerFactory.getLogger(YandexEmbeddingClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    private val docModelUri = "emb://$folderId/text-search-doc/latest"
    private val queryModelUri = "emb://$folderId/text-search-query/latest"
    private val apiUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/textEmbedding"

    suspend fun getDocEmbedding(text: String): List<Double> {
        return getEmbeddingWithRetry(text, docModelUri)
    }

    suspend fun getQueryEmbedding(text: String): List<Double> {
        return getEmbeddingWithRetry(text, queryModelUri)
    }

    private suspend fun getEmbeddingWithRetry(text: String, modelUri: String): List<Double> {
        val maxRetries = 3
        var currentRetry = 0

        while (currentRetry < maxRetries) {
            try {
                val response: EmbeddingResponse = client.post(apiUrl) {
                    header("Authorization", "Api-Key $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(EmbeddingRequest(modelUri, text))
                }.body()

                return response.embedding
            } catch (e: Exception) {
                currentRetry++
                logger.warn("Ошибка API (попытка $currentRetry/$maxRetries): ${e.message}")
                if (currentRetry == maxRetries) throw e
                delay(1000L * (1 shl (currentRetry - 1)))
            }
        }
        return emptyList()
    }
}