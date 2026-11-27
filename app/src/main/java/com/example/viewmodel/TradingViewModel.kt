package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.BackendConnectorClient
import com.example.data.remote.MarketIntelligenceResponseDto
import com.example.data.remote.OrderSubmitRequestDto
import com.example.data.remote.ReplayEvaluationResponseDto
import com.example.data.remote.TelegramNotifier
import com.example.data.repository.LocalTradingRepository
import com.example.engine.LiveReadinessEvaluator
import com.example.engine.LocalBacktestEngine
import com.example.engine.LocalStrategySelectorEngine
import com.example.engine.MarketSimulationEngine
import com.example.engine.MultiTimeframeLocalEngine
import com.example.engine.PerformanceAnalyticsEngine
import com.example.engine.RiskManagementEngine
import com.example.engine.SignalEngine
import com.example.engine.SystemDiagnosticsRunner
import com.example.engine.TechnicalAnalysisEngine
import com.example.model.BackendHealthState
import com.example.model.AssetCatalogItem
import com.example.model.BackendOrderSummary
import com.example.model.BacktestReport
import com.example.model.BrokerDryRunReport
import com.example.model.Candle
import com.example.model.DailyPnLSummary
import com.example.model.DataQualitySummary
import com.example.model.DiagnosticCheckStatus
import com.example.model.DriftStatus
import com.example.model.ExecutionMode
import com.example.model.ExecutionStage
import com.example.model.IntegrationSettings
import com.example.model.LiveReadinessReport
import com.example.model.LocalDatabaseStats
import com.example.model.MarketRegime
import com.example.model.MlDriftReport
import com.example.model.MlModelSummary
import com.example.model.MultiTimeframeReport
import com.example.model.OrderLifecycleEvent
import com.example.model.OrderLifecycleState
import com.example.model.PerformanceAnalyticsReport
import com.example.model.ReplaySessionState
import com.example.model.RiskPolicy
import com.example.model.RiskValidationResult
import com.example.model.SUPPORTED_INSTRUMENTS
import com.example.model.SignalAnalysis
import com.example.model.StrategyConfig
import com.example.model.StrategySelectionReport
import com.example.model.StreamTransportMode
import com.example.model.SystemDiagnosticsReport
import com.example.model.TechnicalSnapshot
import com.example.model.Timeframe
import com.example.model.TradeDirection
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.model.UserProfile
import com.example.model.WebSocketConnectionState
import com.example.security.SecretRedactor
import com.example.security.SecureCredentialVault
import com.example.ui.notifications.TradingNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface LocalUiState<out T> {
    data object Loading : LocalUiState<Nothing>
    data class Success<T>(val data: T) : LocalUiState<T>
    data class Error(val message: String) : LocalUiState<Nothing>
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
    val lastLossTimestampMillis: Long? = null,
    val unresolvedOrdersCount: Int = 0,
    val accountMode: String = "SIMULATION",
    val accountDisplayLabel: String = "Simulation",
    val brokerBalance: Double? = null,
    val connectionState: String = "INTERNAL",
    val sessionVerified: Boolean = false,
    val operationMode: String = "MANUAL",
    val learningStatus: String = "IDLE",
    val trainingStatus: String = "IDLE",
    val backtestStatus: String = "IDLE",
    val realAccountLocked: Boolean = true,
    val assetCatalog: List<AssetCatalogItem> = emptyList(),
    val unavailableAssetTypes: List<String> = emptyList(),
    val assetCatalogSource: String = "LOCAL_SIMULATION",
    val isReconciling: Boolean = false,
    val lastOrderCorrelationId: String? = null,
    val lastOrderLifecycleState: OrderLifecycleState? = null,
    val strategyReport: StrategySelectionReport? = null,
    val isStrategyBusy: Boolean = false,
    val autoSelectStrategy: Boolean = true,
    val pinnedStrategyId: String? = null,
    val wsState: WebSocketConnectionState = WebSocketConnectionState(),
    val activeModelDriftStatus: DriftStatus = DriftStatus.HEALTHY,
    val activeModelDriftScore: Double = 0.0,
    val activeModelDriftNotes: String = "",
    val lastDriftReport: MlDriftReport? = null
)

class TradingViewModel(
    private val localRepository: LocalTradingRepository,
    private val credentialVault: SecureCredentialVault,
    private val telegramNotifier: TelegramNotifier,
    private val backendClient: BackendConnectorClient,
    private val notificationHelper: TradingNotificationHelper,
    private val currentUserId: String = "local_trader",
    private val userDisplayName: String = "Local Trader",
    private val userEmail: String? = null
) : ViewModel() {

    private val simEngine = MarketSimulationEngine()
    private val _terminalState = MutableStateFlow(TerminalUiState())
    val terminalState: StateFlow<TerminalUiState> = _terminalState.asStateFlow()

    private val _backendHealth = MutableStateFlow(BackendHealthState())
    val backendHealth: StateFlow<BackendHealthState> = _backendHealth.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _mlModels = MutableStateFlow<List<MlModelSummary>>(emptyList())
    val mlModels: StateFlow<List<MlModelSummary>> = _mlModels.asStateFlow()

    private val _activeMlModel = MutableStateFlow<MlModelSummary?>(null)
    val activeMlModel: StateFlow<MlModelSummary?> = _activeMlModel.asStateFlow()

    private val _isMlBusy = MutableStateFlow(false)
    val isMlBusy: StateFlow<Boolean> = _isMlBusy.asStateFlow()

    private val _backendOrders = MutableStateFlow<List<BackendOrderSummary>>(emptyList())
    val backendOrders: StateFlow<List<BackendOrderSummary>> = _backendOrders.asStateFlow()

    private val _selectedOrderEvents = MutableStateFlow<List<OrderLifecycleEvent>>(emptyList())
    val selectedOrderEvents: StateFlow<List<OrderLifecycleEvent>> = _selectedOrderEvents.asStateFlow()

    val savedIqOptionEmail: String? = credentialVault.getIqOptionEmail()
    val savedIqOptionPassword: String? = credentialVault.getIqOptionPassword()

    val integrationSettings: StateFlow<IntegrationSettings> = credentialVault.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), IntegrationSettings())

    val databaseStats: StateFlow<LocalDatabaseStats> = localRepository.observeDatabaseStats(currentUserId)
        .catch { emit(LocalDatabaseStats()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalDatabaseStats())

    val profileState: StateFlow<LocalUiState<UserProfile?>> =
        localRepository.observeUserProfile(currentUserId)
            .map<UserProfile?, LocalUiState<UserProfile?>> { LocalUiState.Success(it) }
            .catch { emit(LocalUiState.Error(unwrapSqlError(it, "โหลดข้อมูลโปรไฟล์ SQLite ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalUiState.Loading)

    val strategiesState: StateFlow<LocalUiState<List<StrategyConfig>>> =
        localRepository.observeStrategies(currentUserId)
            .map<List<StrategyConfig>, LocalUiState<List<StrategyConfig>>> { LocalUiState.Success(it) }
            .catch { emit(LocalUiState.Error(unwrapSqlError(it, "โหลดกลยุทธ์จาก SQLite ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalUiState.Loading)

    val riskPolicyState: StateFlow<LocalUiState<RiskPolicy?>> =
        localRepository.observeRiskPolicy(currentUserId)
            .map<RiskPolicy?, LocalUiState<RiskPolicy?>> { LocalUiState.Success(it) }
            .catch { emit(LocalUiState.Error(unwrapSqlError(it, "โหลดนโยบายความเสี่ยง SQLite ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalUiState.Loading)

    val tradesState: StateFlow<LocalUiState<List<TradeRecord>>> =
        localRepository.observeTrades(currentUserId)
            .map<List<TradeRecord>, LocalUiState<List<TradeRecord>>> { LocalUiState.Success(it) }
            .catch { emit(LocalUiState.Error(unwrapSqlError(it, "โหลดประวัติการเทรด SQLite ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalUiState.Loading)

    val dailySummariesState: StateFlow<LocalUiState<List<DailyPnLSummary>>> =
        localRepository.observeDailySummaries(currentUserId)
            .map<List<DailyPnLSummary>, LocalUiState<List<DailyPnLSummary>>> { LocalUiState.Success(it) }
            .catch { emit(LocalUiState.Error(unwrapSqlError(it, "โหลดสรุปผลรายวัน SQLite ไม่สำเร็จ"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalUiState.Loading)

    private val _analyticsModeFilter = MutableStateFlow("SIMULATION")
    val analyticsModeFilter: StateFlow<String> = _analyticsModeFilter.asStateFlow()

    val performanceReport: StateFlow<PerformanceAnalyticsReport> =
        combine(tradesState, _analyticsModeFilter) { state, filter ->
            val settledOrAll = (state as? LocalUiState.Success)?.data.orEmpty()
            PerformanceAnalyticsEngine.buildReport(settledOrAll, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), PerformanceAnalyticsReport())

    private val _backtestReport = MutableStateFlow<BacktestReport?>(null)
    val backtestReport: StateFlow<BacktestReport?> = _backtestReport.asStateFlow()

    private val _isBacktestBusy = MutableStateFlow(false)
    val isBacktestBusy: StateFlow<Boolean> = _isBacktestBusy.asStateFlow()

    private val _diagnosticsReport = MutableStateFlow<SystemDiagnosticsReport?>(null)
    val diagnosticsReport: StateFlow<SystemDiagnosticsReport?> = _diagnosticsReport.asStateFlow()

    private val _isDiagnosticsBusy = MutableStateFlow(false)
    val isDiagnosticsBusy: StateFlow<Boolean> = _isDiagnosticsBusy.asStateFlow()

    private val _brokerDryRunReport = MutableStateFlow<BrokerDryRunReport?>(null)
    val brokerDryRunReport: StateFlow<BrokerDryRunReport?> = _brokerDryRunReport.asStateFlow()

    private val _isDryRunBusy = MutableStateFlow(false)
    val isDryRunBusy: StateFlow<Boolean> = _isDryRunBusy.asStateFlow()

    val liveReadinessReport: StateFlow<LiveReadinessReport> =
        combine(
            _backendHealth,
            riskPolicyState,
            integrationSettings,
            _terminalState,
            profileState
        ) { health, riskUi, settings, term, profUi ->
            val policy = (riskUi as? LocalUiState.Success)?.data ?: RiskPolicy(userId = currentUserId)
            val mode = ExecutionMode.safeValueOf((profUi as? LocalUiState.Success)?.data?.executionMode)
            val dq = term.dataQuality ?: DataQualitySummary(valid = false, reason = "รอข้อมูลแท่งเทียน")
            LiveReadinessEvaluator.evaluateLocal(
                backendHealth = health,
                riskPolicy = policy,
                settings = settings,
                dataQuality = dq,
                unresolvedOrdersCount = term.unresolvedOrdersCount,
                currentMode = mode,
                ownerConfirmed = mode == ExecutionMode.LIVE
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LiveReadinessReport())

    private var marketFeedJob: Job? = null
    private var localReplayBuffer: List<Candle> = emptyList()
    private var wsTickCounter = 0
    private var autoCycleCounter = 0

    init {
        viewModelScope.launch {
            localRepository.ensureUserBootstrap(currentUserId, userDisplayName, userEmail)
                .onFailure { _snackbarMessage.value = "เตรียมฐานข้อมูล SQLite เริ่มต้นไม่สำเร็จ: ${it.localizedMessage}" }
        }
        startMarketStream()
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun notifyMessage(message: String) {
        _snackbarMessage.value = message
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
                    refreshBackendExecutionState(settings.backendBaseUrl, token)
                    backendClient.fetchStrategyModules(settings.backendBaseUrl, token, instrument, tf.code, currentUserId)
                        .onSuccess { report ->
                            _terminalState.update {
                                it.copy(
                                    strategyReport = report,
                                    autoSelectStrategy = report.autoSelected
                                )
                            }
                        }
                }
            }

            localRepository.cacheCandles(instrument, tf.code, candles)
            applyAnalysis(candles, transport, remoteIntel)

            val wsUrl = backendClient.resolveWebSocketUrl(
                baseUrl = settings.backendBaseUrl,
                explicitWsUrl = settings.backendWsUrl,
                instrument = instrument,
                timeframe = tf.code
            )

            if (transport != StreamTransportMode.LOCAL_SIMULATION && wsUrl != null) {
                connectWebSocketWithFallback(
                    wsUrl = wsUrl,
                    baseUrl = settings.backendBaseUrl,
                    explicitWsUrl = settings.backendWsUrl,
                    token = token,
                    instrument = instrument,
                    tf = tf
                )
            } else {
                _terminalState.update {
                    it.copy(
                        wsState = WebSocketConnectionState(
                            marketConnected = false,
                            signalConnected = false,
                            systemConnected = false,
                            reconnectAttempt = 0,
                            isFallbackPolling = false
                        )
                    )
                }
                runLocalSimulationLoop()
            }
        }
    }

    private suspend fun connectWebSocketWithFallback(
        wsUrl: String,
        baseUrl: String,
        explicitWsUrl: String,
        token: String?,
        instrument: String,
        tf: Timeframe
    ) {
        var wsFailed = false
        var reconnectAttempt = 0
        val signalWsUrl = backendClient.resolveChannelWsUrl(
            baseUrl = baseUrl,
            explicitWsUrl = explicitWsUrl,
            channelPath = "/ws/signals?instrument=$instrument&timeframe=${tf.code}"
        )
        val systemWsUrl = backendClient.resolveChannelWsUrl(
            baseUrl = baseUrl,
            explicitWsUrl = explicitWsUrl,
            channelPath = "/ws/system"
        )

        while (viewModelScope.isActive && reconnectAttempt <= 3) {
            wsFailed = false
            _terminalState.update {
                it.copy(
                    wsState = it.wsState.copy(
                        reconnectAttempt = reconnectAttempt,
                        isFallbackPolling = false
                    )
                )
            }

            backendClient.connectTickStream(
                wsUrl = wsUrl,
                bearerToken = token,
                onOpen = {
                    reconnectAttempt = 0
                    _terminalState.update {
                        it.copy(
                            streamTransport = StreamTransportMode.FASTAPI_WEBSOCKET,
                            wsState = it.wsState.copy(
                                marketConnected = true,
                                reconnectAttempt = 0,
                                isFallbackPolling = false,
                                lastError = null
                            )
                        )
                    }
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
                onFailure = { reason ->
                    wsFailed = true
                    _terminalState.update {
                        it.copy(
                            wsState = it.wsState.copy(
                                marketConnected = false,
                                lastError = reason
                            )
                        )
                    }
                }
            )

            if (signalWsUrl != null) {
                backendClient.connectSignalStream(
                    wsUrl = signalWsUrl,
                    bearerToken = token,
                    onOpen = {
                        _terminalState.update {
                            it.copy(wsState = it.wsState.copy(signalConnected = true))
                        }
                    },
                    onSignal = { signalDto ->
                        if (_terminalState.value.replayState?.active == true) return@connectSignalStream
                        val pushedSignal = signalDto.signal?.toDomain("FastAPI WS Signal") ?: return@connectSignalStream
                        _terminalState.update { it.copy(signalAnalysis = pushedSignal) }
                    },
                    onFailure = {
                        _terminalState.update {
                            it.copy(wsState = it.wsState.copy(signalConnected = false))
                        }
                    }
                )
            }

            if (systemWsUrl != null) {
                backendClient.connectSystemStream(
                    wsUrl = systemWsUrl,
                    bearerToken = token,
                    onOpen = {
                        _terminalState.update {
                            it.copy(wsState = it.wsState.copy(systemConnected = true))
                        }
                    },
                    onSystem = { sys ->
                        _backendHealth.update {
                            it.copy(
                                connected = sys.connector_connected,
                                connectorName = sys.connector,
                                liveAuthorized = sys.live_authorized
                            )
                        }
                        _terminalState.update {
                            it.copy(
                                unresolvedOrdersCount = sys.unresolved_orders_count,
                                activeModelDriftStatus = DriftStatus.safeValueOf(sys.active_model_drift_status),
                                activeModelDriftScore = sys.active_model_drift_score,
                                activeModelDriftNotes = sys.active_model_drift_notes
                            )
                        }
                    },
                    onFailure = {
                        _terminalState.update {
                            it.copy(wsState = it.wsState.copy(systemConnected = false))
                        }
                    }
                )
            }

            while (viewModelScope.isActive && !wsFailed) {
                delay(1500L)
            }
            if (!viewModelScope.isActive) return
            reconnectAttempt++
            if (reconnectAttempt <= 3) {
                _terminalState.update {
                    it.copy(wsState = it.wsState.copy(reconnectAttempt = reconnectAttempt))
                }
                delay(backendClient.computeWsReconnectDelayMs(reconnectAttempt - 1))
            }
        }

        // Graceful fallback to local simulation if WebSocket stays down
        backendClient.disconnectTickStream()
        _terminalState.update {
            it.copy(
                streamTransport = StreamTransportMode.LOCAL_SIMULATION,
                wsState = it.wsState.copy(
                    marketConnected = false,
                    signalConnected = false,
                    systemConnected = false,
                    isFallbackPolling = true
                )
            )
        }
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
        val localSnapshot = TechnicalAnalysisEngine.analyze(candles, strategy)
        val remoteSnap = remoteIntel?.signal?.technical_snapshot
        val snapshot = if (remoteSnap != null) {
            localSnapshot.copy(
                adx = if (remoteSnap.adx > 0.0) remoteSnap.adx else localSnapshot.adx,
                plusDi = if (remoteSnap.plus_di > 0.0) remoteSnap.plus_di else localSnapshot.plusDi,
                minusDi = if (remoteSnap.minus_di > 0.0) remoteSnap.minus_di else localSnapshot.minusDi,
                stochasticK = remoteSnap.stochastic_k.takeIf { it in 0.0..100.0 } ?: localSnapshot.stochasticK,
                stochasticD = remoteSnap.stochastic_d.takeIf { it in 0.0..100.0 } ?: localSnapshot.stochasticD,
                vwap = if (remoteSnap.vwap > 0.0) remoteSnap.vwap else localSnapshot.vwap,
                vwapReliable = remoteSnap.vwap_reliable || localSnapshot.vwapReliable,
                regime = MarketRegime.safeValueOf(remoteSnap.regime)
            )
        } else {
            localSnapshot
        }
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

        val currentReport = _terminalState.value.strategyReport
        val updatedReport = if (transport == StreamTransportMode.LOCAL_SIMULATION || currentReport == null) {
            LocalStrategySelectorEngine.evaluate(
                candles = candles,
                currentRegime = snapshot.regime,
                settledTrades = currentTrades(),
                autoSelect = _terminalState.value.autoSelectStrategy,
                pinnedStrategyId = _terminalState.value.pinnedStrategyId
            )
        } else {
            currentReport
        }

        _terminalState.update {
            it.copy(
                candles = candles,
                technicalSnapshot = snapshot,
                signalAnalysis = signal,
                mtfReport = mtfReport,
                dataQuality = dataQuality,
                streamTransport = transport,
                riskValidation = riskCheck,
                strategyReport = updatedReport
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
                    localRepository.saveRiskPolicy(policy.copy(autoTradeEnabled = false))
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
            val settings = integrationSettings.value
            val useBackendExecution = _backendHealth.value.connected &&
                _terminalState.value.replayState?.active != true &&
                backendClient.isValidHttpUrl(settings.backendBaseUrl)

            if (useBackendExecution) {
                val backendToken = credentialVault.getBackendToken()
                val remoteSubmit = backendClient.submitOrder(
                    baseUrl = settings.backendBaseUrl,
                    bearerToken = backendToken,
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
                    refreshBackendExecutionState(settings.backendBaseUrl, backendToken)
                    val safeErr = SecretRedactor.redact(remoteSubmit.exceptionOrNull()?.localizedMessage, backendToken)
                    finishPipeline(
                        ExecutionStage.IDLE,
                        "คำสั่งถูกปฏิเสธโดย Backend: $safeErr"
                    )
                    return
                }

                val dto = remoteSubmit.getOrThrow()
                val lifecycle = OrderLifecycleState.safeValueOf(dto.lifecycle_state)
                _terminalState.update {
                    it.copy(
                        lastOrderCorrelationId = dto.correlation_id.ifBlank { null },
                        lastOrderLifecycleState = lifecycle
                    )
                }

                if (lifecycle == OrderLifecycleState.PENDING || lifecycle == OrderLifecycleState.UNKNOWN) {
                    val openTrade = TradeRecord(
                        id = dto.order_id.ifBlank { "sql_${UUID.randomUUID().toString().take(12)}" },
                        userId = currentUserId,
                        instrument = strategy.instrument,
                        timeframe = _terminalState.value.selectedTimeframe.code,
                        direction = directionToUse.name,
                        mode = mode.name,
                        amount = strategy.tradeAmount,
                        payoutRate = spec.payoutRate,
                        entryPrice = dto.entry_price,
                        exitPrice = 0.0,
                        status = TradeStatus.toNormalizedStatus(lifecycle.name),
                        pnl = 0.0,
                        strategyName = strategy.name,
                        marketRegime = snapshot.regime.name,
                        confidenceScore = currentSignal.confidenceScore,
                        calibratedProbability = currentSignal.calibratedProbability,
                        explanation = "${currentSignal.explanation} [Corr: ${dto.correlation_id} | State: ${lifecycle.name}]"
                    )
                    localRepository.createTrade(openTrade)
                    refreshBackendExecutionState(settings.backendBaseUrl, backendToken)
                    finishPipeline(
                        ExecutionStage.IDLE,
                        "คำสั่ง ${dto.correlation_id} สถานะ ${lifecycle.name} — กรุณากด Reconcile ก่อนเปิดไม้ใหม่"
                    )
                    return
                }

                if (lifecycle == OrderLifecycleState.REJECTED || lifecycle == OrderLifecycleState.FAILED) {
                    refreshBackendExecutionState(settings.backendBaseUrl, backendToken)
                    finishPipeline(
                        ExecutionStage.IDLE,
                        "คำสั่งไม่สำเร็จ (${lifecycle.name}): ${dto.message.orEmpty()}"
                    )
                    return
                }

                // Settled cleanly by Backend BrokerConnector
                _terminalState.update { it.copy(executionStage = ExecutionStage.RESULT_VERIFICATION) }
                val outcome = dto.settlement_outcome ?: dto.status
                val won = outcome.equals(TradeStatus.WON.name, ignoreCase = true)
                val finalStatus = if (won) TradeStatus.WON else TradeStatus.LOST

                _terminalState.update { it.copy(executionStage = ExecutionStage.TRADE_LOGGING) }
                val settledTrade = TradeRecord(
                    id = dto.order_id.ifBlank { "sql_${UUID.randomUUID().toString().take(12)}" },
                    userId = currentUserId,
                    instrument = strategy.instrument,
                    timeframe = _terminalState.value.selectedTimeframe.code,
                    direction = directionToUse.name,
                    mode = mode.name,
                    amount = strategy.tradeAmount,
                    payoutRate = spec.payoutRate,
                    entryPrice = dto.entry_price,
                    exitPrice = dto.exit_price,
                    status = TradeStatus.toNormalizedStatus(finalStatus.name),
                    pnl = dto.pnl,
                    strategyName = strategy.name,
                    marketRegime = snapshot.regime.name,
                    confidenceScore = currentSignal.confidenceScore,
                    calibratedProbability = currentSignal.calibratedProbability,
                    explanation = "${currentSignal.explanation} [Corr: ${dto.correlation_id}]"
                )
                localRepository.createTrade(settledTrade)

                _terminalState.update { it.copy(executionStage = ExecutionStage.PERFORMANCE_EVAL) }
                updateProfileAndDailySummary(pnl = dto.pnl, won = won)
                if (!won) {
                    _terminalState.update { it.copy(lastLossTimestampMillis = System.currentTimeMillis()) }
                }
                refreshBackendExecutionState(settings.backendBaseUrl, backendToken)

                val resultBadge = if (won) "WON (+$${format2(dto.pnl)})" else "LOST (-$${format2(strategy.tradeAmount)})"
                dispatchAlerts(
                    isTradeResult = true,
                    title = "TRADE SETTLED • $resultBadge",
                    lines = listOf(
                        "${strategy.instrument} ${directionToUse.name}: ${formatPrice(dto.entry_price)} -> ${formatPrice(dto.exit_price)}",
                        "Corr: ${dto.correlation_id} | Balance: $${format2(currentBalance() + dto.pnl)}"
                    )
                )
                finishPipeline(
                    ExecutionStage.IDLE,
                    if (won) "ปิดออเดอร์ ${directionToUse.name} ชนะ +$${format2(dto.pnl)} (${dto.correlation_id})"
                    else "ปิดออเดอร์ ${directionToUse.name} ขาดทุน -$${format2(strategy.tradeAmount)} (${dto.correlation_id})"
                )
                return
            }

            val localTradeId = "sql_${UUID.randomUUID().toString().take(12)}"
            val pendingTrade = TradeRecord(
                id = localTradeId,
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
                marketRegime = snapshot.regime.name,
                confidenceScore = currentSignal.confidenceScore,
                calibratedProbability = currentSignal.calibratedProbability,
                explanation = currentSignal.explanation
            )
            localRepository.createTrade(pendingTrade)

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
            localRepository.settleTrade(localTradeId, exitPrice, finalStatus, pnl)

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
            val active = list.firstOrNull { it.isActive }
            _activeMlModel.value = active
            _terminalState.update {
                it.copy(
                    activeModelDriftStatus = active?.driftStatus ?: DriftStatus.HEALTHY,
                    activeModelDriftScore = active?.driftScore ?: 0.0,
                    activeModelDriftNotes = active?.driftNotes.orEmpty()
                )
            }
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

    fun checkMlModelDrift(modelId: String) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) {
                _snackbarMessage.value = "กรุณาเชื่อมต่อ FastAPI Backend ก่อนตรวจ Drift"
                return@launch
            }
            _isMlBusy.value = true
            val token = credentialVault.getBackendToken()
            val instrument = _terminalState.value.selectedInstrument
            backendClient.checkMlModelDrift(baseUrl, token, modelId)
                .onSuccess { report ->
                    refreshMlModelsInternal(baseUrl, token, instrument)
                    _terminalState.update {
                        it.copy(
                            activeModelDriftStatus = report.driftStatus,
                            activeModelDriftScore = report.driftScore,
                            activeModelDriftNotes = report.driftNotes,
                            lastDriftReport = report
                        )
                    }
                    _snackbarMessage.value = when (report.driftStatus) {
                        DriftStatus.DRIFT_DETECTED ->
                            "แจ้งเตือน ML Drift (${String.format("%.2f", report.driftScore)}) — แนะนำให้ผู้ใช้พิจารณา Rollback ด้วยตนเอง"
                        DriftStatus.WARNING ->
                            "เฝ้าระวัง Drift (${String.format("%.2f", report.driftScore)}): ${report.driftNotes}"
                        DriftStatus.HEALTHY ->
                            "ตรวจสอบโมเดลแล้ว: สถานะปกติ (Score ${String.format("%.2f", report.driftScore)})"
                    }
                }
                .onFailure {
                    _snackbarMessage.value = "ตรวจ Drift ไม่สำเร็จ: ${it.localizedMessage}"
                }
            _isMlBusy.value = false
        }
    }

    fun rollbackMlModel(targetModelId: String, reason: String? = null) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) return@launch
            _isMlBusy.value = true
            val token = credentialVault.getBackendToken()
            val instrument = _terminalState.value.selectedInstrument
            backendClient.rollbackMlModel(baseUrl, token, targetModelId, reason)
                .onSuccess { rolledBack ->
                    refreshMlModelsInternal(baseUrl, token, instrument)
                    _snackbarMessage.value = "Rollback โมเดลกลับไปยัง ${rolledBack.algorithm} (${rolledBack.modelId.take(8)}) สำเร็จแล้ว"
                }
                .onFailure {
                    _snackbarMessage.value = "Rollback โมเดลไม่สำเร็จ: ${it.localizedMessage}"
                }
            _isMlBusy.value = false
        }
    }

    // --- Execution Reconciliation & Lifecycle Audit (Phase 5) ---

    fun reconcileBackendOrders(orderId: String? = null) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) {
                _snackbarMessage.value = "กรุณาเชื่อมต่อ FastAPI Backend ก่อนทำ Reconciliation"
                return@launch
            }
            _terminalState.update { it.copy(isReconciling = true) }
            val token = credentialVault.getBackendToken()
            backendClient.reconcileOrders(baseUrl, token, orderId)
                .onSuccess { summary ->
                    refreshBackendExecutionState(baseUrl, token)
                    _snackbarMessage.value = summary.message.ifBlank {
                        "ตรวจสอบออเดอร์ ${summary.checkedCount} รายการ: ปิดบัญชี ${summary.settledCount} รายการ"
                    }
                }
                .onFailure {
                    _snackbarMessage.value = "Reconciliation ไม่สำเร็จ: ${it.localizedMessage}"
                }
            _terminalState.update { it.copy(isReconciling = false) }
        }
    }

    fun loadOrderLifecycleEvents(orderId: String) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (!backendClient.isValidHttpUrl(baseUrl)) return@launch
            val token = credentialVault.getBackendToken()
            backendClient.fetchOrderLifecycleEvents(baseUrl, token, orderId)
                .onSuccess { events -> _selectedOrderEvents.value = events }
                .onFailure { _selectedOrderEvents.value = emptyList() }
        }
    }

    fun clearSelectedOrderEvents() {
        _selectedOrderEvents.value = emptyList()
    }

    private suspend fun refreshBackendExecutionState(baseUrl: String, token: String?) {
        backendClient.fetchTradingState(baseUrl, token).onSuccess { stateDto ->
            _terminalState.update {
                it.copy(
                    unresolvedOrdersCount = stateDto.unresolved_orders_count,
                    accountMode = stateDto.account_mode,
                    accountDisplayLabel = stateDto.account_display_label,
                    brokerBalance = stateDto.balance,
                    connectionState = stateDto.connection_state,
                    sessionVerified = stateDto.session_verified,
                    operationMode = stateDto.operation_mode,
                    learningStatus = stateDto.learning_status,
                    trainingStatus = stateDto.training_status,
                    backtestStatus = stateDto.backtest_status,
                    realAccountLocked = stateDto.real_account_locked
                )
            }
        }
        backendClient.fetchAssetCatalog(baseUrl, token).onSuccess { catalog ->
            _terminalState.update {
                it.copy(
                    assetCatalog = catalog.items.map { item ->
                        AssetCatalogItem(
                            rank = item.rank,
                            productType = item.product_type,
                            productLabel = item.product_label,
                            symbol = item.symbol,
                            displayName = item.display_name,
                            isOpen = item.is_open,
                            status = item.status,
                            payoutRate = item.payout_rate,
                            payoutPercent = item.payout_percent,
                            minStake = item.min_stake,
                            maxStake = item.max_stake,
                            leverage = item.leverage,
                            source = item.source
                        )
                    },
                    unavailableAssetTypes = catalog.unavailable_product_types,
                    assetCatalogSource = catalog.source
                )
            }
        }
        backendClient.fetchBackendOrders(baseUrl, token, limit = 30).onSuccess { list ->
            _backendOrders.value = list
            val unresolved = list.count { it.requiresReconciliation }
            _terminalState.update { it.copy(unresolvedOrdersCount = maxOf(it.unresolvedOrdersCount, unresolved)) }
        }
    }

    // --- 8-Module Strategy Selector (Phase 6) ---

    fun refreshStrategyModules() {
        viewModelScope.launch {
            _terminalState.update { it.copy(isStrategyBusy = true) }
            val state = _terminalState.value
            val baseUrl = integrationSettings.value.backendBaseUrl
            val token = credentialVault.getBackendToken()
            val remoteReport = if (_backendHealth.value.connected && backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.fetchStrategyModules(
                    baseUrl = baseUrl,
                    bearerToken = token,
                    instrument = state.selectedInstrument,
                    timeframe = state.selectedTimeframe.code,
                    userId = currentUserId
                ).getOrNull()
            } else {
                null
            }

            val finalReport = remoteReport ?: LocalStrategySelectorEngine.evaluate(
                candles = state.candles,
                currentRegime = state.technicalSnapshot?.regime ?: MarketRegime.SIDEWAYS,
                settledTrades = currentTrades(),
                autoSelect = state.autoSelectStrategy,
                pinnedStrategyId = state.pinnedStrategyId
            )

            _terminalState.update {
                it.copy(
                    strategyReport = finalReport,
                    autoSelectStrategy = finalReport.autoSelected,
                    isStrategyBusy = false
                )
            }
        }
    }

    fun selectStrategyModule(strategyId: String?, autoSelect: Boolean) {
        viewModelScope.launch {
            _terminalState.update {
                it.copy(
                    isStrategyBusy = true,
                    autoSelectStrategy = autoSelect,
                    pinnedStrategyId = if (autoSelect) null else strategyId
                )
            }
            val state = _terminalState.value
            val baseUrl = integrationSettings.value.backendBaseUrl
            val token = credentialVault.getBackendToken()
            val remoteReport = if (_backendHealth.value.connected && backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.selectStrategyModule(
                    baseUrl = baseUrl,
                    bearerToken = token,
                    strategyId = strategyId,
                    autoSelect = autoSelect,
                    instrument = state.selectedInstrument,
                    timeframe = state.selectedTimeframe.code,
                    userId = currentUserId
                ).getOrNull()
            } else {
                null
            }

            val finalReport = remoteReport ?: LocalStrategySelectorEngine.evaluate(
                candles = state.candles,
                currentRegime = state.technicalSnapshot?.regime ?: MarketRegime.SIDEWAYS,
                settledTrades = currentTrades(),
                autoSelect = autoSelect,
                pinnedStrategyId = if (autoSelect) null else strategyId
            )

            _terminalState.update {
                it.copy(
                    strategyReport = finalReport,
                    autoSelectStrategy = finalReport.autoSelected,
                    pinnedStrategyId = if (finalReport.autoSelected) null else finalReport.selectedStrategyId,
                    isStrategyBusy = false
                )
            }

            val chosenModule = finalReport.strategies.firstOrNull { it.selected }
            if (chosenModule != null) {
                val existing = activeStrategy()
                val updated = chosenModule.defaultConfig.copy(
                    id = existing.id,
                    userId = currentUserId,
                    instrument = state.selectedInstrument,
                    timeframe = state.selectedTimeframe.code,
                    tradeAmount = existing.tradeAmount.coerceIn(1.0, currentRiskPolicy().maxTradeAmount),
                    expirySeconds = existing.expirySeconds,
                    active = true
                )
                saveStrategyConfig(updated)
            }
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
            localRepository.saveRiskPolicy(updated)
                .onSuccess {
                    syncRiskToBackendIfConnected(updated)
                    _snackbarMessage.value = if (enabled) "เปิดระบบเทรดอัตโนมัติ (SIMULATION)" else "ปิดระบบเทรดอัตโนมัติแล้ว"
                }
                .onFailure {
                    _snackbarMessage.value = "บันทึกสถานะ Auto-Trade ลง SQLite ไม่สำเร็จ: ${it.localizedMessage}"
                }
        }
    }

    fun triggerEmergencyStop() {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            val updated = policy.copy(emergencyStopped = true, autoTradeEnabled = false)
            localRepository.saveRiskPolicy(updated)
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

    fun resetEmergencyStop() {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            val updated = policy.copy(emergencyStopped = false)
            localRepository.saveRiskPolicy(updated)
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.syncEmergencyStop(baseUrl, credentialVault.getBackendToken(), stopped = false)
            }
            _snackbarMessage.value = "ปลดล็อก Emergency Stop แล้ว (Auto-Trade ยังคงปิดอยู่)"
        }
    }

    fun saveStrategyConfig(strategy: StrategyConfig) {
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.syncStrategy(baseUrl, credentialVault.getBackendToken(), strategy)
            }
            localRepository.saveStrategy(strategy)
                .onSuccess {
                    _snackbarMessage.value = "บันทึกกลยุทธ์ '${strategy.name}' ลง SQLite แล้ว"
                }
                .onFailure {
                    _snackbarMessage.value = "บันทึกกลยุทธ์ไม่สำเร็จ: ${it.localizedMessage}"
                }
        }
    }

    fun saveRiskPolicyConfig(policy: RiskPolicy) {
        viewModelScope.launch {
            syncRiskToBackendIfConnected(policy)
            localRepository.saveRiskPolicy(policy)
                .onSuccess {
                    _snackbarMessage.value = "บันทึกนโยบาย Risk Management ลง SQLite แล้ว"
                }
                .onFailure {
                    _snackbarMessage.value = "บันทึกนโยบาย Risk ไม่สำเร็จ: ${it.localizedMessage}"
                }
        }
    }

    private suspend fun syncRiskToBackendIfConnected(policy: RiskPolicy) {
        val baseUrl = integrationSettings.value.backendBaseUrl
        if (backendClient.isValidHttpUrl(baseUrl)) {
            backendClient.syncRiskPolicy(baseUrl, credentialVault.getBackendToken(), policy)
        }
    }

    fun switchExecutionMode(targetMode: ExecutionMode, explicitLiveConfirmation: Boolean = false) {
        viewModelScope.launch {
            if (targetMode == ExecutionMode.LIVE) {
                val gateReport = LiveReadinessEvaluator.evaluateLocal(
                    backendHealth = _backendHealth.value,
                    riskPolicy = currentRiskPolicy(),
                    settings = integrationSettings.value,
                    dataQuality = _terminalState.value.dataQuality
                        ?: DataQualitySummary(valid = false, reason = "ไม่มีข้อมูลตลาด"),
                    unresolvedOrdersCount = _terminalState.value.unresolvedOrdersCount,
                    currentMode = currentExecutionMode(),
                    ownerConfirmed = explicitLiveConfirmation
                )
                if (!gateReport.prerequisitesReady) {
                    _snackbarMessage.value = "โหมด LIVE ถูกล็อก: ${gateReport.blockingReasons.firstOrNull() ?: "ยังไม่ผ่าน Live Readiness Gate"}"
                    return@launch
                }
                if (!explicitLiveConfirmation) {
                    _snackbarMessage.value = "กรุณายืนยันผ่านกล่อง Live Readiness 2 ขั้นตอนก่อนเปิดโหมด LIVE"
                    return@launch
                }
            }
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (backendClient.isValidHttpUrl(baseUrl) && _backendHealth.value.connected) {
                val res = backendClient.switchMode(
                    baseUrl = baseUrl,
                    bearerToken = credentialVault.getBackendToken(),
                    mode = targetMode,
                    explicitLiveConfirmation = explicitLiveConfirmation
                )
                if (res.isFailure) {
                    _snackbarMessage.value = "Backend ปฏิเสธการสลับโหมด: ${res.exceptionOrNull()?.localizedMessage}"
                    return@launch
                }
            }
            localRepository.updateUserBalanceAndMode(currentUserId, currentBalance(), targetMode, markSavedTimestamp = true)
                .onSuccess { _snackbarMessage.value = "สลับโหมดเป็น ${targetMode.label} แล้ว" }
        }
    }

    // --- Phase 8: Performance Mode Filter, Backtesting & System Diagnostics ---

    fun setAnalyticsModeFilter(mode: String) {
        _analyticsModeFilter.value = mode
        viewModelScope.launch {
            val baseUrl = integrationSettings.value.backendBaseUrl
            if (_backendHealth.value.connected && backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.fetchTradingPerformance(baseUrl, credentialVault.getBackendToken(), mode)
            }
        }
    }

    fun runWalkForwardBacktest(
        instrument: String,
        timeframe: Timeframe,
        strategyId: String,
        stakeAmount: Double,
        lookbackBars: Int = 360
    ) {
        viewModelScope.launch {
            _isBacktestBusy.value = true
            val baseUrl = integrationSettings.value.backendBaseUrl
            val token = credentialVault.getBackendToken()
            val clampedBars = lookbackBars.coerceIn(60, 1000)
            if (_backendHealth.value.connected && backendClient.isValidHttpUrl(baseUrl)) {
                val remote = backendClient.runBacktest(
                    baseUrl = baseUrl,
                    bearerToken = token,
                    instrument = instrument,
                    timeframe = timeframe.code,
                    strategyId = strategyId,
                    stakeAmount = stakeAmount,
                    lookbackBars = clampedBars
                )
                if (remote.isSuccess) {
                    _backtestReport.value = remote.getOrNull()
                    _isBacktestBusy.value = false
                    _snackbarMessage.value = "รัน Walk-Forward Backtest (FastAPI, $clampedBars แท่ง) เสร็จสิ้น"
                    return@launch
                }
            }

            val localCandles = if (_terminalState.value.selectedInstrument == instrument &&
                _terminalState.value.selectedTimeframe == timeframe &&
                _terminalState.value.candles.size >= 80
            ) {
                _terminalState.value.candles.takeLast(clampedBars)
            } else {
                simEngine.generateInitialCandles(instrument, timeframe.defaultExpirySec, clampedBars)
            }
            val spec = SUPPORTED_INSTRUMENTS.firstOrNull { it.symbol == instrument } ?: SUPPORTED_INSTRUMENTS.first()
            LocalBacktestEngine.runBacktest(
                candles = localCandles,
                instrument = instrument,
                timeframe = timeframe.code,
                strategyId = strategyId,
                stakeAmount = stakeAmount,
                payoutRate = spec.payoutRate
            ).onSuccess { report ->
                _backtestReport.value = report
                _snackbarMessage.value = "รัน Backtest บนข้อมูล Local SQLite (${report.totalTrades} ไม้) เสร็จสิ้น"
            }.onFailure { err ->
                _snackbarMessage.value = "Backtest ไม่สำเร็จ: ${err.localizedMessage}"
            }
            _isBacktestBusy.value = false
        }
    }

    fun runSystemDiagnostics() {
        viewModelScope.launch {
            _isDiagnosticsBusy.value = true
            val vaultOk = credentialVault.verifyKeystoreReady()
            val term = _terminalState.value
            val dq = term.dataQuality ?: MultiTimeframeLocalEngine.inspectDataQuality(term.candles)
            val localReport = SystemDiagnosticsRunner.runLocalDiagnostics(
                dbStats = databaseStats.value,
                candles = term.candles,
                dataQuality = dq,
                riskPolicy = currentRiskPolicy(),
                backendHealth = _backendHealth.value,
                wsState = term.wsState,
                settings = integrationSettings.value,
                activeMlModel = _activeMlModel.value,
                unresolvedOrdersCount = term.unresolvedOrdersCount,
                currentMode = currentExecutionMode(),
                vaultRoundTripOk = vaultOk
            )

            val baseUrl = integrationSettings.value.backendBaseUrl
            val finalReport = if (_backendHealth.value.connected && backendClient.isValidHttpUrl(baseUrl)) {
                val remote = backendClient.fetchSystemDiagnostics(
                    baseUrl = baseUrl,
                    bearerToken = credentialVault.getBackendToken(),
                    instrument = term.selectedInstrument,
                    timeframe = term.selectedTimeframe.code
                ).getOrNull()
                if (remote != null) {
                    val localOnly = localReport.checks.filter { it.checkId in setOf("local_sqlite", "keystore_vault", "risk_fail_closed") }
                    val mergedChecks = localOnly + remote.checks.filterNot { r -> localOnly.any { it.checkId == r.checkId } }
                    val overall = when {
                        mergedChecks.any { it.status == DiagnosticCheckStatus.FAIL } -> DiagnosticCheckStatus.FAIL
                        mergedChecks.any { it.status == DiagnosticCheckStatus.WARN } -> DiagnosticCheckStatus.WARN
                        else -> DiagnosticCheckStatus.PASS
                    }
                    remote.copy(overallStatus = overall, checks = mergedChecks)
                } else {
                    localReport
                }
            } else {
                localReport
            }

            _diagnosticsReport.value = finalReport
            _isDiagnosticsBusy.value = false
            _snackbarMessage.value = "ตรวจ System Diagnostics เสร็จสิ้น: ${finalReport.overallStatus.label}"
        }
    }

    fun runBrokerDryRun() {
        viewModelScope.launch {
            _isDryRunBusy.value = true
            val term = _terminalState.value
            val baseUrl = integrationSettings.value.backendBaseUrl
            val token = credentialVault.getBackendToken()
            val report = if (_backendHealth.value.connected && backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.runBrokerDryRun(
                    baseUrl = baseUrl,
                    bearerToken = token,
                    instrument = term.selectedInstrument
                ).getOrElse { err ->
                    BrokerDryRunReport(
                        connectorName = _backendHealth.value.connectorName,
                        connected = false,
                        dryRunPassed = false,
                        liveAuthorized = false,
                        simulationLocked = true,
                        latencyMs = 0.0,
                        marketPrice = term.candles.lastOrNull()?.close ?: 0.0,
                        balance = currentBalance(),
                        capabilities = _backendHealth.value.supportedCapabilities,
                        statusDetail = "Broker Dry-Run ไม่สำเร็จ (SIM Locked): ${SecretRedactor.redact(err.localizedMessage, token)}"
                    )
                }
            } else {
                BrokerDryRunReport(
                    connectorName = "Local Simulation Engine",
                    connected = false,
                    dryRunPassed = term.candles.isNotEmpty(),
                    liveAuthorized = false,
                    simulationLocked = true,
                    latencyMs = 1.0,
                    marketPrice = term.candles.lastOrNull()?.close ?: 0.0,
                    balance = currentBalance(),
                    capabilities = listOf("SIMULATION_STREAM", "TECHNICAL_ANALYSIS", "PAPER_ORDERS"),
                    statusDetail = "Local Simulation Engine พร้อมทำงานแบบ Non-Executing • ยังไม่ได้เชื่อมต่อ Remote Broker และล็อกที่ SIMULATION"
                )
            }
            _brokerDryRunReport.value = report
            _isDryRunBusy.value = false
            _snackbarMessage.value = if (report.dryRunPassed) {
                "Broker Dry-Run ผ่าน (${report.connectorName} • ${String.format("%.1f", report.latencyMs)}ms • SIM Locked)"
            } else {
                "Broker Dry-Run ไม่ผ่าน — คงโหมด SIMULATION ไว้ปลอดภัย"
            }
        }
    }

    fun resetSimulationBalance() {
        viewModelScope.launch {
            localRepository.updateUserBalanceAndMode(currentUserId, 10000.0, ExecutionMode.SIMULATION, markSavedTimestamp = true)
                .onSuccess { _snackbarMessage.value = "รีเซ็ตยอด Simulation Balance ใน SQLite เป็น $10,000.00 แล้ว" }
        }
    }

    fun updateLocalTraderName(displayName: String) {
        viewModelScope.launch {
            localRepository.updateDisplayName(currentUserId, displayName)
                .onSuccess { _snackbarMessage.value = "บันทึกชื่อโปรไฟล์ลง SQLite แล้ว" }
                .onFailure { _snackbarMessage.value = "บันทึกชื่อโปรไฟล์ไม่สำเร็จ: ${it.localizedMessage}" }
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
            val willHaveToken = !newBackendToken.isNullOrBlank() ||
                !credentialVault.getBackendToken().isNullOrBlank()
            if (backendBaseUrl.isNotBlank() && !backendClient.isSafeTokenTransport(backendBaseUrl, willHaveToken)) {
                _snackbarMessage.value = "คำเตือนความปลอดภัย: ต้องใช้ https:// เมื่อส่ง Bearer Token ไปยังโฮสต์ภายนอก"
                return@launch
            }
            if (backendWsUrl.isNotBlank() && !backendClient.isSafeTokenTransport(backendWsUrl, willHaveToken)) {
                _snackbarMessage.value = "คำเตือนความปลอดภัย: ต้องใช้ wss:// เมื่อส่ง Bearer Token ไปยัง WebSocket ภายนอก"
                return@launch
            }
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
            _snackbarMessage.value = "เข้ารหัส Keystore AES-256-GCM และบันทึกการตั้งค่าแล้ว"
            testBackendConnection()
            startMarketStream()
        }
    }

    fun connectPracticeAccount(email: String, password: String, rememberCredentials: Boolean) {
        viewModelScope.launch {
            val settings = integrationSettings.value
            val baseUrl = settings.backendBaseUrl
            val token = credentialVault.getBackendToken()
            if (!backendClient.isValidHttpUrl(baseUrl) || token.isNullOrBlank()) {
                _snackbarMessage.value = "กรุณาตั้งค่า FastAPI URL และ Backend Bearer Token ก่อนเชื่อมต่อ Practice"
                return@launch
            }
            if (!backendClient.isSafeTokenTransport(baseUrl, hasToken = true)) {
                _snackbarMessage.value = "การส่งข้อมูลบัญชีต้องใช้ HTTPS หรือ localhost เท่านั้น"
                return@launch
            }
            if (rememberCredentials) credentialVault.saveIqOptionCredentials(email, password)
            else credentialVault.clearIqOptionCredentials()

            _terminalState.update { it.copy(connectionState = "CONNECTING", sessionVerified = false) }
            val result = backendClient.connectIqOptionPractice(baseUrl, token, email, password)
            result.onSuccess { connected ->
                if (!connected.connected || !connected.session_verified || connected.account_mode != "PRACTICE" || connected.live_authorized) {
                    _terminalState.update { it.copy(connectionState = "UNVERIFIED", sessionVerified = false, accountMode = "UNVERIFIED") }
                    _snackbarMessage.value = "Broker ตอบกลับแต่ไม่สามารถยืนยัน Practice Session ได้ — ปิดการส่งคำสั่ง"
                    return@onSuccess
                }
                val localSimulationBalance = (profileState.value as? LocalUiState.Success)?.data?.simulationBalance ?: 10000.0
                localRepository.updateUserBalanceAndMode(
                    currentUserId, localSimulationBalance, ExecutionMode.DEMO, markSavedTimestamp = true
                )
                _terminalState.update {
                    it.copy(
                        accountMode = "PRACTICE",
                        accountDisplayLabel = "IQ Option Practice",
                        brokerBalance = connected.balance,
                        connectionState = "CONNECTED",
                        sessionVerified = true,
                        realAccountLocked = true
                    )
                }
                _snackbarMessage.value = "เชื่อมต่อ IQ Option Practice สำเร็จ • Balance: ${format2(connected.balance)}"
                refreshBackendExecutionState(baseUrl, token)
            }.onFailure { error ->
                _terminalState.update { it.copy(connectionState = "DISCONNECTED", sessionVerified = false, accountMode = "UNVERIFIED") }
                _snackbarMessage.value = "เชื่อมต่อ Practice ไม่สำเร็จ: ${SecretRedactor.redact(error.localizedMessage, token)}"
            }
        }
    }

    fun disconnectPracticeAccount() {
        viewModelScope.launch {
            val settings = integrationSettings.value
            val baseUrl = settings.backendBaseUrl
            val token = credentialVault.getBackendToken()
            if (backendClient.isValidHttpUrl(baseUrl)) {
                backendClient.disconnectIqOptionPractice(baseUrl, token)
            }
            val localSimulationBalance = (profileState.value as? LocalUiState.Success)?.data?.simulationBalance ?: 10000.0
            localRepository.updateUserBalanceAndMode(
                currentUserId, localSimulationBalance, ExecutionMode.SIMULATION, markSavedTimestamp = true
            )
            _terminalState.update {
                it.copy(
                    accountMode = "DISCONNECTED",
                    accountDisplayLabel = "Disconnected",
                    brokerBalance = null,
                    connectionState = "DISCONNECTED",
                    sessionVerified = false,
                    assetCatalog = emptyList(),
                    assetCatalogSource = "DISCONNECTED"
                )
            }
            _snackbarMessage.value = "ตัดการเชื่อมต่อ IQ Option Practice แล้ว"
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
                refreshBackendExecutionState(url, token)
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
        localRepository.updateUserBalanceAndMode(
            userId = currentUserId,
            newBalance = updatedBal,
            mode = currentExecutionMode(),
            markSavedTimestamp = true
        )

        val dateKey = localRepository.todayDateKey()
        val existing = todaySummary()
        val newNetPnl = (existing?.netPnl ?: 0.0) + pnl
        val currentDrawdown = if (newNetPnl < 0) -newNetPnl else 0.0
        val summary = DailyPnLSummary(
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
        localRepository.upsertDailySummary(summary)
    }

    private fun finishPipeline(stage: ExecutionStage, message: String) {
        _terminalState.update {
            it.copy(executionStage = stage, isOrderInFlight = false, lastOrderFeedback = message)
        }
        _snackbarMessage.value = message
    }

    private fun activeStrategy(): StrategyConfig {
        val list = (strategiesState.value as? LocalUiState.Success)?.data.orEmpty()
        return list.firstOrNull { it.active } ?: list.firstOrNull() ?: StrategyConfig(userId = currentUserId)
    }

    private fun currentRiskPolicy(): RiskPolicy =
        (riskPolicyState.value as? LocalUiState.Success)?.data ?: RiskPolicy(userId = currentUserId)

    private fun currentTrades(): List<TradeRecord> =
        (tradesState.value as? LocalUiState.Success)?.data.orEmpty()

    private fun todaySummary(): DailyPnLSummary? {
        val key = localRepository.todayDateKey()
        return (dailySummariesState.value as? LocalUiState.Success)?.data.orEmpty().firstOrNull { it.dateKey == key }
    }

    private fun currentBalance(): Double =
        (profileState.value as? LocalUiState.Success)?.data?.simulationBalance ?: 10000.0

    private fun currentExecutionMode(): ExecutionMode =
        ExecutionMode.safeValueOf((profileState.value as? LocalUiState.Success)?.data?.executionMode)

    private fun unwrapSqlError(error: Throwable, fallback: String): String {
        if (error is CancellationException) throw error
        Log.w(TAG, "Local SQLite stream error", error)
        return error.localizedMessage ?: fallback
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
