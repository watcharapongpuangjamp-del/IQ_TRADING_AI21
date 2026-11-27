"""Deterministic statistics for broker-confirmed Practice settlements."""
from __future__ import annotations

from math import isfinite
from typing import Any, Iterable


def summarize_trades(trades: Iterable[dict[str, Any]], starting_balance: float | None = None) -> dict[str, Any]:
    """Summarize settled trades only; draws are separate and reset win/loss streaks."""
    rows = list(trades)
    settled = []
    for row in rows:
        outcome = str(row.get("outcome", "")).upper()
        if outcome not in {"WIN", "DRAW", "LOSS"}:
            continue
        try:
            pnl = float(row.get("pnl"))
        except (TypeError, ValueError, OverflowError):
            continue
        if not isfinite(pnl):
            continue
        settled.append((outcome, pnl, row))

    wins = sum(1 for outcome, _, _ in settled if outcome == "WIN")
    draws = sum(1 for outcome, _, _ in settled if outcome == "DRAW")
    losses = sum(1 for outcome, _, _ in settled if outcome == "LOSS")
    count = len(settled)
    net_pnl = sum(pnl for _, pnl, _ in settled)
    decisive = wins + losses

    current_win = current_loss = max_win = max_loss = 0
    for outcome, _, _ in settled:
        if outcome == "WIN":
            current_win += 1
            current_loss = 0
            max_win = max(max_win, current_win)
        elif outcome == "LOSS":
            current_loss += 1
            current_win = 0
            max_loss = max(max_loss, current_loss)
        else:
            current_win = current_loss = 0

    # Prefer broker-reported post-trade balances. If absent, construct a
    # realized-P&L curve from the supplied starting balance.
    balances = []
    for _, pnl, row in settled:
        try:
            value = float(row.get("balanceAfter"))
        except (TypeError, ValueError, OverflowError):
            value = float("nan")
        if isfinite(value) and value >= 0:
            balances.append(value)
        elif starting_balance is not None and isfinite(float(starting_balance)):
            previous = balances[-1] if balances else float(starting_balance)
            balances.append(previous + pnl)

    peak = float(starting_balance) if starting_balance is not None else (
        balances[0] if balances else 0.0
    )
    max_dd = 0.0
    max_dd_pct = 0.0
    for balance in balances:
        peak = max(peak, balance)
        dd = max(0.0, peak - balance)
        max_dd = max(max_dd, dd)
        if peak > 0:
            max_dd_pct = max(max_dd_pct, dd / peak * 100.0)

    latest_balance = balances[-1] if balances else None
    return {
        "totalTrades": count,
        "wins": wins,
        "draws": draws,
        "losses": losses,
        "winRate": (wins / count * 100.0) if count else 0.0,
        "decisiveWinRate": (wins / decisive * 100.0) if decisive else 0.0,
        "drawRate": (draws / count * 100.0) if count else 0.0,
        "netPnl": net_pnl,
        "currentWinStreak": current_win,
        "currentLossStreak": current_loss,
        "maxWinStreak": max_win,
        "maxLossStreak": max_loss,
        "maxDrawdown": max_dd,
        "maxDrawdownPct": max_dd_pct,
        "latestBalance": latest_balance,
        "dataSource": "BROKER_CONFIRMED_SETTLEMENTS",
    }
