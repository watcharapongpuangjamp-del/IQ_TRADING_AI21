package com.example.model

import androidx.annotation.Keep

enum class ExecutionMode(val label: String) {
    SIMULATION("SIMULATION"), DEMO("DEMO"), LIVE("LIVE");
    companion object {
        fun safeValueOf(raw: String?): ExecutionMode = entries.firstOrNull { it.name == raw } ?: SIMULATION
    }
}

enum class TradeDirection {
    CALL, PUT, NO_TRADE;
    companion object {
        fun safeValueOf(raw: String?): TradeDirection = entries.firstOrNull { it.name == raw } ?: NO_TRADE
    }
}

enum class TradeStatus {
    OPEN, WON, LOST, REJECTED, CANCELLED, PENDING, FAILED, UNKNOWN;
    companion object {
        fun safeValueOf(raw: String?): TradeStatus = entries.firstOrNull { it.name == raw } ?: OPEN
    }
}

enum class OrderLifecycleState(val labelTh: String) {
    CREATED("สร้างคำสั่ง"), VALIDATED("ผ่านการตรวจสอบ"), AUTHORIZED("ยืนยันสิทธิ์แล้ว"),
    SUBMITTED("ส่งเข้า Broker แล้ว"), ACCEPTED("Broker รับคำสั่งแล้ว"), PENDING("รอผลปิดออเดอร์"),
    SETTLED("ปิดบัญชีแล้ว"), REJECTED("ถูกปฏิเสธ"), FAILED("ส่งคำสั่งล้มเหลว"),
    UNKNOWN("ไม่ทราบสถานะ (ห้ามยิงซ้ำ)");
    val isUnresolved: Boolean get() = this == PENDING || this == UNKNOWN
    companion object {
        fun safeValueOf(raw: String?): OrderLifecycleState = entries.firstOrNull { it.name == raw } ?: CREATED
    }
}

enum class Timeframe(val code: String, val defaultExpirySec: Int) {
    M1("1m", 60), M5("5m", 300), M15("15m", 900);
    companion object {
        fun fromCode(code: String?): Timeframe = entries.firstOrNull { it.code == code } ?: M1
    }
}

enum class MarketRegime(val labelTh: String) {
    TRENDING_UP("แนวโน้มขาขึ้น"), TRENDING_DOWN("แนวโน้มขาลง"), RANGING("แกว่งตัวในกรอบ"),
    HIGH_VOLATILITY("ผันผวนสูง"), STRONG_TREND("เทรนด์แข็งแกร่ง"), WEAK_TREND("เทรนด์อ่อนตัว"),
    SIDEWAYS("แกว่งออกข้าง"), LOW_VOLATILITY("ผันผวนต่ำ"), BREAKOUT("ทะลุกรอบราคา"),
    REVERSAL("กลับตัวระยะสั้น"), UNKNOWN("ยังไม่ชัดเจน");
    companion object {
        fun safeValueOf(raw: String?): MarketRegime = entries.firstOrNull { it.name == raw } ?: SIDEWAYS
    }
}

@Keep
data class UserProfile(
    val id: String = "local",
    val userId: String = "local",
    val displayName: String = "Trader",
    val email: String? = null,
    val executionMode: String = ExecutionMode.SIMULATION.name,
    val simulationBalance: Double = 10000.0,
    val updatedAtMillis: Long = 0L
)

@Keep
data class StrategyConfig(
    val id: String = "",
    val userId: String = "local",
    val name: String = "EMA-RSI Momentum",
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val tradeAmount: Double = 25.0,
    val expirySeconds: Int = 60,
    val rsiPeriod: Int = 14,
    val rsiOversold: Int = 30,
    val rsiOverbought: Int = 70,
    val emaFast: Int = 9,
    val emaSlow: Int = 21,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val bbPeriod: Int = 20,
    val bbStdDev: Double = 2.0,
    val minConfidence: Int = 65,
    val active: Boolean = true,
    val updatedAtMillis: Long = 0L
)

@Keep
data class RiskPolicy(
    val id: String = "default",
    val userId: String = "local",
    val maxTradeAmount: Double = 100.0,
    val maxDailyLoss: Double = 250.0,
    val maxConsecutiveLosses: Int = 3,
    val maxDailyTrades: Int = 30,
    val maxPortfolioExposure: Double = 500.0,
    val cooldownMinutes: Int = 15,
    val emergencyStopped: Boolean = false,
    val autoTradeEnabled: Boolean = false,
    val updatedAtMillis: Long = 0L
)

@Keep
data class TradeRecord(
    val id: String = "",
    val userId: String = "local",
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val direction: String = TradeDirection.CALL.name,
    val mode: String = ExecutionMode.SIMULATION.name,
    val amount: Double = 25.0,
    val payoutRate: Double = 0.85,
    val entryPrice: Double = 1.0,
    val exitPrice: Double = 0.0,
    val status: String = TradeStatus.OPEN.name,
    val pnl: Double = 0.0,
    val strategyName: String = "EMA-RSI Momentum",
    val confidenceScore: Int = 70,
    val calibratedProbability: Double = 0.62,
    val explanation: String = "",
    val createdAtMillis: Long = 0L
)

@Keep
data class DailyPnLSummary(
    val id: String = "",
    val userId: String = "local",
    val dateKey: String = "",
    val totalTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val netPnl: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val peakDrawdown: Double = 0.0
) {
    val winRate: Double
        get() = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100.0 else 0.0
}

data class Candle(
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

data class TechnicalSnapshot(
    val rsi: Double,
    val macdLine: Double,
    val macdSignal: Double,
    val macdHistogram: Double,
    val emaFast: Double,
    val emaSlow: Double,
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val atr: Double,
    val volatilityPct: Double,
    val trendStrength: Double,
    val supportLevel: Double,
    val resistanceLevel: Double,
    val candlestickPattern: String,
    val regime: MarketRegime,
    val emaFastSeries: List<Double> = emptyList(),
    val emaSlowSeries: List<Double> = emptyList(),
    val bbUpperSeries: List<Double> = emptyList(),
    val bbLowerSeries: List<Double> = emptyList(),
    val rsiSeries: List<Double> = emptyList(),
    val macdHistSeries: List<Double> = emptyList(),
    val adx: Double = 0.0,
    val plusDi: Double = 0.0,
    val minusDi: Double = 0.0,
    val stochasticK: Double = 50.0,
    val stochasticD: Double = 50.0,
    val vwap: Double = 0.0,
    val vwapReliable: Boolean = false
)

data class SignalAnalysis(
    val instrument: String,
    val timeframe: String,
    val direction: TradeDirection,
    val calibratedProbability: Double,
    val confidenceScore: Int,
    val supportingEvidence: List<String>,
    val contradictingEvidence: List<String>,
    val regime: MarketRegime,
    val riskAssessment: String,
    val explanation: String,
    val expiresAtMillis: Long,
    val sourceLabel: String = "Local Heuristic"
)

enum class AlignmentState(val labelTh: String) {
    ALIGNED_BULLISH("ทุกกรอบเวลาหนุนขาขึ้น"),
    ALIGNED_BEARISH("ทุกกรอบเวลาหนุนขาลง"),
    MIXED("กรอบเวลายังผสม / รอยืนยัน"),
    CONFLICTING("สัญญาณข้ามกรอบเวลาขัดแย้ง");

    companion object {
        fun safeValueOf(raw: String?): AlignmentState = when (raw?.uppercase()) {
            "ALIGNED_BULLISH" -> ALIGNED_BULLISH
            "ALIGNED_BEARISH" -> ALIGNED_BEARISH
            "CONFLICTING", "CONFLICT" -> CONFLICTING
            "MIXED", "NEUTRAL" -> MIXED
            else -> MIXED
        }
    }
}

enum class StreamTransportMode(val label: String) {
    LOCAL_SIMULATION("Built-in Sim"),
    FASTAPI_REST("FastAPI REST"),
    FASTAPI_WEBSOCKET("FastAPI WS Live")
}

data class DataQualitySummary(
    val valid: Boolean = true,
    val reason: String = "Passed OHLCV validation",
    val candleCount: Int = 0,
    val gapCount: Int = 0,
    val outlierCount: Int = 0,
    val duplicateCount: Int = 0
)

data class TimeframeIntelligenceSummary(
    val timeframe: String,
    val trendBias: TradeDirection,
    val regime: MarketRegime,
    val rsi: Double,
    val emaFast: Double,
    val emaSlow: Double,
    val smaFast: Double,
    val smaSlow: Double,
    val macdHistogram: Double,
    val trendStrength: Double,
    val latestClose: Double
)

data class MultiTimeframeReport(
    val instrument: String,
    val primaryTimeframe: String,
    val alignmentState: AlignmentState,
    val alignmentScore: Int,
    val higherTfBias: TradeDirection,
    val timeframeSummaries: List<TimeframeIntelligenceSummary>,
    val alignmentNotes: List<String>
)

data class ReplaySessionState(
    val active: Boolean = false,
    val sessionId: String = "",
    val instrument: String = "EUR/USD",
    val timeframe: String = "1m",
    val cursorIndex: Int = 0,
    val totalBars: Int = 0,
    val cursorTimestamp: Long = 0L,
    val isBackendBacked: Boolean = false
)

data class MlModelSummary(
    val modelId: String,
    val instrument: String,
    val timeframe: String,
    val algorithm: String,
    val status: String,
    val trainSamples: Int,
    val validationSamples: Int,
    val testSamples: Int,
    val outOfSampleAccuracy: Double,
    val brierScore: Double,
    val logLoss: Double,
    val ece: Double,
    val simulatedTrades: Int,
    val simulatedWinRate: Double,
    val simulatedNetPnl: Double,
    val validationNotes: String,
    val createdAt: String = "",
    val promotedAt: String? = null,
    val isActive: Boolean = false
)

data class BackendOrderSummary(
    val orderId: String,
    val correlationId: String,
    val brokerOrderId: String? = null,
    val status: String,
    val lifecycleState: OrderLifecycleState,
    val settlementOutcome: String? = null,
    val entryPrice: Double = 0.0,
    val exitPrice: Double = 0.0,
    val pnl: Double = 0.0,
    val requiresReconciliation: Boolean = false,
    val message: String = ""
)

data class OrderLifecycleEvent(
    val eventId: String,
    val orderId: String,
    val correlationId: String,
    val fromState: String?,
    val toState: String,
    val detail: String,
    val createdAt: String
)

data class ReconcileSummary(
    val checkedCount: Int,
    val settledCount: Int,
    val pendingCount: Int,
    val unknownCount: Int,
    val failedCount: Int,
    val message: String
)

data class RiskValidationResult(
    val allowed: Boolean,
    val reason: String,
    val remainingDailyTrades: Int,
    val remainingLossBudget: Double
)

enum class ExecutionStage(val stepNumber: Int, val titleTh: String) {
    IDLE(0, "พร้อมวิเคราะห์"),
    MARKET_DATA(1, "รับข้อมูลตลาด"),
    DATA_VALIDATION(2, "ตรวจสอบความสมบูรณ์ข้อมูล"),
    MARKET_ANALYSIS(3, "วิเคราะห์กราฟเทคนิค"),
    AI_SIGNAL(4, "คำนวณสัญญาณ AI"),
    STRATEGY_VALIDATION(5, "ตรวจสอบเงื่อนไขกลยุทธ์"),
    RISK_VALIDATION(6, "ตรวจสอบ Risk Guard"),
    EXECUTION_AUTH(7, "ยืนยันสิทธิ์ส่งคำสั่ง"),
    ORDER_SUBMISSION(8, "ส่งคำสั่งซื้อขาย"),
    RESULT_VERIFICATION(9, "รอผลปิดออเดอร์"),
    TRADE_LOGGING(10, "บันทึกประวัติและซิงค์คลาวด์"),
    PERFORMANCE_EVAL(11, "ประเมินผลและแจ้งเตือน")
}

data class IntegrationSettings(
    val backendBaseUrl: String = "",
    val backendWsUrl: String = "",
    val telegramChatId: String = "",
    val notifyOnSignal: Boolean = true,
    val notifyOnTradeResult: Boolean = true,
    val notifyOnRiskStop: Boolean = true,
    val notifyDailySummary: Boolean = true,
    val enableLocalNotifications: Boolean = true,
    val hasBackendToken: Boolean = false,
    val hasTelegramToken: Boolean = false
)

data class BackendHealthState(
    val connected: Boolean = false,
    val checking: Boolean = false,
    val connectorName: String = "Local Simulation Engine",
    val supportedCapabilities: List<String> = listOf("SIMULATION_STREAM", "TECHNICAL_ANALYSIS", "PAPER_ORDERS"),
    val liveAuthorized: Boolean = false,
    val statusMessage: String = "โหมดจำลองในเครื่องพร้อมใช้งาน"
)

data class InstrumentSpec(
    val symbol: String,
    val category: String,
    val basePrice: Double,
    val volatility: Double,
    val payoutRate: Double,
    val decimals: Int
)

val SUPPORTED_INSTRUMENTS = listOf(
    InstrumentSpec("EUR/USD", "Forex", 1.08450, 0.00045, 0.85, 5),
    InstrumentSpec("GBP/USD", "Forex", 1.26820, 0.00060, 0.84, 5),
    InstrumentSpec("USD/JPY", "Forex", 153.420, 0.075, 0.83, 3),
    InstrumentSpec("BTC/USD", "Crypto", 94250.0, 145.0, 0.80, 2),
    InstrumentSpec("XAU/USD", "Commodity", 2642.50, 1.85, 0.82, 2)
)

data class StrategyModuleSummary(
    val strategyId: String,
    val name: String,
    val description: String,
    val targetRegimes: List<String>,
    val entryConditions: String,
    val exitConditions: String,
    val riskParameters: String,
    val winRate: Double,
    val profitFactor: Double,
    val maxDrawdown: Double,
    val sampleCount: Int,
    val expectedValue: Double,
    val regimeFitScore: Double,
    val selectionScore: Double,
    val selected: Boolean,
    val defaultConfig: StrategyConfig
)

data class StrategySelectionReport(
    val selectedStrategyId: String,
    val selectedStrategyName: String,
    val autoSelected: Boolean,
    val currentRegime: MarketRegime,
    val selectionReason: String,
    val strategies: List<StrategyModuleSummary>
)

data class WebSocketConnectionState(
    val marketConnected: Boolean = false,
    val signalConnected: Boolean = false,
    val systemConnected: Boolean = false,
    val reconnectAttempt: Int = 0,
    val isFallbackPolling: Boolean = false,
    val lastError: String? = null
)

