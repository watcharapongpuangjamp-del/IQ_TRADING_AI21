from datetime import datetime
from typing import List, Optional
from pydantic import BaseModel, Field

from app.domain import (
    AlignmentState,
    DataQualityReport,
    DriftStatus,
    ExecutionMode,
    MarketRegime,
    ModelStatus,
    MultiTimeframeReport,
    ReplayState,
    RiskPolicyConfig,
    SignalResult,
    StrategyConfig,
    StrategyModuleProfile,
    StrategySelectionReport,
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
    adx: float = 0.0
    plus_di: float = 0.0
    minus_di: float = 0.0
    stochastic_k: float = 50.0
    stochastic_d: float = 50.0
    vwap: float = 0.0
    vwap_reliable: bool = False

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
            adx=snap.adx,
            plus_di=snap.plus_di,
            minus_di=snap.minus_di,
            stochastic_k=snap.stochastic_k,
            stochastic_d=snap.stochastic_d,
            vwap=snap.vwap,
            vwap_reliable=snap.vwap_reliable,
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
    algorithm: str = Field(
        default="logistic_regression",
        pattern="^(logistic_regression|hist_gradient_boosting|random_forest)$",
    )
    total_bars: int = Field(default=360, ge=220, le=600)
    payout_rate: float = Field(default=0.85, ge=0.50, le=0.98)
    execution_delay_bars: int = Field(default=1, ge=0, le=3)
    max_brier_score: float = Field(default=0.265, ge=0.05, le=0.50)
    max_ece: float = Field(default=0.20, ge=0.01, le=0.50)
    min_accuracy: float = Field(default=0.48, ge=0.40, le=0.99)


class PromoteModelRequest(BaseModel):
    owner_confirmed: bool = False


class RollbackModelRequest(BaseModel):
    owner_confirmed: bool = False
    reason: Optional[str] = Field(default=None, max_length=240)


class ModelDriftCheckResponse(BaseModel):
    model_id: str
    drift_status: DriftStatus
    drift_score: float
    feature_drift_score: float
    drifted_features: List[str]
    live_settled_trades: int
    live_win_rate: float
    baseline_win_rate: float
    win_rate_drop: float
    drift_notes: str
    recommend_rollback: bool
    checked_at: datetime


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
    dataset_version: str = "ds_v2_unknown"
    feature_count: int = 17
    drift_status: DriftStatus = DriftStatus.HEALTHY
    drift_score: float = 0.0
    live_settled_trades: int = 0
    live_win_rate: float = 0.0
    drift_notes: str = ""
    last_drift_checked_at: Optional[datetime] = None
    rolled_back_from_model_id: Optional[str] = None
    created_at: datetime
    promoted_at: Optional[datetime] = None

    @classmethod
    def from_orm_model(cls, row) -> "ModelRegistryItemResponse":
        raw_drift = getattr(row, "drift_status", "HEALTHY") or "HEALTHY"
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
            dataset_version=getattr(row, "dataset_version", "ds_v2_unknown") or "ds_v2_unknown",
            feature_count=int(getattr(row, "feature_count", 17) or 17),
            drift_status=DriftStatus(raw_drift),
            drift_score=float(getattr(row, "drift_score", 0.0) or 0.0),
            live_settled_trades=int(getattr(row, "live_settled_trades", 0) or 0),
            live_win_rate=float(getattr(row, "live_win_rate", 0.0) or 0.0),
            drift_notes=getattr(row, "drift_notes", "") or "",
            last_drift_checked_at=getattr(row, "last_drift_checked_at", None),
            rolled_back_from_model_id=getattr(row, "rolled_back_from_model_id", None),
            created_at=row.created_at,
            promoted_at=row.promoted_at,
        )


class StrategyModuleSchema(BaseModel):
    strategy_id: str
    name: str
    description: str
    target_regimes: List[str]
    entry_conditions: str
    exit_conditions: str
    risk_parameters: str
    win_rate: float
    profit_factor: float
    max_drawdown: float
    sample_count: int
    expected_value: float
    regime_fit_score: float
    selection_score: float
    selected: bool = False
    default_config: StrategyParameters

    @classmethod
    def from_domain(cls, item: StrategyModuleProfile) -> "StrategyModuleSchema":
        cfg = item.default_config
        return cls(
            strategy_id=item.strategy_id,
            name=item.name,
            description=item.description,
            target_regimes=item.target_regimes,
            entry_conditions=item.entry_conditions,
            exit_conditions=item.exit_conditions,
            risk_parameters=item.risk_parameters,
            win_rate=item.win_rate,
            profit_factor=item.profit_factor,
            max_drawdown=item.max_drawdown,
            sample_count=item.sample_count,
            expected_value=item.expected_value,
            regime_fit_score=item.regime_fit_score,
            selection_score=item.selection_score,
            selected=item.selected,
            default_config=StrategyParameters(
                name=cfg.name,
                instrument=cfg.instrument,
                timeframe=cfg.timeframe,
                trade_amount=cfg.trade_amount,
                expiry_seconds=cfg.expiry_seconds,
                rsi_period=cfg.rsi_period,
                rsi_oversold=cfg.rsi_oversold,
                rsi_overbought=cfg.rsi_overbought,
                ema_fast=cfg.ema_fast,
                ema_slow=cfg.ema_slow,
                sma_fast=cfg.sma_fast,
                sma_slow=cfg.sma_slow,
                macd_fast=cfg.macd_fast,
                macd_slow=cfg.macd_slow,
                macd_signal=cfg.macd_signal,
                bb_period=cfg.bb_period,
                bb_std_dev=cfg.bb_std_dev,
                min_confidence=cfg.min_confidence,
            ),
        )


class StrategySelectionReportSchema(BaseModel):
    selected_strategy_id: str
    selected_strategy_name: str
    auto_selected: bool
    current_regime: MarketRegime
    selection_reason: str
    strategies: List[StrategyModuleSchema]

    @classmethod
    def from_domain(cls, report: StrategySelectionReport) -> "StrategySelectionReportSchema":
        return cls(
            selected_strategy_id=report.selected_strategy_id,
            selected_strategy_name=report.selected_strategy_name,
            auto_selected=report.auto_selected,
            current_regime=report.current_regime,
            selection_reason=report.selection_reason,
            strategies=[StrategyModuleSchema.from_domain(s) for s in report.strategies],
        )


class SelectStrategyRequest(BaseModel):
    strategy_id: Optional[str] = None
    auto_select: bool = True
    instrument: str = Field(default="EUR/USD", min_length=3, max_length=20)
    timeframe: str = Field(default="1m", pattern="^(1m|5m|15m)$")


class PerformanceBreakdownRowSchema(BaseModel):
    label: str
    total_trades: int
    wins: int
    losses: int
    win_rate_pct: float
    net_pnl: float
    profit_factor: float


class ModePerformanceSummarySchema(BaseModel):
    mode: str
    total_trades: int
    wins: int
    losses: int
    win_rate_pct: float
    net_pnl: float
    gross_profit: float
    gross_loss: float
    profit_factor: float
    average_return_pct: float
    max_drawdown: float
    max_consecutive_wins: int
    max_consecutive_losses: int
    expectancy: float


class TradingPerformanceResponse(BaseModel):
    mode_filter: str
    simulation_summary: ModePerformanceSummarySchema
    live_summary: ModePerformanceSummarySchema
    active_summary: ModePerformanceSummarySchema
    by_strategy: List[PerformanceBreakdownRowSchema]
    by_regime: List[PerformanceBreakdownRowSchema]
    by_asset: List[PerformanceBreakdownRowSchema]
    by_timeframe: List[PerformanceBreakdownRowSchema]


class BacktestRunRequest(BaseModel):
    instrument: str = Field(default="EUR/USD", min_length=3, max_length=20)
    timeframe: str = Field(default="1m", pattern="^(1m|5m|15m)$")
    strategy_id: str = Field(default="regime_adaptive", min_length=2, max_length=64)
    stake_amount: float = Field(default=25.0, ge=1.0, le=100_000.0)
    lookback_bars: int = Field(default=180, ge=60, le=600)
    expiry_bars: int = Field(default=1, ge=1, le=5)


class BacktestTradeSampleSchema(BaseModel):
    timestamp: int
    direction: str
    entry_price: float
    exit_price: float
    pnl: float
    status: str
    market_regime: str
    strategy_name: str


class BacktestRunResponse(BaseModel):
    instrument: str
    timeframe: str
    strategy_id: str
    strategy_name: str
    bars_evaluated: int
    total_trades: int
    wins: int
    losses: int
    win_rate_pct: float
    net_pnl: float
    gross_profit: float
    gross_loss: float
    profit_factor: float
    max_drawdown: float
    expectancy: float
    equity_curve: List[float]
    simulated_trades: List[BacktestTradeSampleSchema]


class LiveReadinessGateSchema(BaseModel):
    gate_id: str
    step_number: int
    title: str
    passed: bool
    detail: str


class LiveReadinessResponse(BaseModel):
    prerequisites_ready: bool
    overall_ready: bool
    current_mode: str
    gates: List[LiveReadinessGateSchema]
    blocking_reasons: List[str]
    evaluated_at: datetime


class DiagnosticCheckItemSchema(BaseModel):
    check_id: str
    title: str
    status: str
    detail: str


class SystemDiagnosticsResponse(BaseModel):
    overall_status: str
    connector_name: str
    connector_connected: bool
    checks: List[DiagnosticCheckItemSchema]
    live_readiness: LiveReadinessResponse
    checked_at: datetime


