package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CachedCandleEntity
import com.example.data.local.LocalTradeCacheEntity
import com.example.data.local.TradingDao
import com.example.data.remote.BackendConnectorClient
import com.example.data.remote.MarketIntelligenceResponseDto
import com.example.data.remote.OrderSubmitRequestDto
import com.example.data.remote.ReplayEvaluationResponseDto
import com.example.data.remote.TelegramNotifier
import com.example.data.repository.TradingCloudRepository
import com.example.engine.MarketSimulationEngine
import com.example.engine.MultiTimeframeLocalEngine
import com.example.engine.RiskManagementEngine
import com.example.engine.SignalEngine
import com.example.engine.TechnicalAnalysisEngine
import com.example.model.BackendHealthState
import com.example.model.Candle
import com.example.model.DailyPnLSummary
import com.example.model.DataQualitySummary
import com.example.model.ExecutionMode
import com.example.model.ExecutionStage
import com.example.model.IntegrationSettings
import com.example.model.MlModelSummary
import com.example.model.MultiTimeframeReport
import com.example.model.ReplaySessionState
import com.example.model.RiskPolicy
import com.example.model.RiskValidationResult
import com.example.model.SUPPORTED_INSTRUMENTS
import com.example.model.SignalAnalysis
import com.example.model.StrategyConfig
import com.example.model.StreamTransportMode
import com.example.model.TechnicalSnapshot
import com.example.model.Timeframe
import com.example.model.TradeDirection
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.model.UserProfile
import com.example.security.SecureCredentialVault
import com.example.ui.notifications.TradingNotificationHelper
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface CloudUiState<out T> {
    data object Loading : CloudUiState<Nothing>
    data class Success<T>(val data: T) : CloudUiState<T>
    data class Error(val message: String) : CloudUiState<Nothing>
}

data class TerminalUiState(
    val selectedInstrument: String = "EUR/USD",
    val selectedTimeframe: Timeframe = Timeframe.M1,
    val candles: List<Candle> = emptyList(),
    val technicalSnapshot: TechnicalSnapshot? = null,
    val signalAnalysis: SignalAnalysis? = null,
    val mtfReport: MultiTimeframeReport? = null,
    val dataQuality: DataQualitySummary? = null,
    val streamTransport: StreamTransportMode = StreamTransportMode.LOCAL_SIMULATION,
    val replayState: ReplaySessionState? = null,
    val isReplayBusy: Boolean = false,
    val riskValidation: RiskValidationResult = RiskValidationResult(true, "พร้อมตรวจสอบความเสี่ยง", 30, 250.0),
    val executionStage: ExecutionStage = ExecutionStage.IDLE,
    val lastOrderFeedback: String? = null,
    val isOrderInFlight: Boolean = false,
    val lastLossTimestampMillis: Long? = null
)

class TradingViewModel(
    private val cloudRepository: TradingCloudRepository,
    private val tradingDao: TradingDao,
    private val credentialVault: SecureCredentialVault,
    private val telegramNotifier: TelegramNotifier,
    private val backendClient: BackendConnectorClient,
    private val notificationHelper: TradingNotificationHelper,
    private val currentUserId: String,
    private val userDisplayName: String,
    private val userEmail: String?
) : ViewModel() {

    private val simEngine = MarketSimulationEngine()
    private val _terminalState = MutableStateFlow(TerminalUiState())
    val terminalState: StateFlow<TerminalUiState> = _terminalState.asStateFlow()

    private val _backendHealth = MutableStateFlow(BackendHealthState())
    val backendHealth: StateFlow<BackendHealthState> = _backendHealth.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    private val _mlModels = MutableStateFlow<List<MlModelSummary>>(emptyList())
    val mlModels: StateFlow<List<MlModelSummary>> = _mlModels.asStateFlow()

    private val _activeMlModel = MutableStateFlow<MlModelSummary?>(null)
    val activeMlModel: StateFlow<MlModelSummary?> = _activeMlModel.asStateFlow()

    private val _isMlBusy = MutableStateFlow(false)
    val isMlBusy: StateFlow<Boolean> = _isMlBusy.asStateFlow()

    val integrationSettings: StateFlow<IntegrationSettings> = credentialVault.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), IntegrationSettings())

    val profileState: StateFlow<CloudUiState<UserProfile?>> =
        cloudRepository.observeUserProfile(currentUserId)
            .map<UserProfile?, CloudUiState<UserProfile?>> { CloudUiState.Success(it) }
            .catch { emit(CloudUiState.Error(unwrapFirestoreError(it, "โหลดข้อมูลโปรไฟล์ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val strategiesState: StateFlow<CloudUiState<List<StrategyConfig>>> =
        cloudRepository.observeStrategies(currentUserId)
            .map<List<StrategyConfig>, CloudUiState<List<StrategyConfig>>> { CloudUiState.Success(it) }
            .catch { emit(CloudUiState.Error(unwrapFirestoreError(it, "โหลดกลยุทธ์จากคลาวด์ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val riskPolicyState: StateFlow<CloudUiState<RiskPolicy?>> =
        cloudRepository.observeRiskPolicy(currentUserId)
            .map<RiskPolicy?, CloudUiState<RiskPolicy?>> { CloudUiState.Success(it) }
            .catch { emit(CloudUiState.Error(unwrapFirestoreError(it, "โหลดนโยบายความเสี่ยงไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val tradesState: StateFlow<CloudUiState<List<TradeRecord>>> =
        cloudRepository.observeTrades(currentUserId)
            .map<List<TradeRecord>, CloudUiState<List<TradeRecord>>> { CloudUiState.Success(it) }
            .catch { emit(CloudUiState.Error(unwrapFirestoreError(it, "โหลดประวัติการเทรดไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val dailySummariesState: StateFlow<CloudUiState<List<DailyPnLSummary>>> =
        cloudRepository.observeDailySummaries(currentUserId)
            .map<List<DailyPnLSummary>, CloudUiState<List<DailyPnLSummary>>> { CloudUiState.Success(it) }
            .catch { emit(CloudUiState.Error(unwrapFirestoreError(it, "โหลดสรุปผลรายวันไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    private var marketFeedJob: Job? = null
    private var localReplayBuffer: List<Candle> = emptyList()
    private var wsTickCounter = 0
    private var autoCycleCounter = 0

    init {
        viewModelScope.launch {
            cloudRepository.ensureUserBootstrap(currentUserId, userDisplayName, userEmail)
                .onFailure { _snackbarMessage.value = "ซิงค์เริ่มต้นไม่สำเร็จ: ${it.localizedMessage}" }
        }
        startMarketStream()
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun selectInstrument(symbol: String) {
        _terminalState.update {
            it.copy(
                selectedInstrument = symbol,
                candles = emptyList(),
                replayState = null,
                executionStage = ExecutionStage.IDLE
            )
        }
        startMarketStream()
    }

    fun selectTimeframe(timeframe: Timeframe) {
        _terminalState.update {
            it.copy(
                selectedTimeframe = timeframe,
                candles = emptyList(),
                replayState = null,
                executionStage = ExecutionStage.IDLE
            )
        }
        startMarketStream()
    }

    private fun startMarketStream() {
        marketFeedJob?.cancel()
        backendClient.disconnectTickStream()

        marketFeedJob = viewModelScope.launch {
            val instrument = _terminalState.value.selectedInstrument
            val tf = _terminalState.value.selectedTimeframe
            val settings = integrationSettings.value
            val token = credentialVault.getBackendToken()

            var candles = simEngine.generateInitialCandles(instrument, tf.defaultExpirySec, 120)
            var transport = StreamTransportMode.LOCAL_SIMULATION
            var remoteIntel: MarketIntelligenceResponseDto? = null

            if (backendClient.isValidHttpUrl(settings.backendBaseUrl)) {
                val health = backendClient.checkHealth(settings.backendBaseUrl, token)
                _backendHealth.value = health
                if (health.connected) {
                    transport = StreamTransportMode.FASTAPI_REST
                    backendClient.fetchCandleHistory(settings.backendBaseUrl, token, instrument, tf.code, 120)
                        .getOrNull()?.takeIf { it.size >= 25 }?.let { candles = it }
                    remoteIntel = backendClient.fetchMarketIntelligence(settings.backendBaseUrl, token, instrument).getOrNull()
                    refreshMlModelsInternal(settings.backendBaseUrl, token, instrument)
                }
            }

            cacheCandlesToRoom(instrument, tf.code, candles)
            applyAnalysis(candles, transport, remoteIntel)

            val wsUrl = backendClient.resolveWebSocketUrl(
                baseUrl = settings.backendBaseUrl,
                explicitWsUrl = settings.backendWsUrl,
                instrument = instrument,
                timeframe = tf.code
            )

            if (transport != StreamTransportMode.LOCAL_SIMULATION && wsUrl != null) {
                connectWebSocketWithFallback(wsUrl, settings.backendBaseUrl, token, instrument, tf)
            } else {
                runLocalSimulationLoop()
            }
        }
    }

    private suspend fun connectWebSocketWithFallback(
        wsUrl: String,
        baseUrl: String,
        token: String?,
        instrument: String,
        tf: Timeframe
    ) {
        var wsFailed = false
        var reconnectAttempt = 0

        while (viewModelScope.isActive && reconnectAttempt <= 2) {
            wsFailed = false
            backendClient.connectTickStream(
                wsUrl = wsUrl,
                bearerToken = token,
                onOpen = {
                    reconnectAttempt = 0
                    _terminalState.update { it.copy(streamTransport = StreamTransportMode.FASTAPI_WEBSOCKET) }
                },
                onTick = { tick ->
                    if (_terminalState.value.replayState?.active == true) return@connectTickStream
                    val incoming = tick.candle?.toDomain() ?: return@connectTickStream
                    val current = _terminalState.value.candles
                    val updated = mergeCandleTick(current, incoming)
                    wsTickCounter++
                    viewModelScope.launch {
                        val intel = if (wsTickCounter % 4 == 0 && backendClient.isValidHttpUrl(baseUrl)) {
                            backendClient.fetchMarketIntelligence(baseUrl, token, instrument).getOrNull()
                        } else null
                        applyAnalysis(updated, StreamTransportMode.FASTAPI_WEBSOCKET, intel)
                        maybeRunAutoTradeCycle()
                    }
                },
                onFailure = {
                    wsFailed = true
                }
            )

            while (viewModelScope.isActive && !wsFailed) {
                delay(1500L)
            }
            if (!viewModelScope.isActive) return
            reconnectAttempt++
            if (reconnectAttempt <= 2) {
                delay(1500L * reconnectAttempt)
            }
        }

        // Graceful fallback to local simulation if WebSocket stays down
        backendClient.disconnectTickStream()
        _terminalState.update { it.copy(streamTransport = StreamTransportMode.LOCAL_SIMULATION) }
        runLocalSimulationLoop()
    }

    private suspend fun runLocalSimulationLoop() {
        while (viewModelScope.isActive) {
            delay(1200L)
            val state = _terminalState.value
            if (state.replayState?.active == true) continue
            val nextCandles = simEngine.nextTick(
                currentCandles = state.candles,
                instrumentSymbol = state.selectedInstrument,
                timeframeSeconds = state.selectedTimeframe.defaultExpirySec
            )
            applyAnalysis(nextCandles, StreamTransportMode.LOCAL_SIMULATION, null)
            maybeRunAutoTradeCycle()
        }
    }

    private suspend fun maybeRunAutoTradeCycle() {
        autoCycleCounter++
        val activePolicy = currentRiskPolicy()
        if (activePolicy.autoTradeEnabled &&
            !activePolicy.emergencyStopped &&
            _terminalState.value.replayState?.active != true &&
            !_terminalState.value.isOrderInFlight &&
            autoCycleCounter % 5 == 0
        ) {
            runTradePipeline(forcedDirection = null, isAuto = true)
        }
    }

    private fun mergeCandleTick(existing: List<Candle>, incoming: Candle): List<Candle> {
        if (existing.isEmpty()) return listOf(incoming)
        val last = existing.last()
        return when {
            incoming.timestampMillis == last.timestampMillis -> existing.dropLast(1) + incoming
            incoming.timestampMillis > last.timestampMillis -> (existing + incoming).takeLast(120)
            else -> existing
        }
    }

    private fun applyAnalysis(
        candles: List<Candle>,
        transport: StreamTransportMode,
        remoteIntel: MarketIntelligenceResponseDto?
    ) {
        val strategy = activeStrategy()
        val snapshot = TechnicalAnalysisEngine.analyze(candles, strategy)
        val localSignal = SignalEngine.evaluate(candles, snapshot, strategy)
        val signal = remoteIntel?.signal?.toDomain() ?: localSignal
        val mtfReport = remoteIntel?.mtf_report?.toDomain()
            ?: MultiTimeframeLocalEngine.evaluateLocalMtf(_terminalState.value.selectedInstrument, candles, strategy)
        val dataQuality = remoteIntel?.data_quality?.toDomain()
            ?: MultiTimeframeLocalEngine.inspectDataQuality(candles)

        val policy = currentRiskPolicy()
        val todaySummary = todaySummary()
        val openExposure = currentTrades().filter { it.status == TradeStatus.OPEN.name }.sumOf { it.amount }
        val mode = currentExecutionMode()

        val riskCheck = RiskManagementEngine.validateOrder(
            policy = policy,
            todaySummary = todaySummary,
            openExposure = openExposure,
            requestedAmount = strategy.tradeAmount,
            direction = signal.direction,
            mode = mode,
            backendReadyForLive = _backendHealth.value.liveAuthorized,
            lastLossTimestampMillis = _terminalState.value.lastLossTimestampMillis
        )

        _terminalState.update {
            it.copy(
                candles = candles,
                technicalSnapshot = snapshot,
                signalAnalysis = signal,
                mtfReport = mtfReport,
                dataQuality = dataQuality,
                streamTransport = transport,
                riskValidation = riskCheck
            )
        }
    }

    // --- Simulator Replay Controls (Phase 2 Integration, Zero Look-Ahead) ---

    fun startReplayMode() {
        viewModelScope.launch {
            _terminalState.update { it.copy(isReplayBusy = true) }
            backendClient.disconnectTickStream()
            val instrument = _terminalState.value.selectedInstrument
            val settings = integrationSettings.value
            val token = credentialVault.getBackendToken()

            if (_backendHealth.value.connected && backendClient.isValidHttpUrl(settings.backendBaseUrl)) {
                val remote = backendClient.startReplay(settings.backendBaseUrl, token, instrument, 240, 180)
                if (remote.isSuccess) {
                    applyBackendReplayResponse(remote.getOrThrow(), settings.backendBaseUrl, token)
                    _terminalState.update { it.copy(isReplayBusy = false) }
                    return@launch
                }
            }

            // Local deterministic replay fallback with strict cursor slicing
            localReplayBuffer = simEngine.generateInitialCandles(instrument, 60, 180)
            val initialSlice = localReplayBuffer.take(120)
            val replayState = ReplaySessionState(
                active = true,
                sessionId = "local-${System.currentTimeMillis()}",
                instrument = instrument,
                timeframe = "1m",
                cursorIndex = initialSlice.size,
                totalBars = localReplayBuffer.size,
                cursorTimestamp = initialSlice.last().timestampMillis,
                isBackendBacked = false
            )
            _terminalState.update { it.copy(replayState = replayState, isReplayBusy = false) }
            applyAnalysis(initialSlice, StreamTransportMode.LOCAL_SIMULATION, null)
            _snackbarMessage.value = "เริ่มโหมด Simulator Replay (Zero Look-Ahead)"
        }
    }

    fun stepReplay(steps: Int = 1) {
        viewModelScope.launch {
            val replay = _terminalState.value.replayState ?: return@launch
            _terminalState.update { it.copy(isReplayBusy = true) }
            val settings = integrationSettings.value
            val token = credentialVault.getBackendToken()

            if (replay.isBackendBacked && backendClient.isValidHttpUrl(settings.backendBaseUrl)) {
                val remote = backendClient.stepReplay(settings.backendBaseUrl, token, steps)
                if (remote.isSuccess) {
                    applyBackendReplayResponse(remote.getOrThrow(), settings.backendBaseUrl, token)
                    _terminalState.update { it.copy(isReplayBusy = false) }
                    return@launch
                }
            }

            val nextCursor = (replay.cursorIndex + steps).coerceAtMost(localReplayBuffer.size)
            val visible = localReplayBuffer.take(nextCursor)
            val updatedReplay = replay.copy(
                cursorIndex = nextCursor,
                cursorTimestamp = visible.lastOrNull()?.timestampMillis ?: replay.cursorTimestamp
            )
            _terminalState.update { it.copy(replayState = updatedReplay, isReplayBusy = false) }
            applyAnalysis(visible, StreamTransportMode.LOCAL_SIMULATION, null)
        }
    }

    fun resetReplay() {
        viewModelScope.launch {
            val replay = _terminalState.value.replayState ?: return@launch
            _terminalState.update { it.copy(isReplayBusy = true) }
            val settings = integrationSettings.value
            val token = credentialVault.getBackendToken()

            if (replay.isBackendBacked && backendClient.isValidHttpUrl(settings.backendBaseUrl)) {
                val remote = backendClient.resetReplay(settings.backendBaseUrl, token)
                if (remote.isSuccess) {
                    applyBackendReplayResponse(remote.getOrThrow(), settings.backendBaseUrl, token)
                    _terminalState.update { it.copy(isReplayBusy = false) }
                    return@launch
                }
            }

            val initialSlice = localReplayBuffer.take(120)
            _terminalState.update {
                it.copy(
                    replayState = replay.copy(
                        cursorIndex = initialSlice.size,
                        cursorTimestamp = initialSlice.lastOrNull()?.timestampMillis ?: 0L
                    ),
                    isReplayBusy = false
                )
            }
            applyAnalysis(initialSlice, StreamTransportMode.LOCAL_SIMULATION, null)
        }
    }

    fun exitReplayMode() {
        _terminalState.update { it.copy(replayState = null, isReplayBusy = false) }
        startMarketStream()
    }

    private suspend fun applyBackendReplayResponse(
        res: ReplayEvaluationResponseDto,
        baseUrl: String,
        token: String?
    ) {
        val replayState = res.replay_state.toDomain()
        val history = backendClient.fetchCandleHistory(
            baseUrl = baseUrl,
            bearerToken = token,
            instrument = replayState.instrument,
            timeframe = "1m",
            limit = replayState.totalBars.coerceIn(60, 500)
        ).getOrDefault(_terminalState.value.candles)

        // Strictly prevent look-ahead bias on chart
        val visibleCandles = history.filter { it.timestampMillis <= replayState.cursorTimestamp }
            .ifEmpty { _terminalState.value.candles }

        _terminalState.update { it.copy(replayState = replayState) }
        applyAnalysis(
            candles = visibleCandles,
            transport = StreamTransportMode.FASTAPI_REST,
            remoteIntel = MarketIntelligenceResponseDto(
                instrument = replayState.instrument,
                data_quality = res.data_quality,
                mtf_report = res.mtf_report,
                signal = res.signal
            )
        )
    }

    // --- Execution Lifecycle ---

    fun executeManualOrder(requestedDirection: TradeDirection? = null) {
        viewModelScope.launch { runTradePipeline(forcedDirection = requestedDirection, isAuto = false) }
    }

    private suspend fun runTradePipeline(forcedDirection: TradeDirection?, isAuto: Boolean) {
        if (_terminalState.value.isOrderInFlight) return
        _terminalState.update { it.copy(isOrderInFlight = true, executionStage = ExecutionStage.MARKET_DATA) }

        try {
            val candles = _terminalState.value.candles
            delay(100)
            _terminalState.update { it.copy(executionStage = ExecutionStage.DATA_VALIDATION) }
            if (candles.size < 25) {
                finishPipeline(ExecutionStage.IDLE, "ข้อมูลแท่งเทียนยังไม่พร้อมสำหรับการตัดสินใจ")
                return
            }

            val strategy = activeStrategy()
            _terminalState.update { it.copy(executionStage = ExecutionStage.MARKET_ANALYSIS) }
            val snapshot = TechnicalAnalysisEngine.analyze(candles, strategy)

            _terminalState.update { it.copy(executionStage = ExecutionStage.AI_SIGNAL) }
            val currentSignal = _terminalState.value.signalAnalysis ?: SignalEngine.evaluate(candles, snapshot, strategy)
            val directionToUse = forcedDirection ?: currentSignal.direction

            _terminalState.update { it.copy(executionStage = ExecutionStage.STRATEGY_VALIDATION) }
            if (directionToUse == TradeDirection.NO_TRADE) {
                finishPipeline(ExecutionStage.IDLE, "AI แนะนำ NO TRADE — รอจังหวะที่ชัดเจนกว่านี้")
                return
            }

            _terminalState.update { it.copy(executionStage = ExecutionStage.RISK_VALIDATION) }
            val policy = currentRiskPolicy()
            val today = todaySummary()
            val openExposure = currentTrades().filter { it.status == TradeStatus.OPEN.name }.sumOf { it.amount }
            val mode = currentExecutionMode()

            val riskResult = RiskManagementEngine.validateOrder(
                policy = policy,
                todaySummary = today,
                openExposure = openExposure,
                requestedAmount = strategy.tradeAmount,
                direction = directionToUse,
                mode = mode,
                backendReadyForLive = _backendHealth.value.liveAuthorized,
                lastLossTimestampMillis = _terminalState.value.lastLossTimestampMillis
            )

            if (!riskResult.allowed) {
                if (isAuto && (policy.emergencyStopped || riskResult.remainingDailyTrades == 0 || riskResult.remainingLossBudget <= 0.0)) {
                    cloudRepository.saveRiskPolicy(policy.copy(autoTradeEnabled = false))
                    dispatchAlerts(
                        isRiskAlert = true,
                        title = "RISK GUARD LOCKOUT",
                        lines = listOf("${strategy.instrument}: ${riskResult.reason}", "ระงับ Auto-Trade อัตโนมัติแล้ว")
                    )
                }
                finishPipeline(ExecutionStage.IDLE, riskResult.reason)
                return
            }

            _terminalState.update { it.copy(executionStage = ExecutionStage.EXECUTION_AUTH) }
            val spec = SUPPORTED_INSTRUMENTS.firstOrNull { it.symbol == strategy.instrument }
                ?: SUPPORTED_INSTRUMENTS.first()
            val entryPrice = candles.last().close

            _terminalState.update { it.copy(executionStage = ExecutionStage.ORDER_SUBMISSION) }
            if (mode != ExecutionMode.SIMULATION && integrationSettings.value.backendBaseUrl.isNotBlank()) {
                val remoteSubmit = backendClient.submitOrder(
                    baseUrl = integrationSettings.value.backendBaseUrl,
                    bearerToken = credentialVault.getBackendToken(),
                    request = OrderSubmitRequestDto(
                        instrument = strategy.instrument,
                        timeframe = _terminalState.value.selectedTimeframe.code,
                        direction = directionToUse.name,
                        amount = strategy.tradeAmount,
                        expiry_seconds = strategy.expirySeconds,
                        mode = mode.name,
                        idempotency_key = UUID.randomUUID().toString()
                    )
                )
                if (remoteSubmit.isFailure) {
                    finishPipeline(
                        ExecutionStage.IDLE,
                        "ส่งคำสั่งไปยัง FastAPI ไม่สำเร็จ: ${remoteSubmit.exceptionOrNull()?.localizedMessage}"
                    )
                    return
                }
            }

            val pendingTrade = TradeRecord(
                userId = currentUserId,
                instrument = strategy.instrument,
                timeframe = _terminalState.value.selectedTimeframe.code,
                direction = directionToUse.name,
                mode = mode.name,
                amount = strategy.tradeAmount,
                payoutRate = spec.payoutRate,
                entryPrice = entryPrice,
                exitPrice = 0.0,
                status = TradeStatus.OPEN.name,
                pnl = 0.0,
                strategyName = strategy.name,
                confidenceScore = currentSignal.confidenceScore,
                calibratedProbability = currentSignal.calibratedProbability,
                explanation = currentSignal.explanation
            )
            val createdId = cloudRepository.createTrade(pendingTrade).getOrElse { "local_${System.currentTimeMillis()}" }

            dispatchAlerts(
                isSignal = true,
                title = "IQ TRADING SIGNAL • ${directionToUse.name}",
                lines = listOf(
                    "${strategy.instrument} (${_terminalState.value.selectedTimeframe.code}) • ${mode.name}",
                    "เข้า ${formatPrice(entryPrice)} | Prob ${(currentSignal.calibratedProbability * 100).toInt()}% | Conf ${currentSignal.confidenceScore}%"
                )
            )

            _terminalState.update { it.copy(executionStage = ExecutionStage.RESULT_VERIFICATION) }
            repeat(3) {
                delay(1000L)
                val updated = simEngine.nextTick(
                    _terminalState.value.candles,
                    strategy.instrument,
                    _terminalState.value.selectedTimeframe.defaultExpirySec
                )
                applyAnalysis(updated, _terminalState.value.streamTransport, null)
            }

            val exitPrice = _terminalState.value.candles.last().close
            val won = when (directionToUse) {
                TradeDirection.CALL -> exitPrice > entryPrice
                TradeDirection.PUT -> exitPrice < entryPrice
                TradeDirection.NO_TRADE -> false
            }
            val finalStatus = if (won) TradeStatus.WON else TradeStatus.LOST
            val pnl = if (won) strategy.tradeAmount * spec.payoutRate else -strategy.tradeAmount

            _terminalState.update { it.copy(executionStage = ExecutionStage.TRADE_LOGGING) }
            cloudRepository.settleTrade(createdId, exitPrice, finalStatus, pnl)
            withContext(Dispatchers.IO) {
                tradingDao.upsertTrade(
                    LocalTradeCacheEntity(
                        tradeId = createdId,
                        userId = currentUserId,
                        instrument = strategy.instrument,
                        timeframe = _terminalState.value.selectedTimeframe.code,
                        direction = directionToUse.name,
                        mode = mode.name,
                        amount = strategy.tradeAmount,
                        payoutRate = spec.payoutRate,
                        entryPrice = entryPrice,
                        exitPrice = exitPrice,
                        status = finalStatus.name,
                        pnl = pnl,
                        strategyName = strategy.name,
                        confidenceScore = currentSignal.confidenceScore,
                        calibratedProbability = currentSignal.calibratedProbability,
                        explanation = currentSignal.explanation,
                        createdAtMillis = System.currentTimeMillis(),
                        syncedToCloud = true
                    )
                )
            }

            _terminalState.update { it.copy(executionStage = ExecutionStage.PERFORMANCE_EVAL) }
            updateProfileAndDailySummary(pnl = pnl, won = won)
            if (!won) {
                _terminalState.update { it.copy(lastLossTimestampMillis = System.currentTimeMillis()) }
            }

            val resultBadge = if (won) "WON (+$${format2(pnl)})" else "LOST (-$${format2(strategy.tradeAmount)})"
            dispatchAlerts(
                isTradeResult = true,
                title = "TRADE SETTLED • $resultBadge",
                lines = listOf(
                    "${strategy.instrument} ${directionToUse.name}: ${formatPrice(entryPrice)} -> ${formatPrice(exitPrice)}",
                    "Balance: $${format2(currentBalance() + pnl)}"
                )
            )
            finishPipeline(
                ExecutionStage.IDLE,
                if (won) "ปิดออเดอร์ ${directionToUse.name} ชนะ +$${format2(pnl)}"
                else "ปิดออเดอร์ ${directionToUse.name} ขาดทุน -$${format2(strategy.tradeAmount)}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Execution pipeline error", e)
            finishPipeline(ExecutionStage.IDLE, "เกิดข้อผิดพลาดในวงจรคำสั่ง: ${e.localizedMessage}")
        }
    }

    // --- ML Governance (Phase 3 Integration) ---

    fun refreshMlModels() {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) return@launch
            refreshMlModelsInternal(baseUrl, credentialVault.getBackendToken(), _terminalState.value.selectedInstrument)
        }
    }

    private suspend fun refreshMlModelsInternal(baseUrl: String, token: String?, instrument: String) {
        backendClient.fetchMlModels(baseUrl, token, instrument).onSuccess { list ->
            _mlModels.value = list
            _activeMlModel.value = list.firstOrNull { it.isActive }
        }
    }

    fun trainMlModel(algorithm: String) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) {
                _snackbarMessage.value = "กรุณาตั้งค่า FastAPI Backend URL ก่อนเทรนโมเดล ML"
                return@launch
            }
            _isMlBusy.value = true
            val token = credentialVault.getBackendToken()
            val instrument = _terminalState.value.selectedInstrument
            backendClient.trainMlModel(baseUrl, token, instrument, algorithm)
                .onSuccess { item ->
                    refreshMlModelsInternal(baseUrl, token, instrument)
                    _snackbarMessage.value = "เทรน ${item.algorithm} เสร็จสิ้น: สถานะ ${item.status} (Acc ${(item.outOfSampleAccuracy * 100).toInt()}%)"
                }
                .onFailure {
                    _snackbarMessage.value = "เทรนโมเดล ML ไม่สำเร็จ: ${it.localizedMessage}"
                }
            _isMlBusy.value = false
        }
    }

    fun promoteMlModel(modelId: String) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) return@launch
            _isMlBusy.value = true
            val token = credentialVault.getBackendToken()
            val instrument = _terminalState.value.selectedInstrument
            backendClient.promoteMlModel(baseUrl, token, modelId)
                .onSuccess { promoted ->
                    refreshMlModelsInternal(baseUrl, token, instrument)
                    _snackbarMessage.value = "เปิดใช้งานโมเดล ${promoted.modelId.take(8)} สำหรับ Inference แล้ว"
                }
                .onFailure {
                    _snackbarMessage.value = "Promote โมเดลไม่สำเร็จ: ${it.localizedMessage}"
                }
            _isMlBusy.value = false
        }
    }

    // --- Strategy, Risk & Backend Sync ---

    fun toggleAutoTrade(enabled: Boolean) {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            if (enabled && policy.emergencyStopped) {
                _snackbarMessage.value = "กรุณาปลดล็อก Emergency Stop ก่อนเปิด Auto-Trade"
                return@launch
            }
            val updated = policy.copy(autoTradeEnabled = enabled)
            cloudRepository.saveRiskPolicy(updated)
                .onSuccess {
                    syncRiskToBackendIfConnected(updated)
                    _snackbarMessage.value = if (enabled) "เปิดระบบเทรดอัตโนมัติ (SIMULATION)" else "ปิดระบบเทรดอัตโนมัติแล้ว"
                }
                .onFailure { _snackbarMessage.value = "อัปเดตสถานะ Auto-Trade ไม่สำเร็จ" }
        }
    }

    fun triggerEmergencyStop() {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            cloudRepository.saveRiskPolicy(policy.copy(emergencyStopped = true, autoTradeEnabled = false))
                .onSuccess {
                    val baseUrl = integrationSettings.value.backendBaseUrl
                    if (backendClient.isValidHttpUrl(baseUrl)) {
                        backendClient.syncEmergencyStop(baseUrl, credentialVault.getBackendToken(), stopped = true)
                    }
                    _snackbarMessage.value = "EMERGENCY STOP ทำงานแล้ว — ระงับคำสั่งซื้อขายทั้งหมด"
                    dispatchAlerts(
                        isRiskAlert = true,
                        title = "EMERGENCY STOP ACTIVATED",
                        lines = listOf("ผู้ใช้สั่งหยุดฉุกเฉินผ่าน Mobile Terminal — ระงับคำสั่งซื้อขายทั้งหมดทันที")
                    )
                }
        }
    }

    fun resetEmergencyStop() {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            cloudRepository.saveRiskPolicy(policy.copy(emergencyStopped = false))
                .onSuccess {
                    val baseUrl = integrationSettings.value.backendBaseUrl
                    if (backendClient.isValidHttpUrl(baseUrl)) {
                        backendClient.syncEmergencyStop(baseUrl, credentialVault.getBackendToken(), stopped = false)
                    }
                    _snackbarMessage.value = "ปลดล็อก Emergency Stop แล้ว (Auto-Trade ยังคงปิดอยู่)"
                }
        }
    }

    fun saveStrategyConfig(strategy: StrategyConfig) {
        viewModelScope.launch {
            cloudRepository.saveStrategy(strategy)
                .onSuccess {
                    val baseUrl = integrationSettings.value.backendBaseUrl
                    if (backendClient.isValidHttpUrl(baseUrl)) {
                        backendClient.syncStrategy(baseUrl, credentialVault.getBackendToken(), strategy)
                    }
                    _snackbarMessage.value = "บันทึกกลยุทธ์ '${strategy.name}' และซิงค์แล้ว"
                }
                .onFailure { _snackbarMessage.value = "บันทึกกลยุทธ์ไม่สำเร็จ: ${it.localizedMessage}" }
        }
    }

    fun saveRiskPolicyConfig(policy: RiskPolicy) {
        viewModelScope.launch {
            cloudRepository.saveRiskPolicy(policy)
                .onSuccess {
                    syncRiskToBackendIfConnected(policy)
                    _snackbarMessage.value = "บันทึกนโยบาย Risk Management แล้ว"
                }
                .onFailure { _snackbarMessage.value = "บันทึกนโยบายไม่สำเร็จ: ${it.localizedMessage}" }
        }
    }

    private suspend fun syncRiskToBackendIfConnected(policy: RiskPolicy) {
        val baseUrl = integrationSettings.value.backendBaseUrl
        if (backendClient.isValidHttpUrl(baseUrl)) {
            backendClient.syncRiskPolicy(baseUrl, credentialVault.getBackendToken(), policy)
        }
    }

    fun switchExecutionMode(targetMode: ExecutionMode) {
        viewModelScope.launch {
            if (targetMode == ExecutionMode.LIVE && !_backendHealth.value.liveAuthorized) {
                _snackbarMessage.value = "โหมด LIVE ถูกล็อก: ต้องเชื่อมต่อและยืนยัน FastAPI Connector ก่อน"
                return@launch
            }
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (backendClient.isValidHttpUrl(baseUrl) && _backendHealth.value.connected) {
                val res = backendClient.switchMode(baseUrl, credentialVault.getBackendToken(), targetMode, false)
                if (res.isFailure) {
                    _snackbarMessage.value = "Backend ปฏิเสธการสลับโหมด: ${res.exceptionOrNull()?.localizedMessage}"
                    return@launch
                }
            }
            cloudRepository.updateUserBalanceAndMode(currentBalance(), targetMode)
                .onSuccess { _snackbarMessage.value = "สลับโหมดเป็น ${targetMode.label} แล้ว" }
        }
    }

    fun resetSimulationBalance() {
        viewModelScope.launch {
            cloudRepository.updateUserBalanceAndMode(10000.0, ExecutionMode.SIMULATION)
                .onSuccess { _snackbarMessage.value = "รีเซ็ตยอด Simulation Balance เป็น $10,000.00 แล้ว" }
        }
    }

    fun saveIntegrationsAndTestBackend(
        backendBaseUrl: String,
        backendWsUrl: String,
        telegramChatId: String,
        notifyOnSignal: Boolean,
        notifyOnTradeResult: Boolean,
        notifyOnRiskStop: Boolean,
        notifyDailySummary: Boolean,
        enableLocalNotifications: Boolean,
        newBackendToken: String?,
        newTelegramBotToken: String?
    ) {
        viewModelScope.launch {
            credentialVault.saveIntegrationSettings(
                backendBaseUrl = backendBaseUrl,
                backendWsUrl = backendWsUrl,
                telegramChatId = telegramChatId,
                notifyOnSignal = notifyOnSignal,
                notifyOnTradeResult = notifyOnTradeResult,
                notifyOnRiskStop = notifyOnRiskStop,
                notifyDailySummary = notifyDailySummary,
                newBackendToken = newBackendToken,
                newTelegramBotToken = newTelegramBotToken,
                enableLocalNotifications = enableLocalNotifications
            )
            _snackbarMessage.value = "เข้ารหัสและบันทึกการตั้งค่าแล้ว"
            testBackendConnection()
            startMarketStream()
        }
    }

    fun testBackendConnection() {
        viewModelScope.launch {
            _backendHealth.update { it.copy(checking = true) }
            val url = integrationSettings.value.backendBaseUrl
            val token = credentialVault.getBackendToken()
            val health = backendClient.checkHealth(url, token)
            _backendHealth.value = health
            _snackbarMessage.value = health.statusMessage
            if (health.connected) {
                refreshMlModelsInternal(url, token, _terminalState.value.selectedInstrument)
            }
        }
    }

    fun sendTelegramTest() {
        viewModelScope.launch {
            val token = credentialVault.getTelegramBotToken()
            val chatId = integrationSettings.value.telegramChatId
            val summary = todaySummary()
            val result = telegramNotifier.sendAlert(
                botToken = token,
                chatId = chatId,
                title = "IQ TRADING AI • CONNECTION TEST",
                bodyLines = listOf(
                    "สถานะ Telegram Bot: ปกติ",
                    "โหมดปัจจุบัน: ${currentExecutionMode().name}",
                    "P&L วันนี้: $${format2(summary?.netPnl ?: 0.0)} (${summary?.totalTrades ?: 0} ไม้)"
                )
            )
            _snackbarMessage.value = result.getOrElse { it.localizedMessage ?: "ส่งข้อความทดสอบไม่สำเร็จ" }
        }
    }

    fun sendLocalNotificationTest() {
        val posted = notificationHelper.notifyTest()
        _snackbarMessage.value = if (posted) {
            "ส่งการแจ้งเตือน Local Notification ทดสอบแล้ว"
        } else {
            "กรุณาอนุญาตสิทธิ์ Notification บนอุปกรณ์ก่อนทดสอบ"
        }
    }

    fun syncCloudBackupNow() {
        viewModelScope.launch {
            _isSyncingCloud.value = true
            cloudRepository.updateUserBalanceAndMode(currentBalance(), currentExecutionMode(), markBackupTimestamp = true)
                .onSuccess { _snackbarMessage.value = "สำรองข้อมูลกลยุทธ์และประวัติขึ้น Cloud Firestore สำเร็จ" }
                .onFailure { _snackbarMessage.value = "สำรองข้อมูลไม่สำเร็จ: ${it.localizedMessage}" }
            _isSyncingCloud.value = false
        }
    }

    private fun dispatchAlerts(
        isSignal: Boolean = false,
        isTradeResult: Boolean = false,
        isRiskAlert: Boolean = false,
        title: String,
        lines: List<String>
    ) {
        val cfg = integrationSettings.value
        val shouldSend = (isSignal && cfg.notifyOnSignal) ||
            (isTradeResult && cfg.notifyOnTradeResult) ||
            (isRiskAlert && cfg.notifyOnRiskStop)
        if (!shouldSend) return

        if (cfg.enableLocalNotifications) {
            val body = lines.joinToString(" • ")
            when {
                isRiskAlert -> notificationHelper.notifyRiskAlert(title, body)
                isTradeResult -> notificationHelper.notifyTradeResult(title, body)
                isSignal -> notificationHelper.notifySignal(title, body)
            }
        }

        val token = credentialVault.getTelegramBotToken()
        if (!token.isNullOrBlank() && cfg.telegramChatId.isNotBlank()) {
            viewModelScope.launch {
                telegramNotifier.sendAlert(token, cfg.telegramChatId, title, lines)
            }
        }
    }

    private suspend fun updateProfileAndDailySummary(pnl: Double, won: Boolean) {
        val updatedBal = (currentBalance() + pnl).coerceAtLeast(0.0)
        cloudRepository.updateUserBalanceAndMode(updatedBal, currentExecutionMode(), markBackupTimestamp = true)

        val dateKey = cloudRepository.todayDateKey()
        val existing = todaySummary()
        val newNetPnl = (existing?.netPnl ?: 0.0) + pnl
        val currentDrawdown = if (newNetPnl < 0) -newNetPnl else 0.0
        cloudRepository.upsertDailySummary(
            DailyPnLSummary(
                id = dateKey,
                userId = currentUserId,
                dateKey = dateKey,
                totalTrades = (existing?.totalTrades ?: 0) + 1,
                wins = (existing?.wins ?: 0) + if (won) 1 else 0,
                losses = (existing?.losses ?: 0) + if (won) 0 else 1,
                netPnl = newNetPnl,
                consecutiveLosses = if (won) 0 else (existing?.consecutiveLosses ?: 0) + 1,
                peakDrawdown = maxOf(existing?.peakDrawdown ?: 0.0, currentDrawdown)
            )
        )
    }

    private fun finishPipeline(stage: ExecutionStage, message: String) {
        _terminalState.update {
            it.copy(executionStage = stage, isOrderInFlight = false, lastOrderFeedback = message)
        }
        _snackbarMessage.value = message
    }

    private suspend fun cacheCandlesToRoom(instrument: String, timeframe: String, candles: List<Candle>) {
        withContext(Dispatchers.IO) {
            tradingDao.clearCandles(instrument, timeframe)
            tradingDao.insertCandles(
                candles.map {
                    CachedCandleEntity(
                        instrument = instrument,
                        timeframe = timeframe,
                        timestampMillis = it.timestampMillis,
                        open = it.open,
                        high = it.high,
                        low = it.low,
                        close = it.close,
                        volume = it.volume
                    )
                }
            )
        }
    }

    private fun activeStrategy(): StrategyConfig {
        val list = (strategiesState.value as? CloudUiState.Success)?.data.orEmpty()
        return list.firstOrNull { it.active } ?: list.firstOrNull() ?: StrategyConfig(userId = currentUserId)
    }

    private fun currentRiskPolicy(): RiskPolicy =
        (riskPolicyState.value as? CloudUiState.Success)?.data ?: RiskPolicy(userId = currentUserId)

    private fun currentTrades(): List<TradeRecord> =
        (tradesState.value as? CloudUiState.Success)?.data.orEmpty()

    private fun todaySummary(): DailyPnLSummary? {
        val key = cloudRepository.todayDateKey()
        return (dailySummariesState.value as? CloudUiState.Success)?.data.orEmpty().firstOrNull { it.dateKey == key }
    }

    private fun currentBalance(): Double =
        (profileState.value as? CloudUiState.Success)?.data?.simulationBalance ?: 10000.0

    private fun currentExecutionMode(): ExecutionMode =
        ExecutionMode.safeValueOf((profileState.value as? CloudUiState.Success)?.data?.executionMode)

    private fun unwrapFirestoreError(error: Throwable, fallback: String): String {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Stream error", rootError)
        return rootError.localizedMessage ?: fallback
    }

    private fun format2(value: Double): String = String.format("%.2f", value)
    private fun formatPrice(value: Double): String =
        if (value >= 100.0) String.format("%.2f", value) else String.format("%.5f", value)

    override fun onCleared() {
        super.onCleared()
        marketFeedJob?.cancel()
        backendClient.disconnectTickStream()
    }

    private companion object {
        const val TAG = "TradingViewModel"
    }
}
