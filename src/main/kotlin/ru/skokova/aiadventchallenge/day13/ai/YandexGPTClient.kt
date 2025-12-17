package ru.skokova.aiadventchallenge.day13.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * HTTP-клиент для работы с Yandex Cloud YandexGPT API
 * Поддерживает sync-режим completion
 */
class YandexGPTClient(
    val apiKey: String,
    val folderId: String
) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }
    
    private val logger = LoggerFactory.getLogger(YandexGPTClient::class.java)
    
    /**
     * Отправка сообщения в YandexGPT
     * 
     * @param systemPrompt - системная инструкция
     * @param userMessage - сообщение пользователя
     * @param model - yandexgpt-lite (быстрая) или yandexgpt (точная)
     * @return текстовый ответ модели
     */
    suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        model: String = "yandexgpt-lite"
    ): String {
        val endpoint = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
        
        val requestBody = buildJsonObject {
            put("modelUri", "gpt://$folderId/$model/latest")
            putJsonObject("completionOptions") {
                put("stream", false)
                put("temperature", 0.6)
                put("maxTokens", 2000)
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("text", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("text", userMessage)
                }
            }
        }
        
        return try {
            logger.debug("📤 Sending request to YandexGPT API")
            logger.debug("Model: $model")
            logger.debug("User message: ${userMessage.take(100)}...")
            
            val response: HttpResponse = httpClient.post(endpoint) {
                header("Authorization", "Api-Key $apiKey")
                header("Content-Type", "application/json")
                setBody(requestBody.toString())
            }
            
            val responseBody = response.bodyAsText()
            logger.debug("📥 Response received: ${responseBody.take(200)}...")
            
            val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
            
            jsonResponse["result"]?.jsonObject
                ?.get("alternatives")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: throw Exception("Invalid response format from YandexGPT")
                
        } catch (e: Exception) {
            logger.error("❌ YandexGPT API error", e)
            throw Exception("YandexGPT API error: ${e.message}", e)
        }
    }
    
    fun close() {
        httpClient.close()
    }
}