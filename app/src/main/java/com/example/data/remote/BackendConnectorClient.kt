package com.example.data.remote

import com.example.model.BackendHealthState
import com.example.model.BackendOrderSummary
import com.example.model.Candle
import com.example.model.ExecutionMode
import com.example.model.MlModelSummary
import com.example.model.OrderLifecycleEvent
import com.example.model.ReconcileSummary
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.StrategySelectionReport
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
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

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

    @GET("api/v1/candles/history")
    suspend fun getCandleHistory(
        @Header("Authorization") authHeader: String?,
        @Query("instrument") instrument: String,
        @Query("timeframe") timeframe: String,
        @Query("limit") limit: Int = 120
    ): Response<List<CandleDto>>

    @GET("api/v1/market/intelligence")
    suspend fun getMarketIntelligence(
        @Header("Authorization") authHeader: String?,
        @Query("instrument") instrument: String
    ): Response<MarketIntelligenceResponseDto>

    @POST("api/v1/replay/start")
    suspend fun startReplay(
        @Header("Authorization") authHeader: String?,
        @Body request: ReplayStartRequestDto
    ): Response<ReplayEvaluationResponseDto>

    @POST("api/v1/replay/step")
    suspend fun stepReplay(
        @Header("Authorization") authHeader: String?,
        @Body request: ReplayStepRequestDto
    ): Response<ReplayEvaluationResponseDto>

    @POST("api/v1/replay/reset")
    suspend fun resetReplay(
        @Header("Authorization") authHeader: String?
    ): Response<ReplayEvaluationResponseDto>

    @GET("api/v1/state")
    suspend fun getTradingState(
        @Header("Authorization") authHeader: String?
    ): Response<TradingStateDto>

    @PUT("api/v1/strategy")
    suspend fun updateStrategy(
        @Header("Authorization") authHeader: String?,
        @Body params: StrategyParametersDto
    ): Response<StrategyParametersDto>

    @PUT("api/v1/risk/policy")
    suspend fun updateRiskPolicy(
        @Header("Authorization") authHeader: String?,
        @Body policy: RiskPolicyDto
    ): Response<RiskPolicyDto>

    @POST("api/v1/risk/emergency-stop")
    suspend fun triggerEmergencyStop(
        @Header("Authorization") authHeader: String?
    ): Response<RiskPolicyDto>

    @POST("api/v1/risk/reset-emergency-stop")
    suspend fun resetEmergencyStop(
        @Header("Authorization") authHeader: String?
    ): Response<RiskPolicyDto>

    @POST("api/v1/mode")
    suspend fun switchMode(
        @Header("Authorization") authHeader: String?,
        @Body payload: ModeSwitchRequestDto
    ): Response<HealthResponseDto>

    @POST("api/v1/orders")
    suspend fun submitOrder(
        @Header("Authorization") authHeader: String?,
        @Body request: OrderSubmitRequestDto
    ): Response<OrderSubmitResponseDto>

    @GET("api/v1/orders")
    suspend fun listOrders(
        @Header("Authorization") authHeader: String?,
        @Query("limit") limit: Int = 50
    ): Response<List<OrderSubmitResponseDto>>

    @GET("api/v1/orders/{orderId}/events")
    suspend fun getOrderLifecycleEvents(
        @Header("Authorization") authHeader: String?,
        @Path("orderId") orderId: String
    ): Response<List<OrderLifecycleEventDto>>

    @POST("api/v1/trading/reconcile")
    suspend fun reconcileAllOrders(
        @Header("Authorization") authHeader: String?
    ): Response<ReconcileSummaryDto>

    @POST("api/v1/orders/{orderId}/reconcile")
    suspend fun reconcileSingleOrder(
        @Header("Authorization") authHeader: String?,
        @Path("orderId") orderId: String
    ): Response<ReconcileSummaryDto>

    @GET("api/v1/ml/models")
    suspend fun listMlModels(
        @Header("Authorization") authHeader: String?,
        @Query("instrument") instrument: String? = null,
        @Query("limit") limit: Int = 25
    ): Response<List<ModelRegistryItemDto>>

    @GET("api/v1/ml/active")
    suspend fun getActiveMlModel(
        @Header("Authorization") authHeader: String?,
        @Query("instrument") instrument: String,
        @Query("timeframe") timeframe: String = "1m"
    ): Response<ModelRegistryItemDto?>

    @POST("api/v1/ml/train")
    suspend fun trainMlModel(
        @Header("Authorization") authHeader: String?,
        @Body payload: TrainModelRequestDto
    ): Response<ModelRegistryItemDto>

    @POST("api/v1/ml/models/{modelId}/promote")
    suspend fun promoteMlModel(
        @Header("Authorization") authHeader: String?,
        @Path("modelId") modelId: String,
        @Body payload: PromoteModelRequestDto
    ): Response<ModelRegistryItemDto>

    @GET("api/v1/strategies")
    suspend fun listStrategies(
        @Header("Authorization") authHeader: String?,
        @Query("instrument") instrument: String,
        @Query("timeframe") timeframe: String
    ): Response<StrategySelectionReportDto>

    @POST("api/v1/strategies/select")
    suspend fun selectStrategy(
        @Header("Authorization") authHeader: String?,
        @Body payload: SelectStrategyRequestDto
    ): Response<StrategySelectionReportDto>
}

class BackendConnectorClient {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val tickAdapter = moshi.adapter(WebSocketTickDto::class.java)
    private val signalWsAdapter = moshi.adapter(WebSocketSignalDto::class.java)
    private val systemWsAdapter = moshi.adapter(WebSocketSystemDto::class.java)
    private val strategyReportAdapter = moshi.adapter(StrategySelectionReportDto::class.java)
    private val intelligenceAdapter = moshi.adapter(MarketIntelligenceResponseDto::class.java)
    private val modelAdapter = moshi.adapter(ModelRegistryItemDto::class.java)
    private val orderResponseAdapter = moshi.adapter(OrderSubmitResponseDto::class.java)
    private val reconcileAdapter = moshi.adapter(ReconcileSummaryDto::class.java)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var signalWebSocket: WebSocket? = null
    private var systemWebSocket: WebSocket? = null

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

    fun resolveWebSocketUrl(
        baseUrl: String,
        explicitWsUrl: String,
        instrument: String,
        timeframe: String
    ): String? {
        val encodedTf = URLEncoder.encode(timeframe, StandardCharsets.UTF_8.name())
        val rawWs = explicitWsUrl.trim()
        val baseHost = when {
            isValidWsUrl(rawWs) -> rawWs.trimEnd('/')
            isValidHttpUrl(baseUrl) -> {
                val trimmed = baseUrl.trim().trimEnd('/')
                if (trimmed.startsWith("https://", ignoreCase = true)) {
                    "wss://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
                } else {
                    "ws://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
                }
            }
            else -> return null
        }

        return if (baseHost.contains("/ws/market")) {
            val noQuery = baseHost.substringBefore("?")
            "$noQuery?timeframe=$encodedTf"
        } else {
            "$baseHost/ws/market/$instrument?timeframe=$encodedTf"
        }
    }

    fun parseWebSocketTick(rawJson: String): WebSocketTickDto? {
        return try {
            tickAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseMarketIntelligenceJson(rawJson: String): MarketIntelligenceResponseDto? {
        return try {
            intelligenceAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseModelRegistryItemJson(rawJson: String): ModelRegistryItemDto? {
        return try {
            modelAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseOrderSubmitResponseJson(rawJson: String): OrderSubmitResponseDto? {
        return try {
            orderResponseAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseReconcileSummaryJson(rawJson: String): ReconcileSummaryDto? {
        return try {
            reconcileAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseStrategySelectionReportJson(rawJson: String): StrategySelectionReportDto? {
        return try {
            strategyReportAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseWebSocketSignalJson(rawJson: String): WebSocketSignalDto? {
        return try {
            signalWsAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun parseWebSocketSystemJson(rawJson: String): WebSocketSystemDto? {
        return try {
            systemWsAdapter.fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    fun resolveChannelWsUrl(
        baseUrl: String,
        explicitWsUrl: String,
        channelPath: String
    ): String? {
        val rawWs = explicitWsUrl.trim()
        val hostBase = when {
            isValidWsUrl(rawWs) -> rawWs.substringBefore("/ws/").trimEnd('/')
            isValidHttpUrl(baseUrl) -> {
                val trimmed = baseUrl.trim().trimEnd('/')
                if (trimmed.startsWith("https://", ignoreCase = true)) {
                    "wss://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
                } else {
                    "ws://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
                }
            }
            else -> return null
        }
        val normalizedPath = if (channelPath.startsWith("/")) channelPath else "/$channelPath"
        return "$hostBase$normalizedPath"
    }

    fun computeWsReconnectDelayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 5)
        return minOf(30_000L, 1_000L * (1L shl shift))
    }

    private fun buildAuthHeader(bearerToken: String?): String? =
        bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

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
            val response = buildApi(baseUrl).getHealth(buildAuthHeader(bearerToken))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                BackendHealthState(
                    connected = true,
                    connectorName = body.connector,
                    supportedCapabilities = body.capabilities.ifEmpty { listOf("MARKET_DATA", "SIGNALS") },
                    liveAuthorized = body.live_authorized,
                    statusMessage = "เชื่อมต่อ FastAPI สำเร็จ (${body.execution_mode})"
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
        timeframe: String,
        limit: Int = 80
    ): Result<List<Candle>> = safeCall {
        val response = buildApi(baseUrl).getCandles(buildAuthHeader(bearerToken), instrument, timeframe, limit)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.map { it.toDomain() }
        } else {
            throw IllegalStateException("HTTP ${response.code()}")
        }
    }

    suspend fun fetchCandleHistory(
        baseUrl: String,
        bearerToken: String?,
        instrument: String,
        timeframe: String,
        limit: Int = 120
    ): Result<List<Candle>> = safeCall {
        val response = buildApi(baseUrl).getCandleHistory(buildAuthHeader(bearerToken), instrument, timeframe, limit)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.map { it.toDomain() }
        } else {
            throw IllegalStateException("HTTP ${response.code()}")
        }
    }

    suspend fun fetchMarketIntelligence(
        baseUrl: String,
        bearerToken: String?,
        instrument: String
    ): Result<MarketIntelligenceResponseDto> = safeCall {
        val response = buildApi(baseUrl).getMarketIntelligence(buildAuthHeader(bearerToken), instrument)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Intelligence HTTP ${response.code()}")
        }
    }

    suspend fun startReplay(
        baseUrl: String,
        bearerToken: String?,
        instrument: String,
        totalBars: Int = 240,
        initialWindow: Int = 180
    ): Result<ReplayEvaluationResponseDto> = safeCall {
        val response = buildApi(baseUrl).startReplay(
            buildAuthHeader(bearerToken),
            ReplayStartRequestDto(instrument = instrument, total_bars = totalBars, initial_window = initialWindow)
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Replay start HTTP ${response.code()}")
        }
    }

    suspend fun stepReplay(
        baseUrl: String,
        bearerToken: String?,
        steps: Int = 1
    ): Result<ReplayEvaluationResponseDto> = safeCall {
        val response = buildApi(baseUrl).stepReplay(
            buildAuthHeader(bearerToken),
            ReplayStepRequestDto(steps = steps)
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Replay step HTTP ${response.code()}")
        }
    }

    suspend fun resetReplay(
        baseUrl: String,
        bearerToken: String?
    ): Result<ReplayEvaluationResponseDto> = safeCall {
        val response = buildApi(baseUrl).resetReplay(buildAuthHeader(bearerToken))
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Replay reset HTTP ${response.code()}")
        }
    }

    suspend fun fetchTradingState(
        baseUrl: String,
        bearerToken: String?
    ): Result<TradingStateDto> = safeCall {
        val response = buildApi(baseUrl).getTradingState(buildAuthHeader(bearerToken))
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("State HTTP ${response.code()}")
        }
    }

    suspend fun syncStrategy(
        baseUrl: String,
        bearerToken: String?,
        strategy: StrategyConfig
    ): Result<StrategyParametersDto> = safeCall {
        val response = buildApi(baseUrl).updateStrategy(
            buildAuthHeader(bearerToken),
            StrategyParametersDto.fromDomain(strategy)
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Strategy sync HTTP ${response.code()}")
        }
    }

    suspend fun syncRiskPolicy(
        baseUrl: String,
        bearerToken: String?,
        policy: RiskPolicy
    ): Result<RiskPolicyDto> = safeCall {
        val response = buildApi(baseUrl).updateRiskPolicy(
            buildAuthHeader(bearerToken),
            RiskPolicyDto.fromDomain(policy)
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Risk policy sync HTTP ${response.code()}")
        }
    }

    suspend fun syncEmergencyStop(
        baseUrl: String,
        bearerToken: String?,
        stopped: Boolean
    ): Result<RiskPolicyDto> = safeCall {
        val api = buildApi(baseUrl)
        val auth = buildAuthHeader(bearerToken)
        val response = if (stopped) api.triggerEmergencyStop(auth) else api.resetEmergencyStop(auth)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Emergency stop sync HTTP ${response.code()}")
        }
    }

    suspend fun switchMode(
        baseUrl: String,
        bearerToken: String?,
        mode: ExecutionMode,
        explicitLiveConfirmation: Boolean = false
    ): Result<HealthResponseDto> = safeCall {
        val response = buildApi(baseUrl).switchMode(
            buildAuthHeader(bearerToken),
            ModeSwitchRequestDto(
                mode = mode.name,
                explicit_live_confirmation = explicitLiveConfirmation
            )
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            throw IllegalStateException("Mode switch rejected HTTP ${response.code()}")
        }
    }

    suspend fun submitOrder(
        baseUrl: String,
        bearerToken: String?,
        request: OrderSubmitRequestDto
    ): Result<OrderSubmitResponseDto> = safeCall {
        val response = buildApi(baseUrl).submitOrder(buildAuthHeader(bearerToken), request)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!
        } else {
            val detail = response.errorBody()?.string()?.take(240)
            throw IllegalStateException(
                if (!detail.isNullOrBlank()) "Order rejected HTTP ${response.code()}: $detail"
                else "Order rejected HTTP ${response.code()}"
            )
        }
    }

    suspend fun fetchBackendOrders(
        baseUrl: String,
        bearerToken: String?,
        limit: Int = 50
    ): Result<List<BackendOrderSummary>> = safeCall {
        val response = buildApi(baseUrl).listOrders(buildAuthHeader(bearerToken), limit)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.map { it.toDomain() }
        } else {
            throw IllegalStateException("Orders HTTP ${response.code()}")
        }
    }

    suspend fun fetchOrderLifecycleEvents(
        baseUrl: String,
        bearerToken: String?,
        orderId: String
    ): Result<List<OrderLifecycleEvent>> = safeCall {
        val response = buildApi(baseUrl).getOrderLifecycleEvents(buildAuthHeader(bearerToken), orderId)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.map { it.toDomain() }
        } else {
            throw IllegalStateException("Order events HTTP ${response.code()}")
        }
    }

    suspend fun reconcileOrders(
        baseUrl: String,
        bearerToken: String?,
        orderId: String? = null
    ): Result<ReconcileSummary> = safeCall {
        val api = buildApi(baseUrl)
        val auth = buildAuthHeader(bearerToken)
        val response = if (orderId.isNullOrBlank()) {
            api.reconcileAllOrders(auth)
        } else {
            api.reconcileSingleOrder(auth, orderId)
        }
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.toDomain()
        } else {
            throw IllegalStateException("Reconcile HTTP ${response.code()}")
        }
    }

    suspend fun fetchMlModels(
        baseUrl: String,
        bearerToken: String?,
        instrument: String? = null
    ): Result<List<MlModelSummary>> = safeCall {
        val auth = buildAuthHeader(bearerToken)
        val api = buildApi(baseUrl)
        val activeId = if (!instrument.isNullOrBlank()) {
            val activeRes = api.getActiveMlModel(auth, instrument, "1m")
            if (activeRes.isSuccessful) activeRes.body()?.model_id else null
        } else null
        val listRes = api.listMlModels(auth, instrument = instrument, limit = 25)
        if (listRes.isSuccessful && listRes.body() != null) {
            listRes.body()!!.map { it.toDomain(activeId) }
        } else {
            throw IllegalStateException("ML models HTTP ${listRes.code()}")
        }
    }

    suspend fun trainMlModel(
        baseUrl: String,
        bearerToken: String?,
        instrument: String,
        algorithm: String = "logistic_regression"
    ): Result<MlModelSummary> = safeCall {
        val response = buildApi(baseUrl).trainMlModel(
            buildAuthHeader(bearerToken),
            TrainModelRequestDto(
                instrument = instrument,
                timeframe = "1m",
                algorithm = algorithm,
                total_bars = 360
            )
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.toDomain()
        } else {
            throw IllegalStateException("ML train HTTP ${response.code()}")
        }
    }

    suspend fun promoteMlModel(
        baseUrl: String,
        bearerToken: String?,
        modelId: String
    ): Result<MlModelSummary> = safeCall {
        val response = buildApi(baseUrl).promoteMlModel(
            buildAuthHeader(bearerToken),
            modelId,
            PromoteModelRequestDto(owner_confirmed = true)
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.toDomain(activeModelId = modelId)
        } else {
            throw IllegalStateException("ML promote HTTP ${response.code()}")
        }
    }

    suspend fun fetchStrategyModules(
        baseUrl: String,
        bearerToken: String?,
        instrument: String,
        timeframe: String,
        userId: String = ""
    ): Result<StrategySelectionReport> = safeCall {
        val response = buildApi(baseUrl).listStrategies(buildAuthHeader(bearerToken), instrument, timeframe)
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.toDomain(userId)
        } else {
            throw IllegalStateException("Strategies HTTP ${response.code()}")
        }
    }

    suspend fun selectStrategyModule(
        baseUrl: String,
        bearerToken: String?,
        strategyId: String?,
        autoSelect: Boolean,
        instrument: String,
        timeframe: String,
        userId: String = ""
    ): Result<StrategySelectionReport> = safeCall {
        val response = buildApi(baseUrl).selectStrategy(
            buildAuthHeader(bearerToken),
            SelectStrategyRequestDto(
                strategy_id = strategyId,
                auto_select = autoSelect,
                instrument = instrument,
                timeframe = timeframe
            )
        )
        if (response.isSuccessful && response.body() != null) {
            response.body()!!.toDomain(userId)
        } else {
            throw IllegalStateException("Strategy select HTTP ${response.code()}")
        }
    }

    private suspend inline fun <T> safeCall(crossinline block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun connectTickStream(
        wsUrl: String,
        bearerToken: String?,
        onOpen: () -> Unit = {},
        onTick: (WebSocketTickDto) -> Unit,
        onFailure: (String) -> Unit
    ) {
        disconnectTickStream()
        if (!isValidWsUrl(wsUrl)) return
        val reqBuilder = Request.Builder().url(wsUrl.trim())
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${bearerToken.trim()}")
        }
        activeWebSocket = httpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseWebSocketTick(text)?.let(onTick)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                onFailure(t.localizedMessage ?: "WebSocket connection lost")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) {
                    onFailure(reason.ifBlank { "WebSocket closed ($code)" })
                }
            }
        })
    }

    fun connectSignalStream(
        wsUrl: String,
        bearerToken: String?,
        onOpen: () -> Unit = {},
        onSignal: (WebSocketSignalDto) -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        signalWebSocket?.close(1000, "Switching stream")
        signalWebSocket = null
        if (!isValidWsUrl(wsUrl)) return
        val reqBuilder = Request.Builder().url(wsUrl.trim())
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${bearerToken.trim()}")
        }
        signalWebSocket = httpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseWebSocketSignalJson(text)?.let(onSignal)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                onFailure(t.localizedMessage ?: "Signal WebSocket lost")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) {
                    onFailure(reason.ifBlank { "Signal WS closed ($code)" })
                }
            }
        })
    }

    fun connectSystemStream(
        wsUrl: String,
        bearerToken: String?,
        onOpen: () -> Unit = {},
        onSystem: (WebSocketSystemDto) -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        systemWebSocket?.close(1000, "Switching stream")
        systemWebSocket = null
        if (!isValidWsUrl(wsUrl)) return
        val reqBuilder = Request.Builder().url(wsUrl.trim())
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${bearerToken.trim()}")
        }
        systemWebSocket = httpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkResponse) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseWebSocketSystemJson(text)?.let(onSystem)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkResponse?) {
                onFailure(t.localizedMessage ?: "System WebSocket lost")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) {
                    onFailure(reason.ifBlank { "System WS closed ($code)" })
                }
            }
        })
    }

    fun disconnectTickStream() {
        activeWebSocket?.close(1000, "Client closing")
        activeWebSocket = null
        signalWebSocket?.close(1000, "Client closing")
        signalWebSocket = null
        systemWebSocket?.close(1000, "Client closing")
        systemWebSocket = null
    }
}
