package com.example.engine

import com.example.model.Candle
import com.example.model.MarketRegime
import com.example.model.SignalAnalysis
import com.example.model.StrategyConfig
import com.example.model.TechnicalSnapshot
import com.example.model.TradeDirection
import kotlin.math.abs
import kotlin.math.exp

/**
 * Evaluates multiple evidence sources to produce a transparent CALL / PUT / NO_TRADE decision.
 * Separates model confidenceScore (0..100) from calibratedProbability (0.45..0.72) to avoid
 * overconfident probability estimates.
 */
object SignalEngine {

    fun evaluate(
        candles: List<Candle>,
        snapshot: TechnicalSnapshot,
        strategy: StrategyConfig
    ): SignalAnalysis {
        if (candles.size < 25) {
            return SignalAnalysis(
                instrument = strategy.instrument,
                timeframe = strategy.timeframe,
                direction = TradeDirection.NO_TRADE,
                calibratedProbability = 0.50,
                confidenceScore = 0,
                supportingEvidence = emptyList(),
                contradictingEvidence = listOf("จำนวนแท่งเทียนยังไม่ครบสำหรับประเมินหลายตัวชี้วัด"),
                regime = snapshot.regime,
                riskAssessment = "งดออกคำสั่งจนกว่าข้อมูลจะครบ 25 แท่ง",
                explanation = "รอข้อมูลกราฟสะสมเพิ่มเติมเพื่อยืนยันสัญญาณ",
                expiresAtMillis = System.currentTimeMillis() + (strategy.expirySeconds * 1000L)
            )
        }

        val lastPrice = candles.last().close
        val bullEvidence = mutableListOf<String>()
        val bearEvidence = mutableListOf<String>()
        var bullPoints = 0.0
        var bearPoints = 0.0

        // 1. EMA Trend Alignment
        if (snapshot.emaFast > snapshot.emaSlow) {
            bullPoints += 22.0
            bullEvidence.add("EMA${strategy.emaFast} อยู่เหนือ EMA${strategy.emaSlow} สนับสนุนโมเมนตัมขาขึ้น")
        } else if (snapshot.emaFast < snapshot.emaSlow) {
            bearPoints += 22.0
            bearEvidence.add("EMA${strategy.emaFast} อยู่ต่ำกว่า EMA${strategy.emaSlow} สนับสนุนโมเมนตัมขาลง")
        }

        // 2. RSI Mean-Reversion & Momentum Zones
        when {
            snapshot.rsi <= strategy.rsiOversold -> {
                bullPoints += 24.0
                bullEvidence.add("RSI (${format2(snapshot.rsi)}) อยู่ในเขต Oversold <= ${strategy.rsiOversold}")
            }
            snapshot.rsi >= strategy.rsiOverbought -> {
                bearPoints += 24.0
                bearEvidence.add("RSI (${format2(snapshot.rsi)}) อยู่ในเขต Overbought >= ${strategy.rsiOverbought}")
            }
            snapshot.rsi in 52.0..66.0 -> {
                bullPoints += 12.0
                bullEvidence.add("RSI (${format2(snapshot.rsi)}) อยู่ในโซนแรงซื้อสมดุล")
            }
            snapshot.rsi in 34.0..48.0 -> {
                bearPoints += 12.0
                bearEvidence.add("RSI (${format2(snapshot.rsi)}) อยู่ในโซนแรงขายสมดุล")
            }
        }

        // 3. MACD Histogram & Signal Line
        val prevHist = snapshot.macdHistSeries.dropLast(1).lastOrNull() ?: snapshot.macdHistogram
        if (snapshot.macdLine > snapshot.macdSignal && snapshot.macdHistogram > 0) {
            val expanding = snapshot.macdHistogram > prevHist
            bullPoints += if (expanding) 22.0 else 15.0
            bullEvidence.add("MACD เหนือ Signal Line" + if (expanding) " และ Histogram ขยายตัว" else "")
        } else if (snapshot.macdLine < snapshot.macdSignal && snapshot.macdHistogram < 0) {
            val expanding = snapshot.macdHistogram < prevHist
            bearPoints += if (expanding) 22.0 else 15.0
            bearEvidence.add("MACD ต่ำกว่า Signal Line" + if (expanding) " และ Histogram กดตัวลง" else "")
        }

        // 4. Bollinger Bands Position
        when {
            lastPrice <= snapshot.bbLower -> {
                bullPoints += 18.0
                bullEvidence.add("ราคาทดสอบขอบล่าง Bollinger Band มีโอกาสรีบาวด์")
            }
            lastPrice >= snapshot.bbUpper -> {
                bearPoints += 18.0
                bearEvidence.add("ราคาทดสอบขอบบน Bollinger Band มีโอกาสย่อตัว")
            }
        }

        // 5. Candlestick Pattern Confirmation
        when {
            snapshot.candlestickPattern.contains("Bullish") ||
                snapshot.candlestickPattern.contains("Hammer") -> {
                bullPoints += 14.0
                bullEvidence.add("รูปแบบแท่งเทียน: ${snapshot.candlestickPattern}")
            }
            snapshot.candlestickPattern.contains("Bearish") ||
                snapshot.candlestickPattern.contains("Shooting Star") -> {
                bearPoints += 14.0
                bearEvidence.add("รูปแบบแท่งเทียน: ${snapshot.candlestickPattern}")
            }
        }

        // 6. Support / Resistance Proximity
        val range = (snapshot.resistanceLevel - snapshot.supportLevel).coerceAtLeast(1e-9)
        val posInRange = (lastPrice - snapshot.supportLevel) / range
        if (posInRange <= 0.15) {
            bullPoints += 10.0
            bullEvidence.add("ราคาใกล้แนวรับสำคัญ (${formatPrice(snapshot.supportLevel)})")
        } else if (posInRange >= 0.85) {
            bearPoints += 10.0
            bearEvidence.add("ราคาใกล้แนวต้านสำคัญ (${formatPrice(snapshot.resistanceLevel)})")
        }

        val netDiff = abs(bullPoints - bearPoints)
        val dominantBull = bullPoints >= bearPoints

        // Volatility / Regime penalty to prevent overtrading in chaotic markets
        val regimePenalty = when (snapshot.regime) {
            MarketRegime.HIGH_VOLATILITY -> 14
            MarketRegime.RANGING -> if (netDiff < 30.0) 8 else 0
            MarketRegime.TRENDING_UP -> if (dominantBull) 0 else 10
            MarketRegime.TRENDING_DOWN -> if (!dominantBull) 0 else 10
        }

        val rawConfidence = ((maxOf(bullPoints, bearPoints) * 0.65) + (netDiff * 0.45) - regimePenalty)
            .toInt()
            .coerceIn(10, 96)

        // Conservative calibrated win probability via bounded logistic mapping (never equals confidence)
        val logistic = 1.0 / (1.0 + exp(-(rawConfidence - 55.0) / 18.0))
        val calibratedProb = (0.46 + (logistic * 0.24)).coerceIn(0.46, 0.70)

        val supporting = if (dominantBull) bullEvidence else bearEvidence
        val contradicting = (if (dominantBull) bearEvidence else bullEvidence).toMutableList()

        if (snapshot.regime == MarketRegime.HIGH_VOLATILITY) {
            contradicting.add("สภาวะตลาดผันผวนสูง (ATR ${format2(snapshot.volatilityPct)}%) เพิ่มความเสี่ยงไส้เทียน")
        }

        val direction = when {
            rawConfidence < strategy.minConfidence -> TradeDirection.NO_TRADE
            netDiff < 18.0 -> TradeDirection.NO_TRADE
            dominantBull -> TradeDirection.CALL
            else -> TradeDirection.PUT
        }

        val riskAssessment = when {
            direction == TradeDirection.NO_TRADE ->
                "เกณฑ์ความมั่นใจ (${rawConfidence}%) ต่ำกว่าเป้าหมาย (${strategy.minConfidence}%) หรือสัญญาณขัดแย้ง — แนะนำ NO TRADE"
            snapshot.regime == MarketRegime.HIGH_VOLATILITY ->
                "ความเสี่ยงสูงจากความผันผวน ควรจำกัดขนาดไม้และเฝ้าระวัง Stop-Loss"
            else ->
                "สภาวะตลาดสอดคล้องกับกลยุทธ์ ความเสี่ยงอยู่ในเกณฑ์ควบคุมได้"
        }

        val explanation = when (direction) {
            TradeDirection.CALL -> "สัญญาณ CALL: ${supporting.take(2).joinToString(" • ")}"
            TradeDirection.PUT -> "สัญญาณ PUT: ${supporting.take(2).joinToString(" • ")}"
            TradeDirection.NO_TRADE -> "NO TRADE: สัญญาณยังไม่ชัดเจนพอ (Confidence $rawConfidence% < ${strategy.minConfidence}%)"
        }

        return SignalAnalysis(
            instrument = strategy.instrument,
            timeframe = strategy.timeframe,
            direction = direction,
            calibratedProbability = calibratedProb,
            confidenceScore = rawConfidence,
            supportingEvidence = supporting,
            contradictingEvidence = contradicting,
            regime = snapshot.regime,
            riskAssessment = riskAssessment,
            explanation = explanation,
            expiresAtMillis = System.currentTimeMillis() + (strategy.expirySeconds * 1000L)
        )
    }

    private fun format2(value: Double): String = String.format("%.2f", value)
    private fun formatPrice(value: Double): String =
        if (value >= 100.0) String.format("%.2f", value) else String.format("%.5f", value)
}
