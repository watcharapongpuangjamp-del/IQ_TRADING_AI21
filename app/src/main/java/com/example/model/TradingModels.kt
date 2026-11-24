package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties

enum class ExecutionMode(val label: String) {
    SIMULATION("SIMULATION"),
    DEMO("DEMO"),
    LIVE("LIVE");

    companion object {
        fun safeValueOf(raw: String?): ExecutionMode =
            entries.firstOrNull { it.name == raw } ?: SIMULATION
    }
}

enum class TradeDirection {
    CALL, PUT, NO_TRADE;

    companion object {
        fun safeValueOf(raw: String?): TradeDirection =
            entries.firstOrNull { it.name == raw } ?: NO_TRADE
    }
}

enum class TradeStatus {
    OPEN, WON, LOST, REJECTED, CANCELLED;

    companion object {
        fun safeValueOf(raw: String?): TradeStatus =
            entries.firstOrNull { it.name == raw } ?: OPEN
    }
}

enum class Timeframe(val code: String, val defaultExpirySec: Int) {
    M1("1m", 60),
    M5("5m", 300),
    M15("15m", 900);

    companion object {
        fun fromCode(code: String?): Timeframe =
            entries.firstOrNull { it.code == code } ?: M1
    }
}

enum class MarketRegime(val labelTh: String) {
    TRENDING_UP("แนวโน้มขาขึ้น"),
    TRENDING_DOWN("แนวโน้มขาลง"),
    RANGING("แกว่งตัวในกรอบ"),
    HIGH_VOLATILITY("ผันผวนสูง")
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "Trader",
    val email: String? = null,
    val executionMode: String = ExecutionMode.SIMULATION.name,
    val simulationBalance: Double = 10000.0,
    val lastBackupAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = buildMap {
        put("userId", uid)
        put("displayName", displayName.take(100).ifBlank { "Trader" })
        if (!email.isNullOrBlank()) put("email", email.take(150))
        put("executionMode", ExecutionMode.safeValueOf(executionMode).name)
        put("simulationBalance", simulationBalance.coerceIn(0.0, 10_000_000.0))
        put("lastBackupAt", FieldValue.serverTimestamp())
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }
}

@Keep
@IgnoreExtraProperties
data class StrategyConfig(
    @DocumentId val id: String = "",
    val userId: String = "",
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
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "name" to name.trim().take(80).ifBlank { "Custom Strategy" },
        "instrument" to instrument.trim().take(20).ifBlank { "EUR/USD" },
        "timeframe" to Timeframe.fromCode(timeframe).code,
        "tradeAmount" to tradeAmount.coerceIn(1.0, 100_000.0),
        "expirySeconds" to expirySeconds.coerceIn(60, 3600),
        "rsiPeriod" to rsiPeriod.coerceIn(2, 50),
        "rsiOversold" to rsiOversold.coerceIn(5, 45),
        "rsiOverbought" to rsiOverbought.coerceIn(55, 95),
        "emaFast" to emaFast.coerceIn(2, 50),
        "emaSlow" to emaSlow.coerceIn(5, 200),
        "macdFast" to macdFast.coerceIn(2, 50),
        "macdSlow" to macdSlow.coerceIn(5, 100),
        "macdSignal" to macdSignal.coerceIn(2, 50),
        "bbPeriod" to bbPeriod.coerceIn(5, 50),
        "bbStdDev" to bbStdDev.coerceIn(0.5, 4.0),
        "minConfidence" to minConfidence.coerceIn(50, 99),
        "active" to active,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "name" to name.trim().take(80).ifBlank { "Custom Strategy" },
        "instrument" to instrument.trim().take(20).ifBlank { "EUR/USD" },
        "timeframe" to Timeframe.fromCode(timeframe).code,
        "tradeAmount" to tradeAmount.coerceIn(1.0, 100_000.0),
        "expirySeconds" to expirySeconds.coerceIn(60, 3600),
        "rsiPeriod" to rsiPeriod.coerceIn(2, 50),
        "rsiOversold" to rsiOversold.coerceIn(5, 45),
        "rsiOverbought" to rsiOverbought.coerceIn(55, 95),
        "emaFast" to emaFast.coerceIn(2, 50),
        "emaSlow" to emaSlow.coerceIn(5, 200),
        "macdFast" to macdFast.coerceIn(2, 50),
        "macdSlow" to macdSlow.coerceIn(5, 100),
        "macdSignal" to macdSignal.coerceIn(2, 50),
        "bbPeriod" to bbPeriod.coerceIn(5, 50),
        "bbStdDev" to bbStdDev.coerceIn(0.5, 4.0),
        "minConfidence" to minConfidence.coerceIn(50, 99),
        "active" to active,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class RiskPolicy(
    @DocumentId val id: String = "",
    val userId: String = "",
    val maxTradeAmount: Double = 100.0,
    val maxDailyLoss: Double = 250.0,
    val maxConsecutiveLosses: Int = 3,
    val maxDailyTrades: Int = 30,
    val maxPortfolioExposure: Double = 500.0,
    val cooldownMinutes: Int = 15,
    val emergencyStopped: Boolean = false,
    val autoTradeEnabled: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "maxTradeAmount" to maxTradeAmount.coerceIn(1.0, 100_000.0),
        "maxDailyLoss" to maxDailyLoss.coerceIn(1.0, 1_000_000.0),
        "maxConsecutiveLosses" to maxConsecutiveLosses.coerceIn(1, 20),
        "maxDailyTrades" to maxDailyTrades.coerceIn(1, 500),
        "maxPortfolioExposure" to maxPortfolioExposure.coerceIn(1.0, 1_000_000.0),
        "cooldownMinutes" to cooldownMinutes.coerceIn(0, 1440),
        "emergencyStopped" to emergencyStopped,
        "autoTradeEnabled" to autoTradeEnabled,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "maxTradeAmount" to maxTradeAmount.coerceIn(1.0, 100_000.0),
        "maxDailyLoss" to maxDailyLoss.coerceIn(1.0, 1_000_000.0),
        "maxConsecutiveLosses" to maxConsecutiveLosses.coerceIn(1, 20),
        "maxDailyTrades" to maxDailyTrades.coerceIn(1, 500),
        "maxPortfolioExposure" to maxPortfolioExposure.coerceIn(1.0, 1_000_000.0),
        "cooldownMinutes" to cooldownMinutes.coerceIn(0, 1440),
        "emergencyStopped" to emergencyStopped,
        "autoTradeEnabled" to autoTradeEnabled,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class TradeRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
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
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "instrument" to instrument.trim().take(20).ifBlank { "EUR/USD" },
        "timeframe" to Timeframe.fromCode(timeframe).code,
        "direction" to if (direction == TradeDirection.PUT.name) "PUT" else "CALL",
        "mode" to ExecutionMode.safeValueOf(mode).name,
        "amount" to amount.coerceIn(1.0, 100_000.0),
        "payoutRate" to payoutRate.coerceIn(0.1, 2.0),
        "entryPrice" to entryPrice.coerceAtLeast(0.00001),
        "exitPrice" to exitPrice.coerceAtLeast(0.0),
        "status" to TradeStatus.safeValueOf(status).name,
        "pnl" to pnl.coerceIn(-100_000.0, 200_000.0),
        "strategyName" to strategyName.trim().take(80).ifBlank { "Strategy" },
        "confidenceScore" to confidenceScore.coerceIn(0, 100),
        "calibratedProbability" to calibratedProbability.coerceIn(0.0, 1.0),
        "explanation" to explanation.take(500),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class DailyPnLSummary(
    @DocumentId val id: String = "",
    val userId: String = "",
    val dateKey: String = "",
    val totalTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val netPnl: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val peakDrawdown: Double = 0.0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    val winRate: Double
        get() = if (totalTrades > 0) (wins.toDouble() / totalTrades.toDouble()) * 100.0 else 0.0

    fun toCreateMap(uid: String, resolvedDateKey: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "dateKey" to resolvedDateKey,
        "totalTrades" to totalTrades.coerceIn(0, 10_000),
        "wins" to wins.coerceIn(0, 10_000),
        "losses" to losses.coerceIn(0, 10_000),
        "netPnl" to netPnl.coerceIn(-10_000_000.0, 10_000_000.0),
        "consecutiveLosses" to consecutiveLosses.coerceIn(0, 100),
        "peakDrawdown" to peakDrawdown.coerceIn(0.0, 10_000_000.0),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "totalTrades" to totalTrades.coerceIn(0, 10_000),
        "wins" to wins.coerceIn(0, 10_000),
        "losses" to losses.coerceIn(0, 10_000),
        "netPnl" to netPnl.coerceIn(-10_000_000.0, 10_000_000.0),
        "consecutiveLosses" to consecutiveLosses.coerceIn(0, 100),
        "peakDrawdown" to peakDrawdown.coerceIn(0.0, 10_000_000.0),
        "updatedAt" to FieldValue.serverTimestamp()
    )
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
    val macdHistSeries: List<Double> = emptyList()
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
    val expiresAtMillis: Long
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
