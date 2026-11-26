from typing import Dict, List, Optional, Sequence

from app.domain import StrategyConfig, TradeDirection
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.strategy_engine import StrategySelectionEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine


class WalkForwardBacktestEngine:
    """
    Chronological zero-look-ahead backtest engine.
    Evaluates causal windows `candles[:idx + 1]` and settles against `candles[idx + expiry_bars]`.
    Never mutates live/simulation account balances or order tables.
    """

    @staticmethod
    def run_backtest(
        candles: Sequence[Dict[str, float]],
        instrument: str,
        timeframe: str = "1m",
        strategy_id: str = "regime_adaptive",
        stake_amount: float = 25.0,
        payout_rate: float = 0.85,
        expiry_bars: int = 1,
        warmup_bars: int = 35,
    ) -> Dict[str, object]:
        ordered = sorted((dict(c) for c in candles), key=lambda x: int(x["timestamp"]))
        valid, reason = MarketIntelligenceEngine.validate_candles(ordered)
        if not valid:
            raise ValueError(f"Backtest candle validation failed: {reason}")
        if len(ordered) < warmup_bars + expiry_bars + 5:
            raise ValueError(
                f"Need at least {warmup_bars + expiry_bars + 5} candles to run backtest (got {len(ordered)})."
            )

        tpl = StrategySelectionEngine.get_template(strategy_id)
        if tpl is None and strategy_id != "auto":
            raise KeyError(f"Unknown strategy_id: {strategy_id}")

        resolved_name = tpl.name if tpl is not None else "Auto Regime Selector"
        cfg = tpl.default_config if tpl is not None else StrategyConfig(instrument=instrument, timeframe=timeframe)
        selector = StrategySelectionEngine()

        equity = 0.0
        peak = 0.0
        max_drawdown = 0.0
        gross_profit = 0.0
        gross_loss = 0.0
        wins = 0
        losses = 0
        equity_curve: List[float] = [0.0]
        simulated_trades: List[Dict[str, object]] = []

        idx = warmup_bars
        limit_idx = len(ordered) - expiry_bars - 1
        while idx <= limit_idx:
            causal_window = ordered[max(0, idx - 80) : idx + 1]
            snap = TechnicalAnalysisEngine.analyze(causal_window, cfg)
            effective_strategy_id = strategy_id
            effective_strategy_name = resolved_name
            if strategy_id == "auto":
                report = selector.build_report(causal_window, snap.regime)
                effective_strategy_id = report.selected_strategy_id
                effective_strategy_name = report.selected_strategy_name

            direction = StrategySelectionEngine.evaluate_bar_direction(
                effective_strategy_id, causal_window, cfg
            )
            if direction == TradeDirection.NO_TRADE:
                idx += 1
                continue

            entry_bar = ordered[idx + 1]
            exit_bar = ordered[idx + expiry_bars]
            entry_price = float(entry_bar["open"])
            exit_price = float(exit_bar["close"])
            won = (exit_price > entry_price) if direction == TradeDirection.CALL else (exit_price < entry_price)
            pnl = round(stake_amount * payout_rate, 2) if won else round(-stake_amount, 2)

            if won:
                wins += 1
                gross_profit += pnl
            else:
                losses += 1
                gross_loss += abs(pnl)

            equity = round(equity + pnl, 2)
            peak = max(peak, equity)
            max_drawdown = max(max_drawdown, round(peak - equity, 2))
            equity_curve.append(equity)

            simulated_trades.append(
                {
                    "timestamp": int(entry_bar["timestamp"]),
                    "direction": direction.value,
                    "entry_price": round(entry_price, 6),
                    "exit_price": round(exit_price, 6),
                    "pnl": pnl,
                    "status": "WON" if won else "LOST",
                    "market_regime": snap.regime.value,
                    "strategy_name": effective_strategy_name,
                }
            )
            idx += max(1, expiry_bars)

        total_trades = wins + losses
        win_rate_pct = round((wins / total_trades) * 100.0, 2) if total_trades > 0 else 0.0
        profit_factor = (
            round(gross_profit / gross_loss, 3)
            if gross_loss > 1e-9
            else (round(min(9.99, gross_profit), 3) if gross_profit > 0 else 0.0)
        )
        expectancy = round(equity / total_trades, 2) if total_trades > 0 else 0.0

        return {
            "instrument": instrument,
            "timeframe": timeframe,
            "strategy_id": strategy_id,
            "strategy_name": resolved_name,
            "bars_evaluated": len(ordered),
            "total_trades": total_trades,
            "wins": wins,
            "losses": losses,
            "win_rate_pct": win_rate_pct,
            "net_pnl": round(equity, 2),
            "gross_profit": round(gross_profit, 2),
            "gross_loss": round(gross_loss, 2),
            "profit_factor": profit_factor,
            "max_drawdown": round(max_drawdown, 2),
            "expectancy": expectancy,
            "equity_curve": equity_curve[-80:],
            "simulated_trades": simulated_trades[-40:],
        }
