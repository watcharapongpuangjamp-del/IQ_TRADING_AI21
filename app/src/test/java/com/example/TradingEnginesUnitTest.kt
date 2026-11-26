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
import org.junit.Assert.assertNotNull
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

        assertEquals("OPEN", com.example.model.TradeStatus.toNormalizedStatus("PENDING"))
        assertEquals("OPEN", com.example.model.TradeStatus.toNormalizedStatus("UNKNOWN"))
        assertEquals("CANCELLED", com.example.model.TradeStatus.toNormalizedStatus("FAILED"))

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
    fun phase6_localSqlRepositoryEntities_andMultiChannelWsHelpers() = kotlinx.coroutines.test.runTest {
        val fakeDao = InMemoryTradingDao()
        val repo = com.example.data.repository.LocalTradingRepository(fakeDao)

        val boot = repo.ensureUserBootstrap("local_trader", "Quant Local")
        assertTrue(boot.isSuccess)
        assertNotNull(fakeDao.getUserProfileOnce("local_trader"))
        assertNotNull(fakeDao.getRiskPolicyOnce("local_trader"))
        assertEquals(1, fakeDao.getStrategiesOnce("local_trader").size)

        val tradeRes = repo.createTrade(
            com.example.model.TradeRecord(
                id = "sql_test_1",
                userId = "local_trader",
                instrument = "EUR/USD",
                timeframe = "1m",
                direction = "CALL",
                mode = "SIMULATION",
                amount = 25.0,
                payoutRate = 0.85,
                entryPrice = 1.0850,
                status = "OPEN"
            )
        )
        assertEquals("sql_test_1", tradeRes.getOrNull())
        assertTrue(repo.settleTrade("sql_test_1", 1.0860, com.example.model.TradeStatus.WON, 21.25).isSuccess)
        val stored = fakeDao.trades["sql_test_1"]
        assertEquals("WON", stored?.status)
        assertEquals(21.25, stored?.pnl ?: 0.0, 1e-6)

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

    @Test
    fun phase7_mlDriftDtoParsing_datasetVersion_andSystemWsAlert() {
        assertEquals(
            com.example.model.DriftStatus.DRIFT_DETECTED,
            com.example.model.DriftStatus.safeValueOf("DRIFT_DETECTED")
        )
        assertEquals(
            com.example.model.DriftStatus.WARNING,
            com.example.model.DriftStatus.safeValueOf("warning")
        )
        assertEquals(
            com.example.model.DriftStatus.HEALTHY,
            com.example.model.DriftStatus.safeValueOf("unknown_value")
        )

        val client = com.example.data.remote.BackendConnectorClient()
        val rfModelJson = """
            {
              "model_id": "mdl_rf000012",
              "instrument": "EUR/USD",
              "timeframe": "1m",
              "algorithm": "random_forest",
              "status": "ACTIVE",
              "train_samples": 180,
              "validation_samples": 60,
              "test_samples": 60,
              "out_of_sample_accuracy": 0.61,
              "brier_score": 0.22,
              "log_loss": 0.63,
              "ece": 0.05,
              "simulated_trades": 30,
              "simulated_win_rate": 0.63,
              "simulated_net_pnl": 140.5,
              "validation_notes": "[ds_v2_a1b2c3d4_n320] Passed walk-forward gate",
              "dataset_version": "ds_v2_a1b2c3d4_n320",
              "feature_count": 17,
              "drift_status": "WARNING",
              "drift_score": 0.44,
              "live_settled_trades": 12,
              "live_win_rate": 0.50,
              "drift_notes": "Feature shift: adx_norm, vwap_dist_pct",
              "rolled_back_from_model_id": "mdl_lr000009"
            }
        """.trimIndent()
        val parsedRf = client.parseModelRegistryItemJson(rfModelJson)?.toDomain()
        assertEquals("random_forest", parsedRf?.algorithm)
        assertEquals("ds_v2_a1b2c3d4_n320", parsedRf?.datasetVersion)
        assertEquals(17, parsedRf?.featureCount)
        assertEquals(com.example.model.DriftStatus.WARNING, parsedRf?.driftStatus)
        assertEquals(0.44, parsedRf?.driftScore ?: 0.0, 1e-6)
        assertEquals("mdl_lr000009", parsedRf?.rolledBackFromModelId)

        val driftJson = """
            {
              "model_id": "mdl_rf000012",
              "drift_status": "DRIFT_DETECTED",
              "drift_score": 0.82,
              "feature_drift_score": 0.74,
              "drifted_features": ["adx_norm", "stoch_k_norm"],
              "live_settled_trades": 14,
              "live_win_rate": 0.3571,
              "baseline_win_rate": 0.63,
              "win_rate_drop": 0.2729,
              "drift_notes": "DRIFT ALERT: Manual rollback recommended (no auto-swap)",
              "recommend_rollback": true,
              "checked_at": "2026-11-25T10:00:00Z"
            }
        """.trimIndent()
        val parsedDrift = client.parseModelDriftCheckJson(driftJson)?.toDomain()
        assertEquals(com.example.model.DriftStatus.DRIFT_DETECTED, parsedDrift?.driftStatus)
        assertEquals(0.82, parsedDrift?.driftScore ?: 0.0, 1e-6)
        assertEquals(listOf("adx_norm", "stoch_k_norm"), parsedDrift?.driftedFeatures)
        assertTrue(parsedDrift?.recommendRollback == true)

        val sysWsDriftJson = """
            {
              "type": "system",
              "execution_mode": "SIMULATION",
              "connector": "simulation_connector",
              "connector_connected": true,
              "live_authorized": false,
              "emergency_stopped": false,
              "unresolved_orders_count": 0,
              "selected_strategy_id": "regime_adaptive",
              "auto_select_strategy": true,
              "active_model_drift_status": "DRIFT_DETECTED",
              "active_model_drift_score": 0.82,
              "active_model_drift_notes": "Manual rollback recommended"
            }
        """.trimIndent()
        val parsedSysDrift = client.parseWebSocketSystemJson(sysWsDriftJson)
        assertEquals("DRIFT_DETECTED", parsedSysDrift?.active_model_drift_status)
        assertEquals(0.82, parsedSysDrift?.active_model_drift_score ?: 0.0, 1e-6)
    }

    @Test
    fun phase8_performanceAnalyticsEngine_isolatesSimVsLive_andComputesBreakdowns() {
        val baseTime = 1_700_000_000_000L
        val trades = listOf(
            com.example.model.TradeRecord(
                id = "sim_1",
                instrument = "EUR/USD",
                timeframe = "1m",
                mode = "SIMULATION",
                amount = 100.0,
                status = "WON",
                pnl = 85.0,
                strategyName = "EMA Trend Following",
                marketRegime = "STRONG_TREND",
                createdAtMillis = baseTime + 1_000L
            ),
            com.example.model.TradeRecord(
                id = "sim_2",
                instrument = "GBP/USD",
                timeframe = "5m",
                mode = "SIMULATION",
                amount = 100.0,
                status = "LOST",
                pnl = -100.0,
                strategyName = "RSI Reversal",
                marketRegime = "SIDEWAYS",
                createdAtMillis = baseTime + 2_000L
            ),
            com.example.model.TradeRecord(
                id = "sim_3",
                instrument = "EUR/USD",
                timeframe = "1m",
                mode = "SIMULATION",
                amount = 100.0,
                status = "WON",
                pnl = 85.0,
                strategyName = "EMA Trend Following",
                marketRegime = "STRONG_TREND",
                createdAtMillis = baseTime + 3_000L
            ),
            com.example.model.TradeRecord(
                id = "live_1",
                instrument = "BTC/USD",
                timeframe = "15m",
                mode = "LIVE",
                amount = 50.0,
                status = "WON",
                pnl = 42.5,
                strategyName = "Breakout Detection",
                marketRegime = "BREAKOUT",
                createdAtMillis = baseTime + 4_000L
            )
        )

        val simReport = com.example.engine.PerformanceAnalyticsEngine.buildReport(trades, "SIMULATION")
        assertEquals("SIMULATION", simReport.activeSummary.mode)
        assertEquals(3, simReport.activeSummary.totalTrades)
        assertEquals(1, simReport.liveSummary.totalTrades)
        assertEquals(70.0, simReport.activeSummary.netPnl, 1e-6)
        assertEquals(170.0, simReport.activeSummary.grossProfit, 1e-6)
        assertEquals(100.0, simReport.activeSummary.grossLoss, 1e-6)
        assertEquals(1.7, simReport.activeSummary.profitFactor, 1e-3)
        assertEquals(100.0, simReport.activeSummary.maxDrawdown, 1e-6)
        assertEquals(2, simReport.byStrategy.size)
        assertEquals(2, simReport.byRegime.size)
        assertEquals(2, simReport.byAsset.size)
        assertEquals(2, simReport.byTimeframe.size)

        val liveReport = com.example.engine.PerformanceAnalyticsEngine.buildReport(trades, "LIVE")
        assertEquals("LIVE", liveReport.activeSummary.mode)
        assertEquals(1, liveReport.activeSummary.totalTrades)
        assertEquals(42.5, liveReport.activeSummary.netPnl, 1e-6)

        val allReport = com.example.engine.PerformanceAnalyticsEngine.buildReport(trades, "ALL")
        assertEquals("ALL (LABELED)", allReport.activeSummary.mode)
        assertEquals(4, allReport.activeSummary.totalTrades)
    }

    @Test
    fun phase8_localBacktestEngine_runsCausalWalkForward_andRejectsInsufficientCandles() {
        val sim = MarketSimulationEngine()
        val shortCandles = sim.generateInitialCandles("EUR/USD", 60, 20)
        val tooShort = com.example.engine.LocalBacktestEngine.runBacktest(shortCandles, "EUR/USD")
        assertTrue(tooShort.isFailure)

        val candles = sim.generateInitialCandles("EUR/USD", 60, 140)
        val res = com.example.engine.LocalBacktestEngine.runBacktest(
            candles = candles,
            instrument = "EUR/USD",
            timeframe = "1m",
            strategyId = "auto",
            stakeAmount = 25.0,
            payoutRate = 0.85
        )
        assertTrue(res.isSuccess)
        val report = res.getOrThrow()
        assertEquals(140, report.barsEvaluated)
        assertEquals(report.wins + report.losses, report.totalTrades)
        assertTrue(report.equityCurve.isNotEmpty())
        assertTrue(report.equityCurve.size <= 80)
        assertEquals(report.netPnl, report.equityCurve.last(), 1e-6)
        assertTrue(report.maxDrawdown >= 0.0)
    }

    @Test
    fun phase8_liveReadinessEvaluator_andSystemDiagnosticsRunner_enforceFiveGatesAndFailClosed() {
        val settingsLocked = com.example.model.IntegrationSettings(
            backendBaseUrl = "",
            hasBackendToken = false
        )
        val healthOffline = com.example.model.BackendHealthState(
            connected = false,
            liveAuthorized = false
        )
        val dqValid = com.example.model.DataQualitySummary(valid = true, reason = "OK", candleCount = 120)
        val policy = RiskPolicy(maxTradeAmount = 50.0, maxDailyLoss = 100.0, maxPortfolioExposure = 200.0)

        val blocked = com.example.engine.LiveReadinessEvaluator.evaluateLocal(
            backendHealth = healthOffline,
            riskPolicy = policy,
            settings = settingsLocked,
            dataQuality = dqValid,
            unresolvedOrdersCount = 1,
            currentMode = ExecutionMode.SIMULATION,
            ownerConfirmed = false
        )
        assertEquals(5, blocked.gates.size)
        assertFalse(blocked.prerequisitesReady)
        assertFalse(blocked.overallReady)
        assertTrue(blocked.blockingReasons.isNotEmpty())

        val readyUnconfirmed = com.example.engine.LiveReadinessEvaluator.evaluateLocal(
            backendHealth = com.example.model.BackendHealthState(
                connected = true,
                liveAuthorized = true,
                connectorName = "iq_broker",
                supportedCapabilities = listOf("SIMULATION", "LIVE_ORDERS")
            ),
            riskPolicy = policy,
            settings = com.example.model.IntegrationSettings(
                backendBaseUrl = "https://api.iqtrading.example",
                hasBackendToken = true
            ),
            dataQuality = dqValid,
            unresolvedOrdersCount = 0,
            currentMode = ExecutionMode.SIMULATION,
            ownerConfirmed = false
        )
        assertTrue(readyUnconfirmed.prerequisitesReady)
        assertFalse(readyUnconfirmed.overallReady)

        val fullyConfirmed = com.example.engine.LiveReadinessEvaluator.evaluateLocal(
            backendHealth = com.example.model.BackendHealthState(
                connected = true,
                liveAuthorized = true,
                connectorName = "iq_broker",
                supportedCapabilities = listOf("SIMULATION", "LIVE_ORDERS")
            ),
            riskPolicy = policy,
            settings = com.example.model.IntegrationSettings(
                backendBaseUrl = "https://api.iqtrading.example",
                hasBackendToken = true
            ),
            dataQuality = dqValid,
            unresolvedOrdersCount = 0,
            currentMode = ExecutionMode.LIVE,
            ownerConfirmed = true
        )
        assertTrue(fullyConfirmed.overallReady)

        val sim = MarketSimulationEngine()
        val diagOk = com.example.engine.SystemDiagnosticsRunner.runLocalDiagnostics(
            dbStats = com.example.model.LocalDatabaseStats(totalTrades = 3, totalCachedCandles = 120, totalStrategies = 1),
            candles = sim.generateInitialCandles("EUR/USD", 60, 80),
            dataQuality = dqValid,
            riskPolicy = policy,
            backendHealth = healthOffline,
            wsState = com.example.model.WebSocketConnectionState(),
            settings = settingsLocked,
            activeMlModel = null,
            unresolvedOrdersCount = 0,
            currentMode = ExecutionMode.SIMULATION,
            vaultRoundTripOk = true
        )
        assertEquals(
            com.example.model.DiagnosticCheckStatus.PASS,
            diagOk.checks.first { it.checkId == "risk_fail_closed" }.status
        )
        assertEquals(com.example.model.DiagnosticCheckStatus.WARN, diagOk.overallStatus)

        val diagVaultFail = com.example.engine.SystemDiagnosticsRunner.runLocalDiagnostics(
            dbStats = com.example.model.LocalDatabaseStats(),
            candles = emptyList(),
            dataQuality = dqValid,
            riskPolicy = policy,
            backendHealth = healthOffline,
            wsState = com.example.model.WebSocketConnectionState(),
            settings = settingsLocked,
            activeMlModel = null,
            unresolvedOrdersCount = 0,
            currentMode = ExecutionMode.SIMULATION,
            vaultRoundTripOk = false
        )
        assertEquals(com.example.model.DiagnosticCheckStatus.FAIL, diagVaultFail.overallStatus)
    }

    @Test
    fun phase8_backendConnectorClient_parsesPerformanceBacktestAndDiagnosticsJson() {
        val client = com.example.data.remote.BackendConnectorClient()

        val perfJson = """
            {
              "mode_filter": "SIMULATION",
              "simulation_summary": {"mode": "SIMULATION", "total_trades": 5, "wins": 3, "losses": 2, "win_rate_pct": 60.0, "net_pnl": 55.0, "gross_profit": 255.0, "gross_loss": 200.0, "profit_factor": 1.275, "average_return_pct": 11.0, "max_drawdown": 100.0, "max_consecutive_wins": 2, "max_consecutive_losses": 1, "expectancy": 11.0},
              "live_summary": {"mode": "LIVE", "total_trades": 0, "wins": 0, "losses": 0, "win_rate_pct": 0.0, "net_pnl": 0.0, "gross_profit": 0.0, "gross_loss": 0.0, "profit_factor": 0.0, "average_return_pct": 0.0, "max_drawdown": 0.0, "max_consecutive_wins": 0, "max_consecutive_losses": 0, "expectancy": 0.0},
              "active_summary": {"mode": "SIMULATION", "total_trades": 5, "wins": 3, "losses": 2, "win_rate_pct": 60.0, "net_pnl": 55.0, "gross_profit": 255.0, "gross_loss": 200.0, "profit_factor": 1.275, "average_return_pct": 11.0, "max_drawdown": 100.0, "max_consecutive_wins": 2, "max_consecutive_losses": 1, "expectancy": 11.0},
              "by_strategy": [{"label": "EMA Trend Following", "total_trades": 3, "wins": 2, "losses": 1, "win_rate_pct": 66.67, "net_pnl": 70.0, "profit_factor": 1.7}],
              "by_regime": [{"label": "STRONG_TREND", "total_trades": 3, "wins": 2, "losses": 1, "win_rate_pct": 66.67, "net_pnl": 70.0, "profit_factor": 1.7}],
              "by_asset": [{"label": "EUR/USD", "total_trades": 5, "wins": 3, "losses": 2, "win_rate_pct": 60.0, "net_pnl": 55.0, "profit_factor": 1.275}],
              "by_timeframe": [{"label": "1m", "total_trades": 5, "wins": 3, "losses": 2, "win_rate_pct": 60.0, "net_pnl": 55.0, "profit_factor": 1.275}]
            }
        """.trimIndent()
        val parsedPerf = client.parseTradingPerformanceJson(perfJson)?.toDomain()
        assertEquals(5, parsedPerf?.activeSummary?.totalTrades)
        assertEquals(1.275, parsedPerf?.activeSummary?.profitFactor ?: 0.0, 1e-6)
        assertEquals("EMA Trend Following", parsedPerf?.byStrategy?.firstOrNull()?.label)

        val btJson = """
            {
              "instrument": "EUR/USD",
              "timeframe": "1m",
              "strategy_id": "auto",
              "strategy_name": "Auto Regime Selector",
              "bars_evaluated": 180,
              "total_trades": 12,
              "wins": 7,
              "losses": 5,
              "win_rate_pct": 58.33,
              "net_pnl": 23.75,
              "gross_profit": 148.75,
              "gross_loss": 125.0,
              "profit_factor": 1.19,
              "max_drawdown": 50.0,
              "expectancy": 1.98,
              "equity_curve": [0.0, 21.25, -3.75, 23.75],
              "simulated_trades": [{"timestamp": 1700000060000, "direction": "CALL", "entry_price": 1.08, "exit_price": 1.081, "pnl": 21.25, "status": "WON", "market_regime": "STRONG_TREND", "strategy_name": "EMA Trend Following"}]
            }
        """.trimIndent()
        val parsedBt = client.parseBacktestResponseJson(btJson)?.toDomain()
        assertEquals(180, parsedBt?.barsEvaluated)
        assertEquals(23.75, parsedBt?.netPnl ?: 0.0, 1e-6)
        assertEquals(1, parsedBt?.simulatedTrades?.size)

        val diagJson = """
            {
              "overall_status": "WARN",
              "connector_name": "simulation_connector",
              "connector_connected": true,
              "checks": [{"check_id": "risk_fail_closed", "title": "Risk Guard", "status": "PASS", "detail": "OK"}],
              "live_readiness": {
                "prerequisites_ready": false,
                "overall_ready": false,
                "current_mode": "SIMULATION",
                "gates": [{"gate_id": "broker_verified", "step_number": 1, "title": "1. Broker", "passed": false, "detail": "Sim mode"}],
                "blocking_reasons": ["Sim mode"]
              },
              "checked_at": "2026-11-26T10:00:00Z"
            }
        """.trimIndent()
        val parsedDiag = client.parseSystemDiagnosticsJson(diagJson)?.toDomain()
        assertEquals(com.example.model.DiagnosticCheckStatus.WARN, parsedDiag?.overallStatus)
        assertEquals(ExecutionMode.SIMULATION, parsedDiag?.liveReadiness?.currentMode)
        assertEquals(1, parsedDiag?.liveReadiness?.gates?.size)
    }
}

private class InMemoryTradingDao : com.example.data.local.TradingDao {
    private val profiles = mutableMapOf<String, com.example.data.local.LocalUserProfileEntity>()
    private val strategies = mutableMapOf<String, com.example.data.local.LocalStrategyConfigEntity>()
    private val policies = mutableMapOf<String, com.example.data.local.LocalRiskPolicyEntity>()
    val trades = mutableMapOf<String, com.example.data.local.LocalTradeCacheEntity>()
    private val summaries = mutableMapOf<String, com.example.data.local.LocalDailySummaryEntity>()
    private val candles = mutableListOf<com.example.data.local.CachedCandleEntity>()

    override fun observeCandles(instrument: String, timeframe: String) =
        kotlinx.coroutines.flow.flowOf(candles.filter { it.instrument == instrument && it.timeframe == timeframe })

    override fun observeCandleCount() = kotlinx.coroutines.flow.flowOf(candles.size)

    override suspend fun clearCandles(instrument: String, timeframe: String) {
        candles.removeAll { it.instrument == instrument && it.timeframe == timeframe }
    }

    override suspend fun insertCandles(candles: List<com.example.data.local.CachedCandleEntity>) {
        this.candles.addAll(candles)
    }

    override fun observeUserProfile(userId: String) = kotlinx.coroutines.flow.flowOf(profiles[userId])
    override suspend fun getUserProfileOnce(userId: String) = profiles[userId]
    override suspend fun upsertUserProfile(profile: com.example.data.local.LocalUserProfileEntity) {
        profiles[profile.userId] = profile
    }

    override fun observeStrategies(userId: String) =
        kotlinx.coroutines.flow.flowOf(strategies.values.filter { it.userId == userId })

    override suspend fun getStrategiesOnce(userId: String) =
        strategies.values.filter { it.userId == userId }

    override suspend fun upsertStrategy(strategy: com.example.data.local.LocalStrategyConfigEntity) {
        strategies[strategy.id] = strategy
    }

    override fun observeRiskPolicy(userId: String) = kotlinx.coroutines.flow.flowOf(policies[userId])
    override suspend fun getRiskPolicyOnce(userId: String) = policies[userId]
    override suspend fun upsertRiskPolicy(policy: com.example.data.local.LocalRiskPolicyEntity) {
        policies[policy.userId] = policy
    }

    override fun observeLocalTrades(userId: String) =
        kotlinx.coroutines.flow.flowOf(trades.values.filter { it.userId == userId })

    override fun observeTradeCount(userId: String) =
        kotlinx.coroutines.flow.flowOf(trades.values.count { it.userId == userId })

    override suspend fun upsertTrade(trade: com.example.data.local.LocalTradeCacheEntity) {
        trades[trade.tradeId] = trade
    }

    override suspend fun updateTradeSettlement(tradeId: String, exitPrice: Double, status: String, pnl: Double) {
        val current = trades[tradeId] ?: return
        trades[tradeId] = current.copy(exitPrice = exitPrice, status = status, pnl = pnl)
    }

    override fun observeDailySummaries(userId: String) =
        kotlinx.coroutines.flow.flowOf(summaries.values.filter { it.userId == userId })

    override suspend fun getDailySummaryOnce(summaryId: String) = summaries[summaryId]

    override suspend fun upsertDailySummary(summary: com.example.data.local.LocalDailySummaryEntity) {
        summaries[summary.summaryId] = summary
    }
}


