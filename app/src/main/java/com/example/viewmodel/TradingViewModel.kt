package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CachedCandleEntity
import com.example.data.local.LocalTradeCacheEntity
import com.example.data.local.TradingDao
import com.example.data.remote.BackendConnectorClient
import com.example.data.remote.OrderSubmitRequestDto
import com.example.data.remote.TelegramNotifier
import com.example.data.repository.TradingCloudRepository
import com.example.engine.MarketSimulationEngine
import com.example.engine.RiskManagementEngine
import com.example.engine.SignalEngine
import com.example.engine.TechnicalAnalysisEngine
import com.example.model.BackendHealthState
import com.example.model.Candle
import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.ExecutionStage
import com.example.model.IntegrationSettings
import com.example.model.RiskPolicy
import com.example.model.RiskValidationResult
import com.example.model.SUPPORTED_INSTRUMENTS
import com.example.model.SignalAnalysis
import com.example.model.StrategyConfig
import com.example.model.TechnicalSnapshot
import com.example.model.Timeframe
import com.example.model.TradeDirection
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.model.UserProfile
import com.example.security.SecureCredentialVault
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
    val riskValidation: RiskValidationResult = RiskValidationResult(true, "พร้อมตรวจสอบความเสี่ยง", 30, 250.0),
    val executionStage: ExecutionStage = ExecutionStage.IDLE,
    val lastOrderFeedback: String? = null,
    val isOrderInFlight: Boolean = false,
    val lastLossTimestampMillis: Long? = null,
    val autoTradeCooldownSec: Int = 0
)

class TradingViewModel(
    private val cloudRepository: TradingCloudRepository,
    private val tradingDao: TradingDao,
    private val credentialVault: SecureCredentialVault,
    private val telegramNotifier: TelegramNotifier,
    private val backendClient: BackendConnectorClient,
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

    val integrationSettings: StateFlow<IntegrationSettings> = credentialVault.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = IntegrationSettings()
        )

    val profileState: StateFlow<CloudUiState<UserProfile?>> =
        cloudRepository.observeUserProfile(currentUserId)
            .map<UserProfile?, CloudUiState<UserProfile?>> { CloudUiState.Success(it) }
            .catch { error ->
                emit(CloudUiState.Error(unwrapFirestoreError(error, "โหลดข้อมูลโปรไฟล์ไม่สำเร็จ")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val strategiesState: StateFlow<CloudUiState<List<StrategyConfig>>> =
        cloudRepository.observeStrategies(currentUserId)
            .map<List<StrategyConfig>, CloudUiState<List<StrategyConfig>>> { CloudUiState.Success(it) }
            .catch { error ->
                emit(CloudUiState.Error(unwrapFirestoreError(error, "โหลดกลยุทธ์จากคลาวด์ไม่สำเร็จ")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val riskPolicyState: StateFlow<CloudUiState<RiskPolicy?>> =
        cloudRepository.observeRiskPolicy(currentUserId)
            .map<RiskPolicy?, CloudUiState<RiskPolicy?>> { CloudUiState.Success(it) }
            .catch { error ->
                emit(CloudUiState.Error(unwrapFirestoreError(error, "โหลดนโยบายความเสี่ยงไม่สำเร็จ")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val tradesState: StateFlow<CloudUiState<List<TradeRecord>>> =
        cloudRepository.observeTrades(currentUserId)
            .map<List<TradeRecord>, CloudUiState<List<TradeRecord>>> { CloudUiState.Success(it) }
            .catch { error ->
                emit(CloudUiState.Error(unwrapFirestoreError(error, "โหลดประวัติการเทรดไม่สำเร็จ")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val dailySummariesState: StateFlow<CloudUiState<List<DailyPnLSummary>>> =
        cloudRepository.observeDailySummaries(currentUserId)
            .map<List<DailyPnLSummary>, CloudUiState<List<DailyPnLSummary>>> { CloudUiState.Success(it) }
            .catch { error ->
                emit(CloudUiState.Error(unwrapFirestoreError(error, "โหลดสรุปผลรายวันไม่สำเร็จ")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    private var marketFeedJob: Job? = null
    private var autoCycleCounter = 0

    init {
        viewModelScope.launch {
            cloudRepository.ensureUserBootstrap(currentUserId, userDisplayName, userEmail)
                .onFailure { err ->
                    _snackbarMessage.value = "ซิงค์เริ่มต้นไม่สำเร็จ: ${err.localizedMessage}"
                }
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
                executionStage = ExecutionStage.IDLE
            )
        }
        startMarketStream()
    }

    private fun startMarketStream() {
        marketFeedJob?.cancel()
        marketFeedJob = viewModelScope.launch {
            val instrument = _terminalState.value.selectedInstrument
            val tf = _terminalState.value.selectedTimeframe
            var candles = simEngine.generateInitialCandles(instrument, tf.defaultExpirySec, 80)

            // Try pulling candles from FastAPI if configured
            val settings = integrationSettings.value
            if (settings.backendBaseUrl.isNotBlank()) {
                val remoteCandles = backendClient.fetchCandles(
                    baseUrl = settings.backendBaseUrl,
                    bearerToken = credentialVault.getBackendToken(),
                    instrument = instrument,
                    timeframe = tf.code
                )
                remoteCandles.getOrNull()?.takeIf { it.size >= 25 }?.let { candles = it }
            }

            cacheCandlesToRoom(instrument, tf.code, candles)
            recomputeAnalysis(candles)

            while (isActive) {
                delay(1200L)
                val nextCandles = simEngine.nextTick(
                    currentCandles = _terminalState.value.candles,
                    instrumentSymbol = _terminalState.value.selectedInstrument,
                    timeframeSeconds = _terminalState.value.selectedTimeframe.defaultExpirySec
                )
                recomputeAnalysis(nextCandles)

                autoCycleCounter++
                val activePolicy = currentRiskPolicy()
                if (activePolicy.autoTradeEnabled && !activePolicy.emergencyStopped &&
                    !_terminalState.value.isOrderInFlight && autoCycleCounter % 5 == 0
                ) {
                    runAutomatedTradeCycle()
                }
            }
        }
    }

    private fun recomputeAnalysis(candles: List<Candle>) {
        val strategy = activeStrategy()
        val snapshot = TechnicalAnalysisEngine.analyze(candles, strategy)
        val signal = SignalEngine.evaluate(candles, snapshot, strategy)
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
                riskValidation = riskCheck
            )
        }
    }

    fun executeManualOrder(requestedDirection: TradeDirection? = null) {
        viewModelScope.launch {
            runTradePipeline(forcedDirection = requestedDirection, isAuto = false)
        }
    }

    private suspend fun runAutomatedTradeCycle() {
        runTradePipeline(forcedDirection = null, isAuto = true)
    }

    private suspend fun runTradePipeline(forcedDirection: TradeDirection?, isAuto: Boolean) {
        if (_terminalState.value.isOrderInFlight) return
        _terminalState.update { it.copy(isOrderInFlight = true, executionStage = ExecutionStage.MARKET_DATA) }

        try {
            val candles = _terminalState.value.candles
            delay(120)
            _terminalState.update { it.copy(executionStage = ExecutionStage.DATA_VALIDATION) }
            if (candles.size < 25) {
                finishPipeline(ExecutionStage.IDLE, "ข้อมูลแท่งเทียนยังไม่พร้อมสำหรับการตัดสินใจ")
                return
            }

            val strategy = activeStrategy()
            _terminalState.update { it.copy(executionStage = ExecutionStage.MARKET_ANALYSIS) }
            val snapshot = TechnicalAnalysisEngine.analyze(candles, strategy)

            _terminalState.update { it.copy(executionStage = ExecutionStage.AI_SIGNAL) }
            val signal = SignalEngine.evaluate(candles, snapshot, strategy)
            val directionToUse = forcedDirection ?: signal.direction

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
                    dispatchTelegramIfEnabled(
                        isRiskAlert = true,
                        title = "RISK GUARD LOCKOUT",
                        lines = listOf(
                            "สินทรัพย์: ${strategy.instrument}",
                            "เหตุผล: ${riskResult.reason}",
                            "ระบบหยุด Auto-Trade อัตโนมัติแล้ว"
                        )
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
                confidenceScore = signal.confidenceScore,
                calibratedProbability = signal.calibratedProbability,
                explanation = signal.explanation
            )

            val createdId = cloudRepository.createTrade(pendingTrade)
                .getOrElse { "local_${System.currentTimeMillis()}" }

            if (integrationSettings.value.notifyOnSignal) {
                dispatchTelegramIfEnabled(
                    isSignal = true,
                    title = "IQ TRADING SIGNAL • ${directionToUse.name}",
                    lines = listOf(
                        "คู่เงิน: ${strategy.instrument} (${_terminalState.value.selectedTimeframe.code})",
                        "โหมด: ${mode.name} | วงเงิน: $${format2(strategy.tradeAmount)}",
                        "ราคาเข้า: ${formatPrice(entryPrice)} | Confidence: ${signal.confidenceScore}%",
                        "เหตุผล: ${signal.explanation}"
                    )
                )
            }

            _terminalState.update { it.copy(executionStage = ExecutionStage.RESULT_VERIFICATION) }
            // Fast simulation settlement window (~3 seconds for responsive mobile verification)
            repeat(3) {
                delay(1000L)
                val updated = simEngine.nextTick(
                    _terminalState.value.candles,
                    strategy.instrument,
                    _terminalState.value.selectedTimeframe.defaultExpirySec
                )
                recomputeAnalysis(updated)
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
                        confidenceScore = signal.confidenceScore,
                        calibratedProbability = signal.calibratedProbability,
                        explanation = signal.explanation,
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

            if (integrationSettings.value.notifyOnTradeResult) {
                val resultBadge = if (won) "WON (+$${format2(pnl)})" else "LOST (-$${format2(strategy.tradeAmount)})"
                dispatchTelegramIfEnabled(
                    isTradeResult = true,
                    title = "TRADE SETTLED • $resultBadge",
                    lines = listOf(
                        "สินทรัพย์: ${strategy.instrument} | ${directionToUse.name}",
                        "เข้า: ${formatPrice(entryPrice)} -> ออก: ${formatPrice(exitPrice)}",
                        "ยอดคงเหลือ Simulation: $${format2(currentBalance() + pnl)}"
                    )
                )
            }

            val feedbackMsg = if (won) {
                "ปิดออเดอร์ ${directionToUse.name} ชนะ +$${format2(pnl)}"
            } else {
                "ปิดออเดอร์ ${directionToUse.name} ขาดทุน -$${format2(strategy.tradeAmount)}"
            }
            finishPipeline(ExecutionStage.IDLE, feedbackMsg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Execution pipeline error", e)
            finishPipeline(ExecutionStage.IDLE, "เกิดข้อผิดพลาดในวงจรคำสั่ง: ${e.localizedMessage}")
        }
    }

    private suspend fun updateProfileAndDailySummary(pnl: Double, won: Boolean) {
        val currentBal = currentBalance()
        val updatedBal = (currentBal + pnl).coerceAtLeast(0.0)
        cloudRepository.updateUserBalanceAndMode(
            simulationBalance = updatedBal,
            executionMode = currentExecutionMode(),
            markBackupTimestamp = true
        )

        val dateKey = cloudRepository.todayDateKey()
        val existing = todaySummary()
        val newTotal = (existing?.totalTrades ?: 0) + 1
        val newWins = (existing?.wins ?: 0) + if (won) 1 else 0
        val newLosses = (existing?.losses ?: 0) + if (won) 0 else 1
        val newNetPnl = (existing?.netPnl ?: 0.0) + pnl
        val newConsec = if (won) 0 else (existing?.consecutiveLosses ?: 0) + 1
        val currentDrawdown = if (newNetPnl < 0) -newNetPnl else 0.0
        val newPeakDrawdown = maxOf(existing?.peakDrawdown ?: 0.0, currentDrawdown)

        val updatedSummary = DailyPnLSummary(
            id = dateKey,
            userId = currentUserId,
            dateKey = dateKey,
            totalTrades = newTotal,
            wins = newWins,
            losses = newLosses,
            netPnl = newNetPnl,
            consecutiveLosses = newConsec,
            peakDrawdown = newPeakDrawdown
        )
        cloudRepository.upsertDailySummary(updatedSummary)
    }

    private fun finishPipeline(stage: ExecutionStage, message: String) {
        _terminalState.update {
            it.copy(
                executionStage = stage,
                isOrderInFlight = false,
                lastOrderFeedback = message
            )
        }
        _snackbarMessage.value = message
    }

    fun toggleAutoTrade(enabled: Boolean) {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            if (enabled && policy.emergencyStopped) {
                _snackbarMessage.value = "กรุณาปลดล็อก Emergency Stop ก่อนเปิด Auto-Trade"
                return@launch
            }
            cloudRepository.saveRiskPolicy(policy.copy(autoTradeEnabled = enabled))
                .onSuccess {
                    _snackbarMessage.value = if (enabled) "เปิดระบบเทรดอัตโนมัติ (SIMULATION)" else "ปิดระบบเทรดอัตโนมัติแล้ว"
                }
                .onFailure {
                    _snackbarMessage.value = "อัปเดตสถานะ Auto-Trade ไม่สำเร็จ"
                }
        }
    }

    fun triggerEmergencyStop() {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            cloudRepository.saveRiskPolicy(
                policy.copy(emergencyStopped = true, autoTradeEnabled = false)
            ).onSuccess {
                _snackbarMessage.value = "EMERGENCY STOP ทำงานแล้ว — ระงับคำสั่งซื้อขายทั้งหมด"
                dispatchTelegramIfEnabled(
                    isRiskAlert = true,
                    title = "EMERGENCY STOP ACTIVATED",
                    lines = listOf(
                        "ผู้ใช้สั่งหยุดฉุกเฉินผ่าน Mobile Terminal",
                        "ระบบได้ปิด Auto-Trade และระงับคำสั่งซื้อขายทั้งหมดทันที"
                    )
                )
            }
        }
    }

    fun resetEmergencyStop() {
        viewModelScope.launch {
            val policy = currentRiskPolicy()
            cloudRepository.saveRiskPolicy(policy.copy(emergencyStopped = false))
                .onSuccess {
                    _snackbarMessage.value = "ปลดล็อก Emergency Stop แล้ว (Auto-Trade ยังคงปิดอยู่)"
                }
        }
    }

    fun saveStrategyConfig(strategy: StrategyConfig) {
        viewModelScope.launch {
            cloudRepository.saveStrategy(strategy)
                .onSuccess {
                    _snackbarMessage.value = "บันทึกกลยุทธ์ '${strategy.name}' และซิงค์คลาวด์แล้ว"
                }
                .onFailure { err ->
                    _snackbarMessage.value = "บันทึกกลยุทธ์ไม่สำเร็จ: ${err.localizedMessage}"
                }
        }
    }

    fun saveRiskPolicyConfig(policy: RiskPolicy) {
        viewModelScope.launch {
            cloudRepository.saveRiskPolicy(policy)
                .onSuccess {
                    _snackbarMessage.value = "บันทึกนโยบาย Risk Management แล้ว"
                }
                .onFailure { err ->
                    _snackbarMessage.value = "บันทึกนโยบายไม่สำเร็จ: ${err.localizedMessage}"
                }
        }
    }

    fun switchExecutionMode(targetMode: ExecutionMode) {
        viewModelScope.launch {
            if (targetMode == ExecutionMode.LIVE && !_backendHealth.value.liveAuthorized) {
                _snackbarMessage.value = "โหมด LIVE ถูกล็อก: ต้องเชื่อมต่อและยืนยัน FastAPI Connector ก่อน"
                return@launch
            }
            cloudRepository.updateUserBalanceAndMode(
                simulationBalance = currentBalance(),
                executionMode = targetMode
            ).onSuccess {
                _snackbarMessage.value = "สลับโหมดเป็น ${targetMode.label} แล้ว"
            }
        }
    }

    fun resetSimulationBalance() {
        viewModelScope.launch {
            cloudRepository.updateUserBalanceAndMode(
                simulationBalance = 10000.0,
                executionMode = ExecutionMode.SIMULATION
            ).onSuccess {
                _snackbarMessage.value = "รีเซ็ตยอด Simulation Balance เป็น $10,000.00 แล้ว"
            }
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
                newTelegramBotToken = newTelegramBotToken
            )
            _snackbarMessage.value = "เข้ารหัสและบันทึกการตั้งค่าแล้ว"
            testBackendConnection()
        }
    }

    fun testBackendConnection() {
        viewModelScope.launch {
            _backendHealth.update { it.copy(checking = true) }
            val url = integrationSettings.value.backendBaseUrl
            val health = backendClient.checkHealth(url, credentialVault.getBackendToken())
            _backendHealth.value = health
            _snackbarMessage.value = health.statusMessage
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
                    "สถานะการเชื่อมต่อ Telegram Bot: ปกติ",
                    "โหมดปัจจุบัน: ${currentExecutionMode().name}",
                    "P&L วันนี้: $${format2(summary?.netPnl ?: 0.0)} (${summary?.totalTrades ?: 0} ไม้)"
                )
            )
            _snackbarMessage.value = result.getOrElse { it.localizedMessage ?: "ส่งข้อความทดสอบไม่สำเร็จ" }
        }
    }

    fun syncCloudBackupNow() {
        viewModelScope.launch {
            _isSyncingCloud.value = true
            cloudRepository.updateUserBalanceAndMode(
                simulationBalance = currentBalance(),
                executionMode = currentExecutionMode(),
                markBackupTimestamp = true
            ).onSuccess {
                _snackbarMessage.value = "สำรองข้อมูลกลยุทธ์และประวัติขึ้น Cloud Firestore สำเร็จ"
            }.onFailure { err ->
                _snackbarMessage.value = "สำรองข้อมูลไม่สำเร็จ: ${err.localizedMessage}"
            }
            _isSyncingCloud.value = false
        }
    }

    private fun dispatchTelegramIfEnabled(
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

        val token = credentialVault.getTelegramBotToken()
        if (token.isNullOrBlank() || cfg.telegramChatId.isBlank()) return

        viewModelScope.launch {
            telegramNotifier.sendAlert(token, cfg.telegramChatId, title, lines)
        }
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

    private fun currentRiskPolicy(): RiskPolicy {
        return (riskPolicyState.value as? CloudUiState.Success)?.data ?: RiskPolicy(userId = currentUserId)
    }

    private fun currentTrades(): List<TradeRecord> {
        return (tradesState.value as? CloudUiState.Success)?.data.orEmpty()
    }

    private fun todaySummary(): DailyPnLSummary? {
        val key = cloudRepository.todayDateKey()
        val summaries = (dailySummariesState.value as? CloudUiState.Success)?.data.orEmpty()
        return summaries.firstOrNull { it.dateKey == key }
    }

    private fun currentBalance(): Double {
        return (profileState.value as? CloudUiState.Success)?.data?.simulationBalance ?: 10000.0
    }

    private fun currentExecutionMode(): ExecutionMode {
        val raw = (profileState.value as? CloudUiState.Success)?.data?.executionMode
        return ExecutionMode.safeValueOf(raw)
    }

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
