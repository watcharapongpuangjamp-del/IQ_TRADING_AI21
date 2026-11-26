from datetime import datetime, timezone
from sqlalchemy import BigInteger, Boolean, DateTime, Float, Index, Integer, String, Text, UniqueConstraint
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
    correlation_id: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    broker_order_id: Mapped[str | None] = mapped_column(String(64), index=True, nullable=True)
    instrument: Mapped[str] = mapped_column(String(20), nullable=False)
    timeframe: Mapped[str] = mapped_column(String(10), nullable=False)
    direction: Mapped[str] = mapped_column(String(16), nullable=False)
    mode: Mapped[str] = mapped_column(String(20), nullable=False)
    amount: Mapped[float] = mapped_column(Float, nullable=False)
    payout_rate: Mapped[float] = mapped_column(Float, default=0.85, nullable=False)
    entry_price: Mapped[float] = mapped_column(Float, nullable=False)
    exit_price: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    lifecycle_state: Mapped[str] = mapped_column(String(24), default="CREATED", index=True, nullable=False)
    settlement_outcome: Mapped[str | None] = mapped_column(String(20), nullable=True)
    retry_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    error_detail: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    reconciled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    pnl: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    date_key: Mapped[str] = mapped_column(String(10), index=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)


class OrderLifecycleEventModel(Base):
    __tablename__ = "order_lifecycle_events"

    event_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    order_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    correlation_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    from_state: Mapped[str | None] = mapped_column(String(24), nullable=True)
    to_state: Mapped[str] = mapped_column(String(24), nullable=False)
    detail: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)


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


class HistoricalCandleModel(Base):
    __tablename__ = "historical_candles"
    __table_args__ = (
        UniqueConstraint("instrument", "timeframe", "timestamp", name="uq_candle_series_ts"),
        Index("ix_candle_lookup", "instrument", "timeframe", "timestamp"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    instrument: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    timeframe: Mapped[str] = mapped_column(String(10), nullable=False, index=True)
    timestamp: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    open: Mapped[float] = mapped_column(Float, nullable=False)
    high: Mapped[float] = mapped_column(Float, nullable=False)
    low: Mapped[float] = mapped_column(Float, nullable=False)
    close: Mapped[float] = mapped_column(Float, nullable=False)
    volume: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    source: Mapped[str] = mapped_column(String(32), default="simulation", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class ModelRegistryModel(Base):
    __tablename__ = "model_registry"

    model_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    instrument: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    timeframe: Mapped[str] = mapped_column(String(10), nullable=False, index=True)
    algorithm: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(24), default="CANDIDATE", nullable=False, index=True)
    train_samples: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    validation_samples: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    test_samples: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    out_of_sample_accuracy: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    brier_score: Mapped[float] = mapped_column(Float, default=1.0, nullable=False)
    log_loss: Mapped[float] = mapped_column(Float, default=1.0, nullable=False)
    ece: Mapped[float] = mapped_column(Float, default=1.0, nullable=False)
    simulated_trades: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    simulated_win_rate: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    simulated_net_pnl: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    artifact_filename: Mapped[str] = mapped_column(String(128), nullable=False)
    validation_notes: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    dataset_version: Mapped[str] = mapped_column(String(64), default="ds_v2_unknown", nullable=False)
    feature_count: Mapped[int] = mapped_column(Integer, default=17, nullable=False)
    baseline_stats_json: Mapped[str] = mapped_column(Text, default="{}", nullable=False)
    drift_status: Mapped[str] = mapped_column(String(24), default="HEALTHY", nullable=False)
    drift_score: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    live_settled_trades: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    live_win_rate: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    drift_notes: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    last_drift_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    rolled_back_from_model_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    promoted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
