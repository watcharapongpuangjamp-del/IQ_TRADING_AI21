from dataclasses import dataclass
import hashlib
import math
from typing import Dict, List, Sequence

from app.domain import StrategyConfig, TradeDirection
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine

FEATURE_SCHEMA_VERSION = "v2"

FEATURE_NAMES: List[str] = [
    "ret_1",
    "ret_3",
    "ret_5",
    "rsi_norm",
    "ema_spread_pct",
    "sma_spread_pct",
    "macd_hist_norm",
    "bb_position",
    "volatility_pct",
    "body_ratio",
    "upper_wick_ratio",
    "mtf_5m_bias",
    "adx_norm",
    "di_spread",
    "stoch_k_norm",
    "stoch_d_norm",
    "vwap_dist_pct",
]


@dataclass(frozen=True)
class FeatureDataset:
    timestamps: List[int]
    feature_names: List[str]
    features: List[List[float]]
    labels: List[int]
    future_returns: List[float]


class FeatureEngineeringEngine:
    """
    Extracts strictly causal (zero look-ahead) 17-feature quantitative vectors from OHLCV candles
    (including Phase 6 ADX, Stochastic, and VWAP signals) and aligns binary outcomes accounting
    for execution delay and horizon bars.
    """

    @staticmethod
    def compute_dataset_version(
        candles_1m: Sequence[Dict[str, float]],
        feature_names: Sequence[str] = FEATURE_NAMES,
    ) -> str:
        ordered = sorted((dict(c) for c in candles_1m), key=lambda x: int(x["timestamp"]))
        digest_input = "|".join(
            [
                FEATURE_SCHEMA_VERSION,
                ",".join(feature_names),
                str(len(ordered)),
                str(int(ordered[0]["timestamp"])) if ordered else "0",
                str(int(ordered[-1]["timestamp"])) if ordered else "0",
                f"{float(ordered[-1]['close']):.6f}" if ordered else "0.0",
            ]
        )
        short_hash = hashlib.sha256(digest_input.encode("utf-8")).hexdigest()[:8]
        return f"ds_{FEATURE_SCHEMA_VERSION}_{short_hash}_n{len(ordered)}"

    @staticmethod
    def _extract_bar_features(
        window_1m: List[Dict[str, float]],
        strategy: StrategyConfig,
    ) -> List[float]:
        snap = TechnicalAnalysisEngine.analyze(window_1m, strategy)
        curr = window_1m[-1]
        close = float(curr["close"])
        open_p = float(curr["open"])
        high_p = float(curr["high"])
        low_p = float(curr["low"])

        c_prev_1 = float(window_1m[-2]["close"])
        c_prev_3 = float(window_1m[-4]["close"])
        c_prev_5 = float(window_1m[-6]["close"])

        ret_1 = (close - c_prev_1) / c_prev_1 if c_prev_1 > 0 else 0.0
        ret_3 = (close - c_prev_3) / c_prev_3 if c_prev_3 > 0 else 0.0
        ret_5 = (close - c_prev_5) / c_prev_5 if c_prev_5 > 0 else 0.0

        rsi_norm = (snap.rsi - 50.0) / 50.0
        ema_spread_pct = ((snap.ema_fast - snap.ema_slow) / close) * 100.0 if close > 0 else 0.0
        sma_spread_pct = ((snap.sma_fast - snap.sma_slow) / close) * 100.0 if close > 0 else 0.0
        macd_hist_norm = snap.macd_histogram / max(snap.atr, 1e-6)
        bb_span = max(snap.bb_upper - snap.bb_lower, 1e-6)
        bb_position = (close - snap.bb_lower) / bb_span

        bar_range = max(high_p - low_p, 1e-9)
        body_ratio = (close - open_p) / bar_range
        upper_wick = high_p - max(open_p, close)
        upper_wick_ratio = upper_wick / bar_range

        # Causal 5m confirmation strictly aggregated from bars <= current timestamp
        mtf_5m_bias = 0.0
        bars_5m = MarketIntelligenceEngine.aggregate_candles(window_1m[-180:], 5)
        if len(bars_5m) >= 25:
            tf5 = TechnicalAnalysisEngine.summarize_timeframe(bars_5m, "5m", strategy)
            if tf5.trend_bias == TradeDirection.CALL:
                mtf_5m_bias = 1.0
            elif tf5.trend_bias == TradeDirection.PUT:
                mtf_5m_bias = -1.0

        # Phase 6/7 causal ADX, DI spread, Stochastic %K/%D, and reliable VWAP distance
        adx_norm = min(1.0, max(0.0, snap.adx / 60.0))
        di_spread = max(-1.0, min(1.0, (snap.plus_di - snap.minus_di) / 100.0))
        stoch_k_norm = max(-1.0, min(1.0, (snap.stochastic_k - 50.0) / 50.0))
        stoch_d_norm = max(-1.0, min(1.0, (snap.stochastic_d - 50.0) / 50.0))
        vwap_dist_pct = (
            max(-5.0, min(5.0, ((close - snap.vwap) / close) * 100.0))
            if snap.vwap_reliable and snap.vwap > 0.0 and close > 0.0
            else 0.0
        )

        vec = [
            round(ret_1 * 100.0, 6),
            round(ret_3 * 100.0, 6),
            round(ret_5 * 100.0, 6),
            round(rsi_norm, 6),
            round(ema_spread_pct, 6),
            round(sma_spread_pct, 6),
            round(max(-5.0, min(5.0, macd_hist_norm)), 6),
            round(max(-0.5, min(1.5, bb_position)), 6),
            round(snap.volatility_pct, 6),
            round(body_ratio, 6),
            round(upper_wick_ratio, 6),
            mtf_5m_bias,
            round(adx_norm, 6),
            round(di_spread, 6),
            round(stoch_k_norm, 6),
            round(stoch_d_norm, 6),
            round(vwap_dist_pct, 6),
        ]
        if not all(math.isfinite(x) for x in vec):
            raise ValueError("Non-finite feature value computed.")
        return vec

    @classmethod
    def extract_latest_features(
        cls,
        candles_1m: Sequence[Dict[str, float]],
        strategy: StrategyConfig,
    ) -> List[float]:
        ordered = sorted((dict(c) for c in candles_1m), key=lambda x: int(x["timestamp"]))
        if len(ordered) < 35:
            raise ValueError("At least 35 candles required for feature extraction.")
        return cls._extract_bar_features(ordered[-200:], strategy)

    @classmethod
    def extract_recent_feature_matrix(
        cls,
        candles_1m: Sequence[Dict[str, float]],
        strategy: StrategyConfig,
        max_samples: int = 30,
    ) -> List[List[float]]:
        ordered = sorted((dict(c) for c in candles_1m), key=lambda x: int(x["timestamp"]))
        if len(ordered) < 40:
            return []
        start_idx = max(35, len(ordered) - max(1, max_samples))
        matrix: List[List[float]] = []
        for idx in range(start_idx, len(ordered)):
            window_start = max(0, idx - 199)
            matrix.append(cls._extract_bar_features(ordered[window_start : idx + 1], strategy))
        return matrix

    @classmethod
    def build_dataset(
        cls,
        candles_1m: Sequence[Dict[str, float]],
        strategy: StrategyConfig,
        horizon_bars: int = 1,
        execution_delay_bars: int = 1,
    ) -> FeatureDataset:
        if horizon_bars < 1:
            raise ValueError("horizon_bars must be >= 1.")
        if execution_delay_bars < 0:
            raise ValueError("execution_delay_bars must be >= 0.")

        ordered = sorted((dict(c) for c in candles_1m), key=lambda x: int(x["timestamp"]))
        warmup = max(35, strategy.ema_slow + 5, strategy.sma_slow + 5, strategy.macd_slow + 5)
        end_idx = len(ordered) - execution_delay_bars - horizon_bars
        if end_idx <= warmup + 10:
            raise ValueError(
                f"Insufficient candles ({len(ordered)}) for warmup={warmup}, delay={execution_delay_bars}, horizon={horizon_bars}."
            )

        timestamps: List[int] = []
        features: List[List[float]] = []
        labels: List[int] = []
        future_returns: List[float] = []

        for idx in range(warmup, end_idx):
            # Strictly causal slice: only bars <= idx
            window_start = max(0, idx - 199)
            causal_window = ordered[window_start : idx + 1]
            vec = cls._extract_bar_features(causal_window, strategy)

            entry_idx = idx + execution_delay_bars
            exit_idx = entry_idx + horizon_bars
            entry_price = (
                float(ordered[entry_idx]["open"])
                if execution_delay_bars > 0
                else float(ordered[idx]["close"])
            )
            exit_price = float(ordered[exit_idx]["close"])
            ret = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0
            label = 1 if exit_price > entry_price else 0

            timestamps.append(int(ordered[idx]["timestamp"]))
            features.append(vec)
            labels.append(label)
            future_returns.append(ret)

        return FeatureDataset(
            timestamps=timestamps,
            feature_names=list(FEATURE_NAMES),
            features=features,
            labels=labels,
            future_returns=future_returns,
        )
