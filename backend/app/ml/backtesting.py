from dataclasses import dataclass
import math
from typing import List, Sequence


@dataclass(frozen=True)
class BacktestReport:
    total_predictions: int
    total_trades: int
    skipped_signals: int
    wins: int
    losses: int
    win_rate: float
    net_pnl: float
    profit_factor: float
    max_drawdown: float
    accuracy: float
    brier_score: float
    log_loss: float
    ece: float


class WalkForwardBacktester:
    """
    Evaluates calibrated probability estimates on out-of-sample predictions with
    payout assumptions, transaction friction, and NO_TRADE thresholding.
    """

    @staticmethod
    def compute_brier_score(probs: Sequence[float], y_true: Sequence[int]) -> float:
        if not probs or len(probs) != len(y_true):
            return 1.0
        total = sum((float(p) - int(y)) ** 2 for p, y in zip(probs, y_true))
        return round(total / len(probs), 6)

    @staticmethod
    def compute_log_loss(probs: Sequence[float], y_true: Sequence[int]) -> float:
        if not probs or len(probs) != len(y_true):
            return 1.0
        eps = 1e-7
        total = 0.0
        for p, y in zip(probs, y_true):
            clipped = min(1.0 - eps, max(eps, float(p)))
            if int(y) == 1:
                total -= math.log(clipped)
            else:
                total -= math.log(1.0 - clipped)
        return round(total / len(probs), 6)

    @staticmethod
    def compute_ece(probs: Sequence[float], y_true: Sequence[int], n_bins: int = 5) -> float:
        """Expected Calibration Error measuring gap between predicted probability and empirical frequency."""
        n = len(probs)
        if n == 0 or n != len(y_true):
            return 1.0
        ece = 0.0
        for b in range(n_bins):
            low = b / n_bins
            high = (b + 1) / n_bins
            bucket = [
                (float(p), int(y))
                for p, y in zip(probs, y_true)
                if (low <= float(p) < high) or (b == n_bins - 1 and float(p) == 1.0)
            ]
            if not bucket:
                continue
            avg_conf = sum(p for p, _ in bucket) / len(bucket)
            avg_acc = sum(y for _, y in bucket) / len(bucket)
            ece += (len(bucket) / n) * abs(avg_acc - avg_conf)
        return round(ece, 6)

    @classmethod
    def evaluate(
        cls,
        probs: Sequence[float],
        y_true: Sequence[int],
        future_returns: Sequence[float],
        trade_amount: float = 25.0,
        payout_rate: float = 0.85,
        entry_threshold: float = 0.54,
        transaction_cost_pct: float = 0.00005,
    ) -> BacktestReport:
        n = len(probs)
        if n == 0 or not (n == len(y_true) == len(future_returns)):
            raise ValueError("Mismatched or empty backtest arrays.")

        correct = 0
        wins = 0
        losses = 0
        skipped = 0
        gross_profit = 0.0
        gross_loss = 0.0
        equity = 0.0
        peak = 0.0
        max_dd = 0.0

        for prob, y, ret in zip(probs, y_true, future_returns):
            pred = 1 if prob >= 0.5 else 0
            if pred == int(y):
                correct += 1

            # NO_TRADE gate when calibrated probability is too close to 50/50
            if prob >= entry_threshold:
                trade_won = (float(ret) - transaction_cost_pct) > 0.0
            elif prob <= (1.0 - entry_threshold):
                trade_won = (-float(ret) - transaction_cost_pct) > 0.0
            else:
                skipped += 1
                continue

            if trade_won:
                wins += 1
                pnl = round(trade_amount * payout_rate, 2)
                gross_profit += pnl
            else:
                losses += 1
                pnl = round(-trade_amount, 2)
                gross_loss += abs(pnl)

            equity = round(equity + pnl, 2)
            peak = max(peak, equity)
            max_dd = max(max_dd, round(peak - equity, 2))

        total_trades = wins + losses
        win_rate = round((wins / total_trades) * 100.0, 2) if total_trades > 0 else 0.0
        profit_factor = (
            round(gross_profit / gross_loss, 3)
            if gross_loss > 0
            else (round(gross_profit, 3) if gross_profit > 0 else 0.0)
        )

        return BacktestReport(
            total_predictions=n,
            total_trades=total_trades,
            skipped_signals=skipped,
            wins=wins,
            losses=losses,
            win_rate=win_rate,
            net_pnl=round(equity, 2),
            profit_factor=profit_factor,
            max_drawdown=round(max_dd, 2),
            accuracy=round(correct / n, 6),
            brier_score=cls.compute_brier_score(probs, y_true),
            log_loss=cls.compute_log_loss(probs, y_true),
            ece=cls.compute_ece(probs, y_true),
        )
