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

    @Test
    fun backendClient_resolvesWsUrl_andParsesPhase2And3JsonPayloads() {
        val client = com.example.data.remote.BackendConnectorClient()

        val wsFromHttps = client.resolveWebSocketUrl(
            baseUrl = "https://iq.example.com/",
            explicitWsUrl = "",
            instrument = "EUR/USD",
            timeframe = "1m"
        )
        assertEquals("wss://iq.example.com/ws/market/EUR/USD?timeframe=1m", wsFromHttps)

        val tickJson = """
            {
              "type": "tick",
              "instrument": "EUR/USD",
              "timeframe": "1m",
              "payout_rate": 0.85,
              "candle": {"timestamp": 1700000000000, "open": 1.08, "high": 1.09, "low": 1.07, "close": 1.085, "volume": 120.0}
            }
        """.trimIndent()
        val parsedTick = client.parseWebSocketTick(tickJson)
        assertEquals("EUR/USD", parsedTick?.instrument)
        assertEquals(1.085, parsedTick?.candle?.close ?: 0.0, 1e-6)

        val intelJson = """
            {
              "instrument": "EUR/USD",
              "data_quality": {"valid": true, "reason": "Passed OHLCV validation", "candle_count": 120, "gap_count": 0, "outlier_count": 0, "duplicate_count": 0},
              "mtf_report": {
                "instrument": "EUR/USD",
                "primary_timeframe": "1m",
                "alignment_state": "ALIGNED_BULLISH",
                "alignment_score": 88,
                "higher_tf_bias": "CALL",
                "timeframe_summaries": [
                  {"timeframe": "1m", "trend_bias": "CALL", "regime": "TRENDING_UP", "rsi": 58.2, "ema_fast": 1.085, "ema_slow": 1.082, "sma_fast": 1.084, "sma_slow": 1.081, "macd_histogram": 0.0004, "trend_strength": 1.4, "latest_close": 1.086}
                ],
                "alignment_notes": ["Confirmed by 5m and 15m"]
              },
              "signal": {
                "instrument": "EUR/USD",
                "timeframe": "1m",
                "direction": "CALL",
                "calibrated_probability": 0.64,
                "confidence_score": 76,
                "supporting_evidence": ["ML calibrated probability supports CALL"],
                "contradicting_evidence": [],
                "market_regime": "TRENDING_UP",
                "risk_assessment": "OK",
                "explanation": "ML model prediction",
                "expires_at_ms": 1700000060000
              }
            }
        """.trimIndent()
        val parsedIntel = client.parseMarketIntelligenceJson(intelJson)
        assertEquals(88, parsedIntel?.mtf_report?.alignment_score)
        assertEquals("FastAPI Calibrated ML", parsedIntel?.signal?.toDomain()?.sourceLabel)

        val modelJson = """
            {
              "model_id": "mdl-12345678",
              "instrument": "EUR/USD",
              "timeframe": "1m",
              "algorithm": "logistic_regression",
              "status": "VALIDATED",
              "train_samples": 180,
              "validation_samples": 60,
              "test_samples": 60,
              "out_of_sample_accuracy": 0.58,
              "brier_score": 0.23,
              "log_loss": 0.65,
              "ece": 0.06,
              "simulated_trades": 25,
              "simulated_win_rate": 0.60,
              "simulated_net_pnl": 115.0,
              "validation_notes": "Passed walk-forward gate"
            }
        """.trimIndent()
        val parsedModel = client.parseModelRegistryItemJson(modelJson)?.toDomain()
        assertEquals("VALIDATED", parsedModel?.status)
        assertEquals(0.58, parsedModel?.outOfSampleAccuracy ?: 0.0, 1e-6)
    }

    @Test
    fun localMultiTimeframeEngine_inspectsQuality_andProducesThreeTimeframes() {
        val sim = MarketSimulationEngine()
        val candles1m = sim.generateInitialCandles("EUR/USD", 60, 120)
        val strategy = StrategyConfig(instrument = "EUR/USD")

        val quality = com.example.engine.MultiTimeframeLocalEngine.inspectDataQuality(candles1m)
        assertTrue(quality.valid)
        assertEquals(120, quality.candleCount)

        val mtf = com.example.engine.MultiTimeframeLocalEngine.evaluateLocalMtf("EUR/USD", candles1m, strategy)
        assertEquals(3, mtf.timeframeSummaries.size)
        assertEquals("1m", mtf.timeframeSummaries[0].timeframe)
        assertEquals("5m", mtf.timeframeSummaries[1].timeframe)
        assertEquals("15m", mtf.timeframeSummaries[2].timeframe)
    }

    @Test
    fun phase5_orderLifecycleAndAlignmentAndReconcileDtos_parseAccurately() {
        assertEquals(10, com.example.model.OrderLifecycleState.entries.size)
        assertTrue(com.example.model.OrderLifecycleState.PENDING.isUnresolved)
        assertTrue(com.example.model.OrderLifecycleState.UNKNOWN.isUnresolved)
        assertFalse(com.example.model.OrderLifecycleState.SETTLED.isUnresolved)

        assertEquals(com.example.model.AlignmentState.MIXED, com.example.model.AlignmentState.safeValueOf("MIXED"))
        assertEquals(com.example.model.AlignmentState.CONFLICTING, com.example.model.AlignmentState.safeValueOf("CONFLICTING"))
        assertEquals(com.example.model.AlignmentState.CONFLICTING, com.example.model.AlignmentState.safeValueOf("CONFLICT"))

        assertEquals("OPEN", com.example.model.TradeStatus.toFirestoreStatus("PENDING"))
        assertEquals("OPEN", com.example.model.TradeStatus.toFirestoreStatus("UNKNOWN"))
        assertEquals("CANCELLED", com.example.model.TradeStatus.toFirestoreStatus("FAILED"))

        val client = com.example.data.remote.BackendConnectorClient()
        val orderJson = """
            {
              "order_id": "ord_abc123",
              "status": "UNKNOWN",
              "entry_price": 1.0845,
              "exit_price": 0.0,
              "pnl": 0.0,
              "correlation_id": "corr_xyz789",
              "lifecycle_state": "UNKNOWN",
              "requires_reconciliation": true,
              "message": "Order status UNKNOWN — reconciliation required"
            }
        """.trimIndent()
        val parsedOrder = client.parseOrderSubmitResponseJson(orderJson)?.toDomain()
        assertEquals("corr_xyz789", parsedOrder?.correlationId)
        assertEquals(com.example.model.OrderLifecycleState.UNKNOWN, parsedOrder?.lifecycleState)
        assertTrue(parsedOrder?.requiresReconciliation == true)

        val reconcileJson = """
            {
              "checked_count": 1,
              "settled_count": 1,
              "pending_count": 0,
              "unknown_count": 0,
              "failed_count": 0,
              "orders": [],
              "message": "Reconciled 1 order(s): 1 settled"
            }
        """.trimIndent()
        val parsedReconcile = client.parseReconcileSummaryJson(reconcileJson)?.toDomain()
        assertEquals(1, parsedReconcile?.checkedCount)
        assertEquals(1, parsedReconcile?.settledCount)
    }

    @Test
    fun phase6_technicalAnalysis_computesAdxStochasticVwap_andReliability() {
        val strategy = StrategyConfig(instrument = "EUR/USD")
        val now = 1_700_000_000_000L
        val trendWithVol = (0 until 90).map { i ->
            val base = 1.0800 + i * 0.00015
            com.example.model.Candle(
                timestampMillis = now + i * 60_000L,
                open = base - 0.00005,
                high = base + 0.00014,
                low = base - 0.00008,
                close = base + 0.00008,
                volume = 120.0 + i
            )
        }
        val snap = TechnicalAnalysisEngine.analyze(trendWithVol, strategy)
        assertTrue("ADX must be positive on strong trend, got ${snap.adx}", snap.adx >= 18.0)
        assertTrue("+DI should exceed -DI in uptrend", snap.plusDi > snap.minusDi)
        assertTrue("Stochastic K in 0..100", snap.stochasticK in 0.0..100.0)
        assertTrue("Stochastic D in 0..100", snap.stochasticD in 0.0..100.0)
        assertTrue("VWAP should be reliable when volume > 0", snap.vwapReliable)
        assertTrue("VWAP should be positive", snap.vwap > 0.0)
        assertTrue(
            "Regime should be STRONG_TREND, BREAKOUT, WEAK_TREND, or TRENDING_UP (got ${snap.regime})",
            snap.regime in setOf(
                com.example.model.MarketRegime.STRONG_TREND,
                com.example.model.MarketRegime.WEAK_TREND,
                com.example.model.MarketRegime.BREAKOUT,
                com.example.model.MarketRegime.TRENDING_UP
            )
        )

        val zeroVolCandles = trendWithVol.map { it.copy(volume = 0.0) }
        val snapNoVol = TechnicalAnalysisEngine.analyze(zeroVolCandles, strategy)
        assertFalse("VWAP must not be reliable when volume is missing", snapNoVol.vwapReliable)
    }

    @Test
    fun phase6_localStrategySelectorEngine_evaluatesEightModules_andSupportsPinning() {
        val sim = MarketSimulationEngine()
        val candles = sim.generateInitialCandles("EUR/USD", 60, 120)
        val snap = TechnicalAnalysisEngine.analyze(candles, StrategyConfig())

        val autoReport = com.example.engine.LocalStrategySelectorEngine.evaluate(
            candles = candles,
            currentRegime = snap.regime,
            settledTrades = emptyList(),
            autoSelect = true,
            pinnedStrategyId = null
        )
        assertEquals(8, autoReport.strategies.size)
        assertEquals(1, autoReport.strategies.count { it.selected })
        assertTrue(autoReport.autoSelected)
        autoReport.strategies.forEach { mod ->
            assertTrue(mod.winRate in 0.0..100.0)
            assertTrue(mod.profitFactor >= 0.0)
            assertTrue(mod.selectionScore in 0.0..100.0)
        }

        val pinnedReport = com.example.engine.LocalStrategySelectorEngine.evaluate(
            candles = candles,
            currentRegime = snap.regime,
            settledTrades = emptyList(),
            autoSelect = false,
            pinnedStrategyId = "rsi_reversal"
        )
        assertFalse(pinnedReport.autoSelected)
        assertEquals("rsi_reversal", pinnedReport.selectedStrategyId)
        assertTrue(pinnedReport.strategies.first { it.strategyId == "rsi_reversal" }.selected)
    }

    @Test
    fun phase6_offlineSyncQueueBackoff_andMultiChannelWsHelpers() {
        val baseTime = 1_700_000_000_000L
        assertEquals(
            baseTime + 2_000L,
            com.example.data.local.OfflineSyncQueueManager.computeNextRetryMillis(baseTime, 1)
        )
        assertEquals(
            baseTime + 4_000L,
            com.example.data.local.OfflineSyncQueueManager.computeNextRetryMillis(baseTime, 2)
        )
        assertEquals(
            baseTime + 300_000L,
            com.example.data.local.OfflineSyncQueueManager.computeNextRetryMillis(baseTime, 10)
        )

        val client = com.example.data.remote.BackendConnectorClient()
        assertEquals(
            "wss://iq.example.com/ws/signals?instrument=EUR/USD&timeframe=1m",
            client.resolveChannelWsUrl("https://iq.example.com", "", "/ws/signals?instrument=EUR/USD&timeframe=1m")
        )
        assertEquals(
            "ws://10.0.2.2:8000/ws/system",
            client.resolveChannelWsUrl("", "ws://10.0.2.2:8000/ws/market/EUR/USD", "/ws/system")
        )
        assertEquals(1_000L, client.computeWsReconnectDelayMs(0))
        assertEquals(2_000L, client.computeWsReconnectDelayMs(1))
        assertEquals(30_000L, client.computeWsReconnectDelayMs(10))

        val sigWsJson = """
            {
              "type": "signal",
              "instrument": "EUR/USD",
              "timeframe": "1m",
              "selected_strategy_id": "ema_trend_following",
              "selected_strategy_name": "EMA Trend Following",
              "signal": {
                "instrument": "EUR/USD",
                "timeframe": "1m",
                "direction": "CALL",
                "calibrated_probability": 0.63,
                "confidence_score": 74,
                "supporting_evidence": ["ADX 28.4 (+DI > -DI)"],
                "contradicting_evidence": [],
                "market_regime": "STRONG_TREND",
                "risk_assessment": "OK",
                "explanation": "Strong uptrend",
                "expires_at_ms": 1700000060000
              }
            }
        """.trimIndent()
        val parsedSig = client.parseWebSocketSignalJson(sigWsJson)
        assertEquals("ema_trend_following", parsedSig?.selected_strategy_id)
        assertEquals(TradeDirection.CALL, parsedSig?.signal?.toDomain()?.direction)

        val sysWsJson = """
            {
              "type": "system",
              "execution_mode": "SIMULATION",
              "connector": "simulation_connector",
              "connector_connected": true,
              "live_authorized": false,
              "emergency_stopped": false,
              "unresolved_orders_count": 1,
              "selected_strategy_id": "regime_adaptive",
              "auto_select_strategy": true
            }
        """.trimIndent()
        val parsedSys = client.parseWebSocketSystemJson(sysWsJson)
        assertEquals(1, parsedSys?.unresolved_orders_count)
        assertTrue(parsedSys?.auto_select_strategy == true)
    }
}

