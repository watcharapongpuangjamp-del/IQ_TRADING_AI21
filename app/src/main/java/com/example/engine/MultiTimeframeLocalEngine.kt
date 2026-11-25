package com.example.engine

import com.example.model.AlignmentState
import com.example.model.Candle
import com.example.model.DataQualitySummary
import com.example.model.MultiTimeframeReport
import com.example.model.StrategyConfig
import com.example.model.TimeframeIntelligenceSummary
import com.example.model.TradeDirection

object MultiTimeframeLocalEngine {

    fun inspectDataQuality(candles: List<Candle>): DataQualitySummary {
        if (candles.size < 25) {
            return DataQualitySummary(
                valid = false,
                reason = "ต้องมีแท่งเทียนอย่างน้อย 25 แท่ง",
                candleCount = candles.size
            )
        }
        var duplicateCount = 0
        var gapCount = 0
        var outlierCount = 0

        for (i in 1 until candles.size) {
            val prev = candles[i - 1]
            val curr = candles[i]
            if (curr.timestampMillis <= prev.timestampMillis) duplicateCount++
            if (prev.close > 0.0) {
                val pctJump = kotlin.math.abs(curr.close - prev.close) / prev.close
                if (pctJump > 0.08) outlierCount++
            }
            if (i >= 2) {
                val step = curr.timestampMillis - prev.timestampMillis
                val prevStep = prev.timestampMillis - candles[i - 2].timestampMillis
                if (prevStep > 0 && step > prevStep * 2.5) gapCount++
            }
        }

        val valid = duplicateCount == 0 && outlierCount == 0
        val reason = when {
            duplicateCount > 0 -> "พบแท่งเทียนซ้ำ $duplicateCount แท่ง"
            outlierCount > 0 -> "พบราคาผิดปกติ $outlierCount จุด"
            gapCount > 0 -> "ข้อมูลผ่านเกณฑ์ (พบ Gap $gapCount ช่วง)"
            else -> "Passed OHLCV validation"
        }
        return DataQualitySummary(
            valid = valid,
            reason = reason,
            candleCount = candles.size,
            gapCount = gapCount,
            outlierCount = outlierCount,
            duplicateCount = duplicateCount
        )
    }

    fun resampleCandles(candles: List<Candle>, bucketSize: Int): List<Candle> {
        if (candles.isEmpty() || bucketSize <= 1) return candles
        return candles.chunked(bucketSize).map { group ->
            Candle(
                timestampMillis = group.first().timestampMillis,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume }
            )
        }
    }

    fun evaluateLocalMtf(
        instrument: String,
        candles1m: List<Candle>,
        strategy: StrategyConfig
    ): MultiTimeframeReport {
        val c5m = resampleCandles(candles1m, 5)
        val c15m = resampleCandles(candles1m, 15)

        val s1m = summarize("1m", candles1m, strategy)
        val s5m = summarize("5m", c5m, strategy)
        val s15m = summarize("15m", c15m, strategy)

        val summaries = listOf(s1m, s5m, s15m)
        val higherBias = when {
            s5m.trendBias == s15m.trendBias && s5m.trendBias != TradeDirection.NO_TRADE -> s5m.trendBias
            s15m.trendBias != TradeDirection.NO_TRADE -> s15m.trendBias
            else -> s5m.trendBias
        }

        val (alignmentState, alignmentScore, note) = when {
            s1m.trendBias == TradeDirection.CALL &&
                s5m.trendBias == TradeDirection.CALL &&
                s15m.trendBias != TradeDirection.PUT ->
                Triple(AlignmentState.ALIGNED_BULLISH, 88, "1m, 5m และ 15m สอดคล้องฝั่งขาขึ้น")
            s1m.trendBias == TradeDirection.PUT &&
                s5m.trendBias == TradeDirection.PUT &&
                s15m.trendBias != TradeDirection.CALL ->
                Triple(AlignmentState.ALIGNED_BEARISH, 88, "1m, 5m และ 15m สอดคล้องฝั่งขาลง")
            s1m.trendBias != TradeDirection.NO_TRADE &&
                higherBias != TradeDirection.NO_TRADE &&
                s1m.trendBias != higherBias ->
                Triple(AlignmentState.CONFLICTING, 35, "1m ขัดแย้งกับเทรนด์หลัก 5m/15m")
            else ->
                Triple(AlignmentState.MIXED, 55, "กำลังสะสมแรง — รอการยืนยันจาก 5m/15m")
        }

        return MultiTimeframeReport(
            instrument = instrument,
            primaryTimeframe = "1m",
            alignmentState = alignmentState,
            alignmentScore = alignmentScore,
            higherTfBias = higherBias,
            timeframeSummaries = summaries,
            alignmentNotes = listOf(note)
        )
    }

    private fun summarize(
        timeframe: String,
        candles: List<Candle>,
        strategy: StrategyConfig
    ): TimeframeIntelligenceSummary {
        val snap = TechnicalAnalysisEngine.analyze(candles, strategy)
        val closes = candles.map { it.close }
        val smaFast = if (closes.size >= 10) closes.takeLast(10).average() else closes.lastOrNull() ?: 0.0
        val smaSlow = if (closes.size >= 30) closes.takeLast(30).average() else closes.averageOrZero()
        val adxConfirmsBull = snap.adx >= 22.0 && snap.plusDi > snap.minusDi
        val adxConfirmsBear = snap.adx >= 22.0 && snap.minusDi > snap.plusDi
        val bias = when {
            candles.size < 8 -> TradeDirection.NO_TRADE
            snap.emaFast > snap.emaSlow && (snap.macdHistogram >= 0 || adxConfirmsBull) -> TradeDirection.CALL
            snap.emaFast < snap.emaSlow && (snap.macdHistogram <= 0 || adxConfirmsBear) -> TradeDirection.PUT
            snap.emaFast > snap.emaSlow -> TradeDirection.CALL
            snap.emaFast < snap.emaSlow -> TradeDirection.PUT
            else -> TradeDirection.NO_TRADE
        }
        return TimeframeIntelligenceSummary(
            timeframe = timeframe,
            trendBias = bias,
            regime = snap.regime,
            rsi = snap.rsi,
            emaFast = snap.emaFast,
            emaSlow = snap.emaSlow,
            smaFast = smaFast,
            smaSlow = smaSlow,
            macdHistogram = snap.macdHistogram,
            trendStrength = snap.trendStrength,
            latestClose = candles.lastOrNull()?.close ?: 0.0
        )
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
