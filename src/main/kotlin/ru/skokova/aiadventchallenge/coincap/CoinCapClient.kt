package ru.skokova.aiadventchallenge.coincap

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Модели ответа CoinCap API
 */
@Serializable
data class CoinCapResponse(
    val data: List<CryptoData>
)

@Serializable
data class CryptoData(
    val id: String,
    val symbol: String,
    val priceUsd: String,
    val changePercent24Hr: String? = null
)

/**
 * Клиент для работы с CoinCap API v3
 * API Docs: https://docs.coincap.io/
 *
 * @property apiKey API ключ для CoinCap (опционально, но увеличивает rate limits)
 */
class CoinCapClient(private val apiKey: String? = null) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    private val logger = LoggerFactory.getLogger(CoinCapClient::class.java)
    
    /**
     * Получить курсы криптовалют
     *
     * @param coins Список ID криптовалют (например: ["bitcoin", "ethereum"])
     * @return Map<coinId, priceUsd>
     */
    suspend fun getRates(coins: List<String>): Map<String, Double> {
        val results = mutableMapOf<String, Double>()
        
        for (coinId in coins) {
            try {
                val price = getCryptoPrice(coinId)
                if (price != null) {
                    results[coinId] = price
                }
            } catch (e: Exception) {
                logger.error("Error fetching price for $coinId", e)
            }
        }
        
        return results
    }
    
    /**
     * Получить цену одной криптовалюты
     */
    suspend fun getCryptoPrice(assetId: String): Double? {
        return try {
            val response: CoinCapResponse = httpClient.get("https://api.coincap.io/v2/assets") {
                parameter("search", assetId)
                parameter("limit", 1)
                if (apiKey != null) {
                    header("Authorization", "Bearer $apiKey")
                }
            }.body()
            
            val asset = response.data.firstOrNull()
            asset?.priceUsd?.toDoubleOrNull()
        } catch (e: Exception) {
            logger.error("CoinCap API error for asset: $assetId", e)
            null
        }
    }
    
    /**
     * Получить детальную информацию о криптовалюте
     */
    suspend fun getCryptoDetails(assetId: String): String {
        return try {
            val response: CoinCapResponse = httpClient.get("https://api.coincap.io/v2/assets") {
                parameter("search", assetId)
                parameter("limit", 1)
                if (apiKey != null) {
                    header("Authorization", "Bearer $apiKey")
                }
            }.body()
            
            val asset = response.data.firstOrNull()
            if (asset != null) {
                val price = String.format("%.2f", asset.priceUsd.toDouble())
                val change = asset.changePercent24Hr?.let { 
                    String.format("%.2f", it.toDouble())
                } ?: "N/A"
                
                "Цена ${asset.symbol.uppercase()} (${asset.id}): $$price (24h: ${change}%)"
            } else {
                "Криптовалюта '$assetId' не найдена."
            }
        } catch (e: Exception) {
            logger.error("CoinCap API error", e)
            "Ошибка API: ${e.message ?: e.toString()}"
        }
    }
}
