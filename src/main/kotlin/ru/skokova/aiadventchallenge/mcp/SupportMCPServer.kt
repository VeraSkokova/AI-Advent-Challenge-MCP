package ru.skokova.aiadventchallenge.mcp

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

class SupportMCPServer(private val crmFile: File) {
    private val logger = LoggerFactory.getLogger(SupportMCPServer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun getToolsList(): List<ToolInfo> {
        return listOf(
            ToolInfo(
                name = "get_user_details",
                description = "Get customer details (plan, OS, environment) by User ID.",
                parameters = listOf("userId")
            ),
            ToolInfo(
                name = "get_user_history",
                description = "Get previous support tickets for a user.",
                parameters = listOf("userId")
            )
        )
    }

    fun executeTool(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "get_user_details" -> {
                val userId = params["userId"] as? String ?: return "Error: userId required"
                getUserDetails(userId)
            }
            "get_user_history" -> {
                val userId = params["userId"] as? String ?: return "Error: userId required"
                getUserHistory(userId)
            }
            else -> "Error: Unknown tool $toolName"
        }
    }

    private fun getUserDetails(userId: String): String {
        val users = loadUsers()
        val user = users.find { it.jsonObject["id"]?.jsonPrimitive?.content == userId }
        
        return if (user != null) {
            // Return everything except history to keep context small
            val details = user.jsonObject.toMutableMap()
            details.remove("history")
            JsonObject(details).toString()
        } else {
            "Error: User '$userId' not found in CRM."
        }
    }

    private fun getUserHistory(userId: String): String {
        val users = loadUsers()
        val user = users.find { it.jsonObject["id"]?.jsonPrimitive?.content == userId }
        
        return if (user != null) {
            val history = user.jsonObject["history"]?.jsonArray
            history?.toString() ?: "No history found."
        } else {
            "Error: User '$userId' not found."
        }
    }

    private fun loadUsers(): JsonArray {
        if (!crmFile.exists()) return JsonArray(emptyList())
        return try {
            json.parseToJsonElement(crmFile.readText()).jsonArray
        } catch (e: Exception) {
            logger.error("Failed to parse CRM file", e)
            JsonArray(emptyList())
        }
    }
}
