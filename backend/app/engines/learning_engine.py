from __future__ import annotations

from typing import TYPE_CHECKING, Dict, List, Sequence

from app.domain import TradeStatus

if TYPE_CHECKING:
    from app.models import OrderRecordModel


class LearningEvaluationEngine:
    """
    Mode-isolated Performance Analytics engine tracking settled trade metrics
    (SIMULATION vs. LIVE separated strictly) and dimensional breakdowns by
    Strategy, Market Regime, Asset, and Timeframe.
    """

    @staticmethod
    def _compute_mode_metrics(mode_label: str, settled: Sequence[object]) -> Dict[str, object]:
        ordered = sorted(settled, key=lambda x: getattr(x, "created_at", 0) or 0)
        total = len(ordered)
        wins = sum(1 for o in ordered if o.status == TradeStatus.WON.value)
        losses = sum(1 for o in ordered if o.status == TradeStatus.LOST.value)
        gross_profit = round(sum(float(o.pnl) for o in ordered if float(o.pnl) > 0), 2)
        gross_loss = round(abs(sum(float(o.pnl) for o in ordered if float(o.pnl) < 0)), 2)
        net_pnl = round(sum(float(o.pnl) for o in ordered), 2)

        equity = 0.0
        peak = 0.0
        max_drawdown = 0.0
        cur_win_streak = 0
        max_win_streak = 0
        cur_loss_streak = 0
        max_loss_streak = 0
        total_return_pct = 0.0

        for order in ordered:
            pnl = float(order.pnl)
            stake = max(1.0, float(getattr(order, "amount", 25.0) or 25.0))
            total_return_pct += (pnl / stake) * 100.0
            equity += pnl
            peak = max(peak, equity)
            max_drawdown = max(max_drawdown, peak - equity)
            if order.status == TradeStatus.WON.value:
                cur_win_streak += 1
                cur_loss_streak = 0
                max_win_streak = max(max_win_streak, cur_win_streak)
            else:
                cur_loss_streak += 1
                cur_win_streak = 0
                max_loss_streak = max(max_loss_streak, cur_loss_streak)

        win_rate = round((wins / total) * 100.0, 2) if total > 0 else 0.0
        profit_factor = (
            round(gross_profit / gross_loss, 3)
            if gross_loss > 0
            else (round(min(9.99, gross_profit), 3) if gross_profit > 0 else 0.0)
        )
        avg_return = round(total_return_pct / total, 2) if total > 0 else 0.0
        expectancy = round(net_pnl / total, 2) if total > 0 else 0.0

        return {
            "mode": mode_label,
            "total_trades": total,
            "wins": wins,
            "losses": losses,
            "win_rate_pct": win_rate,
            "net_pnl": net_pnl,
            "gross_profit": gross_profit,
            "gross_loss": gross_loss,
            "profit_factor": profit_factor,
            "average_return_pct": avg_return,
            "max_drawdown": round(max_drawdown, 2),
            "max_consecutive_wins": max_win_streak,
            "max_consecutive_losses": max_loss_streak,
            "expectancy": expectancy,
        }

    @staticmethod
    def _build_breakdown(
        settled: Sequence[OrderRecordModel],
        key_fn,
    ) -> List[Dict[str, object]]:
        buckets: Dict[str, List[OrderRecordModel]] = {}
        for o in settled:
            label = str(key_fn(o) or "UNKNOWN").strip() or "UNKNOWN"
            buckets.setdefault(label, []).append(o)

        rows: List[Dict[str, object]] = []
        for label, group in buckets.items():
            summary = LearningEvaluationEngine._compute_mode_metrics(label, group)
            rows.append(
                {
                    "label": label,
                    "total_trades": summary["total_trades"],
                    "wins": summary["wins"],
                    "losses": summary["losses"],
                    "win_rate_pct": summary["win_rate_pct"],
                    "net_pnl": summary["net_pnl"],
                    "profit_factor": summary["profit_factor"],
                }
            )
        rows.sort(key=lambda r: (-float(r["net_pnl"]), -int(r["total_trades"])))
        return rows

    @staticmethod
    def build_performance_report(
        orders: Sequence[OrderRecordModel],
        mode_filter: str = "SIMULATION",
    ) -> Dict[str, object]:
        normalized_filter = (mode_filter or "SIMULATION").strip().upper()
        if normalized_filter not in ("SIMULATION", "LIVE", "DEMO", "ALL"):
            normalized_filter = "SIMULATION"

        settled_all = [
            o for o in orders if o.status in (TradeStatus.WON.value, TradeStatus.LOST.value)
        ]
        sim_orders = [o for o in settled_all if str(getattr(o, "mode", "SIMULATION")).upper() == "SIMULATION"]
        live_orders = [o for o in settled_all if str(getattr(o, "mode", "")).upper() == "LIVE"]

        if normalized_filter == "ALL":
            filtered_orders = settled_all
            active_label = "ALL (LABELED)"
        elif normalized_filter == "LIVE":
            filtered_orders = live_orders
            active_label = "LIVE"
        elif normalized_filter == "DEMO":
            filtered_orders = [o for o in settled_all if str(getattr(o, "mode", "")).upper() == "DEMO"]
            active_label = "DEMO"
        else:
            filtered_orders = sim_orders
            active_label = "SIMULATION"

        sim_summary = LearningEvaluationEngine._compute_mode_metrics("SIMULATION", sim_orders)
        live_summary = LearningEvaluationEngine._compute_mode_metrics("LIVE", live_orders)
        active_summary = LearningEvaluationEngine._compute_mode_metrics(active_label, filtered_orders)

        return {
            "mode_filter": normalized_filter,
            "simulation_summary": sim_summary,
            "live_summary": live_summary,
            "active_summary": active_summary,
            "by_strategy": LearningEvaluationEngine._build_breakdown(
                filtered_orders, lambda o: getattr(o, "strategy_name", "Market Regime Adaptive")
            ),
            "by_regime": LearningEvaluationEngine._build_breakdown(
                filtered_orders, lambda o: getattr(o, "market_regime", "UNKNOWN")
            ),
            "by_asset": LearningEvaluationEngine._build_breakdown(
                filtered_orders, lambda o: getattr(o, "instrument", "UNKNOWN")
            ),
            "by_timeframe": LearningEvaluationEngine._build_breakdown(
                filtered_orders, lambda o: getattr(o, "timeframe", "1m")
            ),
        }

    @staticmethod
    def summarize_orders(orders: Sequence[OrderRecordModel]) -> Dict[str, object]:
        settled = [o for o in orders if o.status in (TradeStatus.WON.value, TradeStatus.LOST.value)]
        metrics = LearningEvaluationEngine._compute_mode_metrics("ALL", settled)
        total = int(metrics["total_trades"])
        return {
            "total_settled_trades": total,
            "wins": metrics["wins"],
            "losses": metrics["losses"],
            "win_rate_pct": metrics["win_rate_pct"],
            "net_pnl": metrics["net_pnl"],
            "profit_factor": metrics["profit_factor"],
            "max_drawdown": metrics["max_drawdown"],
            "out_of_sample_ready": total >= 30,
            "model_registry_status": "PHASE_8_MODE_ISOLATED_ANALYTICS_READY",
        }
