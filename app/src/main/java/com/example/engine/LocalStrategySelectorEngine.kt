package com.example.engine

import com.example.model.Candle
import com.example.model.MarketRegime
import com.example.model.StrategyConfig
import com.example.model.StrategyModuleSummary
import com.example.model.StrategySelectionReport
import com.example.model.TradeDirection
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local 8-module quantitative strategy catalog and regime-weighted walk-forward selector
 * mirroring Python StrategySelectionEngine for seamless offline/fallback operation.
 */
object LocalStrategySelectorEngine {

    private data class StrategyTemplate(
        val strategyId: String,
        val name: String,
        val description: String,
        val targetRegimes: List<String>,
        val entryConditions: String,
        val exitConditions: String,
        val riskParameters: String,
        val defaultConfig: StrategyConfig
    )

    private val templates = listOf(
        StrategyTemplate(
            strategyId = "ema_trend_following",
            name = "EMA Trend Following",
            description = "Follows confirmed EMA momentum aligned with ADX +DI/-DI direction.",
            targetRegimes = listOf("STRONG_TREND", "WEAK_TREND", "TRENDING_UP", "TRENDING_DOWN"),
            entryConditions = "EMA9 crosses/holds beyond EMA21 with ADX >= 18 and aligned DI.",
            exitConditions = "Fixed binary expiry or EMA cross reversal.",
            riskParameters = "Min confidence 64%, avoid HIGH_VOLATILITY and tight SIDEWAYS noise.",
            defaultConfig = StrategyConfig(
                name = "EMA Trend Following",
                emaFast = 9,
                emaSlow = 21,
                rsiPeriod = 14,
                rsiOversold = 32,
                rsiOverbought = 68,
                minConfidence = 64
            )
        ),
        StrategyTemplate(
            strategyId = "rsi_reversal",
            name = "RSI Reversal",
            description = "Captures exhaustion reversals at RSI extremes confirmed by Stochastic.",
            targetRegimes = listOf("REVERSAL", "SIDEWAYS", "RANGING"),
            entryConditions = "RSI <= 28 (CALL) or RSI >= 72 (PUT) with Stochastic cross.",
            exitConditions = "Fixed binary expiry upon mean reversion toward RSI 50.",
            riskParameters = "Min confidence 68%, blocked during runaway STRONG_TREND moves.",
            defaultConfig = StrategyConfig(
                name = "RSI Reversal",
                emaFast = 7,
                emaSlow = 21,
                rsiPeriod = 14,
                rsiOversold = 28,
                rsiOverbought = 72,
                minConfidence = 68
            )
        ),
        StrategyTemplate(
            strategyId = "macd_momentum",
            name = "MACD Momentum",
            description = "Trades expanding MACD histogram momentum bursts with VWAP confirmation.",
            targetRegimes = listOf("STRONG_TREND", "BREAKOUT", "TRENDING_UP", "TRENDING_DOWN"),
            entryConditions = "MACD line crosses signal with rising histogram slope and VWAP alignment.",
            exitConditions = "Fixed binary expiry or histogram momentum contraction.",
            riskParameters = "Min confidence 65%, requires positive net indicator spread.",
            defaultConfig = StrategyConfig(
                name = "MACD Momentum",
                emaFast = 8,
                emaSlow = 21,
                macdFast = 12,
                macdSlow = 26,
                macdSignal = 9,
                minConfidence = 65
            )
        ),
        StrategyTemplate(
            strategyId = "bb_mean_reversion",
            name = "Bollinger Mean Reversion",
            description = "Fades overextended touches on Bollinger outer bands back toward mean.",
            targetRegimes = listOf("SIDEWAYS", "RANGING", "LOW_VOLATILITY"),
            entryConditions = "Close <= BB Lower (CALL) or Close >= BB Upper (PUT) + Stochastic.",
            exitConditions = "Fixed binary expiry targeting middle Bollinger Band reversion.",
            riskParameters = "Min confidence 68%, avoids HIGH_VOLATILITY breakouts.",
            defaultConfig = StrategyConfig(
                name = "Bollinger Mean Reversion",
                bbPeriod = 20,
                bbStdDev = 2.1,
                rsiOversold = 27,
                rsiOverbought = 73,
                minConfidence = 68
            )
        ),
        StrategyTemplate(
            strategyId = "breakout_detection",
            name = "Breakout Detection",
            description = "Detects range expansion beyond prior 30-bar support/resistance levels.",
            targetRegimes = listOf("BREAKOUT", "HIGH_VOLATILITY"),
            entryConditions = "Close breaks prior 30-bar high/low with expanding ATR & MACD impulse.",
            exitConditions = "Fixed binary expiry or quick invalidation inside broken structure.",
            riskParameters = "Min confidence 70%, elevated threshold to filter false wick breakouts.",
            defaultConfig = StrategyConfig(
                name = "Breakout Detection",
                emaFast = 5,
                emaSlow = 20,
                bbPeriod = 20,
                bbStdDev = 2.0,
                minConfidence = 70
            )
        ),
        StrategyTemplate(
            strategyId = "volatility_adaptive",
            name = "Volatility-Adaptive",
            description = "Adapts band thresholds and confidence gates to ATR/Bollinger shifts.",
            targetRegimes = listOf("HIGH_VOLATILITY", "LOW_VOLATILITY"),
            entryConditions = "Widens confirmation gate in high ATR; tightens oscillator triggers in quiet regimes.",
            exitConditions = "Fixed binary expiry governed by strict Volatility & Risk Gate.",
            riskParameters = "Min confidence 72% to prevent overtrading during volatility spikes.",
            defaultConfig = StrategyConfig(
                name = "Volatility-Adaptive",
                emaFast = 9,
                emaSlow = 34,
                bbPeriod = 20,
                bbStdDev = 2.3,
                minConfidence = 72
            )
        ),
        StrategyTemplate(
            strategyId = "mtf_confirmation",
            name = "Multi-Timeframe Confirmation",
            description = "Aligns primary 1m entries with 5m and 15m structural trend consensus.",
            targetRegimes = listOf("STRONG_TREND", "WEAK_TREND", "BREAKOUT", "TRENDING_UP", "TRENDING_DOWN"),
            entryConditions = "1m trigger confirmed by non-conflicting 5m/15m directional bias.",
            exitConditions = "Fixed binary expiry; invalidated if 5m/15m conflict appears.",
            riskParameters = "Min confidence 67%, hard NO_TRADE gate on timeframe divergence.",
            defaultConfig = StrategyConfig(
                name = "Multi-Timeframe Confirmation",
                emaFast = 9,
                emaSlow = 21,
                minConfidence = 67
            )
        ),
        StrategyTemplate(
            strategyId = "regime_adaptive",
            name = "Market Regime Adaptive",
            description = "Synthesizes trend, oscillator, and VWAP weights dynamically per MarketRegime.",
            targetRegimes = listOf(
                "STRONG_TREND", "WEAK_TREND", "SIDEWAYS", "HIGH_VOLATILITY",
                "LOW_VOLATILITY", "BREAKOUT", "REVERSAL", "RANGING", "TRENDING_UP", "TRENDING_DOWN"
            ),
            entryConditions = "Delegates bias to trend engines in trends and oscillators in ranges.",
            exitConditions = "Fixed binary expiry with strict out-of-sample expectancy gating.",
            riskParameters = "Min confidence 66%, balanced drawdown control across regime shifts.",
            defaultConfig = StrategyConfig(
                name = "Market Regime Adaptive",
                emaFast = 9,
                emaSlow = 21,
                rsiOversold = 30,
                rsiOverbought = 70,
                minConfidence = 66
            )
        )
    )

    fun evaluate(
        candles: List<Candle>,
        currentRegime: MarketRegime,
        settledTrades: List<TradeRecord> = emptyList(),
        autoSelect: Boolean = true,
        pinnedStrategyId: String? = null
    ): StrategySelectionReport {
        val ordered = candles.sortedBy { it.timestampMillis }
        val profiles = templates.map { template ->
            val metrics = walkForwardMetrics(template, ordered, settledTrades)
            val fit = computeRegimeFit(template.targetRegimes, currentRegime)
            val sampleWeight = min(1.0, metrics.sampleCount / 12.0)
            val evComponent = metrics.expectedValue.coerceIn(-1.0, 1.0) * sampleWeight
            val pfComponent = (min(2.0, metrics.profitFactor) / 2.0) * sampleWeight
            val ddPenalty = min(0.4, metrics.maxDrawdown / 100.0)
            val score = (
                (fit * 55.0) +
                    (evComponent * 20.0) +
                    (pfComponent * 22.0) +
                    (sampleWeight * 8.0) -
                    (ddPenalty * 15.0)
                ).coerceIn(0.0, 100.0)

            StrategyModuleSummary(
                strategyId = template.strategyId,
                name = template.name,
                description = template.description,
                targetRegimes = template.targetRegimes,
                entryConditions = template.entryConditions,
                exitConditions = template.exitConditions,
                riskParameters = template.riskParameters,
                winRate = metrics.winRate,
                profitFactor = metrics.profitFactor,
                maxDrawdown = metrics.maxDrawdown,
                sampleCount = metrics.sampleCount,
                expectedValue = metrics.expectedValue,
                regimeFitScore = (fit * 1000).toInt() / 1000.0,
                selectionScore = (score * 100).toInt() / 100.0,
                selected = false,
                defaultConfig = template.defaultConfig
            )
        }

        val chosen = if (autoSelect || pinnedStrategyId.isNullOrBlank()) {
            profiles.maxBy { it.selectionScore }
        } else {
            profiles.firstOrNull { it.strategyId == pinnedStrategyId } ?: profiles.maxBy { it.selectionScore }
        }

        val marked = profiles
            .map { it.copy(selected = it.strategyId == chosen.strategyId) }
            .sortedWith(compareByDescending<StrategyModuleSummary> { it.selected }.thenByDescending { it.selectionScore })

        val reason = if (autoSelect) {
            "AI เลือก '${chosen.name}' สำหรับตลาด ${currentRegime.labelTh} (Score ${String.format("%.1f", chosen.selectionScore)})"
        } else {
            "ตรึงกลยุทธ์ '${chosen.name}' ด้วยตนเอง (${currentRegime.labelTh})"
        }

        return StrategySelectionReport(
            selectedStrategyId = chosen.strategyId,
            selectedStrategyName = chosen.name,
            autoSelected = autoSelect,
            currentRegime = currentRegime,
            selectionReason = reason,
            strategies = marked
        )
    }

    private data class WalkForwardMetrics(
        val winRate: Double,
        val profitFactor: Double,
        val maxDrawdown: Double,
        val sampleCount: Int,
        val expectedValue: Double
    )

    private fun walkForwardMetrics(
        template: StrategyTemplate,
        ordered: List<Candle>,
        settledTrades: List<TradeRecord>,
        payoutRate: Double = 0.85
    ): WalkForwardMetrics {
        val outcomes = mutableListOf<Double>()
        if (ordered.size >= 40) {
            val step = max(1, (ordered.size - 32) / 20)
            var idx = 30
            while (idx < ordered.size - 2) {
                val window = ordered.subList(max(0, idx - 60), idx + 1)
                val dir = barDirection(template.strategyId, window, template.defaultConfig)
                if (dir != TradeDirection.NO_TRADE) {
                    val entry = ordered[idx + 1].open
                    val exit = ordered[idx + 2].close
                    val won = if (dir == TradeDirection.CALL) exit > entry else exit < entry
                    outcomes.add(if (won) payoutRate else -1.0)
                }
                idx += step
            }
        }

        settledTrades.forEach { trade ->
            if ((trade.status == TradeStatus.WON.name || trade.status == TradeStatus.LOST.name) &&
                trade.strategyName.equals(template.name, ignoreCase = true)
            ) {
                outcomes.add(trade.pnl / max(1.0, trade.amount))
            }
        }

        if (outcomes.isEmpty()) {
            return WalkForwardMetrics(50.0, 1.0, 0.0, 0, 0.0)
        }

        val wins = outcomes.count { it > 0.0 }
        val grossProfit = outcomes.filter { it > 0.0 }.sum()
        val grossLoss = abs(outcomes.filter { it < 0.0 }.sum())
        val winRate = (wins.toDouble() / outcomes.size) * 100.0
        val pf = if (grossLoss > 1e-9) grossProfit / grossLoss else min(3.5, grossProfit)

        var equity = 0.0
        var peak = 0.0
        var maxDd = 0.0
        outcomes.forEach { r ->
            equity += r
            peak = max(peak, equity)
            maxDd = max(maxDd, peak - equity)
        }
        val maxDrawdownPct = min(100.0, (maxDd / 10.0) * 100.0)
        val ev = outcomes.average()

        return WalkForwardMetrics(
            winRate = (winRate * 100).toInt() / 100.0,
            profitFactor = (min(5.0, pf) * 1000).toInt() / 1000.0,
            maxDrawdown = (maxDrawdownPct * 100).toInt() / 100.0,
            sampleCount = outcomes.size,
            expectedValue = (ev * 10000).toInt() / 10000.0
        )
    }

    fun listCatalogIdsAndNames(): List<Pair<String, String>> =
        listOf("auto" to "Auto Regime Selector") + templates.map { it.strategyId to it.name }

    fun findTemplateConfig(strategyId: String): Pair<String, StrategyConfig> {
        val tpl = templates.firstOrNull { it.strategyId == strategyId }
        return if (tpl != null) tpl.name to tpl.defaultConfig else "Auto Regime Selector" to StrategyConfig()
    }

    fun evaluateBarDirection(
        strategyId: String,
        window: List<Candle>,
        cfg: StrategyConfig
    ): TradeDirection = barDirection(strategyId, window, cfg)

    private fun barDirection(
        strategyId: String,
        window: List<Candle>,
        cfg: StrategyConfig
    ): TradeDirection {
        val snap = TechnicalAnalysisEngine.analyze(window, cfg)
        val lastClose = window.last().close
        val prior = if (window.size > 2) window.dropLast(1).takeLast(30) else window.dropLast(1)
        val priorHigh = prior.maxOfOrNull { it.high } ?: snap.resistanceLevel
        val priorLow = prior.minOfOrNull { it.low } ?: snap.supportLevel

        return when (strategyId) {
            "ema_trend_following" -> when {
                snap.emaFast > snap.emaSlow && snap.adx >= 16.0 && snap.plusDi >= snap.minusDi -> TradeDirection.CALL
                snap.emaFast < snap.emaSlow && snap.adx >= 16.0 && snap.minusDi >= snap.plusDi -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
            "rsi_reversal" -> when {
                snap.rsi <= cfg.rsiOversold || (snap.rsi <= 36.0 && snap.stochasticK <= 24.0) -> TradeDirection.CALL
                snap.rsi >= cfg.rsiOverbought || (snap.rsi >= 64.0 && snap.stochasticK >= 76.0) -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
            "macd_momentum" -> when {
                snap.macdLine > snap.macdSignal && snap.macdHistogram > 0 -> TradeDirection.CALL
                snap.macdLine < snap.macdSignal && snap.macdHistogram < 0 -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
            "bb_mean_reversion" -> when {
                lastClose <= snap.bbLower && snap.stochasticK <= 32.0 -> TradeDirection.CALL
                lastClose >= snap.bbUpper && snap.stochasticK >= 68.0 -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
            "breakout_detection" -> when {
                lastClose > priorHigh && snap.macdHistogram >= 0 -> TradeDirection.CALL
                lastClose < priorLow && snap.macdHistogram <= 0 -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
            "volatility_adaptive" -> if (snap.volatilityPct > 0.35) {
                when {
                    snap.emaFast > snap.emaSlow && snap.adx >= 24.0 -> TradeDirection.CALL
                    snap.emaFast < snap.emaSlow && snap.adx >= 24.0 -> TradeDirection.PUT
                    else -> TradeDirection.NO_TRADE
                }
            } else {
                when {
                    lastClose <= snap.bbLower || snap.stochasticK <= 22.0 -> TradeDirection.CALL
                    lastClose >= snap.bbUpper || snap.stochasticK >= 78.0 -> TradeDirection.PUT
                    else -> TradeDirection.NO_TRADE
                }
            }
            "mtf_confirmation" -> when {
                snap.emaFast > snap.emaSlow && snap.macdHistogram > 0 -> TradeDirection.CALL
                snap.emaFast < snap.emaSlow && snap.macdHistogram < 0 -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
            else -> when {
                snap.emaFast > snap.emaSlow && snap.macdHistogram >= 0 -> TradeDirection.CALL
                snap.emaFast < snap.emaSlow && snap.macdHistogram <= 0 -> TradeDirection.PUT
                else -> TradeDirection.NO_TRADE
            }
        }
    }

    private fun computeRegimeFit(targetRegimes: List<String>, current: MarketRegime): Double {
        if (current == MarketRegime.UNKNOWN) return 0.40
        if (current.name in targetRegimes) return if (targetRegimes.size <= 4) 0.95 else 0.85
        val trendSet = setOf("STRONG_TREND", "WEAK_TREND", "TRENDING_UP", "TRENDING_DOWN", "BREAKOUT")
        val rangeSet = setOf("SIDEWAYS", "RANGING", "LOW_VOLATILITY", "REVERSAL")
        if (current.name in trendSet && targetRegimes.any { it in trendSet }) return 0.62
        if (current.name in rangeSet && targetRegimes.any { it in rangeSet }) return 0.62
        return 0.25
    }
}
