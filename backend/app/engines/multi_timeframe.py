from typing import Dict, List

from app.domain import (
    AlignmentState,
    MultiTimeframeReport,
    StrategyConfig,
    TimeframeIntelligence,
    TradeDirection,
)
from app.engines.technical_analysis import TechnicalAnalysisEngine


class MultiTimeframeEngine:
    """
    Evaluates primary 1m market structure alongside 5m and 15m higher-timeframe
    trend confirmations to filter out counter-trend entries.
    """

    @staticmethod
    def evaluate(
        instrument: str,
        candles_by_tf: Dict[str, List[Dict[str, float]]],
        strategy: StrategyConfig,
    ) -> MultiTimeframeReport:
        ordered_tfs = ["1m", "5m", "15m"]
        summaries: List[TimeframeIntelligence] = []
        by_tf: Dict[str, TimeframeIntelligence] = {}

        for tf in ordered_tfs:
            series = candles_by_tf.get(tf) or []
            if len(series) >= 25:
                summary = TechnicalAnalysisEngine.summarize_timeframe(series, tf, strategy)
                summaries.append(summary)
                by_tf[tf] = summary

        tf_1m = by_tf.get("1m")
        tf_5m = by_tf.get("5m")
        tf_15m = by_tf.get("15m")

        notes: List[str] = []
        higher_votes = 0
        for label, tf_sum in (("5m", tf_5m), ("15m", tf_15m)):
            if tf_sum is None:
                notes.append(f"{label} confirmation unavailable (insufficient history).")
            elif tf_sum.trend_bias == TradeDirection.CALL:
                higher_votes += 1
                notes.append(f"{label} bullish ({tf_sum.regime.value}, RSI {tf_sum.rsi:.1f}).")
            elif tf_sum.trend_bias == TradeDirection.PUT:
                higher_votes -= 1
                notes.append(f"{label} bearish ({tf_sum.regime.value}, RSI {tf_sum.rsi:.1f}).")
            else:
                notes.append(f"{label} neutral/ranging ({tf_sum.regime.value}).")

        if higher_votes > 0:
            higher_bias = TradeDirection.CALL
        elif higher_votes < 0:
            higher_bias = TradeDirection.PUT
        else:
            higher_bias = TradeDirection.NO_TRADE

        primary_bias = tf_1m.trend_bias if tf_1m is not None else TradeDirection.NO_TRADE

        if primary_bias == TradeDirection.CALL and tf_5m and tf_15m:
            if tf_5m.trend_bias == TradeDirection.CALL and tf_15m.trend_bias == TradeDirection.CALL:
                alignment_state = AlignmentState.ALIGNED_BULLISH
                score = 92
            elif tf_5m.trend_bias == TradeDirection.PUT and tf_15m.trend_bias == TradeDirection.PUT:
                alignment_state = AlignmentState.CONFLICTING
                score = 24
                notes.append("1m CALL strongly opposes both 5m and 15m bearish trends.")
            else:
                alignment_state = AlignmentState.MIXED
                score = 62
        elif primary_bias == TradeDirection.PUT and tf_5m and tf_15m:
            if tf_5m.trend_bias == TradeDirection.PUT and tf_15m.trend_bias == TradeDirection.PUT:
                alignment_state = AlignmentState.ALIGNED_BEARISH
                score = 92
            elif tf_5m.trend_bias == TradeDirection.CALL and tf_15m.trend_bias == TradeDirection.CALL:
                alignment_state = AlignmentState.CONFLICTING
                score = 24
                notes.append("1m PUT strongly opposes both 5m and 15m bullish trends.")
            else:
                alignment_state = AlignmentState.MIXED
                score = 62
        elif primary_bias != TradeDirection.NO_TRADE and higher_bias == primary_bias:
            alignment_state = (
                AlignmentState.ALIGNED_BULLISH if primary_bias == TradeDirection.CALL else AlignmentState.ALIGNED_BEARISH
            )
            score = 80
        elif primary_bias != TradeDirection.NO_TRADE and higher_bias != TradeDirection.NO_TRADE and higher_bias != primary_bias:
            alignment_state = AlignmentState.CONFLICTING
            score = 32
        else:
            alignment_state = AlignmentState.MIXED
            score = 50

        return MultiTimeframeReport(
            instrument=instrument,
            primary_timeframe="1m",
            alignment_state=alignment_state,
            alignment_score=score,
            higher_tf_bias=higher_bias,
            timeframe_summaries=summaries,
            alignment_notes=notes,
        )
