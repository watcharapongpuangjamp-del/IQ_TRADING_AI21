import math
from typing import Dict, List, Tuple

from app.domain import (
    MarketRegime,
    StrategyConfig,
    TechnicalSnapshot,
    TimeframeIntelligence,
    TradeDirection,
)


class TechnicalAnalysisEngine:
    """Computes technical indicators (RSI, MACD, EMA, SMA, Bollinger Bands, ATR, Candlestick patterns)."""

    @classmethod
    def analyze(cls, candles: List[Dict[str, float]], strategy: StrategyConfig) -> TechnicalSnapshot:
        closes = [float(c["close"]) for c in candles]
        ema_fast_series = cls.calculate_ema(closes, strategy.ema_fast)
        ema_slow_series = cls.calculate_ema(closes, strategy.ema_slow)
        sma_fast_series = cls.calculate_sma(closes, strategy.sma_fast)
        sma_slow_series = cls.calculate_sma(closes, strategy.sma_slow)
        rsi_series = cls.calculate_rsi(closes, strategy.rsi_period)
        macd_line, macd_sig, macd_hist = cls.calculate_macd(
            closes, strategy.macd_fast, strategy.macd_slow, strategy.macd_signal
        )
        bb_upper, bb_middle, bb_lower = cls.calculate_bollinger_bands(
            closes, strategy.bb_period, strategy.bb_std_dev
        )
        atr = cls.calculate_atr(candles, 14)

        last_close = closes[-1]
        ema_fast = ema_fast_series[-1]
        ema_slow = ema_slow_series[-1]
        sma_fast = sma_fast_series[-1]
        sma_slow = sma_slow_series[-1]
        volatility_pct = (atr / last_close) * 100.0 if last_close > 0 else 0.0
        spread_pct = ((ema_fast - ema_slow) / ema_slow) * 100.0 if ema_slow > 0 else 0.0
        trend_strength = min(3.0, max(0.0, abs(spread_pct) / max(volatility_pct, 0.01)))

        recent = candles[-30:]
        support = min(float(c["low"]) for c in recent)
        resistance = max(float(c["high"]) for c in recent)
        pattern = cls.detect_candlestick_pattern(candles)
        bb_width_pct = ((bb_upper[-1] - bb_lower[-1]) / bb_middle[-1]) * 100.0 if bb_middle[-1] > 0 else 0.0
        regime = cls.classify_regime(ema_fast, ema_slow, trend_strength, volatility_pct, bb_width_pct)

        return TechnicalSnapshot(
            rsi=round(rsi_series[-1], 2),
            macd_line=round(macd_line[-1], 6),
            macd_signal=round(macd_sig[-1], 6),
            macd_histogram=round(macd_hist[-1], 6),
            ema_fast=round(ema_fast, 6),
            ema_slow=round(ema_slow, 6),
            sma_fast=round(sma_fast, 6),
            sma_slow=round(sma_slow, 6),
            bb_upper=round(bb_upper[-1], 6),
            bb_middle=round(bb_middle[-1], 6),
            bb_lower=round(bb_lower[-1], 6),
            atr=round(atr, 6),
            volatility_pct=round(volatility_pct, 4),
            trend_strength=round(trend_strength, 3),
            support_level=round(support, 6),
            resistance_level=round(resistance, 6),
            candlestick_pattern=pattern,
            regime=regime,
        )

    @classmethod
    def summarize_timeframe(
        cls,
        candles: List[Dict[str, float]],
        timeframe: str,
        strategy: StrategyConfig,
    ) -> TimeframeIntelligence:
        snap = cls.analyze(candles, strategy)
        latest_close = float(candles[-1]["close"])

        bull_score = 0
        bear_score = 0
        if snap.ema_fast > snap.ema_slow:
            bull_score += 2
        elif snap.ema_fast < snap.ema_slow:
            bear_score += 2

        if snap.sma_fast > snap.sma_slow:
            bull_score += 1
        elif snap.sma_fast < snap.sma_slow:
            bear_score += 1

        if snap.macd_histogram > 0:
            bull_score += 1
        elif snap.macd_histogram < 0:
            bear_score += 1

        if bull_score >= 3 and snap.rsi < strategy.rsi_overbought:
            bias = TradeDirection.CALL
        elif bear_score >= 3 and snap.rsi > strategy.rsi_oversold:
            bias = TradeDirection.PUT
        elif snap.regime == MarketRegime.TRENDING_UP:
            bias = TradeDirection.CALL
        elif snap.regime == MarketRegime.TRENDING_DOWN:
            bias = TradeDirection.PUT
        else:
            bias = TradeDirection.NO_TRADE

        return TimeframeIntelligence(
            timeframe=timeframe,
            trend_bias=bias,
            regime=snap.regime,
            rsi=snap.rsi,
            ema_fast=snap.ema_fast,
            ema_slow=snap.ema_slow,
            sma_fast=snap.sma_fast,
            sma_slow=snap.sma_slow,
            macd_histogram=snap.macd_histogram,
            trend_strength=snap.trend_strength,
            latest_close=round(latest_close, 6),
        )

    @staticmethod
    def calculate_sma(values: List[float], period: int) -> List[float]:
        if not values:
            return []
        p = max(1, period)
        out: List[float] = []
        for i in range(len(values)):
            window = values[max(0, i - p + 1) : i + 1]
            out.append(sum(window) / len(window))
        return out

    @staticmethod
    def calculate_ema(values: List[float], period: int) -> List[float]:
        if not values:
            return []
        p = max(1, period)
        k = 2.0 / (p + 1.0)
        out = [values[0]]
        for v in values[1:]:
            out.append(v * k + out[-1] * (1.0 - k))
        return out

    @staticmethod
    def calculate_rsi(closes: List[float], period: int) -> List[float]:
        if len(closes) < 2:
            return [50.0] * len(closes)
        p = max(2, period)
        rsi = [50.0] * len(closes)
        warmup = min(p, len(closes) - 1)
        gains = 0.0
        losses = 0.0
        for i in range(1, warmup + 1):
            diff = closes[i] - closes[i - 1]
            if diff > 0:
                gains += diff
            else:
                losses += abs(diff)
        avg_gain = gains / p
        avg_loss = losses / p

        def _rsi(ag: float, al: float) -> float:
            if ag <= 1e-12 and al <= 1e-12:
                return 50.0
            if al <= 1e-12:
                return 100.0
            rs = ag / al
            return 100.0 - (100.0 / (1.0 + rs))

        rsi[warmup] = _rsi(avg_gain, avg_loss)
        for i in range(warmup + 1, len(closes)):
            diff = closes[i] - closes[i - 1]
            g = diff if diff > 0 else 0.0
            l = abs(diff) if diff < 0 else 0.0
            avg_gain = ((avg_gain * (p - 1)) + g) / p
            avg_loss = ((avg_loss * (p - 1)) + l) / p
            rsi[i] = _rsi(avg_gain, avg_loss)
        return rsi

    @classmethod
    def calculate_macd(
        cls, closes: List[float], fast_period: int, slow_period: int, signal_period: int
    ) -> Tuple[List[float], List[float], List[float]]:
        fast = cls.calculate_ema(closes, fast_period)
        slow = cls.calculate_ema(closes, max(fast_period + 1, slow_period))
        macd_line = [f - s for f, s in zip(fast, slow)]
        signal_line = cls.calculate_ema(macd_line, signal_period)
        hist = [m - s for m, s in zip(macd_line, signal_line)]
        return macd_line, signal_line, hist

    @staticmethod
    def calculate_bollinger_bands(
        closes: List[float], period: int, std_mult: float
    ) -> Tuple[List[float], List[float], List[float]]:
        p = max(2, period)
        upper, middle, lower = [], [], []
        for i in range(len(closes)):
            window = closes[max(0, i - p + 1) : i + 1]
            mean = sum(window) / len(window)
            var = sum((x - mean) ** 2 for x in window) / len(window)
            std = math.sqrt(var)
            middle.append(mean)
            upper.append(mean + std_mult * std)
            lower.append(mean - std_mult * std)
        return upper, middle, lower

    @staticmethod
    def calculate_atr(candles: List[Dict[str, float]], period: int = 14) -> float:
        if len(candles) < 2:
            return 0.0
        trs = []
        for i in range(1, len(candles)):
            curr = candles[i]
            prev_close = float(candles[i - 1]["close"])
            tr = max(
                float(curr["high"]) - float(curr["low"]),
                abs(float(curr["high"]) - prev_close),
                abs(float(curr["low"]) - prev_close),
            )
            trs.append(tr)
        tail = trs[-max(2, period) :]
        return sum(tail) / len(tail)

    @staticmethod
    def detect_candlestick_pattern(candles: List[Dict[str, float]]) -> str:
        if len(candles) < 2:
            return "Normal"
        prev = candles[-2]
        curr = candles[-1]
        body = abs(float(curr["close"]) - float(curr["open"]))
        rng = max(float(curr["high"]) - float(curr["low"]), 1e-9)
        upper_wick = float(curr["high"]) - max(float(curr["open"]), float(curr["close"]))
        lower_wick = min(float(curr["open"]), float(curr["close"])) - float(curr["low"])
        is_bull = float(curr["close"]) > float(curr["open"])
        prev_bear = float(prev["close"]) < float(prev["open"])
        prev_bull = float(prev["close"]) > float(prev["open"])

        if body / rng < 0.15:
            return "Doji"
        if is_bull and prev_bear and curr["close"] >= prev["open"] and curr["open"] <= prev["close"]:
            return "Bullish Engulfing"
        if not is_bull and prev_bull and curr["open"] >= prev["close"] and curr["close"] <= prev["open"]:
            return "Bearish Engulfing"
        if lower_wick > body * 2.0 and upper_wick < body * 0.5:
            return "Hammer"
        if upper_wick > body * 2.0 and lower_wick < body * 0.5:
            return "Shooting Star"
        return "Bullish Candle" if is_bull else "Bearish Candle"

    @staticmethod
    def classify_regime(
        ema_fast: float, ema_slow: float, trend_strength: float, volatility_pct: float, bb_width_pct: float
    ) -> MarketRegime:
        if volatility_pct > 0.35 or bb_width_pct > 1.2:
            return MarketRegime.HIGH_VOLATILITY
        if trend_strength >= 0.45 and ema_fast > ema_slow:
            return MarketRegime.TRENDING_UP
        if trend_strength >= 0.45 and ema_fast < ema_slow:
            return MarketRegime.TRENDING_DOWN
        return MarketRegime.RANGING
