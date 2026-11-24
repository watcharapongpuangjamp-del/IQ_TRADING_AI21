from typing import List, Optional
from pydantic import BaseModel, Field

from app.domain import (
    ExecutionMode,
    MarketRegime,
    RiskPolicyConfig,
    SignalResult,
    StrategyConfig,
    TechnicalSnapshot,
    TradeDirection,
    TradeStatus,
)


class HealthResponse(BaseModel):
    status: str = "ok"
    connector: str
    capabilities: List[str]
    live_authorized: bool = False
    execution_mode: ExecutionMode = ExecutionMode.SIMULATION


class CandleSchema(BaseModel):
    timestamp: int = Field(..., gt=0)
    open: float = Field(..., gt=0)
    high: float = Field(..., gt=0)
    low: float = Field(..., gt=0)
    close: float = Field(..., gt=0)
    volume: float = Field(default=0.0, ge=0)


class StrategyParameters(BaseModel):
    name: str = Field(default="EMA-RSI Momentum", min_length=1, max_length=80)
    instrument: str = Field(default="EUR/USD", min_length=3, max_length=20)
    timeframe: str = Field(default="1m", pattern="^(1m|5m|15m)$")
    trade_amount: float = Field(default=25.0, ge=1.0, le=100_000.0)
    expiry_seconds: int = Field(default=60, ge=60, le=3600)
    rsi_period: int = Field(default=14, ge=2, le=50)
    rsi_oversold: int = Field(default=30, ge=5, le=45)
    rsi_overbought: int = Field(default=70, ge=55, le=95)
    ema_fast: int = Field(default=9, ge=2, le=50)
    ema_slow: int = Field(default=21, ge=5, le=200)
    macd_fast: int = Field(default=12, ge=2, le=50)
    macd_slow: int = Field(default=26, ge=5, le=100)
    macd_signal: int = Field(default=9, ge=2, le=50)
    bb_period: int = Field(default=20, ge=5, le=50)
    bb_std_dev: float = Field(default=2.0, ge=0.5, le=4.0)
    min_confidence: int = Field(default=65, ge=50, le=99)

    def to_domain(self) -> StrategyConfig:
        return StrategyConfig(**self.model_dump())


class TechnicalSnapshotSchema(BaseModel):
    rsi: float
    macd_line: float
    macd_signal: float
    macd_histogram: float
    ema_fast: float
    ema_slow: float
    bb_upper: float
    bb_middle: float
    bb_lower: float
    atr: float
    volatility_pct: float
    trend_strength: float
    support_level: float
    resistance_level: float
    candlestick_pattern: str
    regime: MarketRegime

    @classmethod
    def from_domain(cls, snap: TechnicalSnapshot) -> "TechnicalSnapshotSchema":
        return cls(
            rsi=snap.rsi,
            macd_line=snap.macd_line,
            macd_signal=snap.macd_signal,
            macd_histogram=snap.macd_histogram,
            ema_fast=snap.ema_fast,
            ema_slow=snap.ema_slow,
            bb_upper=snap.bb_upper,
            bb_middle=snap.bb_middle,
            bb_lower=snap.bb_lower,
            atr=snap.atr,
            volatility_pct=snap.volatility_pct,
            trend_strength=snap.trend_strength,
            support_level=snap.support_level,
            resistance_level=snap.resistance_level,
            candlestick_pattern=snap.candlestick_pattern,
            regime=snap.regime,
        )


class SignalResponse(BaseModel):
    instrument: str
    timeframe: str
    direction: TradeDirection
    calibrated_probability: float = Field(..., ge=0.0, le=1.0)
    confidence_score: int = Field(..., ge=0, le=100)
    supporting_evidence: List[str]
    contradicting_evidence: List[str]
    market_regime: MarketRegime
    risk_assessment: str
    explanation: str
    expires_at_ms: int
    technical_snapshot: TechnicalSnapshotSchema

    @classmethod
    def from_domain(cls, res: SignalResult) -> "SignalResponse":
        assert res.technical_snapshot is not None
        return cls(
            instrument=res.instrument,
            timeframe=res.timeframe,
            direction=res.direction,
            calibrated_probability=res.calibrated_probability,
            confidence_score=res.confidence_score,
            supporting_evidence=res.supporting_evidence,
            contradicting_evidence=res.contradicting_evidence,
            market_regime=res.market_regime,
            risk_assessment=res.risk_assessment,
            explanation=res.explanation,
            expires_at_ms=res.expires_at_ms,
            technical_snapshot=TechnicalSnapshotSchema.from_domain(res.technical_snapshot),
        )


class RiskPolicySchema(BaseModel):
    max_trade_amount: float = Field(default=100.0, ge=1.0, le=100_000.0)
    max_daily_loss: float = Field(default=250.0, ge=1.0, le=1_000_000.0)
    max_consecutive_losses: int = Field(default=3, ge=1, le=20)
    max_daily_trades: int = Field(default=30, ge=1, le=500)
    max_portfolio_exposure: float = Field(default=500.0, ge=1.0, le=1_000_000.0)
    cooldown_minutes: int = Field(default=15, ge=0, le=1440)
    emergency_stopped: bool = False
    auto_trade_enabled: bool = False

    def to_domain(self) -> RiskPolicyConfig:
        return RiskPolicyConfig(**self.model_dump())


class OrderSubmitRequest(BaseModel):
    instrument: str = Field(..., min_length=3, max_length=20)
    timeframe: str = Field(..., pattern="^(1m|5m|15m)$")
    direction: TradeDirection
    amount: float = Field(..., ge=1.0, le=100_000.0)
    expiry_seconds: int = Field(default=60, ge=60, le=3600)
    mode: ExecutionMode = ExecutionMode.SIMULATION
    idempotency_key: str = Field(..., min_length=8, max_length=128)


class OrderSubmitResponse(BaseModel):
    order_id: str
    status: str
    entry_price: float
    exit_price: float = 0.0
    pnl: float = 0.0
    message: Optional[str] = None


class ModeSwitchRequest(BaseModel):
    mode: ExecutionMode
    explicit_live_confirmation: bool = False


class TradingStateResponse(BaseModel):
    execution_mode: ExecutionMode
    connector_name: str
    connector_connected: bool
    live_authorized: bool
    balance: float
    total_trades_today: int
    wins_today: int
    losses_today: int
    net_pnl_today: float
    consecutive_losses: int
    emergency_stopped: bool
    win_rate_pct: float
