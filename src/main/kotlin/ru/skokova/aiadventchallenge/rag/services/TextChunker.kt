package ru.skokova.aiadventchallenge.rag.services

import org.slf4j.LoggerFactory
import ru.skokova.aiadventchallenge.rag.config.Config
import ru.skokova.aiadventchallenge.rag.models.ChunkMetadata
import ru.skokova.aiadventchallenge.rag.models.DocumentChunk
import java.util.UUID

class TextChunker {
    private val logger = LoggerFactory.getLogger(TextChunker::class.java)

    fun chunkDocument(fileName: String, text: String): List<DocumentChunk> {
        if (text.isBlank()) return emptyList()

        val lines = text.lines()
        val chunks = mutableListOf<DocumentChunk>()
        
        val currentChunkText = StringBuilder()
        var currentChunkStartLine = 0 // 0-based index
        
        // Track character offset for legacy fields
        // Since we are rebuilding text from lines, exact char match might vary if original had mixed line endings
        // But for our purpose, we just want line numbers.
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // +1 for newline character approximation
            val lineLength = line.length + 1 
            
            if (currentChunkText.length + lineLength > Config.MAX_CHUNK_SIZE && currentChunkText.isNotEmpty()) {
                // Finalize current chunk
                chunks.add(createChunk(
                    fileName, 
                    currentChunkText.toString(), 
                    chunks.size, 
                    currentChunkStartLine + 1, // 1-based
                    i // 1-based, points to the line before current 'i'
                ))
                
                // Start new chunk with overlap
                currentChunkText.clear()
                
                // Backtrack to fill overlap
                var overlapSize = 0
                var backTrackIndex = i - 1
                val overlapBuffer = java.util.LinkedList<String>()
                
                while (backTrackIndex >= 0 && overlapSize < Config.CHUNK_OVERLAP) {
                    val prevLine = lines[backTrackIndex]
                    overlapBuffer.addFirst(prevLine)
                    overlapSize += prevLine.length + 1
                    backTrackIndex--
                }
                
                // The new chunk starts from the first line of the overlap
                currentChunkStartLine = backTrackIndex + 1
                
                overlapBuffer.forEach { 
                    currentChunkText.append(it).append("\n") 
                }
            }
            
            currentChunkText.append(line).append("\n")
            i++
        }

        if (currentChunkText.isNotEmpty()) {
            chunks.add(createChunk(
                fileName, 
                currentChunkText.toString(), 
                chunks.size, 
                currentChunkStartLine + 1, 
                lines.size
            ))
        }

        return chunks.map { it.copy(metadata = it.metadata.copy(totalChunksInFile = chunks.size)) }
    }

    private fun createChunk(fileName: String, text: String, index: Int, startLine: Int, endLine: Int): DocumentChunk {
        return DocumentChunk(
            id = UUID.randomUUID().toString(),
            text = text.trimEnd(), // Remove trailing newline
            embedding = emptyList(),
            metadata = ChunkMetadata(
                sourceFile = fileName,
                chunkIndex = index,
                totalChunksInFile = 0,
                startPosition = 0, // Legacy, unused now
                endPosition = 0,   // Legacy, unused now
                startLine = startLine,
                endLine = endLine
            )
        )
    }
}