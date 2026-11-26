package com.example.engine

import com.example.model.BacktestReport
import com.example.model.BacktestTradeSample
import com.example.model.Candle
import com.example.model.TradeDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Pure-Kotlin chronological zero-look-ahead walk-forward backtester.
 * Evaluates strictly causal slices `ordered.subList(start, idx + 1)` and settles on `idx + expiryBars`.
 */
object LocalBacktestEngine {

    fun runBacktest(
        candles: List<Candle>,
        instrument: String,
        timeframe: String = "1m",
        strategyId: String = "regime_adaptive",
        stakeAmount: Double = 25.0,
        payoutRate: Double = 0.85,
        expiryBars: Int = 1,
        warmupBars: Int = 35
    ): Result<BacktestReport> {
        val ordered = candles.sortedBy { it.timestampMillis }
        val dq = MultiTimeframeLocalEngine.inspectDataQuality(ordered)
        if (!dq.valid) {
            return Result.failure(IllegalArgumentException("Backtest data quality failed: ${dq.reason}"))
        }
        val minRequired = warmupBars + expiryBars + 5
        if (ordered.size < minRequired) {
            return Result.failure(
                IllegalArgumentException("Need at least $minRequired candles for backtest (got ${ordered.size}).")
            )
        }

        val (resolvedName, cfg) = LocalStrategySelectorEngine.findTemplateConfig(strategyId)
        val effectiveStake = stakeAmount.coerceIn(1.0, 100_000.0)
        val effectivePayout = payoutRate.coerceIn(0.10, 1.50)

        var equity = 0.0
        var peak = 0.0
        var maxDd = 0.0
        var grossProfit = 0.0
        var grossLoss = 0.0
        var wins = 0
        var losses = 0
        val equityCurve = mutableListOf(0.0)
        val trades = mutableListOf<BacktestTradeSample>()

        var idx = warmupBars
        val limitIdx = ordered.size - expiryBars - 1
        while (idx <= limitIdx) {
            val window = ordered.subList(max(0, idx - 80), idx + 1)
            val snap = TechnicalAnalysisEngine.analyze(window, cfg)
            var activeId = strategyId
            var activeName = resolvedName
            if (strategyId == "auto") {
                val report = LocalStrategySelectorEngine.evaluate(
                    candles = window,
                    currentRegime = snap.regime,
                    autoSelect = true
                )
                activeId = report.selectedStrategyId
                activeName = report.selectedStrategyName
            }

            val dir = LocalStrategySelectorEngine.evaluateBarDirection(activeId, window, cfg)
            if (dir == TradeDirection.NO_TRADE) {
                idx += 1
                continue
            }

            val entryBar = ordered[idx + 1]
            val exitBar = ordered[idx + expiryBars]
            val entryPrice = entryBar.open
            val exitPrice = exitBar.close
            val won = if (dir == TradeDirection.CALL) exitPrice > entryPrice else exitPrice < entryPrice
            val pnl = round2(if (won) effectiveStake * effectivePayout else -effectiveStake)

            if (won) {
                wins += 1
                grossProfit += pnl
            } else {
                losses += 1
                grossLoss += -pnl
            }

            equity = round2(equity + pnl)
            peak = max(peak, equity)
            maxDd = max(maxDd, round2(peak - equity))
            equityCurve.add(equity)

            trades.add(
                BacktestTradeSample(
                    timestampMillis = entryBar.timestampMillis,
                    direction = dir.name,
                    entryPrice = entryPrice,
                    exitPrice = exitPrice,
                    pnl = pnl,
                    status = if (won) "WON" else "LOST",
                    marketRegime = snap.regime.name,
                    strategyName = activeName
                )
            )
            idx += max(1, expiryBars)
        }

        val totalTrades = wins + losses
        val winRatePct = if (totalTrades > 0) round2((wins.toDouble() / totalTrades) * 100.0) else 0.0
        val profitFactor = when {
            grossLoss > 1e-9 -> round3(grossProfit / grossLoss)
            grossProfit > 0.0 -> round3(min(9.99, grossProfit))
            else -> 0.0
        }
        val expectancy = if (totalTrades > 0) round2(equity / totalTrades) else 0.0

        return Result.success(
            BacktestReport(
                instrument = instrument,
                timeframe = timeframe,
                strategyId = strategyId,
                strategyName = resolvedName,
                barsEvaluated = ordered.size,
                totalTrades = totalTrades,
                wins = wins,
                losses = losses,
                winRatePct = winRatePct,
                netPnl = round2(equity),
                grossProfit = round2(grossProfit),
                grossLoss = round2(grossLoss),
                profitFactor = profitFactor,
                maxDrawdown = round2(maxDd),
                expectancy = expectancy,
                equityCurve = equityCurve.takeLast(80),
                simulatedTrades = trades.takeLast(40),
                sourceLabel = "Local Walk-Forward SQLite"
            )
        )
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0
}
