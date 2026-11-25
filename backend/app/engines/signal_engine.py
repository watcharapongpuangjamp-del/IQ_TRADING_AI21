from dataclasses import replace
import math
import time
from typing import Dict, List

from app.domain import (
    AlignmentState,
    MarketRegime,
    MultiTimeframeReport,
    SignalResult,
    StrategyConfig,
    TechnicalSnapshot,
    TradeDirection,
)


class AISignalEngine:
    """Evaluates technical and multi-timeframe evidence to return calibrated CALL / PUT / NO_TRADE signals."""

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

        if snapshot.sma_fast > 0 and snapshot.sma_slow > 0:
            if snapshot.sma_fast > snapshot.sma_slow:
                bull_pts += 10.0
                bull_evidence.append(f"SMA{strategy.sma_fast} > SMA{strategy.sma_slow} supportive trend")
            elif snapshot.sma_fast < snapshot.sma_slow:
                bear_pts += 10.0
                bear_evidence.append(f"SMA{strategy.sma_fast} < SMA{strategy.sma_slow} bearish trend")

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

        if snapshot.adx >= 22.0:
            if snapshot.plus_di > snapshot.minus_di:
                bull_pts += 12.0
                bull_evidence.append(f"ADX ({snapshot.adx:.1f}) confirms bullish trend (+DI {snapshot.plus_di:.1f} > -DI {snapshot.minus_di:.1f})")
            elif snapshot.minus_di > snapshot.plus_di:
                bear_pts += 12.0
                bear_evidence.append(f"ADX ({snapshot.adx:.1f}) confirms bearish trend (-DI {snapshot.minus_di:.1f} > +DI {snapshot.plus_di:.1f})")

        if snapshot.stochastic_k <= 20.0 and snapshot.stochastic_k >= snapshot.stochastic_d:
            bull_pts += 12.0
            bull_evidence.append(f"Stochastic oversold bullish turn (%K {snapshot.stochastic_k:.1f} / %D {snapshot.stochastic_d:.1f})")
        elif snapshot.stochastic_k >= 80.0 and snapshot.stochastic_k <= snapshot.stochastic_d:
            bear_pts += 12.0
            bear_evidence.append(f"Stochastic overbought bearish turn (%K {snapshot.stochastic_k:.1f} / %D {snapshot.stochastic_d:.1f})")

        if snapshot.vwap_reliable and snapshot.vwap > 0.0:
            if last_close > snapshot.vwap:
                bull_pts += 8.0
                bull_evidence.append(f"Price above reliable VWAP ({snapshot.vwap:.5f})")
            elif last_close < snapshot.vwap:
                bear_pts += 8.0
                bear_evidence.append(f"Price below reliable VWAP ({snapshot.vwap:.5f})")

        net_diff = abs(bull_pts - bear_pts)
        dominant_bull = bull_pts >= bear_pts
        if snapshot.regime == MarketRegime.HIGH_VOLATILITY:
            regime_penalty = 14
        elif snapshot.regime in (MarketRegime.SIDEWAYS, MarketRegime.RANGING, MarketRegime.LOW_VOLATILITY) and net_diff < 28.0:
            regime_penalty = 8
        elif snapshot.regime == MarketRegime.UNKNOWN:
            regime_penalty = 20
        else:
            regime_penalty = 0
        confidence = int(max(10, min(96, round(max(bull_pts, bear_pts) * 0.62 + net_diff * 0.42 - regime_penalty))))

        # Calibrated win probability kept distinct and conservative compared to model confidence
        logistic = 1.0 / (1.0 + math.exp(-(confidence - 55.0) / 18.0))
        calibrated_prob = round(min(0.70, max(0.46, 0.46 + logistic * 0.24)), 4)

        supporting = bull_evidence if dominant_bull else bear_evidence
        contradicting = list(bear_evidence if dominant_bull else bull_evidence)
        if snapshot.regime == MarketRegime.HIGH_VOLATILITY:
            contradicting.append(f"High volatility regime (ATR {snapshot.volatility_pct:.2f}%)")
        elif snapshot.regime in (MarketRegime.SIDEWAYS, MarketRegime.RANGING):
            contradicting.append("Sideways regime increases mean-reversion noise")

        if confidence < strategy.min_confidence or net_diff < 18.0 or snapshot.regime == MarketRegime.UNKNOWN:
            direction = TradeDirection.NO_TRADE
        elif dominant_bull:
            direction = TradeDirection.CALL
        else:
            direction = TradeDirection.PUT

        explanation = (
            f"{direction.value} [{snapshot.regime.value}]: {'; '.join(supporting[:2])}"
            if direction != TradeDirection.NO_TRADE
            else f"NO_TRADE [{snapshot.regime.value}]: Insufficient edge (confidence {confidence}% < {strategy.min_confidence}%)"
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

    @classmethod
    def evaluate_with_mtf(
        cls,
        candles_1m: List[Dict[str, float]],
        snapshot_1m: TechnicalSnapshot,
        mtf_report: MultiTimeframeReport,
        strategy: StrategyConfig,
    ) -> SignalResult:
        base = cls.evaluate(candles_1m, snapshot_1m, strategy)
        supporting = list(base.supporting_evidence)
        contradicting = list(base.contradicting_evidence)
        confidence = base.confidence_score
        direction = base.direction

        if (
            direction == TradeDirection.CALL
            and mtf_report.alignment_state == AlignmentState.ALIGNED_BULLISH
        ) or (
            direction == TradeDirection.PUT
            and mtf_report.alignment_state == AlignmentState.ALIGNED_BEARISH
        ):
            confidence = min(96, confidence + 6)
            supporting.append(f"Multi-timeframe aligned ({mtf_report.alignment_state.value}, score {mtf_report.alignment_score})")
        elif mtf_report.alignment_state == AlignmentState.CONFLICTING:
            confidence = max(10, confidence - 18)
            contradicting.append("Higher timeframes (5m/15m) oppose primary 1m direction")
            direction = TradeDirection.NO_TRADE
        elif mtf_report.higher_tf_bias != TradeDirection.NO_TRADE and direction != TradeDirection.NO_TRADE:
            if mtf_report.higher_tf_bias != direction:
                confidence = max(10, confidence - 10)
                contradicting.append(f"Higher-timeframe bias ({mtf_report.higher_tf_bias.value}) differs from {direction.value}")
                if confidence < strategy.min_confidence:
                    direction = TradeDirection.NO_TRADE

        logistic = 1.0 / (1.0 + math.exp(-(confidence - 55.0) / 18.0))
        calibrated_prob = round(min(0.71, max(0.46, 0.46 + logistic * 0.25)), 4)

        explanation = (
            f"{direction.value} [MTF {mtf_report.alignment_state.value}]: {'; '.join(supporting[:2])}"
            if direction != TradeDirection.NO_TRADE
            else f"NO_TRADE [MTF {mtf_report.alignment_state.value}]: Blocked by confidence ({confidence}%) or higher-TF conflict"
        )
        risk_assessment = (
            "Multi-timeframe gate blocked execution (NO_TRADE)."
            if direction == TradeDirection.NO_TRADE
            else "Multi-timeframe aligned; subject to independent RiskManagementEngine pre-trade validation."
        )

        return replace(
            base,
            direction=direction,
            confidence_score=confidence,
            calibrated_probability=calibrated_prob,
            supporting_evidence=supporting,
            contradicting_evidence=contradicting,
            explanation=explanation,
            risk_assessment=risk_assessment,
        )
