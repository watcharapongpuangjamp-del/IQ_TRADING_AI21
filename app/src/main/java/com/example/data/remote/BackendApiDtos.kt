package com.example.data.remote

import com.example.model.AlignmentState
import com.example.model.BackendOrderSummary
import com.example.model.BacktestReport
import com.example.model.BacktestTradeSample
import com.example.model.BrokerDryRunReport
import com.example.model.IQAirReport
import com.example.model.Candle
import com.example.model.DataQualitySummary
import com.example.model.DiagnosticCheckItem
import com.example.model.DiagnosticCheckStatus
import com.example.model.DriftStatus
import com.example.model.ExecutionMode
import com.example.model.LiveReadinessGateItem
import com.example.model.LiveReadinessReport
import com.example.model.MarketRegime
import com.example.model.MlDriftReport
import com.example.model.MlModelSummary
import com.example.model.ModePerformanceSummary
import com.example.model.MultiTimeframeReport
import com.example.model.OrderLifecycleEvent
import com.example.model.OrderLifecycleState
import com.example.model.PerformanceAnalyticsReport
import com.example.model.PerformanceBreakdownRow
import com.example.model.ReconcileSummary
import com.example.model.ReplaySessionState
import com.example.model.RiskPolicy
import com.example.model.SignalAnalysis
import com.example.model.StrategyConfig
import com.example.model.StrategyModuleSummary
import com.example.model.StrategySelectionReport
import com.example.model.SystemDiagnosticsReport
import com.example.model.TimeframeIntelligenceSummary
import com.example.model.TradeDirection
import com.squareup.moshi.JsonClass

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
) {
    fun toDomain(): Candle = Candle(
        timestampMillis = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )
}

@JsonClass(generateAdapter = true)
data class WebSocketTickDto(
    val type: String = "tick",
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val payout_rate: Double = 0.85,
    val candle: CandleDto? = null
)

@JsonClass(generateAdapter = true)
data class TechnicalSnapshotDto(
    val rsi: Double = 50.0,
    val macd_line: Double = 0.0,
    val macd_signal: Double = 0.0,
    val macd_histogram: Double = 0.0,
    val ema_fast: Double = 0.0,
    val ema_slow: Double = 0.0,
    val sma_fast: Double = 0.0,
    val sma_slow: Double = 0.0,
    val bb_upper: Double = 0.0,
    val bb_middle: Double = 0.0,
    val bb_lower: Double = 0.0,
    val atr: Double = 0.0,
    val volatility_pct: Double = 0.0,
    val trend_strength: Double = 0.0,
    val support_level: Double = 0.0,
    val resistance_level: Double = 0.0,
    val candlestick_pattern: String = "Neutral",
    val regime: String = MarketRegime.SIDEWAYS.name,
    val adx: Double = 0.0,
    val plus_di: Double = 0.0,
    val minus_di: Double = 0.0,
    val stochastic_k: Double = 50.0,
    val stochastic_d: Double = 50.0,
    val vwap: Double = 0.0,
    val vwap_reliable: Boolean = false
)

@JsonClass(generateAdapter = true)
data class SignalResponseDto(
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val direction: String = "NO_TRADE",
    val calibrated_probability: Double = 0.5,
    val confidence_score: Int = 50,
    val supporting_evidence: List<String> = emptyList(),
    val contradicting_evidence: List<String> = emptyList(),
    val market_regime: String = MarketRegime.SIDEWAYS.name,
    val risk_assessment: String = "",
    val explanation: String = "",
    val expires_at_ms: Long = 0L,
    val technical_snapshot: TechnicalSnapshotDto? = null
) {
    fun toDomain(sourceLabel: String = "FastAPI Calibrated Intelligence"): SignalAnalysis {
        val parsedRegime = MarketRegime.safeValueOf(market_regime)
        val inferredLabel = when {
            explanation.contains("ML", ignoreCase = true) ||
                supporting_evidence.any { it.contains("ML", ignoreCase = true) } -> "FastAPI Calibrated ML"
            else -> sourceLabel
        }
        return SignalAnalysis(
            instrument = instrument,
            timeframe = timeframe,
            direction = TradeDirection.safeValueOf(direction),
            calibratedProbability = calibrated_probability.coerceIn(0.0, 1.0),
            confidenceScore = confidence_score.coerceIn(0, 100),
            supportingEvidence = supporting_evidence,
            contradictingEvidence = contradicting_evidence,
            regime = parsedRegime,
            riskAssessment = risk_assessment,
            explanation = explanation,
            expiresAtMillis = expires_at_ms,
            sourceLabel = inferredLabel
        )
    }
}

@JsonClass(generateAdapter = true)
data class DataQualityReportDto(
    val valid: Boolean = true,
    val reason: String = "Passed OHLCV validation",
    val candle_count: Int = 0,
    val gap_count: Int = 0,
    val outlier_count: Int = 0,
    val duplicate_count: Int = 0
) {
    fun toDomain(): DataQualitySummary = DataQualitySummary(
        valid = valid,
        reason = reason,
        candleCount = candle_count,
        gapCount = gap_count,
        outlierCount = outlier_count,
        duplicateCount = duplicate_count
    )
}

@JsonClass(generateAdapter = true)
data class TimeframeIntelligenceDto(
    val timeframe: String = "1m",
    val trend_bias: String = "NO_TRADE",
    val regime: String = MarketRegime.SIDEWAYS.name,
    val rsi: Double = 50.0,
    val ema_fast: Double = 0.0,
    val ema_slow: Double = 0.0,
    val sma_fast: Double = 0.0,
    val sma_slow: Double = 0.0,
    val macd_histogram: Double = 0.0,
    val trend_strength: Double = 0.0,
    val latest_close: Double = 0.0
) {
    fun toDomain(): TimeframeIntelligenceSummary = TimeframeIntelligenceSummary(
        timeframe = timeframe,
        trendBias = TradeDirection.safeValueOf(trend_bias),
        regime = MarketRegime.safeValueOf(regime),
        rsi = rsi,
        emaFast = ema_fast,
        emaSlow = ema_slow,
        smaFast = sma_fast,
        smaSlow = sma_slow,
        macdHistogram = macd_histogram,
        trendStrength = trend_strength,
        latestClose = latest_close
    )
}

@JsonClass(generateAdapter = true)
data class MultiTimeframeReportDto(
    val instrument: String = "EUR/USD",
    val primary_timeframe: String = "1m",
    val alignment_state: String = AlignmentState.MIXED.name,
    val alignment_score: Int = 50,
    val higher_tf_bias: String = "NO_TRADE",
    val timeframe_summaries: List<TimeframeIntelligenceDto> = emptyList(),
    val alignment_notes: List<String> = emptyList()
) {
    fun toDomain(): MultiTimeframeReport = MultiTimeframeReport(
        instrument = instrument,
        primaryTimeframe = primary_timeframe,
        alignmentState = AlignmentState.safeValueOf(alignment_state),
        alignmentScore = alignment_score.coerceIn(0, 100),
        higherTfBias = TradeDirection.safeValueOf(higher_tf_bias),
        timeframeSummaries = timeframe_summaries.map { it.toDomain() },
        alignmentNotes = alignment_notes
    )
}

@JsonClass(generateAdapter = true)
data class MarketIntelligenceResponseDto(
    val instrument: String = "EUR/USD",
    val data_quality: DataQualityReportDto = DataQualityReportDto(),
    val mtf_report: MultiTimeframeReportDto = MultiTimeframeReportDto(),
    val signal: SignalResponseDto = SignalResponseDto()
)

@JsonClass(generateAdapter = true)
data class ReplayStartRequestDto(
    val instrument: String = "EUR/USD",
    val total_bars: Int = 420,
    val initial_window: Int = 375
)

@JsonClass(generateAdapter = true)
data class ReplayStepRequestDto(
    val steps: Int = 1
)

@JsonClass(generateAdapter = true)
data class ReplayStateDto(
    val session_id: String = "",
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val cursor_index: Int = 0,
    val total_bars: Int = 0,
    val cursor_timestamp: Long = 0L,
    val active: Boolean = false
) {
    fun toDomain(): ReplaySessionState = ReplaySessionState(
        active = active,
        sessionId = session_id,
        instrument = instrument,
        timeframe = timeframe,
        cursorIndex = cursor_index,
        totalBars = total_bars,
        cursorTimestamp = cursor_timestamp,
        isBackendBacked = true
    )
}

@JsonClass(generateAdapter = true)
data class ReplayEvaluationResponseDto(
    val replay_state: ReplayStateDto = ReplayStateDto(),
    val data_quality: DataQualityReportDto = DataQualityReportDto(),
    val mtf_report: MultiTimeframeReportDto = MultiTimeframeReportDto(),
    val signal: SignalResponseDto = SignalResponseDto()
)

@JsonClass(generateAdapter = true)
data class StrategyParametersDto(
    val name: String,
    val instrument: String,
    val timeframe: String,
    val trade_amount: Double,
    val expiry_seconds: Int,
    val rsi_period: Int,
    val rsi_oversold: Int,
    val rsi_overbought: Int,
    val ema_fast: Int,
    val ema_slow: Int,
    val sma_fast: Int = 10,
    val sma_slow: Int = 30,
    val macd_fast: Int,
    val macd_slow: Int,
    val macd_signal: Int,
    val bb_period: Int,
    val bb_std_dev: Double,
    val min_confidence: Int
) {
    companion object {
        fun fromDomain(s: StrategyConfig): StrategyParametersDto = StrategyParametersDto(
            name = s.name,
            instrument = s.instrument,
            timeframe = s.timeframe,
            trade_amount = s.tradeAmount,
            expiry_seconds = s.expirySeconds,
            rsi_period = s.rsiPeriod,
            rsi_oversold = s.rsiOversold,
            rsi_overbought = s.rsiOverbought,
            ema_fast = s.emaFast,
            ema_slow = s.emaSlow,
            macd_fast = s.macdFast,
            macd_slow = s.macdSlow,
            macd_signal = s.macdSignal,
            bb_period = s.bbPeriod,
            bb_std_dev = s.bbStdDev,
            min_confidence = s.minConfidence
        )
    }
}

@JsonClass(generateAdapter = true)
data class RiskPolicyDto(
    val max_trade_amount: Double,
    val max_daily_loss: Double,
    val max_consecutive_losses: Int,
    val max_daily_trades: Int,
    val max_portfolio_exposure: Double,
    val cooldown_minutes: Int,
    val emergency_stopped: Boolean,
    val auto_trade_enabled: Boolean
) {
    companion object {
        fun fromDomain(p: RiskPolicy): RiskPolicyDto = RiskPolicyDto(
            max_trade_amount = p.maxTradeAmount,
            max_daily_loss = p.maxDailyLoss,
            max_consecutive_losses = p.maxConsecutiveLosses,
            max_daily_trades = p.maxDailyTrades,
            max_portfolio_exposure = p.maxPortfolioExposure,
            cooldown_minutes = p.cooldownMinutes,
            emergency_stopped = p.emergencyStopped,
            auto_trade_enabled = p.autoTradeEnabled
        )
    }
}

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
    val exit_price: Double = 0.0,
    val pnl: Double = 0.0,
    val message: String? = null,
    val correlation_id: String = "",
    val broker_order_id: String? = null,
    val lifecycle_state: String = "SETTLED",
    val settlement_outcome: String? = null,
    val reconciled_at: String? = null,
    val requires_reconciliation: Boolean = false
) {
    fun toDomain(): BackendOrderSummary = BackendOrderSummary(
        orderId = order_id,
        correlationId = correlation_id,
        brokerOrderId = broker_order_id,
        status = status,
        lifecycleState = OrderLifecycleState.safeValueOf(lifecycle_state),
        settlementOutcome = settlement_outcome,
        entryPrice = entry_price,
        exitPrice = exit_price,
        pnl = pnl,
        requiresReconciliation = requires_reconciliation,
        message = message.orEmpty()
    )
}

@JsonClass(generateAdapter = true)
data class OrderLifecycleEventDto(
    val event_id: String,
    val order_id: String,
    val correlation_id: String,
    val from_state: String? = null,
    val to_state: String,
    val detail: String = "",
    val created_at: String = ""
) {
    fun toDomain(): OrderLifecycleEvent = OrderLifecycleEvent(
        eventId = event_id,
        orderId = order_id,
        correlationId = correlation_id,
        fromState = from_state,
        toState = to_state,
        detail = detail,
        createdAt = created_at
    )
}

@JsonClass(generateAdapter = true)
data class ReconcileSummaryDto(
    val checked_count: Int = 0,
    val settled_count: Int = 0,
    val pending_count: Int = 0,
    val unknown_count: Int = 0,
    val failed_count: Int = 0,
    val orders: List<OrderSubmitResponseDto> = emptyList(),
    val message: String = ""
) {
    fun toDomain(): ReconcileSummary = ReconcileSummary(
        checkedCount = checked_count,
        settledCount = settled_count,
        pendingCount = pending_count,
        unknownCount = unknown_count,
        failedCount = failed_count,
        message = message
    )
}

@JsonClass(generateAdapter = true)
data class ModeSwitchRequestDto(
    val mode: String,
    val explicit_live_confirmation: Boolean = false
)

@JsonClass(generateAdapter = true)
data class TradingStateDto(
    val execution_mode: String = "SIMULATION",
    val execution_mode_label: String = "Simulation",
    val account_mode: String = "SIMULATION",
    val account_display_label: String = "Simulation",
    val connector_name: String = "simulation",
    val connector_connected: Boolean = false,
    val connection_state: String = "INTERNAL",
    val session_verified: Boolean = false,
    val live_authorized: Boolean = false,
    val real_account_locked: Boolean = true,
    val operation_mode: String = "MANUAL",
    val learning_status: String = "IDLE",
    val training_status: String = "IDLE",
    val backtest_status: String = "IDLE",
    val balance: Double = 10000.0,
    val total_trades_today: Int = 0,
    val wins_today: Int = 0,
    val losses_today: Int = 0,
    val net_pnl_today: Double = 0.0,
    val consecutive_losses: Int = 0,
    val emergency_stopped: Boolean = false,
    val win_rate_pct: Double = 0.0,
    val unresolved_orders_count: Int = 0
)

@JsonClass(generateAdapter = true)
data class AssetCatalogItemDto(
    val rank: Int = 0,
    val product_type: String = "UNKNOWN",
    val product_label: String = "Unknown",
    val symbol: String = "",
    val display_name: String = "",
    val is_open: Boolean? = null,
    val status: String = "UNKNOWN",
    val payout_rate: Double? = null,
    val payout_percent: Double? = null,
    val min_stake: Double? = null,
    val max_stake: Double? = null,
    val leverage: Double? = null,
    val source: String = ""
)

@JsonClass(generateAdapter = true)
data class AssetCatalogResponseDto(
    val account_mode: String = "UNSUPPORTED",
    val connector: String = "",
    val source: String = "",
    val supported_product_types: List<String> = emptyList(),
    val unavailable_product_types: List<String> = emptyList(),
    val items: List<AssetCatalogItemDto> = emptyList(),
    val fetched_at: Long = 0L
)

@JsonClass(generateAdapter = true)
data class TrainModelRequestDto(
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val algorithm: String = "logistic_regression",
    val total_bars: Int = 360,
    val payout_rate: Double = 0.85,
    val execution_delay_bars: Int = 1
)

@JsonClass(generateAdapter = true)
data class PromoteModelRequestDto(
    val owner_confirmed: Boolean = true
)

@JsonClass(generateAdapter = true)
data class RollbackModelRequestDto(
    val owner_confirmed: Boolean = true,
    val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class ModelDriftCheckResponseDto(
    val model_id: String,
    val drift_status: String = "HEALTHY",
    val drift_score: Double = 0.0,
    val feature_drift_score: Double = 0.0,
    val drifted_features: List<String> = emptyList(),
    val live_settled_trades: Int = 0,
    val live_win_rate: Double = 0.0,
    val baseline_win_rate: Double = 0.0,
    val win_rate_drop: Double = 0.0,
    val drift_notes: String = "",
    val recommend_rollback: Boolean = false,
    val checked_at: String = ""
) {
    fun toDomain(): MlDriftReport = MlDriftReport(
        modelId = model_id,
        driftStatus = DriftStatus.safeValueOf(drift_status),
        driftScore = drift_score,
        featureDriftScore = feature_drift_score,
        driftedFeatures = drifted_features,
        liveSettledTrades = live_settled_trades,
        liveWinRate = live_win_rate,
        baselineWinRate = baseline_win_rate,
        winRateDrop = win_rate_drop,
        driftNotes = drift_notes,
        recommendRollback = recommend_rollback,
        checkedAt = checked_at
    )
}

@JsonClass(generateAdapter = true)
data class ModelRegistryItemDto(
    val model_id: String,
    val instrument: String,
    val timeframe: String,
    val algorithm: String,
    val status: String,
    val train_samples: Int = 0,
    val validation_samples: Int = 0,
    val test_samples: Int = 0,
    val out_of_sample_accuracy: Double = 0.0,
    val brier_score: Double = 0.25,
    val log_loss: Double = 0.69,
    val ece: Double = 0.0,
    val simulated_trades: Int = 0,
    val simulated_win_rate: Double = 0.0,
    val simulated_net_pnl: Double = 0.0,
    val artifact_filename: String = "",
    val validation_notes: String = "",
    val dataset_version: String = "ds_v2_unknown",
    val feature_count: Int = 17,
    val drift_status: String = "HEALTHY",
    val drift_score: Double = 0.0,
    val live_settled_trades: Int = 0,
    val live_win_rate: Double = 0.0,
    val drift_notes: String = "",
    val last_drift_checked_at: String? = null,
    val rolled_back_from_model_id: String? = null,
    val created_at: String = "",
    val promoted_at: String? = null
) {
    fun toDomain(activeModelId: String? = null): MlModelSummary = MlModelSummary(
        modelId = model_id,
        instrument = instrument,
        timeframe = timeframe,
        algorithm = algorithm,
        status = status,
        trainSamples = train_samples,
        validationSamples = validation_samples,
        testSamples = test_samples,
        outOfSampleAccuracy = out_of_sample_accuracy,
        brierScore = brier_score,
        logLoss = log_loss,
        ece = ece,
        simulatedTrades = simulated_trades,
        simulatedWinRate = simulated_win_rate,
        simulatedNetPnl = simulated_net_pnl,
        validationNotes = validation_notes,
        createdAt = created_at,
        promotedAt = promoted_at,
        isActive = status.equals("ACTIVE", ignoreCase = true) || model_id == activeModelId,
        datasetVersion = dataset_version,
        featureCount = feature_count,
        driftStatus = DriftStatus.safeValueOf(drift_status),
        driftScore = drift_score,
        liveSettledTrades = live_settled_trades,
        liveWinRate = live_win_rate,
        driftNotes = drift_notes,
        lastDriftCheckedAt = last_drift_checked_at,
        rolledBackFromModelId = rolled_back_from_model_id
    )
}

@JsonClass(generateAdapter = true)
data class StrategyModuleDto(
    val strategy_id: String,
    val name: String,
    val description: String = "",
    val target_regimes: List<String> = emptyList(),
    val entry_conditions: String = "",
    val exit_conditions: String = "",
    val risk_parameters: String = "",
    val win_rate: Double = 50.0,
    val profit_factor: Double = 1.0,
    val max_drawdown: Double = 0.0,
    val sample_count: Int = 0,
    val expected_value: Double = 0.0,
    val regime_fit_score: Double = 0.5,
    val selection_score: Double = 50.0,
    val selected: Boolean = false,
    val default_config: StrategyParametersDto
) {
    fun toDomain(userId: String = ""): StrategyModuleSummary = StrategyModuleSummary(
        strategyId = strategy_id,
        name = name,
        description = description,
        targetRegimes = target_regimes,
        entryConditions = entry_conditions,
        exitConditions = exit_conditions,
        riskParameters = risk_parameters,
        winRate = win_rate,
        profitFactor = profit_factor,
        maxDrawdown = max_drawdown,
        sampleCount = sample_count,
        expectedValue = expected_value,
        regimeFitScore = regime_fit_score,
        selectionScore = selection_score,
        selected = selected,
        defaultConfig = StrategyConfig(
            userId = userId,
            name = default_config.name,
            instrument = default_config.instrument,
            timeframe = default_config.timeframe,
            tradeAmount = default_config.trade_amount,
            expirySeconds = default_config.expiry_seconds,
            rsiPeriod = default_config.rsi_period,
            rsiOversold = default_config.rsi_oversold,
            rsiOverbought = default_config.rsi_overbought,
            emaFast = default_config.ema_fast,
            emaSlow = default_config.ema_slow,
            macdFast = default_config.macd_fast,
            macdSlow = default_config.macd_slow,
            macdSignal = default_config.macd_signal,
            bbPeriod = default_config.bb_period,
            bbStdDev = default_config.bb_std_dev,
            minConfidence = default_config.min_confidence,
            active = true
        )
    )
}

@JsonClass(generateAdapter = true)
data class StrategySelectionReportDto(
    val selected_strategy_id: String = "regime_adaptive",
    val selected_strategy_name: String = "Market Regime Adaptive",
    val auto_selected: Boolean = true,
    val current_regime: String = MarketRegime.SIDEWAYS.name,
    val selection_reason: String = "",
    val strategies: List<StrategyModuleDto> = emptyList()
) {
    fun toDomain(userId: String = ""): StrategySelectionReport = StrategySelectionReport(
        selectedStrategyId = selected_strategy_id,
        selectedStrategyName = selected_strategy_name,
        autoSelected = auto_selected,
        currentRegime = MarketRegime.safeValueOf(current_regime),
        selectionReason = selection_reason,
        strategies = strategies.map { it.toDomain(userId) }
    )
}

@JsonClass(generateAdapter = true)
data class SelectStrategyRequestDto(
    val strategy_id: String? = null,
    val auto_select: Boolean = true,
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m"
)

@JsonClass(generateAdapter = true)
data class WebSocketSignalDto(
    val type: String = "signal",
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val selected_strategy_id: String = "",
    val selected_strategy_name: String = "",
    val signal: SignalResponseDto? = null
)

@JsonClass(generateAdapter = true)
data class WebSocketSystemDto(
    val type: String = "system",
    val execution_mode: String = "SIMULATION",
    val connector: String = "simulation_connector",
    val connector_connected: Boolean = true,
    val live_authorized: Boolean = false,
    val emergency_stopped: Boolean = false,
    val unresolved_orders_count: Int = 0,
    val selected_strategy_id: String = "",
    val auto_select_strategy: Boolean = true,
    val active_model_drift_status: String = "HEALTHY",
    val active_model_drift_score: Double = 0.0,
    val active_model_drift_notes: String = ""
)

@JsonClass(generateAdapter = true)
data class PerformanceBreakdownRowDto(
    val label: String,
    val total_trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val win_rate_pct: Double = 0.0,
    val net_pnl: Double = 0.0,
    val profit_factor: Double = 0.0
) {
    fun toDomain(): PerformanceBreakdownRow = PerformanceBreakdownRow(
        label = label,
        totalTrades = total_trades,
        wins = wins,
        losses = losses,
        winRatePct = win_rate_pct,
        netPnl = net_pnl,
        profitFactor = profit_factor
    )
}

@JsonClass(generateAdapter = true)
data class ModePerformanceSummaryDto(
    val mode: String = "SIMULATION",
    val total_trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val win_rate_pct: Double = 0.0,
    val net_pnl: Double = 0.0,
    val gross_profit: Double = 0.0,
    val gross_loss: Double = 0.0,
    val profit_factor: Double = 0.0,
    val average_return_pct: Double = 0.0,
    val max_drawdown: Double = 0.0,
    val max_consecutive_wins: Int = 0,
    val max_consecutive_losses: Int = 0,
    val expectancy: Double = 0.0
) {
    fun toDomain(): ModePerformanceSummary = ModePerformanceSummary(
        mode = mode,
        totalTrades = total_trades,
        wins = wins,
        losses = losses,
        winRatePct = win_rate_pct,
        netPnl = net_pnl,
        grossProfit = gross_profit,
        grossLoss = gross_loss,
        profitFactor = profit_factor,
        averageReturnPct = average_return_pct,
        maxDrawdown = max_drawdown,
        maxConsecutiveWins = max_consecutive_wins,
        maxConsecutiveLosses = max_consecutive_losses,
        expectancy = expectancy
    )
}

@JsonClass(generateAdapter = true)
data class TradingPerformanceResponseDto(
    val mode_filter: String = "SIMULATION",
    val simulation_summary: ModePerformanceSummaryDto = ModePerformanceSummaryDto("SIMULATION"),
    val live_summary: ModePerformanceSummaryDto = ModePerformanceSummaryDto("LIVE"),
    val active_summary: ModePerformanceSummaryDto = ModePerformanceSummaryDto("SIMULATION"),
    val by_strategy: List<PerformanceBreakdownRowDto> = emptyList(),
    val by_regime: List<PerformanceBreakdownRowDto> = emptyList(),
    val by_asset: List<PerformanceBreakdownRowDto> = emptyList(),
    val by_timeframe: List<PerformanceBreakdownRowDto> = emptyList()
) {
    fun toDomain(): PerformanceAnalyticsReport = PerformanceAnalyticsReport(
        modeFilter = mode_filter,
        simulationSummary = simulation_summary.toDomain(),
        liveSummary = live_summary.toDomain(),
        activeSummary = active_summary.toDomain(),
        byStrategy = by_strategy.map { it.toDomain() },
        byRegime = by_regime.map { it.toDomain() },
        byAsset = by_asset.map { it.toDomain() },
        byTimeframe = by_timeframe.map { it.toDomain() }
    )
}

@JsonClass(generateAdapter = true)
data class BacktestRunRequestDto(
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val strategy_id: String = "regime_adaptive",
    val stake_amount: Double = 25.0,
    val lookback_bars: Int = 180,
    val expiry_bars: Int = 1
)

@JsonClass(generateAdapter = true)
data class BacktestTradeSampleDto(
    val timestamp: Long = 0L,
    val direction: String = "CALL",
    val entry_price: Double = 0.0,
    val exit_price: Double = 0.0,
    val pnl: Double = 0.0,
    val status: String = "WON",
    val market_regime: String = "UNKNOWN",
    val strategy_name: String = ""
) {
    fun toDomain(): BacktestTradeSample = BacktestTradeSample(
        timestampMillis = timestamp,
        direction = direction,
        entryPrice = entry_price,
        exitPrice = exit_price,
        pnl = pnl,
        status = status,
        marketRegime = market_regime,
        strategyName = strategy_name
    )
}

@JsonClass(generateAdapter = true)
data class BacktestRunResponseDto(
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val strategy_id: String = "regime_adaptive",
    val strategy_name: String = "Market Regime Adaptive",
    val bars_evaluated: Int = 0,
    val total_trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val win_rate_pct: Double = 0.0,
    val net_pnl: Double = 0.0,
    val gross_profit: Double = 0.0,
    val gross_loss: Double = 0.0,
    val profit_factor: Double = 0.0,
    val max_drawdown: Double = 0.0,
    val expectancy: Double = 0.0,
    val equity_curve: List<Double> = emptyList(),
    val simulated_trades: List<BacktestTradeSampleDto> = emptyList()
) {
    fun toDomain(): BacktestReport = BacktestReport(
        instrument = instrument,
        timeframe = timeframe,
        strategyId = strategy_id,
        strategyName = strategy_name,
        barsEvaluated = bars_evaluated,
        totalTrades = total_trades,
        wins = wins,
        losses = losses,
        winRatePct = win_rate_pct,
        netPnl = net_pnl,
        grossProfit = gross_profit,
        grossLoss = gross_loss,
        profitFactor = profit_factor,
        maxDrawdown = max_drawdown,
        expectancy = expectancy,
        equityCurve = equity_curve,
        simulatedTrades = simulated_trades.map { it.toDomain() },
        sourceLabel = "FastAPI Walk-Forward"
    )
}

@JsonClass(generateAdapter = true)
data class LiveReadinessGateDto(
    val gate_id: String,
    val step_number: Int = 1,
    val title: String,
    val passed: Boolean = false,
    val detail: String = ""
) {
    fun toDomain(): LiveReadinessGateItem = LiveReadinessGateItem(
        gateId = gate_id,
        stepNumber = step_number,
        title = title,
        passed = passed,
        detail = detail
    )
}

@JsonClass(generateAdapter = true)
data class LiveReadinessResponseDto(
    val prerequisites_ready: Boolean = false,
    val overall_ready: Boolean = false,
    val current_mode: String = "SIMULATION",
    val gates: List<LiveReadinessGateDto> = emptyList(),
    val blocking_reasons: List<String> = emptyList(),
    val evaluated_at: String = ""
) {
    fun toDomain(): LiveReadinessReport = LiveReadinessReport(
        prerequisitesReady = prerequisites_ready,
        overallReady = overall_ready,
        currentMode = ExecutionMode.safeValueOf(current_mode),
        gates = gates.map { it.toDomain() },
        blockingReasons = blocking_reasons
    )
}

@JsonClass(generateAdapter = true)
data class DiagnosticCheckItemDto(
    val check_id: String,
    val title: String,
    val status: String = "WARN",
    val detail: String = ""
) {
    fun toDomain(): DiagnosticCheckItem = DiagnosticCheckItem(
        checkId = check_id,
        title = title,
        status = DiagnosticCheckStatus.safeValueOf(status),
        detail = detail
    )
}

@JsonClass(generateAdapter = true)
data class SystemDiagnosticsResponseDto(
    val overall_status: String = "WARN",
    val connector_name: String = "simulation_connector",
    val connector_connected: Boolean = true,
    val checks: List<DiagnosticCheckItemDto> = emptyList(),
    val live_readiness: LiveReadinessResponseDto = LiveReadinessResponseDto(),
    val checked_at: String = ""
) {
    fun toDomain(): SystemDiagnosticsReport = SystemDiagnosticsReport(
        overallStatus = DiagnosticCheckStatus.safeValueOf(overall_status),
        connectorName = connector_name,
        connectorConnected = connector_connected,
        checks = checks.map { it.toDomain() },
        liveReadiness = live_readiness.toDomain()
    )
}

@JsonClass(generateAdapter = true)
data class BrokerDryRunRequestDto(
    val instrument: String = "EUR/USD"
)

@JsonClass(generateAdapter = true)
data class BrokerDryRunResponseDto(
    val connector_name: String = "simulation_connector",
    val connected: Boolean = false,
    val dry_run_passed: Boolean = false,
    val live_authorized: Boolean = false,
    val simulation_locked: Boolean = true,
    val latency_ms: Double = 0.0,
    val market_price: Double = 0.0,
    val balance: Double = 0.0,
    val capabilities: List<String> = emptyList(),
    val status_detail: String = "",
    val checked_at: String = ""
) {
    fun toDomain(): BrokerDryRunReport = BrokerDryRunReport(
        connectorName = connector_name,
        connected = connected,
        dryRunPassed = dry_run_passed,
        liveAuthorized = live_authorized,
        simulationLocked = simulation_locked,
        latencyMs = latency_ms,
        marketPrice = market_price,
        balance = balance,
        capabilities = capabilities,
        statusDetail = status_detail
    )
}

@JsonClass(generateAdapter = true)
data class IQAirResponseDto(
    val success: Boolean = true,
    val city: String = "London",
    val state: String = "London",
    val country: String = "United Kingdom",
    val aqi_us: Int = 42,
    val main_pollution: String = "p2",
    val temperature_c: Double = 20.0,
    val humidity_pct: Int = 55,
    val wind_speed_ms: Double = 3.2,
    val pressure_hpa: Double = 1013.0,
    val macro_impact_score: Double = 0.95,
    val source: String = "iqair_api_v2",
    val detail: String = ""
) {
    fun toDomain(): IQAirReport = IQAirReport(
        success = success,
        city = city,
        state = state,
        country = country,
        aqiUs = aqi_us,
        mainPollution = main_pollution,
        temperatureC = temperature_c,
        humidityPct = humidity_pct,
        windSpeedMs = wind_speed_ms,
        pressureHpa = pressure_hpa,
        macroImpactScore = macro_impact_score,
        source = source,
        detail = detail
    )
}
