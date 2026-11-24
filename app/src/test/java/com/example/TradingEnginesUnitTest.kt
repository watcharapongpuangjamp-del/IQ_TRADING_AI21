package com.example

import com.example.engine.MarketSimulationEngine
import com.example.engine.RiskManagementEngine
import com.example.engine.SignalEngine
import com.example.engine.TechnicalAnalysisEngine
import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.TradeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingEnginesUnitTest {

    @Test
    fun technicalAnalysis_and_signalEngine_computeValidBounds() {
        val sim = MarketSimulationEngine()
        val candles = sim.generateInitialCandles("EUR/USD", 60, 80)
        val strategy = StrategyConfig(instrument = "EUR/USD", minConfidence = 60)

        val snapshot = TechnicalAnalysisEngine.analyze(candles, strategy)
        assertTrue("RSI must be in [0, 100]", snapshot.rsi in 0.0..100.0)
        assertTrue("Bollinger upper >= lower", snapshot.bbUpper >= snapshot.bbLower)

        val signal = SignalEngine.evaluate(candles, snapshot, strategy)
        assertTrue("Confidence in 0..100", signal.confidenceScore in 0..100)
        assertTrue(
            "Calibrated probability must stay realistically bounded",
            signal.calibratedProbability in 0.45..0.75
        )
    }

    @Test
    fun signalEngine_returnsNoTrade_whenThresholdIsHigh() {
        val sim = MarketSimulationEngine()
        val candles = sim.generateInitialCandles("GBP/USD", 60, 80)
        val strictStrategy = StrategyConfig(instrument = "GBP/USD", minConfidence = 99)

        val snapshot = TechnicalAnalysisEngine.analyze(candles, strictStrategy)
        val signal = SignalEngine.evaluate(candles, snapshot, strictStrategy)
        assertEquals(TradeDirection.NO_TRADE, signal.direction)
    }

    @Test
    fun riskEngine_enforcesDailyLossLimit_consecutiveLosses_and_liveLock() {
        val policy = RiskPolicy(
            maxTradeAmount = 50.0,
            maxDailyLoss = 100.0,
            maxConsecutiveLosses = 3,
            maxDailyTrades = 10,
            maxPortfolioExposure = 200.0,
            cooldownMinutes = 15,
            emergencyStopped = false
        )

        // 1. Live mode blocked when backend connector is not authorized
        val liveCheck = RiskManagementEngine.validateOrder(
            policy = policy,
            todaySummary = null,
            openExposure = 0.0,
            requestedAmount = 25.0,
            direction = TradeDirection.CALL,
            mode = ExecutionMode.LIVE,
            backendReadyForLive = false,
            lastLossTimestampMillis = null
        )
        assertFalse(liveCheck.allowed)

        // 2. Daily loss hit
        val dailyLossHit = RiskManagementEngine.validateOrder(
            policy = policy,
            todaySummary = DailyPnLSummary(netPnl = -105.0, totalTrades = 4, consecutiveLosses = 1),
            openExposure = 0.0,
            requestedAmount = 25.0,
            direction = TradeDirection.CALL,
            mode = ExecutionMode.SIMULATION,
            backendReadyForLive = false,
            lastLossTimestampMillis = null
        )
        assertFalse(dailyLossHit.allowed)

        // 3. Consecutive losses hit
        val consecHit = RiskManagementEngine.validateOrder(
            policy = policy,
            todaySummary = DailyPnLSummary(netPnl = -50.0, totalTrades = 3, consecutiveLosses = 3),
            openExposure = 0.0,
            requestedAmount = 25.0,
            direction = TradeDirection.PUT,
            mode = ExecutionMode.SIMULATION,
            backendReadyForLive = false,
            lastLossTimestampMillis = null
        )
        assertFalse(consecHit.allowed)

        // 4. Valid simulation order passes
        val valid = RiskManagementEngine.validateOrder(
            policy = policy,
            todaySummary = DailyPnLSummary(netPnl = 40.0, totalTrades = 2, consecutiveLosses = 0),
            openExposure = 0.0,
            requestedAmount = 25.0,
            direction = TradeDirection.CALL,
            mode = ExecutionMode.SIMULATION,
            backendReadyForLive = false,
            lastLossTimestampMillis = null
        )
        assertTrue(valid.allowed)
    }
}
