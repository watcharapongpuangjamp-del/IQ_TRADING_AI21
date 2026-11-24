package com.example.engine

import com.example.model.Candle
import com.example.model.InstrumentSpec
import com.example.model.SUPPORTED_INSTRUMENTS
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates deterministic-seeded realistic OHLCV candlestick history and live market ticks
 * for built-in Simulation Mode when external FastAPI backend is not attached.
 */
class MarketSimulationEngine {

    private var trendBias = 0.08
    private var tickStep = 0

    fun generateInitialCandles(
        instrumentSymbol: String,
        timeframeSeconds: Int,
        count: Int = 80
    ): List<Candle> {
        val spec = findSpec(instrumentSymbol)
        val now = System.currentTimeMillis()
        val intervalMs = timeframeSeconds * 1000L
        val candles = ArrayList<Candle>(count)
        var price = spec.basePrice
        val rng = Random(instrumentSymbol.hashCode() + timeframeSeconds)

        for (i in count downTo 1) {
            val ts = now - (i * intervalMs)
            val wave = sin(i * 0.22) * spec.volatility * 0.9
            val noise = (rng.nextDouble() - 0.5) * spec.volatility * 2.2
            val delta = wave + noise
            val open = price
            val close = (open + delta).coerceAtLeast(spec.basePrice * 0.5)
            val wickUp = rng.nextDouble() * spec.volatility * 0.95
            val wickDown = rng.nextDouble() * spec.volatility * 0.95
            val high = max(open, close) + wickUp
            val low = min(open, close) - wickDown
            val volume = 120.0 + rng.nextDouble() * 480.0
            candles.add(Candle(ts, open, high, low, close, volume))
            price = close
        }
        return candles
    }

    fun nextTick(
        currentCandles: List<Candle>,
        instrumentSymbol: String,
        timeframeSeconds: Int
    ): List<Candle> {
        val spec = findSpec(instrumentSymbol)
        if (currentCandles.isEmpty()) {
            return generateInitialCandles(instrumentSymbol, timeframeSeconds)
        }

        tickStep++
        if (tickStep % 14 == 0) {
            trendBias = Random.nextDouble(-0.35, 0.35)
        }

        val last = currentCandles.last()
        val now = System.currentTimeMillis()
        val intervalMs = timeframeSeconds * 1000L
        val tickDelta = (trendBias * spec.volatility * 0.35) +
            ((Random.nextDouble() - 0.5) * spec.volatility * 1.15)
        val nextPrice = (last.close + tickDelta).coerceAtLeast(spec.basePrice * 0.5)

        val updated = currentCandles.toMutableList()
        if (now - last.timestampMillis >= intervalMs) {
            val newCandle = Candle(
                timestampMillis = now,
                open = last.close,
                high = max(last.close, nextPrice) + Random.nextDouble() * spec.volatility * 0.25,
                low = min(last.close, nextPrice) - Random.nextDouble() * spec.volatility * 0.25,
                close = nextPrice,
                volume = 80.0 + Random.nextDouble() * 220.0
            )
            updated.add(newCandle)
            if (updated.size > 120) {
                updated.removeAt(0)
            }
        } else {
            val amended = last.copy(
                high = max(last.high, nextPrice),
                low = min(last.low, nextPrice),
                close = nextPrice,
                volume = last.volume + Random.nextDouble() * 35.0
            )
            updated[updated.lastIndex] = amended
        }
        return updated
    }

    private fun findSpec(symbol: String): InstrumentSpec =
        SUPPORTED_INSTRUMENTS.firstOrNull { it.symbol == symbol } ?: SUPPORTED_INSTRUMENTS.first()
}
