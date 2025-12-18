package ru.skokova.aiadventchallenge.mcp

import kotlinx.serialization.Serializable

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val parameters: List<String>
)
