from datetime import datetime, timezone
from sqlalchemy import BigInteger, Boolean, DateTime, Float, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class TradingStateModel(Base):
    __tablename__ = "trading_state"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    execution_mode: Mapped[str] = mapped_column(String(20), default="SIMULATION", nullable=False)
    simulation_balance: Mapped[float] = mapped_column(Float, default=10000.0, nullable=False)
    last_loss_epoch_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)


class RiskPolicyModel(Base):
    __tablename__ = "risk_policy"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    max_trade_amount: Mapped[float] = mapped_column(Float, default=100.0, nullable=False)
    max_daily_loss: Mapped[float] = mapped_column(Float, default=250.0, nullable=False)
    max_consecutive_losses: Mapped[int] = mapped_column(Integer, default=3, nullable=False)
    max_daily_trades: Mapped[int] = mapped_column(Integer, default=30, nullable=False)
    max_portfolio_exposure: Mapped[float] = mapped_column(Float, default=500.0, nullable=False)
    cooldown_minutes: Mapped[int] = mapped_column(Integer, default=15, nullable=False)
    emergency_stopped: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    auto_trade_enabled: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)


class OrderRecordModel(Base):
    __tablename__ = "orders"

    order_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    idempotency_key: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)
    instrument: Mapped[str] = mapped_column(String(20), nullable=False)
    timeframe: Mapped[str] = mapped_column(String(10), nullable=False)
    direction: Mapped[str] = mapped_column(String(16), nullable=False)
    mode: Mapped[str] = mapped_column(String(20), nullable=False)
    amount: Mapped[float] = mapped_column(Float, nullable=False)
    payout_rate: Mapped[float] = mapped_column(Float, default=0.85, nullable=False)
    entry_price: Mapped[float] = mapped_column(Float, nullable=False)
    exit_price: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    pnl: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    date_key: Mapped[str] = mapped_column(String(10), index=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)


class DailySummaryModel(Base):
    __tablename__ = "daily_summaries"

    date_key: Mapped[str] = mapped_column(String(10), primary_key=True)
    total_trades: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    wins: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    losses: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    net_pnl: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    consecutive_losses: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    peak_drawdown: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)
