package ru.skokova.aiadventchallenge.rag.services

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.rag.client.YandexEmbeddingClient
import ru.skokova.aiadventchallenge.rag.models.DocumentChunk
import ru.skokova.aiadventchallenge.rag.models.VectorIndex
import kotlin.math.sqrt

@Serializable
data class SearchResult(
    val chunk: DocumentChunk,
    val similarity: Double
)

class SearchService(private val client: YandexEmbeddingClient) {
    private val logger = LoggerFactory.getLogger(SearchService::class.java)

    suspend fun search(query: String, index: VectorIndex, topK: Int = 3): List<SearchResult> {
        logger.debug("Генерация эмбеддинга для запроса: '$query'")

        val queryEmbedding = try {
            client.getQueryEmbedding(query)
        } catch (e: Exception) {
            logger.error("Ошибка API при поиске: ${e.message}")
            return emptyList()
        }

        return index.chunks
            .map { chunk ->
                SearchResult(chunk, cosineSimilarity(queryEmbedding, chunk.embedding))
            }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    private fun cosineSimilarity(vecA: List<Double>, vecB: List<Double>): Double {
        if (vecA.size != vecB.size) return 0.0

        val dotProduct = vecA.zip(vecB) { a, b -> a * b }.sum()
        val normA = sqrt(vecA.sumOf { it * it })
        val normB = sqrt(vecB.sumOf { it * it })

        return if (normA == 0.0 || normB == 0.0) 0.0 else dotProduct / (normA * normB)
    }
}