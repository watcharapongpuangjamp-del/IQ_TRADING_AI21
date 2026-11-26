package com.example.engine

import com.example.model.ExecutionMode
import com.example.model.ModePerformanceSummary
import com.example.model.PerformanceAnalyticsReport
import com.example.model.PerformanceBreakdownRow
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Pure Kotlin Performance Analytics engine calculating mode-isolated metrics
 * (SIMULATION vs LIVE) and dimensional breakdowns by Strategy, Regime, Asset, and Timeframe.
 */
object PerformanceAnalyticsEngine {

    fun buildReport(
        trades: List<TradeRecord>,
        modeFilter: String = "SIMULATION"
    ): PerformanceAnalyticsReport {
        val normalizedFilter = modeFilter.trim().uppercase().let {
            if (it in setOf("SIMULATION", "LIVE", "DEMO", "ALL")) it else "SIMULATION"
        }

        val settled = trades.filter {
            it.status == TradeStatus.WON.name || it.status == TradeStatus.LOST.name
        }
        val simTrades = settled.filter {
            ExecutionMode.safeValueOf(it.mode) == ExecutionMode.SIMULATION
        }
        val liveTrades = settled.filter {
            ExecutionMode.safeValueOf(it.mode) == ExecutionMode.LIVE
        }
        val demoTrades = settled.filter {
            ExecutionMode.safeValueOf(it.mode) == ExecutionMode.DEMO
        }

        val (activeLabel, filtered) = when (normalizedFilter) {
            "ALL" -> "ALL (LABELED)" to settled
            "LIVE" -> "LIVE" to liveTrades
            "DEMO" -> "DEMO" to demoTrades
            else -> "SIMULATION" to simTrades
        }

        return PerformanceAnalyticsReport(
            modeFilter = normalizedFilter,
            simulationSummary = computeSummary("SIMULATION", simTrades),
            liveSummary = computeSummary("LIVE", liveTrades),
            activeSummary = computeSummary(activeLabel, filtered),
            byStrategy = buildBreakdown(filtered) { it.strategyName.ifBlank { "Strategy" } },
            byRegime = buildBreakdown(filtered) { it.marketRegime.ifBlank { "UNKNOWN" } },
            byAsset = buildBreakdown(filtered) { it.instrument.ifBlank { "UNKNOWN" } },
            byTimeframe = buildBreakdown(filtered) { it.timeframe.ifBlank { "1m" } }
        )
    }

    fun computeSummary(modeLabel: String, settled: List<TradeRecord>): ModePerformanceSummary {
        if (settled.isEmpty()) {
            return ModePerformanceSummary(mode = modeLabel)
        }
        val ordered = settled.sortedBy { it.createdAtMillis }
        val total = ordered.size
        val wins = ordered.count { it.status == TradeStatus.WON.name }
        val losses = ordered.count { it.status == TradeStatus.LOST.name }
        val grossProfit = ordered.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = abs(ordered.filter { it.pnl < 0.0 }.sumOf { it.pnl })
        val netPnl = ordered.sumOf { it.pnl }

        var equity = 0.0
        var peak = 0.0
        var maxDd = 0.0
        var curWinStreak = 0
        var maxWinStreak = 0
        var curLossStreak = 0
        var maxLossStreak = 0
        var totalReturnPct = 0.0

        ordered.forEach { t ->
            val stake = max(1.0, t.amount)
            totalReturnPct += (t.pnl / stake) * 100.0
            equity += t.pnl
            peak = max(peak, equity)
            maxDd = max(maxDd, peak - equity)
            if (t.status == TradeStatus.WON.name) {
                curWinStreak++
                curLossStreak = 0
                maxWinStreak = max(maxWinStreak, curWinStreak)
            } else {
                curLossStreak++
                curWinStreak = 0
                maxLossStreak = max(maxLossStreak, curLossStreak)
            }
        }

        val winRate = (wins.toDouble() / total) * 100.0
        val pf = when {
            grossLoss > 1e-9 -> grossProfit / grossLoss
            grossProfit > 0.0 -> min(9.99, grossProfit)
            else -> 0.0
        }

        return ModePerformanceSummary(
            mode = modeLabel,
            totalTrades = total,
            wins = wins,
            losses = losses,
            winRatePct = round2(winRate),
            netPnl = round2(netPnl),
            grossProfit = round2(grossProfit),
            grossLoss = round2(grossLoss),
            profitFactor = round3(pf),
            averageReturnPct = round2(totalReturnPct / total),
            maxDrawdown = round2(maxDd),
            maxConsecutiveWins = maxWinStreak,
            maxConsecutiveLosses = maxLossStreak,
            expectancy = round2(netPnl / total)
        )
    }

    private fun buildBreakdown(
        settled: List<TradeRecord>,
        keySelector: (TradeRecord) -> String
    ): List<PerformanceBreakdownRow> {
        return settled
            .groupBy { keySelector(it).trim().ifBlank { "UNKNOWN" } }
            .map { (label, group) ->
                val summary = computeSummary(label, group)
                PerformanceBreakdownRow(
                    label = label,
                    totalTrades = summary.totalTrades,
                    wins = summary.wins,
                    losses = summary.losses,
                    winRatePct = summary.winRatePct,
                    netPnl = summary.netPnl,
                    profitFactor = summary.profitFactor
                )
            }
            .sortedWith(compareByDescending<PerformanceBreakdownRow> { it.netPnl }.thenByDescending { it.totalTrades })
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0
}
