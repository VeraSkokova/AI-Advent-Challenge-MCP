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

    // Overload for single path for backward compatibility
    suspend fun createIndex(folderPath: String): VectorIndex {
        return createIndex(listOf(folderPath))
    }

    suspend fun createIndex(paths: List<String>): VectorIndex {
        val allChunks = mutableListOf<DocumentChunk>()
        val filesToIndex = mutableSetOf<File>()

        paths.forEach { path ->
            val folder = File(path).canonicalFile
            if (!folder.exists()) {
                logger.warn("Путь не найден: $path (resolved: ${folder.absolutePath})")
                return@forEach
            }

            if (folder.isFile) {
                if (shouldIndexFile(folder)) filesToIndex.add(folder)
            } else {
                folder.walkTopDown()
                    .onEnter { file ->
                        // Всегда заходим в корневую папку поиска
                        if (file.absolutePath == folder.absolutePath) return@onEnter true
                        
                        // Игнорируем технические папки
                        val name = file.name
                        !name.startsWith(".") && 
                        name != "build" && 
                        name != "gradle" && 
                        name != "resources" // Skip resources to avoid binary files
                    }
                    .filter { file -> 
                        file.isFile && shouldIndexFile(file)
                    }
                    .forEach { filesToIndex.add(it) }
            }
        }

        logger.info("Найдено уникальных файлов для индексации: ${filesToIndex.size}")

        filesToIndex.forEachIndexed { index, file ->
            logger.info("[${index + 1}/${filesToIndex.size}] Индексация: ${file.path}")

            val text = try {
                file.readText()
            } catch (e: Exception) {
                logger.warn("Не удалось прочитать файл ${file.name}: ${e.message}")
                return@forEachIndexed
            }

            // Добавляем путь к имени файла, чтобы RAG мог точнее ссылаться
            // Но берем относительный путь от корня проекта, если возможно
            val relativePath = file.path.substringAfterLast("src/").substringAfterLast("docs/")
            
            val chunks = chunker.chunkDocument(file.name, text)

            val enrichedChunks = chunks.map { chunk ->
                try {
                    val embedding = client.getDocEmbedding(chunk.text)
                    chunk.copy(embedding = embedding)
                } catch (e: Exception) {
                    logger.error("Ошибка API эмбеддингов для ${chunk.id}: ${e.message}")
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

    private fun shouldIndexFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        // Index Markdown, Text, Kotlin files. 
        // Also ensure we don't index Generated files or huge assets
        return (ext == "md" || ext == "txt" || ext == "kt") && file.length() < 1_000_000 // Skip files > 1MB
    }

    fun saveIndex(index: VectorIndex, path: String = "rag_index.json") {
        val file = File(path)
        file.writeText(json.encodeToString(index))
        logger.info("Индекс сохранен в файл: ${file.absolutePath} (Всего чанков: ${index.chunks.size})")
    }

    fun loadIndex(path: String = "rag_index.json"): VectorIndex? {
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