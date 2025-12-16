package ru.skokova.aiadventchallenge.mcp.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.skokova.aiadventchallenge.utils.getEnvOrProperty
import ru.skokova.aiadventchallenge.utils.loadProperties

// Модели ответа CoinCap
@Serializable
data class CoinCapResponse(val data: List<CryptoData>) // CoinCap возвращает { "data": [...] }

@Serializable
data class CryptoData(
    val id: String,
    val symbol: String,
    val priceUsd: String
)

class CoinCapTool {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun getCryptoPrice(assetId: String): String {
        return try {
            val props = loadProperties()

            // CoinCap API: GET https://api.coincap.io/v2/assets?search=bitcoin
            val response: CoinCapResponse = httpClient.get("https://rest.coincap.io/v3/assets") {
                parameter("search", assetId)
                parameter("limit", 1)
                header("Authorization", "Bearer ${getEnvOrProperty("COINCAP_API_KEY", props)}")
            }.body()

            val asset = response.data.firstOrNull()
            if (asset != null) {
                "Цена ${asset.symbol} (${asset.id}): $${String.format("%.2f", asset.priceUsd.toDouble())}"
            } else {
                "Криптовалюта '$assetId' не найдена."
            }
        } catch (e: Exception) {
            System.err.println("CoinCap Error:")
            e.printStackTrace(System.err)

            "Ошибка API: ${e.message ?: e.toString()}"
        }
    }
}
