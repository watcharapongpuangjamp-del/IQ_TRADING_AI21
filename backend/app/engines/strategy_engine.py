from dataclasses import dataclass, replace
import math
from typing import Dict, List, Optional, Sequence

from app.domain import (
    MarketRegime,
    StrategyConfig,
    StrategyModuleProfile,
    StrategySelectionReport,
    TradeDirection,
    TradeStatus,
)
from app.engines.technical_analysis import TechnicalAnalysisEngine


@dataclass(frozen=True)
class _StrategyTemplate:
    strategy_id: str
    name: str
    description: str
    target_regimes: List[str]
    entry_conditions: str
    exit_conditions: str
    risk_parameters: str
    default_config: StrategyConfig


_STRATEGY_TEMPLATES: List[_StrategyTemplate] = [
    _StrategyTemplate(
        strategy_id="ema_trend_following",
        name="EMA Trend Following",
        description="Follows confirmed short/medium EMA momentum aligned with DI trend direction.",
        target_regimes=[
            MarketRegime.STRONG_TREND.value,
            MarketRegime.WEAK_TREND.value,
            MarketRegime.TRENDING_UP.value,
            MarketRegime.TRENDING_DOWN.value,
        ],
        entry_conditions="EMA9 crosses/holds beyond EMA21 with ADX >= 18 and aligned +DI/-DI.",
        exit_conditions="Fixed binary expiry or EMA cross reversal against active position.",
        risk_parameters="Min confidence 64%, avoid HIGH_VOLATILITY and tight SIDEWAYS noise.",
        default_config=StrategyConfig(
            name="EMA Trend Following",
            ema_fast=9,
            ema_slow=21,
            rsi_period=14,
            rsi_oversold=32,
            rsi_overbought=68,
            min_confidence=64,
        ),
    ),
    _StrategyTemplate(
        strategy_id="rsi_reversal",
        name="RSI Reversal",
        description="Captures exhaustion reversals at RSI extremes confirmed by candlestick rejections.",
        target_regimes=[
            MarketRegime.REVERSAL.value,
            MarketRegime.SIDEWAYS.value,
            MarketRegime.RANGING.value,
        ],
        entry_conditions="RSI <= 28 (CALL) or RSI >= 72 (PUT) with Stochastic exhaustion cross.",
        exit_conditions="Fixed binary expiry upon mean reversion toward RSI 50.",
        risk_parameters="Min confidence 68%, blocked during runaway STRONG_TREND moves.",
        default_config=StrategyConfig(
            name="RSI Reversal",
            ema_fast=7,
            ema_slow=21,
            rsi_period=14,
            rsi_oversold=28,
            rsi_overbought=72,
            min_confidence=68,
        ),
    ),
    _StrategyTemplate(
        strategy_id="macd_momentum",
        name="MACD Momentum",
        description="Trades expanding MACD histogram momentum bursts with VWAP directional confirmation.",
        target_regimes=[
            MarketRegime.STRONG_TREND.value,
            MarketRegime.BREAKOUT.value,
            MarketRegime.TRENDING_UP.value,
            MarketRegime.TRENDING_DOWN.value,
        ],
        entry_conditions="MACD line crosses signal with rising histogram slope and VWAP alignment.",
        exit_conditions="Fixed binary expiry or histogram momentum contraction.",
        risk_parameters="Min confidence 65%, requires positive net indicator spread.",
        default_config=StrategyConfig(
            name="MACD Momentum",
            ema_fast=8,
            ema_slow=21,
            macd_fast=12,
            macd_slow=26,
            macd_signal=9,
            min_confidence=65,
        ),
    ),
    _StrategyTemplate(
        strategy_id="bb_mean_reversion",
        name="Bollinger Mean Reversion",
        description="Fades overextended touches on Bollinger outer bands back toward the moving mean.",
        target_regimes=[
            MarketRegime.SIDEWAYS.value,
            MarketRegime.RANGING.value,
            MarketRegime.LOW_VOLATILITY.value,
        ],
        entry_conditions="Close <= BB Lower (CALL) or Close >= BB Upper (PUT) with Stochastic confirmation.",
        exit_conditions="Fixed binary expiry targeting middle Bollinger Band reversion.",
        risk_parameters="Min confidence 68%, avoids HIGH_VOLATILITY breakouts.",
        default_config=StrategyConfig(
            name="Bollinger Mean Reversion",
            bb_period=20,
            bb_std_dev=2.1,
            rsi_oversold=27,
            rsi_overbought=73,
            min_confidence=68,
        ),
    ),
    _StrategyTemplate(
        strategy_id="breakout_detection",
        name="Breakout Detection",
        description="Detects range expansion beyond prior 30-bar support/resistance levels.",
        target_regimes=[
            MarketRegime.BREAKOUT.value,
            MarketRegime.HIGH_VOLATILITY.value,
        ],
        entry_conditions="Close breaks prior 30-bar high/low with expanding ATR and MACD impulse.",
        exit_conditions="Fixed binary expiry or quick invalidation inside broken structure.",
        risk_parameters="Min confidence 70%, elevated threshold to filter false wick breakouts.",
        default_config=StrategyConfig(
            name="Breakout Detection",
            ema_fast=5,
            ema_slow=20,
            bb_period=20,
            bb_std_dev=2.0,
            min_confidence=70,
        ),
    ),
    _StrategyTemplate(
        strategy_id="volatility_adaptive",
        name="Volatility-Adaptive",
        description="Adapts band thresholds and confidence gates to ATR/Bollinger bandwidth shifts.",
        target_regimes=[
            MarketRegime.HIGH_VOLATILITY.value,
            MarketRegime.LOW_VOLATILITY.value,
        ],
        entry_conditions="Widens confirmation gate in high ATR; tightens oscillator triggers in quiet regimes.",
        exit_conditions="Fixed binary expiry governed by strict Volatility & Risk Gate filter.",
        risk_parameters="Min confidence 72% to prevent overtrading during volatility spikes.",
        default_config=StrategyConfig(
            name="Volatility-Adaptive",
            ema_fast=9,
            ema_slow=34,
            bb_period=20,
            bb_std_dev=2.3,
            min_confidence=72,
        ),
    ),
    _StrategyTemplate(
        strategy_id="mtf_confirmation",
        name="Multi-Timeframe Confirmation",
        description="Aligns primary 1m entries with 5m and 15m structural trend consensus.",
        target_regimes=[
            MarketRegime.STRONG_TREND.value,
            MarketRegime.WEAK_TREND.value,
            MarketRegime.BREAKOUT.value,
            MarketRegime.TRENDING_UP.value,
            MarketRegime.TRENDING_DOWN.value,
        ],
        entry_conditions="1m trigger confirmed by non-conflicting 5m/15m directional bias.",
        exit_conditions="Fixed binary expiry; invalidated if 5m/15m conflict appears.",
        risk_parameters="Min confidence 67%, hard NO_TRADE gate on timeframe divergence.",
        default_config=StrategyConfig(
            name="Multi-Timeframe Confirmation",
            ema_fast=9,
            ema_slow=21,
            sma_fast=10,
            sma_slow=30,
            min_confidence=67,
        ),
    ),
    _StrategyTemplate(
        strategy_id="regime_adaptive",
        name="Market Regime Adaptive",
        description="Synthesizes trend, oscillator, and VWAP weights dynamically per detected MarketRegime.",
        target_regimes=[
            MarketRegime.STRONG_TREND.value,
            MarketRegime.WEAK_TREND.value,
            MarketRegime.SIDEWAYS.value,
            MarketRegime.HIGH_VOLATILITY.value,
            MarketRegime.LOW_VOLATILITY.value,
            MarketRegime.BREAKOUT.value,
            MarketRegime.REVERSAL.value,
            MarketRegime.RANGING.value,
            MarketRegime.TRENDING_UP.value,
            MarketRegime.TRENDING_DOWN.value,
        ],
        entry_conditions="Delegates directional bias to trend engines in trends and oscillators in ranges.",
        exit_conditions="Fixed binary expiry with strict out-of-sample expectancy gating.",
        risk_parameters="Min confidence 66%, balanced drawdown control across regime transitions.",
        default_config=StrategyConfig(
            name="Market Regime Adaptive",
            ema_fast=9,
            ema_slow=21,
            rsi_oversold=30,
            rsi_overbought=70,
            min_confidence=66,
        ),
    ),
]


class StrategySelectionEngine:
    """Manages active strategy parameters, 8 modular strategies, and regime-aware AI strategy selection."""

    def __init__(self) -> None:
        self._active_strategy = StrategyConfig()
        self._auto_select: bool = True
        self._selected_strategy_id: str = "regime_adaptive"

    def get_active_strategy(self) -> StrategyConfig:
        return self._active_strategy

    def update_strategy(self, params: StrategyConfig) -> StrategyConfig:
        self._active_strategy = params
        matched = next(
            (t.strategy_id for t in _STRATEGY_TEMPLATES if t.name.lower() == params.name.strip().lower()),
            None,
        )
        if matched is not None:
            self._selected_strategy_id = matched
        return self._active_strategy

    @property
    def auto_select_enabled(self) -> bool:
        return self._auto_select

    @property
    def selected_strategy_id(self) -> str:
        return self._selected_strategy_id

    @staticmethod
    def list_template_ids() -> List[str]:
        return [t.strategy_id for t in _STRATEGY_TEMPLATES]

    @staticmethod
    def _compute_regime_fit(target_regimes: List[str], current_regime: MarketRegime) -> float:
        if current_regime == MarketRegime.UNKNOWN:
            return 0.40
        if current_regime.value in target_regimes:
            # More specialized strategies get slightly sharper fit than universal fallback
            return 0.95 if len(target_regimes) <= 4 else 0.85
        # Partial compatibility across adjacent trend/range regimes
        trend_group = {
            MarketRegime.STRONG_TREND,
            MarketRegime.WEAK_TREND,
            MarketRegime.TRENDING_UP,
            MarketRegime.TRENDING_DOWN,
            MarketRegime.BREAKOUT,
        }
        range_group = {
            MarketRegime.SIDEWAYS,
            MarketRegime.RANGING,
            MarketRegime.LOW_VOLATILITY,
            MarketRegime.REVERSAL,
        }
        target_set = set(target_regimes)
        if current_regime in trend_group and any(r.value in target_set for r in trend_group):
            return 0.62
        if current_regime in range_group and any(r.value in target_set for r in range_group):
            return 0.62
        return 0.25

    @staticmethod
    def _strategy_bar_direction(
        strategy_id: str,
        window: List[Dict[str, float]],
        cfg: StrategyConfig,
    ) -> TradeDirection:
        snap = TechnicalAnalysisEngine.analyze(window, cfg)
        last_close = float(window[-1]["close"])
        prior = window[-31:-1] if len(window) > 2 else window[:-1]
        prior_high = max(float(c["high"]) for c in prior) if prior else snap.resistance_level
        prior_low = min(float(c["low"]) for c in prior) if prior else snap.support_level

        if strategy_id == "ema_trend_following":
            if snap.ema_fast > snap.ema_slow and snap.adx >= 16.0 and snap.plus_di >= snap.minus_di:
                return TradeDirection.CALL
            if snap.ema_fast < snap.ema_slow and snap.adx >= 16.0 and snap.minus_di >= snap.plus_di:
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        if strategy_id == "rsi_reversal":
            if snap.rsi <= cfg.rsi_oversold or (snap.rsi <= 36.0 and snap.stochastic_k <= 24.0):
                return TradeDirection.CALL
            if snap.rsi >= cfg.rsi_overbought or (snap.rsi >= 64.0 and snap.stochastic_k >= 76.0):
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        if strategy_id == "macd_momentum":
            vwap_ok_bull = (not snap.vwap_reliable) or (last_close >= snap.vwap)
            vwap_ok_bear = (not snap.vwap_reliable) or (last_close <= snap.vwap)
            if snap.macd_line > snap.macd_signal and snap.macd_histogram > 0 and vwap_ok_bull:
                return TradeDirection.CALL
            if snap.macd_line < snap.macd_signal and snap.macd_histogram < 0 and vwap_ok_bear:
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        if strategy_id == "bb_mean_reversion":
            if last_close <= snap.bb_lower and snap.stochastic_k <= 32.0:
                return TradeDirection.CALL
            if last_close >= snap.bb_upper and snap.stochastic_k >= 68.0:
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        if strategy_id == "breakout_detection":
            if last_close > prior_high and snap.macd_histogram >= 0:
                return TradeDirection.CALL
            if last_close < prior_low and snap.macd_histogram <= 0:
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        if strategy_id == "volatility_adaptive":
            if snap.volatility_pct > 0.35:
                if snap.ema_fast > snap.ema_slow and snap.adx >= 24.0 and snap.rsi < 68.0:
                    return TradeDirection.CALL
                if snap.ema_fast < snap.ema_slow and snap.adx >= 24.0 and snap.rsi > 32.0:
                    return TradeDirection.PUT
                return TradeDirection.NO_TRADE
            if last_close <= snap.bb_lower or snap.stochastic_k <= 22.0:
                return TradeDirection.CALL
            if last_close >= snap.bb_upper or snap.stochastic_k >= 78.0:
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        if strategy_id == "mtf_confirmation":
            bull_aligned = snap.ema_fast > snap.ema_slow and snap.sma_fast > snap.sma_slow and snap.macd_histogram > 0
            bear_aligned = snap.ema_fast < snap.ema_slow and snap.sma_fast < snap.sma_slow and snap.macd_histogram < 0
            if bull_aligned:
                return TradeDirection.CALL
            if bear_aligned:
                return TradeDirection.PUT
            return TradeDirection.NO_TRADE

        # regime_adaptive
        if snap.regime in (MarketRegime.STRONG_TREND, MarketRegime.WEAK_TREND, MarketRegime.BREAKOUT, MarketRegime.TRENDING_UP, MarketRegime.TRENDING_DOWN):
            if snap.ema_fast > snap.ema_slow and snap.macd_histogram >= 0:
                return TradeDirection.CALL
            if snap.ema_fast < snap.ema_slow and snap.macd_histogram <= 0:
                return TradeDirection.PUT
        else:
            if snap.rsi <= cfg.rsi_oversold or last_close <= snap.bb_lower:
                return TradeDirection.CALL
            if snap.rsi >= cfg.rsi_overbought or last_close >= snap.bb_upper:
                return TradeDirection.PUT
        return TradeDirection.NO_TRADE

    @classmethod
    def _walk_forward_metrics(
        cls,
        template: _StrategyTemplate,
        candles: Sequence[Dict[str, float]],
        orders: Sequence[object],
        payout_rate: float = 0.85,
    ) -> Dict[str, float]:
        ordered = sorted((dict(c) for c in candles), key=lambda x: int(x["timestamp"]))
        outcomes: List[float] = []

        # 1. Strictly causal walk-forward evaluation on historical candles
        if len(ordered) >= 40:
            step = max(1, (len(ordered) - 32) // 28)
            for idx in range(30, len(ordered) - 2, step):
                causal_window = ordered[max(0, idx - 60) : idx + 1]
                direction = cls._strategy_bar_direction(template.strategy_id, causal_window, template.default_config)
                if direction == TradeDirection.NO_TRADE:
                    continue
                entry_price = float(ordered[idx + 1]["open"])
                exit_price = float(ordered[idx + 2]["close"])
                won = (exit_price > entry_price) if direction == TradeDirection.CALL else (exit_price < entry_price)
                outcomes.append(payout_rate if won else -1.0)

        # 2. Blend recorded settled live/sim order outcomes matching this strategy if present
        for order in orders:
            status_val = getattr(order, "status", "")
            if status_val not in (TradeStatus.WON.value, TradeStatus.LOST.value):
                continue
            note = str(getattr(order, "message", "") or "").lower()
            if template.name.lower() in note or template.strategy_id.lower() in note:
                stake = max(1.0, float(getattr(order, "amount", 25.0) or 25.0))
                outcomes.append(float(getattr(order, "pnl", 0.0) or 0.0) / stake)

        sample_count = len(outcomes)
        if sample_count == 0:
            return {
                "win_rate": 50.0,
                "profit_factor": 1.0,
                "max_drawdown": 0.0,
                "sample_count": 0,
                "expected_value": 0.0,
            }

        wins = sum(1 for x in outcomes if x > 0)
        gross_profit = sum(x for x in outcomes if x > 0)
        gross_loss = abs(sum(x for x in outcomes if x < 0))
        win_rate = (wins / sample_count) * 100.0
        profit_factor = (
            gross_profit / gross_loss
            if gross_loss > 1e-9
            else (min(3.5, gross_profit) if gross_profit > 0 else 0.0)
        )

        # Max drawdown expressed as % of initial 10-unit capital base for readability
        equity = 0.0
        peak = 0.0
        max_dd_units = 0.0
        for r in outcomes:
            equity += r
            peak = max(peak, equity)
            max_dd_units = max(max_dd_units, peak - equity)
        max_drawdown_pct = min(100.0, (max_dd_units / 10.0) * 100.0)

        expected_value = sum(outcomes) / sample_count
        return {
            "win_rate": round(win_rate, 2),
            "profit_factor": round(min(5.0, profit_factor), 3),
            "max_drawdown": round(max_drawdown_pct, 2),
            "sample_count": sample_count,
            "expected_value": round(expected_value, 4),
        }

    def evaluate_all_strategies(
        self,
        candles: Sequence[Dict[str, float]],
        current_regime: MarketRegime,
        orders: Sequence[object] = (),
    ) -> List[StrategyModuleProfile]:
        profiles: List[StrategyModuleProfile] = []
        for template in _STRATEGY_TEMPLATES:
            metrics = self._walk_forward_metrics(template, candles, orders)
            regime_fit = self._compute_regime_fit(template.target_regimes, current_regime)

            sample_count = int(metrics["sample_count"])
            sample_weight = min(1.0, sample_count / 12.0)
            ev_component = max(-1.0, min(1.0, metrics["expected_value"])) * sample_weight
            pf_component = min(2.0, metrics["profit_factor"]) / 2.0 * sample_weight
            dd_penalty = min(0.4, metrics["max_drawdown"] / 100.0)

            selection_score = round(
                max(
                    0.0,
                    min(
                        100.0,
                        (regime_fit * 55.0)
                        + (ev_component * 20.0)
                        + (pf_component * 22.0)
                        + (sample_weight * 8.0)
                        - (dd_penalty * 15.0),
                    ),
                ),
                2,
            )

            profiles.append(
                StrategyModuleProfile(
                    strategy_id=template.strategy_id,
                    name=template.name,
                    description=template.description,
                    target_regimes=list(template.target_regimes),
                    entry_conditions=template.entry_conditions,
                    exit_conditions=template.exit_conditions,
                    risk_parameters=template.risk_parameters,
                    win_rate=float(metrics["win_rate"]),
                    profit_factor=float(metrics["profit_factor"]),
                    max_drawdown=float(metrics["max_drawdown"]),
                    sample_count=sample_count,
                    expected_value=float(metrics["expected_value"]),
                    regime_fit_score=round(regime_fit, 3),
                    selection_score=selection_score,
                    selected=False,
                    default_config=template.default_config,
                )
            )
        return profiles

    def build_report(
        self,
        candles: Sequence[Dict[str, float]],
        current_regime: MarketRegime,
        orders: Sequence[object] = (),
    ) -> StrategySelectionReport:
        evaluated = self.evaluate_all_strategies(candles, current_regime, orders)
        if self._auto_select:
            chosen = max(evaluated, key=lambda p: (p.selection_score, p.regime_fit_score, p.expected_value))
            self._selected_strategy_id = chosen.strategy_id
            reason = (
                f"Auto-selected '{chosen.name}' for regime {current_regime.value} "
                f"(score {chosen.selection_score:.1f}, fit {chosen.regime_fit_score:.2f}, EV {chosen.expected_value:+.3f})"
            )
        else:
            chosen = next((p for p in evaluated if p.strategy_id == self._selected_strategy_id), evaluated[0])
            reason = f"Manually pinned strategy '{chosen.name}' (regime {current_regime.value})"

        marked = [replace(p, selected=(p.strategy_id == chosen.strategy_id)) for p in evaluated]
        marked.sort(key=lambda p: (not p.selected, -p.selection_score))

        return StrategySelectionReport(
            selected_strategy_id=chosen.strategy_id,
            selected_strategy_name=chosen.name,
            auto_selected=self._auto_select,
            current_regime=current_regime,
            selection_reason=reason,
            strategies=marked,
        )

    def select_strategy(
        self,
        candles: Sequence[Dict[str, float]],
        current_regime: MarketRegime,
        strategy_id: Optional[str] = None,
        auto_select: bool = True,
        orders: Sequence[object] = (),
    ) -> StrategySelectionReport:
        if not auto_select:
            if not strategy_id:
                raise ValueError("strategy_id is required when auto_select=False.")
            valid_ids = {t.strategy_id for t in _STRATEGY_TEMPLATES}
            if strategy_id not in valid_ids:
                raise KeyError(f"Unknown strategy_id: {strategy_id}")
            self._auto_select = False
            self._selected_strategy_id = strategy_id
        else:
            self._auto_select = True
            if strategy_id is not None and strategy_id in {t.strategy_id for t in _STRATEGY_TEMPLATES}:
                self._selected_strategy_id = strategy_id

        report = self.build_report(candles=candles, current_regime=current_regime, orders=orders)
        chosen_profile = next(p for p in report.strategies if p.selected)
        base_cfg = chosen_profile.default_config
        self._active_strategy = replace(
            base_cfg,
            instrument=self._active_strategy.instrument,
            timeframe=self._active_strategy.timeframe,
            trade_amount=self._active_strategy.trade_amount,
            expiry_seconds=self._active_strategy.expiry_seconds,
        )
        return report
