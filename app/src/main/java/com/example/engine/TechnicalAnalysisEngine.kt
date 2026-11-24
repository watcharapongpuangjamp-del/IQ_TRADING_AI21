package com.example.engine

import com.example.model.Candle
import com.example.model.MarketRegime
import com.example.model.StrategyConfig
import com.example.model.TechnicalSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object TechnicalAnalysisEngine {

    fun analyze(candles: List<Candle>, strategy: StrategyConfig): TechnicalSnapshot {
        if (candles.size < 20) {
            val lastClose = candles.lastOrNull()?.close ?: 1.0
            return TechnicalSnapshot(
                rsi = 50.0,
                macdLine = 0.0,
                macdSignal = 0.0,
                macdHistogram = 0.0,
                emaFast = lastClose,
                emaSlow = lastClose,
                bbUpper = lastClose,
                bbMiddle = lastClose,
                bbLower = lastClose,
                atr = 0.0,
                volatilityPct = 0.0,
                trendStrength = 0.0,
                supportLevel = lastClose,
                resistanceLevel = lastClose,
                candlestickPattern = "ข้อมูลยังไม่เพียงพอ",
                regime = MarketRegime.RANGING
            )
        }

        val closes = candles.map { it.close }
        val emaFastSeries = calculateEmaSeries(closes, strategy.emaFast)
        val emaSlowSeries = calculateEmaSeries(closes, strategy.emaSlow)
        val rsiSeries = calculateRsiSeries(closes, strategy.rsiPeriod)
        val macdTuple = calculateMacdSeries(
            closes = closes,
            fastPeriod = strategy.macdFast,
            slowPeriod = strategy.macdSlow,
            signalPeriod = strategy.macdSignal
        )
        val bbTuple = calculateBollingerSeries(
            closes = closes,
            period = strategy.bbPeriod,
            stdDevMultiplier = strategy.bbStdDev
        )
        val atr = calculateAtr(candles, period = 14)

        val lastClose = closes.last()
        val emaFastLast = emaFastSeries.lastOrNull() ?: lastClose
        val emaSlowLast = emaSlowSeries.lastOrNull() ?: lastClose
        val rsiLast = rsiSeries.lastOrNull() ?: 50.0
        val macdLineLast = macdTuple.first.lastOrNull() ?: 0.0
        val macdSignalLast = macdTuple.second.lastOrNull() ?: 0.0
        val macdHistLast = macdTuple.third.lastOrNull() ?: 0.0
        val bbUpperLast = bbTuple.first.lastOrNull() ?: lastClose
        val bbMiddleLast = bbTuple.second.lastOrNull() ?: lastClose
        val bbLowerLast = bbTuple.third.lastOrNull() ?: lastClose

        val volatilityPct = if (lastClose > 0.0) (atr / lastClose) * 100.0 else 0.0
        val trendSpreadPct = if (emaSlowLast > 0.0) ((emaFastLast - emaSlowLast) / emaSlowLast) * 100.0 else 0.0
        val trendStrength = (abs(trendSpreadPct) / max(volatilityPct, 0.01)).coerceIn(0.0, 3.0)

        val recentWindow = candles.takeLast(30)
        val supportLevel = recentWindow.minOf { it.low }
        val resistanceLevel = recentWindow.maxOf { it.high }

        val pattern = detectCandlestickPattern(candles)
        val regime = classifyMarketRegime(
            emaFast = emaFastLast,
            emaSlow = emaSlowLast,
            trendStrength = trendStrength,
            volatilityPct = volatilityPct,
            bbWidthPct = if (bbMiddleLast > 0.0) ((bbUpperLast - bbLowerLast) / bbMiddleLast) * 100.0 else 0.0
        )

        return TechnicalSnapshot(
            rsi = rsiLast,
            macdLine = macdLineLast,
            macdSignal = macdSignalLast,
            macdHistogram = macdHistLast,
            emaFast = emaFastLast,
            emaSlow = emaSlowLast,
            bbUpper = bbUpperLast,
            bbMiddle = bbMiddleLast,
            bbLower = bbLowerLast,
            atr = atr,
            volatilityPct = volatilityPct,
            trendStrength = trendStrength,
            supportLevel = supportLevel,
            resistanceLevel = resistanceLevel,
            candlestickPattern = pattern,
            regime = regime,
            emaFastSeries = emaFastSeries,
            emaSlowSeries = emaSlowSeries,
            bbUpperSeries = bbTuple.first,
            bbLowerSeries = bbTuple.third,
            rsiSeries = rsiSeries,
            macdHistSeries = macdTuple.third
        )
    }

    fun calculateEmaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val p = period.coerceAtLeast(1)
        val k = 2.0 / (p + 1.0)
        val result = ArrayList<Double>(values.size)
        var prev = values.first()
        result.add(prev)
        for (i in 1 until values.size) {
            prev = values[i] * k + prev * (1.0 - k)
            result.add(prev)
        }
        return result
    }

    fun calculateRsiSeries(closes: List<Double>, period: Int): List<Double> {
        if (closes.size < 2) return List(closes.size) { 50.0 }
        val p = period.coerceAtLeast(2)
        val rsi = MutableList(closes.size) { 50.0 }
        var avgGain = 0.0
        var avgLoss = 0.0

        val warmupEnd = minOf(p, closes.size - 1)
        for (i in 1..warmupEnd) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change else avgLoss += abs(change)
        }
        avgGain /= p
        avgLoss /= p

        if (warmupEnd < closes.size) {
            rsi[warmupEnd] = computeRsiFromAverages(avgGain, avgLoss)
        }

        for (i in (warmupEnd + 1) until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0
            avgGain = ((avgGain * (p - 1)) + gain) / p
            avgLoss = ((avgLoss * (p - 1)) + loss) / p
            rsi[i] = computeRsiFromAverages(avgGain, avgLoss)
        }
        return rsi
    }

    private fun computeRsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss <= 1e-12 && avgGain <= 1e-12) return 50.0
        if (avgLoss <= 1e-12) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun calculateMacdSeries(
        closes: List<Double>,
        fastPeriod: Int,
        slowPeriod: Int,
        signalPeriod: Int
    ): Triple<List<Double>, List<Double>, List<Double>> {
        val fast = calculateEmaSeries(closes, fastPeriod)
        val slow = calculateEmaSeries(closes, slowPeriod.coerceAtLeast(fastPeriod + 1))
        val macdLine = fast.indices.map { fast[it] - slow[it] }
        val signalLine = calculateEmaSeries(macdLine, signalPeriod)
        val histogram = macdLine.indices.map { macdLine[it] - signalLine[it] }
        return Triple(macdLine, signalLine, histogram)
    }

    fun calculateBollingerSeries(
        closes: List<Double>,
        period: Int,
        stdDevMultiplier: Double
    ): Triple<List<Double>, List<Double>, List<Double>> {
        val p = period.coerceAtLeast(2)
        val upper = ArrayList<Double>(closes.size)
        val middle = ArrayList<Double>(closes.size)
        val lower = ArrayList<Double>(closes.size)

        for (i in closes.indices) {
            val start = max(0, i - p + 1)
            val window = closes.subList(start, i + 1)
            val mean = window.average()
            val variance = window.sumOf { (it - mean) * (it - mean) } / window.size
            val std = sqrt(variance)
            middle.add(mean)
            upper.add(mean + stdDevMultiplier * std)
            lower.add(mean - stdDevMultiplier * std)
        }
        return Triple(upper, middle, lower)
    }

    fun calculateAtr(candles: List<Candle>, period: Int): Double {
        if (candles.size < 2) return 0.0
        val p = period.coerceAtLeast(2)
        val trueRanges = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val curr = candles[i]
            val prevClose = candles[i - 1].close
            val tr = max(
                curr.high - curr.low,
                max(abs(curr.high - prevClose), abs(curr.low - prevClose))
            )
            trueRanges.add(tr)
        }
        return trueRanges.takeLast(p).average()
    }

    fun detectCandlestickPattern(candles: List<Candle>): String {
        if (candles.size < 2) return "ปกติ"
        val prev = candles[candles.size - 2]
        val curr = candles.last()
        val body = abs(curr.close - curr.open)
        val range = (curr.high - curr.low).coerceAtLeast(1e-9)
        val upperWick = curr.high - max(curr.open, curr.close)
        val lowerWick = minOf(curr.open, curr.close) - curr.low

        val isBullish = curr.close > curr.open
        val prevBearish = prev.close < prev.open
        val prevBullish = prev.close > prev.open

        return when {
            body / range < 0.15 -> "Doji (ลังเลทิศทาง)"
            isBullish && prevBearish && curr.close >= prev.open && curr.open <= prev.close ->
                "Bullish Engulfing (กลืนกินขาขึ้น)"
            !isBullish && prevBullish && curr.open >= prev.close && curr.close <= prev.open ->
                "Bearish Engulfing (กลืนกินขาลง)"
            lowerWick > body * 2.0 && upperWick < body * 0.5 ->
                "Hammer / Pin Bar (แรงซื้อสวนกลับ)"
            upperWick > body * 2.0 && lowerWick < body * 0.5 ->
                "Shooting Star (แรงขายกดดัน)"
            isBullish -> "Bullish Candle"
            else -> "Bearish Candle"
        }
    }

    private fun classifyMarketRegime(
        emaFast: Double,
        emaSlow: Double,
        trendStrength: Double,
        volatilityPct: Double,
        bbWidthPct: Double
    ): MarketRegime {
        return when {
            volatilityPct > 0.35 || bbWidthPct > 1.2 -> MarketRegime.HIGH_VOLATILITY
            trendStrength >= 0.45 && emaFast > emaSlow -> MarketRegime.TRENDING_UP
            trendStrength >= 0.45 && emaFast < emaSlow -> MarketRegime.TRENDING_DOWN
            else -> MarketRegime.RANGING
        }
    }
}
