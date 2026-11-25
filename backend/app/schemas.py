from datetime import datetime
from typing import List, Optional
from pydantic import BaseModel, Field

from app.domain import (
    AlignmentState,
    DataQualityReport,
    ExecutionMode,
    MarketRegime,
    ModelStatus,
    MultiTimeframeReport,
    ReplayState,
    RiskPolicyConfig,
    SignalResult,
    StrategyConfig,
    TechnicalSnapshot,
    TimeframeIntelligence,
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
    sma_fast: int = Field(default=10, ge=2, le=100)
    sma_slow: int = Field(default=30, ge=5, le=200)
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
    sma_fast: float = 0.0
    sma_slow: float = 0.0
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
            sma_fast=snap.sma_fast,
            sma_slow=snap.sma_slow,
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


class DataQualityReportSchema(BaseModel):
    valid: bool
    reason: str
    candle_count: int
    gap_count: int = 0
    outlier_count: int = 0
    duplicate_count: int = 0

    @classmethod
    def from_domain(cls, report: DataQualityReport) -> "DataQualityReportSchema":
        return cls(
            valid=report.valid,
            reason=report.reason,
            candle_count=report.candle_count,
            gap_count=report.gap_count,
            outlier_count=report.outlier_count,
            duplicate_count=report.duplicate_count,
        )


class TimeframeIntelligenceSchema(BaseModel):
    timeframe: str
    trend_bias: TradeDirection
    regime: MarketRegime
    rsi: float
    ema_fast: float
    ema_slow: float
    sma_fast: float
    sma_slow: float
    macd_histogram: float
    trend_strength: float
    latest_close: float

    @classmethod
    def from_domain(cls, item: TimeframeIntelligence) -> "TimeframeIntelligenceSchema":
        return cls(
            timeframe=item.timeframe,
            trend_bias=item.trend_bias,
            regime=item.regime,
            rsi=item.rsi,
            ema_fast=item.ema_fast,
            ema_slow=item.ema_slow,
            sma_fast=item.sma_fast,
            sma_slow=item.sma_slow,
            macd_histogram=item.macd_histogram,
            trend_strength=item.trend_strength,
            latest_close=item.latest_close,
        )


class MultiTimeframeReportSchema(BaseModel):
    instrument: str
    primary_timeframe: str
    alignment_state: AlignmentState
    alignment_score: int = Field(..., ge=0, le=100)
    higher_tf_bias: TradeDirection
    timeframe_summaries: List[TimeframeIntelligenceSchema]
    alignment_notes: List[str]

    @classmethod
    def from_domain(cls, report: MultiTimeframeReport) -> "MultiTimeframeReportSchema":
        return cls(
            instrument=report.instrument,
            primary_timeframe=report.primary_timeframe,
            alignment_state=report.alignment_state,
            alignment_score=report.alignment_score,
            higher_tf_bias=report.higher_tf_bias,
            timeframe_summaries=[TimeframeIntelligenceSchema.from_domain(s) for s in report.timeframe_summaries],
            alignment_notes=report.alignment_notes,
        )


class MarketIntelligenceResponse(BaseModel):
    instrument: str
    data_quality: DataQualityReportSchema
    mtf_report: MultiTimeframeReportSchema
    signal: SignalResponse


class CandleHistoryIngestRequest(BaseModel):
    instrument: str = Field(..., min_length=3, max_length=20)
    timeframe: str = Field(..., pattern="^(1m|5m|15m)$")
    source: str = Field(default="manual_ingest", min_length=2, max_length=32)
    candles: List[CandleSchema] = Field(..., min_length=25, max_length=1000)


class CandleHistoryIngestResponse(BaseModel):
    instrument: str
    timeframe: str
    received_count: int
    inserted_count: int
    total_stored: int


class ReplayStartRequest(BaseModel):
    instrument: str = Field(default="EUR/USD", min_length=3, max_length=20)
    total_bars: int = Field(default=420, ge=60, le=600)
    initial_window: int = Field(default=375, ge=25, le=500)


class ReplayStepRequest(BaseModel):
    steps: int = Field(default=1, ge=1, le=100)


class ReplayStateResponse(BaseModel):
    session_id: str
    instrument: str
    timeframe: str
    cursor_index: int
    total_bars: int
    cursor_timestamp: int
    active: bool

    @classmethod
    def from_domain(cls, state: ReplayState) -> "ReplayStateResponse":
        return cls(
            session_id=state.session_id,
            instrument=state.instrument,
            timeframe=state.timeframe,
            cursor_index=state.cursor_index,
            total_bars=state.total_bars,
            cursor_timestamp=state.cursor_timestamp,
            active=state.active,
        )


class ReplayEvaluationResponse(BaseModel):
    replay_state: ReplayStateResponse
    data_quality: DataQualityReportSchema
    mtf_report: MultiTimeframeReportSchema
    signal: SignalResponse


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
    correlation_id: str = ""
    broker_order_id: Optional[str] = None
    lifecycle_state: str = "SETTLED"
    settlement_outcome: Optional[str] = None
    reconciled_at: Optional[datetime] = None
    requires_reconciliation: bool = False


class OrderLifecycleEventResponse(BaseModel):
    event_id: str
    order_id: str
    correlation_id: str
    from_state: Optional[str] = None
    to_state: str
    detail: str = ""
    created_at: datetime


class ReconcileSummaryResponse(BaseModel):
    checked_count: int
    settled_count: int
    pending_count: int
    unknown_count: int
    failed_count: int
    orders: List[OrderSubmitResponse]
    message: str


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
    unresolved_orders_count: int = 0


class TrainModelRequest(BaseModel):
    instrument: str = Field(default="EUR/USD", min_length=3, max_length=20)
    timeframe: str = Field(default="1m", pattern="^(1m)$")
    algorithm: str = Field(default="logistic_regression", pattern="^(logistic_regression|hist_gradient_boosting)$")
    total_bars: int = Field(default=360, ge=220, le=600)
    payout_rate: float = Field(default=0.85, ge=0.50, le=0.98)
    execution_delay_bars: int = Field(default=1, ge=0, le=3)
    max_brier_score: float = Field(default=0.265, ge=0.05, le=0.50)
    max_ece: float = Field(default=0.20, ge=0.01, le=0.50)
    min_accuracy: float = Field(default=0.48, ge=0.40, le=0.99)


class PromoteModelRequest(BaseModel):
    owner_confirmed: bool = False


class ModelRegistryItemResponse(BaseModel):
    model_id: str
    instrument: str
    timeframe: str
    algorithm: str
    status: ModelStatus
    train_samples: int
    validation_samples: int
    test_samples: int
    out_of_sample_accuracy: float
    brier_score: float
    log_loss: float
    ece: float
    simulated_trades: int
    simulated_win_rate: float
    simulated_net_pnl: float
    artifact_filename: str
    validation_notes: str
    created_at: datetime
    promoted_at: Optional[datetime] = None

    @classmethod
    def from_orm_model(cls, row) -> "ModelRegistryItemResponse":
        return cls(
            model_id=row.model_id,
            instrument=row.instrument,
            timeframe=row.timeframe,
            algorithm=row.algorithm,
            status=ModelStatus(row.status),
            train_samples=row.train_samples,
            validation_samples=row.validation_samples,
            test_samples=row.test_samples,
            out_of_sample_accuracy=row.out_of_sample_accuracy,
            brier_score=row.brier_score,
            log_loss=row.log_loss,
            ece=row.ece,
            simulated_trades=row.simulated_trades,
            simulated_win_rate=row.simulated_win_rate,
            simulated_net_pnl=row.simulated_net_pnl,
            artifact_filename=row.artifact_filename,
            validation_notes=row.validation_notes,
            created_at=row.created_at,
            promoted_at=row.promoted_at,
        )
