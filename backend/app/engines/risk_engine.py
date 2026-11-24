import time
from dataclasses import dataclass
from typing import Optional

from app.domain import ExecutionMode, RiskPolicyConfig, TradeDirection


@dataclass(frozen=True)
class RiskDecision:
    allowed: bool
    reason: str
    remaining_daily_trades: int
    remaining_loss_budget: float


class RiskManagementEngine:
    """Independent pre-trade risk gate. No AI component may override these limits."""

    @staticmethod
    def validate_order(
        policy: RiskPolicyConfig,
        total_trades_today: int,
        net_pnl_today: float,
        consecutive_losses: int,
        open_exposure: float,
        requested_amount: float,
        direction: TradeDirection,
        mode: ExecutionMode,
        live_authorized: bool,
        last_loss_epoch_ms: Optional[int],
        now_ms: Optional[int] = None,
    ) -> RiskDecision:
        now = now_ms if now_ms is not None else int(time.time() * 1000)
        daily_loss = max(0.0, -net_pnl_today)
        remaining_trades = max(0, policy.max_daily_trades - total_trades_today)
        remaining_loss_budget = max(0.0, policy.max_daily_loss - daily_loss)

        if policy.emergency_stopped:
            return RiskDecision(False, "Emergency stop is active.", remaining_trades, remaining_loss_budget)

        if direction == TradeDirection.NO_TRADE:
            return RiskDecision(False, "Direction is NO_TRADE.", remaining_trades, remaining_loss_budget)

        if mode == ExecutionMode.LIVE and not live_authorized:
            return RiskDecision(
                False,
                "LIVE execution is locked until connector validation and explicit owner authorization.",
                remaining_trades,
                remaining_loss_budget,
            )

        if requested_amount <= 0 or requested_amount > policy.max_trade_amount:
            return RiskDecision(
                False,
                f"Trade amount ${requested_amount:.2f} exceeds max per trade ${policy.max_trade_amount:.2f}.",
                remaining_trades,
                remaining_loss_budget,
            )

        if daily_loss >= policy.max_daily_loss:
            return RiskDecision(
                False,
                f"Max daily loss limit reached (${daily_loss:.2f} / ${policy.max_daily_loss:.2f}).",
                remaining_trades,
                0.0,
            )

        if consecutive_losses >= policy.max_consecutive_losses:
            return RiskDecision(
                False,
                f"Max consecutive losses reached ({consecutive_losses}/{policy.max_consecutive_losses}).",
                remaining_trades,
                remaining_loss_budget,
            )

        if total_trades_today >= policy.max_daily_trades:
            return RiskDecision(
                False,
                f"Max daily trades reached ({total_trades_today}/{policy.max_daily_trades}).",
                0,
                remaining_loss_budget,
            )

        if open_exposure + requested_amount > policy.max_portfolio_exposure:
            return RiskDecision(
                False,
                f"Portfolio exposure ${open_exposure + requested_amount:.2f} exceeds limit ${policy.max_portfolio_exposure:.2f}.",
                remaining_trades,
                remaining_loss_budget,
            )

        if policy.cooldown_minutes > 0 and last_loss_epoch_ms is not None and consecutive_losses > 0:
            cooldown_ms = policy.cooldown_minutes * 60_000
            elapsed = now - last_loss_epoch_ms
            if 0 <= elapsed < cooldown_ms:
                remain_sec = max(1, (cooldown_ms - elapsed) // 1000)
                return RiskDecision(
                    False,
                    f"Loss cooldown active ({remain_sec}s remaining).",
                    remaining_trades,
                    remaining_loss_budget,
                )

        return RiskDecision(True, "Risk validation passed.", remaining_trades, remaining_loss_budget)
