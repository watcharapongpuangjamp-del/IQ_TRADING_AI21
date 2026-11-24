import math
import time
from typing import Dict, List

from app.domain import (
    MarketRegime,
    SignalResult,
    StrategyConfig,
    TechnicalSnapshot,
    TradeDirection,
)


class AISignalEngine:
    """Evaluates technical evidence and returns calibrated CALL / PUT / NO_TRADE signals."""

    @staticmethod
    def evaluate(
        candles: List[Dict[str, float]],
        snapshot: TechnicalSnapshot,
        strategy: StrategyConfig,
    ) -> SignalResult:
        last_close = float(candles[-1]["close"])
        bull_evidence: List[str] = []
        bear_evidence: List[str] = []
        bull_pts = 0.0
        bear_pts = 0.0

        if snapshot.ema_fast > snapshot.ema_slow:
            bull_pts += 22.0
            bull_evidence.append(f"EMA{strategy.ema_fast} > EMA{strategy.ema_slow} bullish alignment")
        elif snapshot.ema_fast < snapshot.ema_slow:
            bear_pts += 22.0
            bear_evidence.append(f"EMA{strategy.ema_fast} < EMA{strategy.ema_slow} bearish alignment")

        if snapshot.rsi <= strategy.rsi_oversold:
            bull_pts += 24.0
            bull_evidence.append(f"RSI ({snapshot.rsi:.2f}) in oversold zone <= {strategy.rsi_oversold}")
        elif snapshot.rsi >= strategy.rsi_overbought:
            bear_pts += 24.0
            bear_evidence.append(f"RSI ({snapshot.rsi:.2f}) in overbought zone >= {strategy.rsi_overbought}")

        if snapshot.macd_line > snapshot.macd_signal and snapshot.macd_histogram > 0:
            bull_pts += 20.0
            bull_evidence.append("MACD above signal line with positive histogram")
        elif snapshot.macd_line < snapshot.macd_signal and snapshot.macd_histogram < 0:
            bear_pts += 20.0
            bear_evidence.append("MACD below signal line with negative histogram")

        if last_close <= snapshot.bb_lower:
            bull_pts += 18.0
            bull_evidence.append("Price touching lower Bollinger Band")
        elif last_close >= snapshot.bb_upper:
            bear_pts += 18.0
            bear_evidence.append("Price touching upper Bollinger Band")

        if "Bullish" in snapshot.candlestick_pattern or "Hammer" in snapshot.candlestick_pattern:
            bull_pts += 14.0
            bull_evidence.append(f"Candlestick pattern: {snapshot.candlestick_pattern}")
        elif "Bearish" in snapshot.candlestick_pattern or "Shooting Star" in snapshot.candlestick_pattern:
            bear_pts += 14.0
            bear_evidence.append(f"Candlestick pattern: {snapshot.candlestick_pattern}")

        net_diff = abs(bull_pts - bear_pts)
        dominant_bull = bull_pts >= bear_pts
        regime_penalty = 14 if snapshot.regime == MarketRegime.HIGH_VOLATILITY else 0
        confidence = int(max(10, min(96, round(max(bull_pts, bear_pts) * 0.65 + net_diff * 0.45 - regime_penalty))))

        # Calibrated win probability kept distinct and conservative compared to model confidence
        logistic = 1.0 / (1.0 + math.exp(-(confidence - 55.0) / 18.0))
        calibrated_prob = round(min(0.70, max(0.46, 0.46 + logistic * 0.24)), 4)

        supporting = bull_evidence if dominant_bull else bear_evidence
        contradicting = list(bear_evidence if dominant_bull else bull_evidence)
        if snapshot.regime == MarketRegime.HIGH_VOLATILITY:
            contradicting.append(f"High volatility regime (ATR {snapshot.volatility_pct:.2f}%)")

        if confidence < strategy.min_confidence or net_diff < 18.0:
            direction = TradeDirection.NO_TRADE
        elif dominant_bull:
            direction = TradeDirection.CALL
        else:
            direction = TradeDirection.PUT

        explanation = (
            f"{direction.value}: {'; '.join(supporting[:2])}"
            if direction != TradeDirection.NO_TRADE
            else f"NO_TRADE: Insufficient edge (confidence {confidence}% < {strategy.min_confidence}%)"
        )
        risk_assessment = (
            "Signal below threshold — no execution recommended."
            if direction == TradeDirection.NO_TRADE
            else "Risk subject to independent RiskManagementEngine pre-trade validation."
        )

        return SignalResult(
            instrument=strategy.instrument,
            timeframe=strategy.timeframe,
            direction=direction,
            calibrated_probability=calibrated_prob,
            confidence_score=confidence,
            supporting_evidence=supporting,
            contradicting_evidence=contradicting,
            market_regime=snapshot.regime,
            risk_assessment=risk_assessment,
            explanation=explanation,
            expires_at_ms=int(time.time() * 1000) + strategy.expiry_seconds * 1000,
            technical_snapshot=snapshot,
        )
