import asyncio
from contextlib import asynccontextmanager
import hmac
import re
from typing import Dict, List

from dataclasses import asdict, replace
from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.connectors.simulation import INSTRUMENT_SPECS, SimulationConnector
from app.database import Base, SessionLocal, engine, get_db
from app.engines.execution_engine import (
    ExecutionAuthorizationError,
    ExecutionConnectorError,
    ExecutionModeController,
    ExecutionValidationError,
    TradeExecutionEngine,
)
from app.engines.learning_engine import LearningEvaluationEngine
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.signal_engine import AISignalEngine
from app.engines.strategy_engine import StrategySelectionEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine
from app.models import OrderRecordModel, RiskPolicyModel, TradingStateModel
from app.schemas import (
    CandleSchema,
    ExecutionMode,
    HealthResponse,
    ModeSwitchRequest,
    OrderSubmitRequest,
    OrderSubmitResponse,
    RiskPolicySchema,
    SignalResponse,
    StrategyParameters,
    TradingStateResponse,
)
from app.security import verify_bearer_token

INSTRUMENT_PATTERN = re.compile(r"^[A-Za-z0-9/_-]{3,20}$")
TIMEFRAME_PATTERN = re.compile(r"^(1m|5m|15m)$")


def seed_initial_records(db: Session) -> None:
    if db.get(TradingStateModel, 1) is None:
        db.add(TradingStateModel(id=1, execution_mode=ExecutionMode.SIMULATION.value, simulation_balance=10000.0))
    if db.get(RiskPolicyModel, 1) is None:
        db.add(RiskPolicyModel(id=1))
    db.commit()


def create_app(custom_settings: Settings | None = None) -> FastAPI:
    settings = custom_settings or get_settings()
    connector = SimulationConnector(initial_balance=10000.0)
    strategy_engine = StrategySelectionEngine()
    execution_engine = TradeExecutionEngine(
        connector=connector,
        allow_live_trading=settings.allow_live_trading,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        Base.metadata.create_all(bind=engine)
        db = SessionLocal()
        try:
            seed_initial_records(db)
        finally:
            db.close()
        yield

    application = FastAPI(
        title=settings.app_name,
        version="1.0.0",
        description="IQ Trading AI Hybrid FastAPI Backend (Phase 1 Foundation - Simulation Mode)",
        lifespan=lifespan,
    )

    application.state.connector = connector
    application.state.strategy_engine = strategy_engine
    application.state.execution_engine = execution_engine

    origins = [origin.strip() for origin in settings.allowed_origins.split(",") if origin.strip() and origin.strip() != "*"]
    if origins:
        application.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "PUT"],
            allow_headers=["Authorization", "Content-Type"],
        )

    @application.get("/api/v1/health", response_model=HealthResponse, tags=["System"])
    async def get_health(db: Session = Depends(get_db)) -> HealthResponse:
        state = TradeExecutionEngine.get_or_create_state(db)
        mode = ExecutionMode(state.execution_mode)
        return HealthResponse(
            status="ok",
            connector=connector.name,
            capabilities=connector.get_capabilities(),
            live_authorized=bool(connector.live_authorized and settings.allow_live_trading),
            execution_mode=mode,
        )

    @application.get(
        "/api/v1/candles",
        response_model=List[CandleSchema],
        dependencies=[Depends(verify_bearer_token)],
        tags=["Market Data"],
    )
    async def get_candles(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern=r"^(1m|5m|15m)$"),
        limit: int = Query(default=80, ge=25, le=200),
    ) -> List[CandleSchema]:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=limit)
        valid, reason = MarketIntelligenceEngine.validate_candles(candles)
        if not valid:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)
        return [CandleSchema(**c) for c in candles]

    @application.get(
        "/api/v1/signals/latest",
        response_model=SignalResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Intelligence"],
    )
    async def get_latest_signal(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern=r"^(1m|5m|15m)$"),
    ) -> SignalResponse:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=80)
        valid, reason = MarketIntelligenceEngine.validate_candles(candles)
        if not valid:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)
        active = replace(
            strategy_engine.get_active_strategy(),
            instrument=instrument,
            timeframe=timeframe,
        )
        snapshot = TechnicalAnalysisEngine.analyze(candles, active)
        result = AISignalEngine.evaluate(candles, snapshot, active)
        return SignalResponse.from_domain(result)

    @application.get(
        "/api/v1/strategy",
        response_model=StrategyParameters,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Strategy"],
    )
    async def get_strategy() -> StrategyParameters:
        return StrategyParameters(**asdict(strategy_engine.get_active_strategy()))

    @application.put(
        "/api/v1/strategy",
        response_model=StrategyParameters,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Strategy"],
    )
    async def update_strategy(params: StrategyParameters) -> StrategyParameters:
        updated = strategy_engine.update_strategy(params.to_domain())
        return StrategyParameters(**asdict(updated))

    @application.get(
        "/api/v1/state",
        response_model=TradingStateResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["System"],
    )
    async def get_trading_state(db: Session = Depends(get_db)) -> TradingStateResponse:
        state = TradeExecutionEngine.get_or_create_state(db)
        policy = TradeExecutionEngine.get_or_create_policy(db)
        summary = TradeExecutionEngine.get_or_create_daily_summary(db)
        balance = await connector.get_balance()
        win_rate = round((summary.wins / summary.total_trades) * 100.0, 2) if summary.total_trades > 0 else 0.0
        return TradingStateResponse(
            execution_mode=ExecutionMode(state.execution_mode),
            connector_name=connector.name,
            connector_connected=connector.connected,
            live_authorized=bool(connector.live_authorized and settings.allow_live_trading),
            balance=balance,
            total_trades_today=summary.total_trades,
            wins_today=summary.wins,
            losses_today=summary.losses,
            net_pnl_today=summary.net_pnl,
            consecutive_losses=summary.consecutive_losses,
            emergency_stopped=policy.emergency_stopped,
            win_rate_pct=win_rate,
        )

    @application.post(
        "/api/v1/orders",
        response_model=OrderSubmitResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def submit_order(
        request: OrderSubmitRequest,
        db: Session = Depends(get_db),
    ) -> OrderSubmitResponse:
        if not INSTRUMENT_PATTERN.match(request.instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        try:
            return await execution_engine.execute_order(
                db=db,
                request=request,
                strategy=strategy_engine.get_active_strategy(),
            )
        except ExecutionAuthorizationError as exc:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc)) from exc
        except ExecutionValidationError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
        except ExecutionConnectorError as exc:
            raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc

    @application.get(
        "/api/v1/orders",
        response_model=List[OrderSubmitResponse],
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def list_orders(
        limit: int = Query(default=50, ge=1, le=200),
        db: Session = Depends(get_db),
    ) -> List[OrderSubmitResponse]:
        rows = (
            db.query(OrderRecordModel)
            .order_by(OrderRecordModel.created_at.desc())
            .limit(limit)
            .all()
        )
        return [
            OrderSubmitResponse(
                order_id=r.order_id,
                status=r.status,
                entry_price=r.entry_price,
                exit_price=r.exit_price,
                pnl=r.pnl,
                message=f"{r.instrument} {r.direction} ({r.mode})",
            )
            for r in rows
        ]

    @application.get(
        "/api/v1/orders/{order_id}",
        response_model=OrderSubmitResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def get_order(order_id: str, db: Session = Depends(get_db)) -> OrderSubmitResponse:
        row = db.get(OrderRecordModel, order_id)
        if row is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Order {order_id} not found.")
        return OrderSubmitResponse(
            order_id=row.order_id,
            status=row.status,
            entry_price=row.entry_price,
            exit_price=row.exit_price,
            pnl=row.pnl,
            message=f"{row.instrument} {row.direction} ({row.mode})",
        )

    @application.get(
        "/api/v1/risk/policy",
        response_model=RiskPolicySchema,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Risk"],
    )
    async def get_risk_policy(db: Session = Depends(get_db)) -> RiskPolicySchema:
        policy = TradeExecutionEngine.get_or_create_policy(db)
        return TradeExecutionEngine.policy_to_schema(policy)

    @application.put(
        "/api/v1/risk/policy",
        response_model=RiskPolicySchema,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Risk"],
    )
    async def update_risk_policy(payload: RiskPolicySchema, db: Session = Depends(get_db)) -> RiskPolicySchema:
        policy = TradeExecutionEngine.get_or_create_policy(db)
        policy.max_trade_amount = payload.max_trade_amount
        policy.max_daily_loss = payload.max_daily_loss
        policy.max_consecutive_losses = payload.max_consecutive_losses
        policy.max_daily_trades = payload.max_daily_trades
        policy.max_portfolio_exposure = payload.max_portfolio_exposure
        policy.cooldown_minutes = payload.cooldown_minutes
        policy.emergency_stopped = payload.emergency_stopped
        policy.auto_trade_enabled = payload.auto_trade_enabled
        db.commit()
        db.refresh(policy)
        return TradeExecutionEngine.policy_to_schema(policy)

    @application.post(
        "/api/v1/risk/emergency-stop",
        response_model=RiskPolicySchema,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Risk"],
    )
    async def trigger_emergency_stop(db: Session = Depends(get_db)) -> RiskPolicySchema:
        policy = TradeExecutionEngine.get_or_create_policy(db)
        policy.emergency_stopped = True
        policy.auto_trade_enabled = False
        db.commit()
        db.refresh(policy)
        return TradeExecutionEngine.policy_to_schema(policy)

    @application.post(
        "/api/v1/risk/reset-emergency-stop",
        response_model=RiskPolicySchema,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Risk"],
    )
    async def reset_emergency_stop(db: Session = Depends(get_db)) -> RiskPolicySchema:
        policy = TradeExecutionEngine.get_or_create_policy(db)
        policy.emergency_stopped = False
        db.commit()
        db.refresh(policy)
        return TradeExecutionEngine.policy_to_schema(policy)

    @application.post(
        "/api/v1/mode",
        response_model=HealthResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def switch_execution_mode(
        payload: ModeSwitchRequest,
        db: Session = Depends(get_db),
    ) -> HealthResponse:
        authorized, reason = ExecutionModeController.authorize_mode(
            requested_mode=payload.mode,
            connector=connector,
            allow_live_trading=settings.allow_live_trading,
            explicit_live_confirmation=payload.explicit_live_confirmation,
        )
        if not authorized:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=reason)

        state = TradeExecutionEngine.get_or_create_state(db)
        state.execution_mode = payload.mode.value
        db.commit()
        return HealthResponse(
            status="ok",
            connector=connector.name,
            capabilities=connector.get_capabilities(),
            live_authorized=bool(connector.live_authorized and settings.allow_live_trading),
            execution_mode=payload.mode,
        )

    @application.get(
        "/api/v1/evaluation/summary",
        dependencies=[Depends(verify_bearer_token)],
        tags=["Evaluation"],
    )
    async def get_evaluation_summary(db: Session = Depends(get_db)) -> Dict[str, object]:
        orders = db.query(OrderRecordModel).all()
        return LearningEvaluationEngine.summarize_orders(orders)

    @application.websocket("/ws/market/{instrument:path}")
    async def market_stream(
        websocket: WebSocket,
        instrument: str,
        timeframe: str = Query(default="1m"),
    ) -> None:
        expected_token = settings.api_bearer_token.strip()
        if expected_token:
            auth_header = websocket.headers.get("authorization", "")
            token = auth_header[7:].strip() if auth_header.lower().startswith("bearer ") else ""
            if not token or not hmac.compare_digest(token.encode("utf-8"), expected_token.encode("utf-8")):
                await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
                return

        if not INSTRUMENT_PATTERN.match(instrument) or not TIMEFRAME_PATTERN.match(timeframe):
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

        await websocket.accept()
        try:
            while True:
                candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=2)
                latest = candles[-1]
                payout = INSTRUMENT_SPECS.get(instrument, INSTRUMENT_SPECS["EUR/USD"])["payout"]
                await websocket.send_json(
                    {
                        "type": "tick",
                        "instrument": instrument,
                        "timeframe": timeframe,
                        "payout_rate": payout,
                        "candle": latest,
                    }
                )
                await asyncio.sleep(1.2)
        except WebSocketDisconnect:
            return

    return application


app = create_app()
