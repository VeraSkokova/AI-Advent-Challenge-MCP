package ru.skokova.aiadventchallenge.rag.models

import kotlinx.serialization.Serializable

@Serializable
data class DocumentChunk(
    val id: String,
    val text: String,
    val embedding: List<Double>,
    val metadata: ChunkMetadata
)

@Serializable
data class ChunkMetadata(
    val sourceFile: String,
    val chunkIndex: Int,
    val totalChunksInFile: Int,
    val startPosition: Int,
    val endPosition: Int,
    val startLine: Int = 0,
    val endLine: Int = 0
)

@Serializable
data class VectorIndex(
    val version: String = "1.0",
    val createdAt: String,
    val chunks: List<DocumentChunk>
)

@Serializable
data class EmbeddingRequest(
    val modelUri: String,
    val text: String
)

@Serializable
data class EmbeddingResponse(
    val embedding: List<Double>,
    val numTokens: String,
    val modelVersion: String
)