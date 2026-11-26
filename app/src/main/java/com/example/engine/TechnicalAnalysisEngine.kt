package com.example.engine

import com.example.model.Candle
import com.example.model.MarketRegime
import com.example.model.StrategyConfig
import com.example.model.TechnicalSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
                regime = MarketRegime.UNKNOWN
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
        val (adx, plusDi, minusDi) = calculateAdx(candles, period = 14)
        val (stochK, stochD) = calculateStochastic(candles, kPeriod = 14, dPeriod = 3)
        val (vwap, vwapReliable) = calculateVwap(candles, window = 60)
        val vwapSeries = calculateVwapSeries(candles, window = 60)

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
        val priorWindow = if (candles.size > 2) candles.dropLast(1).takeLast(30) else candles.dropLast(1)
        val priorSupport = priorWindow.minOfOrNull { it.low } ?: supportLevel
        val priorResistance = priorWindow.maxOfOrNull { it.high } ?: resistanceLevel

        val pattern = detectCandlestickPattern(candles)
        val bbWidthPct = if (bbMiddleLast > 0.0) ((bbUpperLast - bbLowerLast) / bbMiddleLast) * 100.0 else 0.0
        val regime = classifyMarketRegime(
            trendStrength = trendStrength,
            volatilityPct = volatilityPct,
            bbWidthPct = bbWidthPct,
            adx = adx,
            rsi = rsiLast,
            lastClose = lastClose,
            priorSupport = priorSupport,
            priorResistance = priorResistance,
            pattern = pattern
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
            macdHistSeries = macdTuple.third,
            vwapSeries = vwapSeries,
            stochKSeries = stochK,
            stochDSeries = stochD,
            adx = adx,
            plusDi = plusDi,
            minusDi = minusDi,
            stochasticK = stochK.lastOrNull() ?: 50.0,
            stochasticD = stochD.lastOrNull() ?: 50.0,
            vwap = vwap,
            vwapReliable = vwapReliable
        )
    }

    fun calculateSmaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val p = period.coerceAtLeast(1)
        return values.indices.map { idx ->
            val start = max(0, idx - p + 1)
            values.subList(start, idx + 1).average()
        }
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

    fun calculateAdx(candles: List<Candle>, period: Int = 14): Triple<Double, Double, Double> {
        val p = period.coerceAtLeast(2)
        if (candles.size < p + 2) return Triple(0.0, 0.0, 0.0)

        val trs = ArrayList<Double>(candles.size - 1)
        val plusDm = ArrayList<Double>(candles.size - 1)
        val minusDm = ArrayList<Double>(candles.size - 1)

        for (i in 1 until candles.size) {
            val curr = candles[i]
            val prev = candles[i - 1]
            val upMove = curr.high - prev.high
            val downMove = prev.low - curr.low
            val pdm = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            val mdm = if (downMove > upMove && downMove > 0.0) downMove else 0.0
            val tr = max(curr.high - curr.low, max(abs(curr.high - prev.close), abs(curr.low - prev.close)))
            trs.add(tr)
            plusDm.add(pdm)
            minusDm.add(mdm)
        }

        var smoothTr = trs.take(p).sum()
        var smoothPdm = plusDm.take(p).sum()
        var smoothMdm = minusDm.take(p).sum()

        fun computeDiDx(st: Double, sp: Double, sm: Double): Triple<Double, Double, Double> {
            if (st <= 1e-12) return Triple(0.0, 0.0, 0.0)
            val pdi = 100.0 * (sp / st)
            val mdi = 100.0 * (sm / st)
            val denom = pdi + mdi
            val dx = if (denom > 1e-12) 100.0 * abs(pdi - mdi) / denom else 0.0
            return Triple(pdi, mdi, dx)
        }

        val dxValues = ArrayList<Double>()
        var (lastPdi, lastMdi, firstDx) = computeDiDx(smoothTr, smoothPdm, smoothMdm)
        dxValues.add(firstDx)

        for (i in p until trs.size) {
            smoothTr = smoothTr - (smoothTr / p) + trs[i]
            smoothPdm = smoothPdm - (smoothPdm / p) + plusDm[i]
            smoothMdm = smoothMdm - (smoothMdm / p) + minusDm[i]
            val (pdi, mdi, dx) = computeDiDx(smoothTr, smoothPdm, smoothMdm)
            lastPdi = pdi
            lastMdi = mdi
            dxValues.add(dx)
        }

        var adx = if (dxValues.size < p) {
            dxValues.average()
        } else {
            var acc = dxValues.take(p).average()
            for (i in p until dxValues.size) {
                acc = ((acc * (p - 1)) + dxValues[i]) / p
            }
            acc
        }
        adx = min(100.0, max(0.0, adx))
        return Triple(adx, lastPdi.coerceIn(0.0, 100.0), lastMdi.coerceIn(0.0, 100.0))
    }

    fun calculateStochastic(
        candles: List<Candle>,
        kPeriod: Int = 14,
        dPeriod: Int = 3
    ): Pair<List<Double>, List<Double>> {
        if (candles.isEmpty()) return emptyList<Double>() to emptyList()
        val kp = kPeriod.coerceAtLeast(2)
        val dp = dPeriod.coerceAtLeast(1)
        val kSeries = candles.indices.map { i ->
            val window = candles.subList(max(0, i - kp + 1), i + 1)
            val highest = window.maxOf { it.high }
            val lowest = window.minOf { it.low }
            val span = highest - lowest
            if (span <= 1e-12) 50.0
            else (((candles[i].close - lowest) / span) * 100.0).coerceIn(0.0, 100.0)
        }
        val dSeries = calculateSmaSeries(kSeries, dp)
        return kSeries to dSeries
    }

    fun calculateVwap(candles: List<Candle>, window: Int = 60): Pair<Double, Boolean> {
        if (candles.isEmpty()) return 0.0 to false
        val recent = candles.takeLast(window.coerceAtLeast(1))
        var positiveBars = 0
        var totalPv = 0.0
        var totalVol = 0.0
        val typicalPrices = ArrayList<Double>(recent.size)
        for (c in recent) {
            val tp = (c.high + c.low + c.close) / 3.0
            typicalPrices.add(tp)
            if (c.volume > 0.0) {
                positiveBars++
                totalPv += tp * c.volume
                totalVol += c.volume
            }
        }
        val reliable = totalVol > 0.0 && (positiveBars.toDouble() / recent.size) >= 0.25
        return if (reliable) (totalPv / totalVol) to true else typicalPrices.average() to false
    }

    fun calculateVwapSeries(candles: List<Candle>, window: Int = 60): List<Double> {
        if (candles.isEmpty()) return emptyList()
        val w = window.coerceAtLeast(1)
        val out = ArrayList<Double>(candles.size)
        for (i in candles.indices) {
            val start = max(0, i - w + 1)
            val slice = candles.subList(start, i + 1)
            var positiveBars = 0
            var totalPv = 0.0
            var totalVol = 0.0
            var tpSum = 0.0
            for (c in slice) {
                val tp = (c.high + c.low + c.close) / 3.0
                tpSum += tp
                if (c.volume > 0.0) {
                    positiveBars++
                    totalPv += tp * c.volume
                    totalVol += c.volume
                }
            }
            val reliable = totalVol > 0.0 && (positiveBars.toDouble() / slice.size) >= 0.25
            out.add(if (reliable) totalPv / totalVol else tpSum / slice.size)
        }
        return out
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
        trendStrength: Double,
        volatilityPct: Double,
        bbWidthPct: Double,
        adx: Double,
        rsi: Double,
        lastClose: Double,
        priorSupport: Double,
        priorResistance: Double,
        pattern: String
    ): MarketRegime {
        return when {
            volatilityPct > 0.35 || bbWidthPct > 1.2 -> MarketRegime.HIGH_VOLATILITY
            lastClose > 0.0 && priorResistance > 0.0 &&
                (lastClose > priorResistance || lastClose < priorSupport) -> MarketRegime.BREAKOUT
            (rsi <= 26.0 || rsi >= 74.0) &&
                (pattern.contains("Engulfing") || pattern.contains("Hammer") || pattern.contains("Shooting Star")) ->
                MarketRegime.REVERSAL
            adx >= 26.0 || trendStrength >= 0.75 -> MarketRegime.STRONG_TREND
            adx >= 18.0 || trendStrength >= 0.40 -> MarketRegime.WEAK_TREND
            volatilityPct < 0.035 && bbWidthPct < 0.18 -> MarketRegime.LOW_VOLATILITY
            else -> MarketRegime.SIDEWAYS
        }
    }
}
