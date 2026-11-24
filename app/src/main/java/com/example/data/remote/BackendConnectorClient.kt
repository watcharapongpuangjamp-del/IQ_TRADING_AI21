package com.example.data.remote

import com.example.model.BackendHealthState
import com.example.model.Candle
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class HealthResponseDto(
    val status: String = "ok",
    val connector: String = "fastapi_connector",
    val capabilities: List<String> = emptyList(),
    val live_authorized: Boolean = false,
    val execution_mode: String = "SIMULATION"
)

@JsonClass(generateAdapter = true)
data class CandleDto(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class OrderSubmitRequestDto(
    val instrument: String,
    val timeframe: String,
    val direction: String,
    val amount: Double,
    val expiry_seconds: Int,
    val mode: String,
    val idempotency_key: String
)

@JsonClass(generateAdapter = true)
data class OrderSubmitResponseDto(
    val order_id: String,
    val status: String,
    val entry_price: Double,
    val message: String? = null
)

interface FastApiTradingApi {
    @GET("api/v1/health")
    suspend fun getHealth(
        @Header("Authorization") authHeader: String?
    ): Response<HealthResponseDto>

    @GET("api/v1/candles")
    suspend fun getCandles(
        @Header("Authorization") authHeader: String?,
        @Query("instrument") instrument: String,
        @Query("timeframe") timeframe: String,
        @Query("limit") limit: Int = 80
    ): Response<List<CandleDto>>

    @POST("api/v1/orders")
    suspend fun submitOrder(
        @Header("Authorization") authHeader: String?,
        @Body request: OrderSubmitRequestDto
    ): Response<OrderSubmitResponseDto>
}

class BackendConnectorClient {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null

    fun isValidHttpUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
    }

    fun isValidWsUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("wss://", ignoreCase = true) ||
            trimmed.startsWith("ws://", ignoreCase = true)
    }

    private fun buildApi(baseUrl: String): FastApiTradingApi {
        val normalized = if (baseUrl.trim().endsWith("/")) baseUrl.trim() else "${baseUrl.trim()}/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FastApiTradingApi::class.java)
    }

    suspend fun checkHealth(baseUrl: String, bearerToken: String?): BackendHealthState {
        if (!isValidHttpUrl(baseUrl)) {
            return BackendHealthState(
                connected = false,
                connectorName = "Local Simulation Engine",
                supportedCapabilities = listOf("SIMULATION_STREAM", "TECHNICAL_ANALYSIS", "PAPER_ORDERS"),
                liveAuthorized = false,
                statusMessage = "ยังไม่ได้กำหนด FastAPI URL (ทำงานในโหมด Simulation อิสระ)"
            )
        }
        return try {
            val authHeader = bearerToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
            val response = buildApi(baseUrl).getHealth(authHeader)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                BackendHealthState(
                    connected = true,
                    connectorName = body.connector,
                    supportedCapabilities = body.capabilities.ifEmpty { listOf("MARKET_DATA", "SIGNALS") },
                    liveAuthorized = body.live_authorized,
                    statusMessage = "เชื่อมต่อ FastAPI สำเร็จ (${body.status})"
                )
            } else {
                BackendHealthState(
                    connected = false,
                    connectorName = "Disconnected",
                    liveAuthorized = false,
                    statusMessage = "Backend ตอบกลับรหัส HTTP ${response.code()} — ใช้ Simulation แทน"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackendHealthState(
                connected = false,
                connectorName = "Offline Fallback",
                liveAuthorized = false,
                statusMessage = "ไม่สามารถติดต่อ FastAPI: ${e.localizedMessage ?: "Connection failed"}"
            )
        }
    }

    suspend fun fetchCandles(
        baseUrl: String,
        bearerToken: String?,
        instrument: String,
        timeframe: String
    ): Result<List<Candle>> {
        if (!isValidHttpUrl(baseUrl)) return Result.failure(IllegalArgumentException("Invalid base URL"))
        return try {
            val authHeader = bearerToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
            val response = buildApi(baseUrl).getCandles(authHeader, instrument, timeframe)
            if (response.isSuccessful && response.body() != null) {
                val candles = response.body()!!.map {
                    Candle(it.timestamp, it.open, it.high, it.low, it.close, it.volume)
                }
                Result.success(candles)
            } else {
                Result.failure(IllegalStateException("HTTP ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitOrder(
        baseUrl: String,
        bearerToken: String?,
        request: OrderSubmitRequestDto
    ): Result<OrderSubmitResponseDto> {
        if (!isValidHttpUrl(baseUrl)) return Result.failure(IllegalArgumentException("Invalid base URL"))
        return try {
            val authHeader = bearerToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
            val response = buildApi(baseUrl).submitOrder(authHeader, request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(IllegalStateException("Order rejected HTTP ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun connectTickStream(
        wsUrl: String,
        bearerToken: String?,
        onTickJson: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        disconnectTickStream()
        if (!isValidWsUrl(wsUrl)) return
        val reqBuilder = Request.Builder().url(wsUrl.trim())
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${bearerToken.trim()}")
        }
        activeWebSocket = httpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onTickJson(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                onFailure(t.localizedMessage ?: "WebSocket connection lost")
            }
        })
    }

    fun disconnectTickStream() {
        activeWebSocket?.close(1000, "Client closing")
        activeWebSocket = null
    }
}
