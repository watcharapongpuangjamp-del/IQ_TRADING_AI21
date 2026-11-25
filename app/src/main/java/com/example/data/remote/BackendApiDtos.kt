package com.example.data.remote

import com.example.model.AlignmentState
import com.example.model.BackendOrderSummary
import com.example.model.Candle
import com.example.model.DataQualitySummary
import com.example.model.MarketRegime
import com.example.model.MlModelSummary
import com.example.model.MultiTimeframeReport
import com.example.model.OrderLifecycleEvent
import com.example.model.OrderLifecycleState
import com.example.model.ReconcileSummary
import com.example.model.ReplaySessionState
import com.example.model.RiskPolicy
import com.example.model.SignalAnalysis
import com.example.model.StrategyConfig
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
    val regime: String = MarketRegime.RANGING.name
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
    val market_regime: String = MarketRegime.RANGING.name,
    val risk_assessment: String = "",
    val explanation: String = "",
    val expires_at_ms: Long = 0L,
    val technical_snapshot: TechnicalSnapshotDto? = null
) {
    fun toDomain(sourceLabel: String = "FastAPI Calibrated Intelligence"): SignalAnalysis {
        val parsedRegime = MarketRegime.entries.firstOrNull { it.name == market_regime } ?: MarketRegime.RANGING
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
    val regime: String = MarketRegime.RANGING.name,
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
        regime = MarketRegime.entries.firstOrNull { it.name == regime } ?: MarketRegime.RANGING,
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
    val connector_name: String = "simulation",
    val connector_connected: Boolean = true,
    val live_authorized: Boolean = false,
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
        isActive = status.equals("ACTIVE", ignoreCase = true) || model_id == activeModelId
    )
}
