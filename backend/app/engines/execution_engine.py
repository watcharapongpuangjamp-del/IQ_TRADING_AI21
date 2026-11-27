from datetime import datetime, timezone
import time
from typing import Dict, List, Optional, Tuple
import uuid

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.connectors.base import BrokerConnector, ConnectorOrderResult
from app.connectors.simulation import INSTRUMENT_SPECS
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.risk_engine import RiskManagementEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine
from app.domain import (
    ExecutionMode,
    OrderLifecycleState,
    RiskPolicyConfig,
    StrategyConfig,
    TradeDirection,
    TradeStatus,
    UNRESOLVED_LIFECYCLE_STATES,
)
from app.models import (
    DailySummaryModel,
    OrderLifecycleEventModel,
    OrderRecordModel,
    RiskPolicyModel,
    TradingStateModel,
)
from app.schemas import (
    OrderLifecycleEventResponse,
    OrderSubmitRequest,
    OrderSubmitResponse,
    ReconcileSummaryResponse,
    RiskPolicySchema,
)


class ExecutionAuthorizationError(PermissionError):
    """Raised when execution mode is unauthorized (e.g., locked LIVE mode)."""


class ExecutionValidationError(ValueError):
    """Raised when market data, strategy, reconciliation, or risk validation blocks an order."""


class ExecutionConnectorError(RuntimeError):
    """Raised when the underlying broker connector fails before submission."""


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
            capabilities = connector.get_capabilities()
            if "DEMO_ORDERS" not in capabilities and "PAPER_ORDERS" not in capabilities:
                return False, "Connected broker does not declare DEMO order support."
            if not connector.connected:
                return False, "Broker connector must be connected before DEMO execution."
            if "PRACTICE_ACCOUNT_ONLY" in capabilities and not bool(getattr(connector, "session_verified", False)):
                return False, "IQ Option Practice account session is not verified."
            if "PRACTICE_ACCOUNT_ONLY" in capabilities and getattr(connector, "account_mode", None) != "PRACTICE":
                return False, "IQ Option REAL account is locked; only verified PRACTICE is permitted."
            return True, "DEMO/PRACTICE mode authorized."

        if requested_mode == ExecutionMode.LIVE:
            return False, "LIVE/REAL trading is hard-locked by project policy. Only SIMULATION or verified PRACTICE is permitted."

        return False, f"Unsupported execution mode: {requested_mode}"


class TradeExecutionEngine:
    """
    Implements the 11-stage automated trading pipeline and 10-state Order Lifecycle:
    CREATED -> VALIDATED -> AUTHORIZED -> SUBMITTED -> ACCEPTED -> PENDING -> SETTLED
    (plus REJECTED / FAILED / UNKNOWN with strict reconciliation and no blind resubmits).
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

    @staticmethod
    def to_order_response(
        record: OrderRecordModel,
        message_override: Optional[str] = None,
    ) -> OrderSubmitResponse:
        unresolved = record.lifecycle_state in UNRESOLVED_LIFECYCLE_STATES
        default_msg = record.error_detail or f"{record.instrument} {record.direction} ({record.mode})"
        return OrderSubmitResponse(
            order_id=record.order_id,
            status=record.status,
            entry_price=record.entry_price,
            exit_price=record.exit_price,
            pnl=record.pnl,
            message=message_override or default_msg,
            correlation_id=record.correlation_id,
            broker_order_id=record.broker_order_id,
            lifecycle_state=record.lifecycle_state,
            settlement_outcome=record.settlement_outcome,
            reconciled_at=record.reconciled_at,
            requires_reconciliation=unresolved,
        )

    @staticmethod
    def _record_transition(
        db: Session,
        record: OrderRecordModel,
        to_state: OrderLifecycleState,
        detail: str,
        initial: bool = False,
    ) -> None:
        from_state = None if initial else record.lifecycle_state
        record.lifecycle_state = to_state.value
        db.add(
            OrderLifecycleEventModel(
                event_id=f"evt_{uuid.uuid4().hex[:14]}",
                order_id=record.order_id,
                correlation_id=record.correlation_id,
                from_state=from_state,
                to_state=to_state.value,
                detail=detail[:500],
            )
        )

    @staticmethod
    def list_order_events(db: Session, order_id: str) -> List[OrderLifecycleEventResponse]:
        rows = (
            db.query(OrderLifecycleEventModel)
            .filter(OrderLifecycleEventModel.order_id == order_id)
            .order_by(OrderLifecycleEventModel.created_at.asc())
            .all()
        )
        return [
            OrderLifecycleEventResponse(
                event_id=r.event_id,
                order_id=r.order_id,
                correlation_id=r.correlation_id,
                from_state=r.from_state,
                to_state=r.to_state,
                detail=r.detail,
                created_at=r.created_at,
            )
            for r in rows
        ]

    @staticmethod
    def count_unresolved_orders(db: Session) -> int:
        return int(
            db.query(func.count(OrderRecordModel.order_id))
            .filter(OrderRecordModel.lifecycle_state.in_(UNRESOLVED_LIFECYCLE_STATES))
            .scalar()
            or 0
        )

    async def _finalize_settlement_ledger(
        self,
        db: Session,
        record: OrderRecordModel,
        res: ConnectorOrderResult,
        state: TradingStateModel,
        summary: DailySummaryModel,
    ) -> None:
        already_settled = (
            record.reconciled_at is not None
            or record.status in (TradeStatus.WON.value, TradeStatus.LOST.value)
        )
        outcome = res.settlement_outcome or res.status
        record.broker_order_id = res.broker_order_id or res.order_id
        record.entry_price = res.entry_price or record.entry_price
        record.exit_price = res.exit_price
        record.pnl = res.pnl
        record.status = outcome
        record.settlement_outcome = outcome
        record.error_detail = res.message[:500] if res.message else record.error_detail
        record.reconciled_at = datetime.now(timezone.utc)

        if not already_settled and outcome in (TradeStatus.WON.value, TradeStatus.LOST.value):
            summary.total_trades += 1
            summary.net_pnl = round(summary.net_pnl + res.pnl, 2)
            if outcome == TradeStatus.WON.value:
                summary.wins += 1
                summary.consecutive_losses = 0
            else:
                summary.losses += 1
                summary.consecutive_losses += 1
                state.last_loss_epoch_ms = int(time.time() * 1000)

            if summary.net_pnl < 0:
                summary.peak_drawdown = max(summary.peak_drawdown, round(abs(summary.net_pnl), 2))

        state.simulation_balance = await self._connector.get_balance()

    async def reconcile_unresolved_orders(
        self,
        db: Session,
        target_order_id: Optional[str] = None,
    ) -> ReconcileSummaryResponse:
        query = db.query(OrderRecordModel)
        if target_order_id is not None:
            query = query.filter(OrderRecordModel.order_id == target_order_id)
        else:
            query = query.filter(OrderRecordModel.lifecycle_state.in_(UNRESOLVED_LIFECYCLE_STATES))

        targets = query.order_by(OrderRecordModel.created_at.asc()).all()
        if not targets:
            return ReconcileSummaryResponse(
                checked_count=0,
                settled_count=0,
                pending_count=0,
                unknown_count=0,
                failed_count=0,
                orders=[],
                message="No PENDING or UNKNOWN orders require reconciliation.",
            )

        state = self.get_or_create_state(db)
        settled_count = 0
        pending_count = 0
        unknown_count = 0
        failed_count = 0

        for record in targets:
            if record.lifecycle_state == OrderLifecycleState.SETTLED.value:
                settled_count += 1
                continue

            lookup_id = record.broker_order_id or record.order_id
            try:
                res = await self._connector.reconcile_order(lookup_id, correlation_id=record.correlation_id)
            except KeyError as exc:
                record.retry_count += 1
                record.error_detail = f"Not found at broker during reconciliation: {exc}"[:500]
                record.status = TradeStatus.FAILED.value
                record.reconciled_at = datetime.now(timezone.utc)
                self._record_transition(
                    db,
                    record,
                    OrderLifecycleState.FAILED,
                    f"Reconciliation confirmed order absent on broker: {exc}",
                )
                failed_count += 1
                continue
            except Exception as exc:
                record.retry_count += 1
                record.error_detail = f"Reconciliation error: {exc}"[:500]
                record.status = TradeStatus.UNKNOWN.value
                self._record_transition(
                    db,
                    record,
                    OrderLifecycleState.UNKNOWN,
                    f"Reconciliation failed/timed out (attempt {record.retry_count}): {exc}",
                )
                unknown_count += 1
                continue

            if res.lifecycle_state == OrderLifecycleState.SETTLED.value or res.status in (
                TradeStatus.WON.value,
                TradeStatus.LOST.value,
            ):
                summary = self.get_or_create_daily_summary(db, record.date_key)
                await self._finalize_settlement_ledger(db, record, res, state, summary)
                self._record_transition(
                    db,
                    record,
                    OrderLifecycleState.SETTLED,
                    f"Reconciled settlement: {record.settlement_outcome} (PnL={record.pnl:.2f})",
                )
                settled_count += 1
            elif res.lifecycle_state == OrderLifecycleState.REJECTED.value or res.status == TradeStatus.REJECTED.value:
                record.status = TradeStatus.REJECTED.value
                record.error_detail = res.message[:500]
                record.reconciled_at = datetime.now(timezone.utc)
                self._record_transition(db, record, OrderLifecycleState.REJECTED, res.message or "Reconciled REJECTED")
                failed_count += 1
            elif res.lifecycle_state == OrderLifecycleState.FAILED.value or res.status == TradeStatus.FAILED.value:
                record.status = TradeStatus.FAILED.value
                record.error_detail = res.message[:500]
                record.reconciled_at = datetime.now(timezone.utc)
                self._record_transition(db, record, OrderLifecycleState.FAILED, res.message or "Reconciled FAILED")
                failed_count += 1
            elif res.lifecycle_state == OrderLifecycleState.PENDING.value or res.status == TradeStatus.PENDING.value:
                record.broker_order_id = res.broker_order_id or res.order_id
                record.status = TradeStatus.PENDING.value
                self._record_transition(db, record, OrderLifecycleState.PENDING, "Order still PENDING at broker")
                pending_count += 1
            else:
                record.status = TradeStatus.UNKNOWN.value
                self._record_transition(db, record, OrderLifecycleState.UNKNOWN, f"Ambiguous broker state: {res.status}")
                unknown_count += 1

        db.commit()
        for r in targets:
            db.refresh(r)

        return ReconcileSummaryResponse(
            checked_count=len(targets),
            settled_count=settled_count,
            pending_count=pending_count,
            unknown_count=unknown_count,
            failed_count=failed_count,
            orders=[self.to_order_response(r) for r in targets],
            message=(
                f"Reconciled {len(targets)} order(s): "
                f"{settled_count} settled, {pending_count} pending, {unknown_count} unknown, {failed_count} failed."
            ),
        )

    async def execute_order(
        self,
        db: Session,
        request: OrderSubmitRequest,
        strategy: StrategyConfig,
        explicit_live_confirmation: bool = False,
    ) -> OrderSubmitResponse:
        # 1. Duplicate order protection via idempotency_key (NEVER resubmit when UNKNOWN)
        existing = (
            db.query(OrderRecordModel)
            .filter(OrderRecordModel.idempotency_key == request.idempotency_key)
            .first()
        )
        if existing is not None:
            if existing.lifecycle_state == OrderLifecycleState.UNKNOWN.value:
                return self.to_order_response(
                    existing,
                    message_override="Order is in UNKNOWN state — automatic resubmission blocked. Run reconciliation.",
                )
            return self.to_order_response(
                existing,
                message_override="Idempotent replay of existing order.",
            )

        state = self.get_or_create_state(db)
        policy_model = self.get_or_create_policy(db)
        policy = self.policy_to_schema(policy_model)
        date_key = self.today_key()
        summary = self.get_or_create_daily_summary(db, date_key)

        # 2. Reconcile-Before-Trade Gate: settle any prior PENDING/UNKNOWN orders before opening a new trade
        if self.count_unresolved_orders(db) > 0:
            reconcile_summary = await self.reconcile_unresolved_orders(db)
            if reconcile_summary.unknown_count > 0:
                raise ExecutionValidationError(
                    "Risk Gate blocked: unresolved UNKNOWN order(s) failed reconciliation; new trades disabled (fail-closed)."
                )
            if reconcile_summary.pending_count > 0:
                raise ExecutionValidationError(
                    "Risk Gate blocked: existing PENDING order(s) must settle via reconciliation before opening a new trade."
                )

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
            .filter(
                OrderRecordModel.date_key == date_key,
                OrderRecordModel.lifecycle_state.in_(
                    (
                        OrderLifecycleState.CREATED.value,
                        OrderLifecycleState.VALIDATED.value,
                        OrderLifecycleState.AUTHORIZED.value,
                        OrderLifecycleState.SUBMITTED.value,
                        OrderLifecycleState.ACCEPTED.value,
                        OrderLifecycleState.PENDING.value,
                        OrderLifecycleState.UNKNOWN.value,
                    )
                ),
            )
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

        # Persist order intent BEFORE calling broker so mid-flight crashes/timeouts are traceable
        payout_rate = INSTRUMENT_SPECS.get(request.instrument, INSTRUMENT_SPECS["EUR/USD"])["payout"]
        broker_payout_reader = getattr(self._connector, "get_payout_rate", None)
        if callable(broker_payout_reader):
            try:
                payout_rate = await broker_payout_reader(request.instrument, request.expiry_seconds)
            except Exception as exc:
                raise ExecutionValidationError(f"Broker asset/payout validation failed: {exc}") from exc
        order_id = f"ord_{uuid.uuid4().hex[:12]}"
        correlation_id = f"corr_{uuid.uuid4().hex[:12]}"
        entry_estimate = float(candles[-1]["close"])

        record = OrderRecordModel(
            order_id=order_id,
            idempotency_key=request.idempotency_key,
            correlation_id=correlation_id,
            broker_order_id=None,
            instrument=request.instrument,
            timeframe=request.timeframe,
            direction=request.direction.value,
            mode=request.mode.value,
            amount=request.amount,
            payout_rate=payout_rate,
            entry_price=entry_estimate,
            exit_price=0.0,
            status=TradeStatus.OPEN.value,
            lifecycle_state=OrderLifecycleState.CREATED.value,
            strategy_name=strategy.name,
            market_regime=_snapshot.regime.value,
            date_key=date_key,
        )
        db.add(record)
        self._record_transition(
            db,
            record,
            OrderLifecycleState.CREATED,
            f"Order created ({request.instrument} {request.direction.value} ${request.amount:.2f})",
            initial=True,
        )
        self._record_transition(
            db,
            record,
            OrderLifecycleState.VALIDATED,
            f"Data & Risk Gate passed ({risk_decision.reason})",
        )
        self._record_transition(
            db,
            record,
            OrderLifecycleState.AUTHORIZED,
            auth_reason,
        )
        self._record_transition(
            db,
            record,
            OrderLifecycleState.SUBMITTED,
            f"Dispatching to connector '{self._connector.name}'",
        )
        db.commit()

        # Stage 8: Broker Submission (any timeout/connection uncertainty -> UNKNOWN, never blind resubmit)
        try:
            connector_res = await self._connector.submit_order(
                instrument=request.instrument,
                timeframe=request.timeframe,
                direction=request.direction.value,
                amount=request.amount,
                expiry_seconds=request.expiry_seconds,
                correlation_id=correlation_id,
            )
        except (TimeoutError, ConnectionError) as exc:
            record.status = TradeStatus.UNKNOWN.value
            record.error_detail = f"Ambiguous broker transport failure after submission: {exc}"[:500]
            self._record_transition(
                db,
                record,
                OrderLifecycleState.UNKNOWN,
                record.error_detail,
            )
            db.commit()
            db.refresh(record)
            return self.to_order_response(
                record,
                message_override=f"Order status UNKNOWN after transport timeout — reconciliation required ({correlation_id}).",
            )
        except Exception as exc:
            record.status = TradeStatus.FAILED.value
            record.error_detail = f"Broker submission exception: {exc}"[:500]
            self._record_transition(db, record, OrderLifecycleState.FAILED, record.error_detail)
            db.commit()
            raise ExecutionConnectorError(f"Order submission failed: {exc}") from exc

        record.broker_order_id = connector_res.broker_order_id or connector_res.order_id
        record.entry_price = connector_res.entry_price or entry_estimate

        if connector_res.lifecycle_state == OrderLifecycleState.REJECTED.value or connector_res.status == TradeStatus.REJECTED.value:
            record.status = TradeStatus.REJECTED.value
            record.error_detail = connector_res.message[:500]
            record.reconciled_at = datetime.now(timezone.utc)
            self._record_transition(db, record, OrderLifecycleState.REJECTED, connector_res.message or "Broker rejected order")
            db.commit()
            db.refresh(record)
            return self.to_order_response(record)

        if connector_res.lifecycle_state == OrderLifecycleState.FAILED.value or connector_res.status == TradeStatus.FAILED.value:
            record.status = TradeStatus.FAILED.value
            record.error_detail = connector_res.message[:500]
            record.reconciled_at = datetime.now(timezone.utc)
            self._record_transition(db, record, OrderLifecycleState.FAILED, connector_res.message or "Broker execution failed")
            db.commit()
            db.refresh(record)
            return self.to_order_response(record)

        self._record_transition(
            db,
            record,
            OrderLifecycleState.ACCEPTED,
            f"Broker acknowledged order {record.broker_order_id}",
        )

        # Stage 9: Result Verification / Pending Handling
        if connector_res.lifecycle_state == OrderLifecycleState.PENDING.value or connector_res.status == TradeStatus.PENDING.value:
            record.status = TradeStatus.PENDING.value
            record.error_detail = connector_res.message[:500]
            self._record_transition(db, record, OrderLifecycleState.PENDING, connector_res.message or "Awaiting settlement")
            db.commit()
            db.refresh(record)
            return self.to_order_response(record)

        try:
            verified_res = await self._connector.get_settlement_result(connector_res.order_id)
        except Exception as exc:
            record.status = TradeStatus.UNKNOWN.value
            record.error_detail = f"Settlement verification error: {exc}"[:500]
            self._record_transition(db, record, OrderLifecycleState.UNKNOWN, record.error_detail)
            db.commit()
            db.refresh(record)
            return self.to_order_response(record)

        if verified_res.lifecycle_state == OrderLifecycleState.PENDING.value or verified_res.status == TradeStatus.PENDING.value:
            record.status = TradeStatus.PENDING.value
            self._record_transition(db, record, OrderLifecycleState.PENDING, "Settlement still pending")
            db.commit()
            db.refresh(record)
            return self.to_order_response(record)

        # Stage 10: Settled Trade Logging & Daily Risk Ledger Update
        await self._finalize_settlement_ledger(db, record, verified_res, state, summary)
        self._record_transition(
            db,
            record,
            OrderLifecycleState.SETTLED,
            verified_res.message or f"Settled as {record.settlement_outcome}",
        )
        db.commit()
        db.refresh(record)
        return self.to_order_response(record)

