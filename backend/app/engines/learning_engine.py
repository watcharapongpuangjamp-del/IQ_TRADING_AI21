from typing import Dict, Sequence

from app.models import OrderRecordModel
from app.schemas import TradeStatus


class LearningEvaluationEngine:
    """
    Phase 1 evaluation foundation tracking settled trade metrics and enforcing
    chronological / out-of-sample gating rules prior to Phase 3 & 6 ML deployment.
    """

    @staticmethod
    def summarize_orders(orders: Sequence[OrderRecordModel]) -> Dict[str, object]:
        settled = [o for o in orders if o.status in (TradeStatus.WON.value, TradeStatus.LOST.value)]
        total = len(settled)
        wins = sum(1 for o in settled if o.status == TradeStatus.WON.value)
        losses = sum(1 for o in settled if o.status == TradeStatus.LOST.value)
        gross_profit = sum(o.pnl for o in settled if o.pnl > 0)
        gross_loss = abs(sum(o.pnl for o in settled if o.pnl < 0))
        net_pnl = round(sum(o.pnl for o in settled), 2)

        equity = 0.0
        peak = 0.0
        max_drawdown = 0.0
        for order in sorted(settled, key=lambda x: x.created_at):
            equity += order.pnl
            peak = max(peak, equity)
            max_drawdown = max(max_drawdown, peak - equity)

        win_rate = round((wins / total) * 100.0, 2) if total > 0 else 0.0
        profit_factor = round(gross_profit / gross_loss, 3) if gross_loss > 0 else (round(gross_profit, 3) if gross_profit > 0 else 0.0)

        return {
            "total_settled_trades": total,
            "wins": wins,
            "losses": losses,
            "win_rate_pct": win_rate,
            "net_pnl": net_pnl,
            "profit_factor": profit_factor,
            "max_drawdown": round(max_drawdown, 2),
            "out_of_sample_ready": total >= 30,
            "model_registry_status": "PHASE_1_BASELINE_HEURISTIC_NO_AUTO_DEPLOY",
        }
