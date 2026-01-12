package ru.skokova.aiadventchallenge.rag.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.rag.client.YandexEmbeddingClient
import ru.skokova.aiadventchallenge.rag.models.DocumentChunk
import ru.skokova.aiadventchallenge.rag.models.VectorIndex
import java.io.File
import java.time.Instant

class IndexService(
    private val client: YandexEmbeddingClient,
    private val chunker: TextChunker
) {
    private val logger = LoggerFactory.getLogger(IndexService::class.java)
    private val json = Json { prettyPrint = true }

    suspend fun createIndex(folderPath: String): VectorIndex {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Папка не найдена: $folderPath")
        }

        val files = folder.walkTopDown()
            .onEnter { file -> 
                // Пропускаем скрытые папки (.git, .idea) и папки сборки (build, gradle)
                !file.name.startsWith(".") && file.name != "build" && file.name != "gradle" 
            }
            .filter { file ->
                if (!file.isFile) return@filter false
                val ext = file.extension.lowercase()
                // Теперь индексируем и Kotlin файлы
                ext == "md" || ext == "txt" || ext == "kt"
            }
            .toList()

        logger.info("Найдено документов для индексации: ${files.size}")

        val allChunks = mutableListOf<DocumentChunk>()

        files.forEachIndexed { index, file ->
            logger.info("[${index + 1}/${files.size}] Обработка файла: ${file.name}")

            val text = file.readText()
            val chunks = chunker.chunkDocument(file.name, text)

            val enrichedChunks = chunks.map { chunk ->
                try {
                    val embedding = client.getDocEmbedding(chunk.text)
                    chunk.copy(embedding = embedding)
                } catch (e: Exception) {
                    logger.error("Не удалось получить эмбеддинг для чанка ${chunk.id}: ${e.message}")
                    chunk
                }
            }.filter { it.embedding.isNotEmpty() }

            allChunks.addAll(enrichedChunks)
        }

        return VectorIndex(
            createdAt = Instant.now().toString(),
            chunks = allChunks
        )
    }

    fun saveIndex(index: VectorIndex, path: String = "index.json") {
        val file = File(path)
        file.writeText(json.encodeToString(index))
        logger.info("Индекс сохранен в файл: ${file.absolutePath} (Всего чанков: ${index.chunks.size})")
    }

    fun loadIndex(path: String = "index.json"): VectorIndex? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<VectorIndex>(file.readText())
        } catch (e: Exception) {
            logger.error("Ошибка чтения индекса: ${e.message}")
            null
        }
    }
}