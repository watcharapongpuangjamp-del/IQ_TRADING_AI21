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
    """Computes strictly causal technical indicators (RSI, MACD, EMA, SMA, Bollinger, ATR, ADX, Stochastic, VWAP) and Market Regime."""

    @classmethod
    def analyze(cls, candles: List[Dict[str, float]], strategy: StrategyConfig) -> TechnicalSnapshot:
        if len(candles) < 2:
            last_close = float(candles[-1]["close"]) if candles else 1.0
            return TechnicalSnapshot(
                rsi=50.0,
                macd_line=0.0,
                macd_signal=0.0,
                macd_histogram=0.0,
                ema_fast=last_close,
                ema_slow=last_close,
                sma_fast=last_close,
                sma_slow=last_close,
                bb_upper=last_close,
                bb_middle=last_close,
                bb_lower=last_close,
                atr=0.0,
                volatility_pct=0.0,
                trend_strength=0.0,
                support_level=last_close,
                resistance_level=last_close,
                candlestick_pattern="Normal",
                regime=MarketRegime.UNKNOWN,
            )

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
        adx, plus_di, minus_di = cls.calculate_adx(candles, 14)
        stoch_k, stoch_d = cls.calculate_stochastic(candles, 14, 3)
        vwap, vwap_reliable = cls.calculate_vwap(candles)

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
        prior_window = candles[-31:-1] if len(candles) > 2 else candles[:-1]
        prior_support = min(float(c["low"]) for c in prior_window) if prior_window else support
        prior_resistance = max(float(c["high"]) for c in prior_window) if prior_window else resistance

        pattern = cls.detect_candlestick_pattern(candles)
        bb_width_pct = ((bb_upper[-1] - bb_lower[-1]) / bb_middle[-1]) * 100.0 if bb_middle[-1] > 0 else 0.0
        regime = cls.classify_regime(
            ema_fast=ema_fast,
            ema_slow=ema_slow,
            trend_strength=trend_strength,
            volatility_pct=volatility_pct,
            bb_width_pct=bb_width_pct,
            adx=adx,
            rsi=rsi_series[-1],
            last_close=last_close,
            prior_support=prior_support,
            prior_resistance=prior_resistance,
            pattern=pattern,
        )

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
            adx=round(adx, 2),
            plus_di=round(plus_di, 2),
            minus_di=round(minus_di, 2),
            stochastic_k=round(stoch_k[-1] if stoch_k else 50.0, 2),
            stochastic_d=round(stoch_d[-1] if stoch_d else 50.0, 2),
            vwap=round(vwap, 6),
            vwap_reliable=vwap_reliable,
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

        if snap.adx >= 22.0:
            if snap.plus_di > snap.minus_di:
                bull_score += 1
            elif snap.minus_di > snap.plus_di:
                bear_score += 1

        if bull_score >= 3 and snap.rsi < strategy.rsi_overbought:
            bias = TradeDirection.CALL
        elif bear_score >= 3 and snap.rsi > strategy.rsi_oversold:
            bias = TradeDirection.PUT
        elif snap.regime in (MarketRegime.TRENDING_UP, MarketRegime.STRONG_TREND, MarketRegime.WEAK_TREND, MarketRegime.BREAKOUT) and snap.ema_fast > snap.ema_slow:
            bias = TradeDirection.CALL
        elif snap.regime in (MarketRegime.TRENDING_DOWN, MarketRegime.STRONG_TREND, MarketRegime.WEAK_TREND, MarketRegime.BREAKOUT) and snap.ema_fast < snap.ema_slow:
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
    def calculate_adx(candles: List[Dict[str, float]], period: int = 14) -> Tuple[float, float, float]:
        if len(candles) < period + 2:
            return 0.0, 0.0, 0.0

        p = max(2, period)
        trs: List[float] = []
        plus_dm: List[float] = []
        minus_dm: List[float] = []

        for i in range(1, len(candles)):
            curr_h = float(candles[i]["high"])
            curr_l = float(candles[i]["low"])
            prev_h = float(candles[i - 1]["high"])
            prev_l = float(candles[i - 1]["low"])
            prev_c = float(candles[i - 1]["close"])

            up_move = curr_h - prev_h
            down_move = prev_l - curr_l
            pdm = up_move if (up_move > down_move and up_move > 0) else 0.0
            mdm = down_move if (down_move > up_move and down_move > 0) else 0.0
            tr = max(curr_h - curr_l, abs(curr_h - prev_c), abs(curr_l - prev_c))

            trs.append(tr)
            plus_dm.append(pdm)
            minus_dm.append(mdm)

        smooth_tr = sum(trs[:p])
        smooth_pdm = sum(plus_dm[:p])
        smooth_mdm = sum(minus_dm[:p])

        dx_values: List[float] = []
        last_pdi = 0.0
        last_mdi = 0.0

        def _di_dx(st: float, sp: float, sm: float) -> Tuple[float, float, float]:
            if st <= 1e-12:
                return 0.0, 0.0, 0.0
            pdi = 100.0 * (sp / st)
            mdi = 100.0 * (sm / st)
            denom = pdi + mdi
            dx = 100.0 * abs(pdi - mdi) / denom if denom > 1e-12 else 0.0
            return pdi, mdi, dx

        last_pdi, last_mdi, first_dx = _di_dx(smooth_tr, smooth_pdm, smooth_mdm)
        dx_values.append(first_dx)

        for i in range(p, len(trs)):
            smooth_tr = smooth_tr - (smooth_tr / p) + trs[i]
            smooth_pdm = smooth_pdm - (smooth_pdm / p) + plus_dm[i]
            smooth_mdm = smooth_mdm - (smooth_mdm / p) + minus_dm[i]
            last_pdi, last_mdi, dx = _di_dx(smooth_tr, smooth_pdm, smooth_mdm)
            dx_values.append(dx)

        if len(dx_values) < p:
            adx = sum(dx_values) / len(dx_values)
        else:
            adx = sum(dx_values[:p]) / p
            for dx in dx_values[p:]:
                adx = ((adx * (p - 1)) + dx) / p

        return min(100.0, max(0.0, adx)), min(100.0, max(0.0, last_pdi)), min(100.0, max(0.0, last_mdi))

    @classmethod
    def calculate_stochastic(
        cls, candles: List[Dict[str, float]], k_period: int = 14, d_period: int = 3
    ) -> Tuple[List[float], List[float]]:
        if not candles:
            return [], []
        kp = max(2, k_period)
        dp = max(1, d_period)
        k_series: List[float] = []
        for i in range(len(candles)):
            window = candles[max(0, i - kp + 1) : i + 1]
            highest = max(float(c["high"]) for c in window)
            lowest = min(float(c["low"]) for c in window)
            close = float(candles[i]["close"])
            span = highest - lowest
            if span <= 1e-12:
                k_series.append(50.0)
            else:
                k_series.append(min(100.0, max(0.0, ((close - lowest) / span) * 100.0)))
        d_series = cls.calculate_sma(k_series, dp)
        return k_series, d_series

    @staticmethod
    def calculate_vwap(candles: List[Dict[str, float]], window: int = 60) -> Tuple[float, bool]:
        if not candles:
            return 0.0, False
        recent = candles[-max(1, window) :]
        positive_vol_bars = 0
        total_pv = 0.0
        total_vol = 0.0
        typical_prices: List[float] = []
        for c in recent:
            tp = (float(c["high"]) + float(c["low"]) + float(c["close"])) / 3.0
            typical_prices.append(tp)
            vol = float(c.get("volume", 0.0) or 0.0)
            if vol > 0.0:
                positive_vol_bars += 1
                total_pv += tp * vol
                total_vol += vol

        reliable = total_vol > 0.0 and (positive_vol_bars / len(recent)) >= 0.25
        if reliable:
            return total_pv / total_vol, True
        return sum(typical_prices) / len(typical_prices), False

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
        ema_fast: float,
        ema_slow: float,
        trend_strength: float,
        volatility_pct: float,
        bb_width_pct: float,
        adx: float = 0.0,
        rsi: float = 50.0,
        last_close: float = 0.0,
        prior_support: float = 0.0,
        prior_resistance: float = 0.0,
        pattern: str = "Normal",
    ) -> MarketRegime:
        if volatility_pct > 0.35 or bb_width_pct > 1.2:
            return MarketRegime.HIGH_VOLATILITY
        if last_close > 0.0 and prior_resistance > 0.0 and (last_close > prior_resistance or last_close < prior_support):
            return MarketRegime.BREAKOUT
        if (rsi <= 26.0 or rsi >= 74.0) and (
            "Engulfing" in pattern or "Hammer" in pattern or "Shooting Star" in pattern
        ):
            return MarketRegime.REVERSAL
        if adx >= 26.0 or trend_strength >= 0.75:
            return MarketRegime.STRONG_TREND
        if adx >= 18.0 or trend_strength >= 0.40:
            return MarketRegime.WEAK_TREND
        if volatility_pct < 0.035 and bb_width_pct < 0.18:
            return MarketRegime.LOW_VOLATILITY
        return MarketRegime.SIDEWAYS
