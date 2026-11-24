from datetime import datetime, timezone
import time
from typing import Dict, List, Optional, Tuple

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.connectors.base import BrokerConnector
from app.connectors.simulation import INSTRUMENT_SPECS
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.risk_engine import RiskManagementEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine
from app.domain import (
    ExecutionMode,
    RiskPolicyConfig,
    StrategyConfig,
    TradeDirection,
    TradeStatus,
)
from app.models import DailySummaryModel, OrderRecordModel, RiskPolicyModel, TradingStateModel
from app.schemas import (
    OrderSubmitRequest,
    OrderSubmitResponse,
    RiskPolicySchema,
)


class ExecutionAuthorizationError(PermissionError):
    """Raised when execution mode is unauthorized (e.g., locked LIVE mode)."""


class ExecutionValidationError(ValueError):
    """Raised when market data, strategy, or risk validation blocks an order."""


class ExecutionConnectorError(RuntimeError):
    """Raised when the underlying broker connector fails during execution."""


class ExecutionModeController:
    """Strict controller enforcing SIMULATION default and locking LIVE trading."""

    @staticmethod
    def authorize_mode(
        requested_mode: ExecutionMode,
        connector: BrokerConnector,
        allow_live_trading: bool,
        explicit_live_confirmation: bool = False,
    ) -> Tuple[bool, str]:
        if requested_mode == ExecutionMode.SIMULATION:
            return True, "SIMULATION mode authorized."

        if requested_mode == ExecutionMode.DEMO:
            if "DEMO_ORDERS" in connector.get_capabilities() or "PAPER_ORDERS" in connector.get_capabilities():
                return True, "DEMO/Paper mode authorized on current connector."
            return False, "Connected broker does not declare DEMO order support."

        if requested_mode == ExecutionMode.LIVE:
            if not allow_live_trading:
                return False, "LIVE trading is disabled by server policy (ALLOW_LIVE_TRADING=false)."
            if not connector.live_authorized:
                return False, f"Connector '{connector.name}' is not authorized for LIVE execution."
            if not explicit_live_confirmation:
                return False, "Explicit human owner confirmation is required for LIVE trading."
            if not connector.connected:
                return False, "Broker connector must be connected before LIVE execution."
            return True, "LIVE mode authorized."

        return False, f"Unsupported execution mode: {requested_mode}"


class TradeExecutionEngine:
    """
    Implements the 11-stage automated trading lifecycle:
    MARKET DATA -> DATA VALIDATION -> MARKET ANALYSIS -> AI SIGNAL ->
    STRATEGY VALIDATION -> RISK VALIDATION -> EXECUTION AUTHORIZATION ->
    ORDER SUBMISSION -> RESULT VERIFICATION -> TRADE LOGGING -> PERFORMANCE EVALUATION.
    """

    def __init__(
        self,
        connector: BrokerConnector,
        allow_live_trading: bool = False,
    ) -> None:
        self._connector = connector
        self._allow_live_trading = allow_live_trading

    @staticmethod
    def today_key() -> str:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d")

    @staticmethod
    def get_or_create_state(db: Session) -> TradingStateModel:
        state = db.get(TradingStateModel, 1)
        if state is None:
            state = TradingStateModel(id=1, execution_mode=ExecutionMode.SIMULATION.value, simulation_balance=10000.0)
            db.add(state)
            db.commit()
            db.refresh(state)
        return state

    @staticmethod
    def get_or_create_policy(db: Session) -> RiskPolicyModel:
        policy = db.get(RiskPolicyModel, 1)
        if policy is None:
            policy = RiskPolicyModel(id=1)
            db.add(policy)
            db.commit()
            db.refresh(policy)
        return policy

    @staticmethod
    def get_or_create_daily_summary(db: Session, date_key: Optional[str] = None) -> DailySummaryModel:
        key = date_key or TradeExecutionEngine.today_key()
        summary = db.get(DailySummaryModel, key)
        if summary is None:
            summary = DailySummaryModel(date_key=key)
            db.add(summary)
            db.commit()
            db.refresh(summary)
        return summary

    @staticmethod
    def policy_to_schema(model: RiskPolicyModel) -> RiskPolicySchema:
        return RiskPolicySchema(
            max_trade_amount=model.max_trade_amount,
            max_daily_loss=model.max_daily_loss,
            max_consecutive_losses=model.max_consecutive_losses,
            max_daily_trades=model.max_daily_trades,
            max_portfolio_exposure=model.max_portfolio_exposure,
            cooldown_minutes=model.cooldown_minutes,
            emergency_stopped=model.emergency_stopped,
            auto_trade_enabled=model.auto_trade_enabled,
        )

    async def execute_order(
        self,
        db: Session,
        request: OrderSubmitRequest,
        strategy: StrategyConfig,
        explicit_live_confirmation: bool = False,
    ) -> OrderSubmitResponse:
        # Duplicate order protection via idempotency_key
        existing = (
            db.query(OrderRecordModel)
            .filter(OrderRecordModel.idempotency_key == request.idempotency_key)
            .first()
        )
        if existing is not None:
            return OrderSubmitResponse(
                order_id=existing.order_id,
                status=existing.status,
                entry_price=existing.entry_price,
                exit_price=existing.exit_price,
                pnl=existing.pnl,
                message="Idempotent replay of existing order.",
            )

        state = self.get_or_create_state(db)
        policy_model = self.get_or_create_policy(db)
        policy = self.policy_to_schema(policy_model)
        date_key = self.today_key()
        summary = self.get_or_create_daily_summary(db, date_key)

        # Stage 7 pre-check: Execution mode authorization
        authorized, auth_reason = ExecutionModeController.authorize_mode(
            requested_mode=request.mode,
            connector=self._connector,
            allow_live_trading=self._allow_live_trading,
            explicit_live_confirmation=explicit_live_confirmation,
        )
        if not authorized:
            raise ExecutionAuthorizationError(auth_reason)

        # Stage 4 pre-check: AI Signal direction must be directional
        if request.direction == TradeDirection.NO_TRADE:
            raise ExecutionValidationError("Cannot submit an order when signal direction is NO_TRADE.")

        # Stage 1 & 2: Market Data + Data Validation
        if not self._connector.connected:
            state.execution_mode = ExecutionMode.SIMULATION.value
            db.commit()
            raise ExecutionConnectorError("Connector disconnected; live execution disabled and analysis preserved.")

        try:
            candles: List[Dict[str, float]] = await self._connector.get_candles(
                instrument=request.instrument,
                timeframe=request.timeframe,
                limit=60,
            )
        except Exception as exc:
            state.execution_mode = ExecutionMode.SIMULATION.value
            db.commit()
            raise ExecutionConnectorError(f"Market data retrieval failed: {exc}") from exc

        valid_data, data_reason = MarketIntelligenceEngine.validate_candles(candles)
        if not valid_data:
            raise ExecutionValidationError(f"Market data validation failed: {data_reason}")

        # Stage 3: Market Analysis
        _snapshot = TechnicalAnalysisEngine.analyze(candles, strategy)

        # Stage 5: Strategy Validation
        if request.expiry_seconds < 60 or request.expiry_seconds > 3600:
            raise ExecutionValidationError("Expiry duration outside strategy bounds (60s - 3600s).")

        # Stage 6: Independent Risk Validation (never overridden by AI)
        open_exposure = float(
            db.query(func.coalesce(func.sum(OrderRecordModel.amount), 0.0))
            .filter(OrderRecordModel.date_key == date_key, OrderRecordModel.status == TradeStatus.OPEN.value)
            .scalar()
            or 0.0
        )

        risk_decision = RiskManagementEngine.validate_order(
            policy=policy.to_domain(),
            total_trades_today=summary.total_trades,
            net_pnl_today=summary.net_pnl,
            consecutive_losses=summary.consecutive_losses,
            open_exposure=open_exposure,
            requested_amount=request.amount,
            direction=request.direction,
            mode=request.mode,
            live_authorized=self._connector.live_authorized and self._allow_live_trading,
            last_loss_epoch_ms=state.last_loss_epoch_ms,
        )
        if not risk_decision.allowed:
            raise ExecutionValidationError(risk_decision.reason)

        # Stage 8 & 9: Order Submission & Result Verification
        try:
            connector_res = await self._connector.submit_order(
                instrument=request.instrument,
                timeframe=request.timeframe,
                direction=request.direction.value,
                amount=request.amount,
                expiry_seconds=request.expiry_seconds,
            )
            verified_res = await self._connector.get_settlement_result(connector_res.order_id)
        except ConnectionError as exc:
            state.execution_mode = ExecutionMode.SIMULATION.value
            db.commit()
            raise ExecutionConnectorError(f"Broker connection failure during submission: {exc}") from exc
        except Exception as exc:
            raise ExecutionConnectorError(f"Order verification failed: {exc}") from exc

        # Stage 10: Trade Logging & Daily Risk Ledger Update
        payout_rate = INSTRUMENT_SPECS.get(request.instrument, INSTRUMENT_SPECS["EUR/USD"])["payout"]
        record = OrderRecordModel(
            order_id=verified_res.order_id,
            idempotency_key=request.idempotency_key,
            instrument=request.instrument,
            timeframe=request.timeframe,
            direction=request.direction.value,
            mode=request.mode.value,
            amount=request.amount,
            payout_rate=payout_rate,
            entry_price=verified_res.entry_price,
            exit_price=verified_res.exit_price,
            status=verified_res.status,
            pnl=verified_res.pnl,
            date_key=date_key,
        )
        db.add(record)

        summary.total_trades += 1
        summary.net_pnl = round(summary.net_pnl + verified_res.pnl, 2)
        if verified_res.status == TradeStatus.WON.value:
            summary.wins += 1
            summary.consecutive_losses = 0
        elif verified_res.status == TradeStatus.LOST.value:
            summary.losses += 1
            summary.consecutive_losses += 1
            state.last_loss_epoch_ms = int(time.time() * 1000)

        if summary.net_pnl < 0:
            summary.peak_drawdown = max(summary.peak_drawdown, round(abs(summary.net_pnl), 2))

        state.simulation_balance = await self._connector.get_balance()
        db.commit()

        return OrderSubmitResponse(
            order_id=verified_res.order_id,
            status=verified_res.status,
            entry_price=verified_res.entry_price,
            exit_price=verified_res.exit_price,
            pnl=verified_res.pnl,
            message=verified_res.message,
        )
