import asyncio
from contextlib import asynccontextmanager
import hmac
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, List

from dataclasses import asdict, replace
from pathlib import Path
from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.connectors.http_broker import HttpBrokerConnector
from app.connectors.iqoption_broker import IQOptionBrokerConnector
from app.connectors.simulation import INSTRUMENT_SPECS, SimulationConnector
from app.database import SessionLocal, engine, ensure_schema_migrations, get_db
from app.engines.backtest_engine import WalkForwardBacktestEngine
from app.engines.diagnostics_engine import LiveReadinessEvaluator, SystemDiagnosticsEngine
from app.engines.iqair_client import IQAirClient
from app.engines.execution_engine import (
    ExecutionAuthorizationError,
    ExecutionConnectorError,
    ExecutionModeController,
    ExecutionValidationError,
    TradeExecutionEngine,
)
from app.engines.learning_engine import LearningEvaluationEngine
from app.engines.latency_intelligence import LatencySample, summarize_latency
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.multi_timeframe import MultiTimeframeEngine
from app.engines.replay_engine import SimulatorReplayEngine
from app.engines.signal_engine import AISignalEngine
from app.engines.strategy_engine import StrategySelectionEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine
from app.ml.inference import MLSignalInferenceService
from app.ml.registry import ModelRegistryService
from app.ml.trainer import ModelTrainer, ValidationGatePolicy
from app.models import LatencySampleModel, ModelRegistryModel, OrderRecordModel, RiskPolicyModel, TradingStateModel
from app.repositories.historical_candles import HistoricalCandleRepository
from app.schemas import (
    BacktestRunRequest,
    BacktestRunResponse,
    BrokerDryRunRequest,
    BrokerDryRunResponse,
    CandleHistoryIngestRequest,
    CandleHistoryIngestResponse,
    CandleSchema,
    DataQualityReportSchema,
    ExecutionMode,
    HealthResponse,
    IQAirResponse,
    LiveReadinessResponse,
    MarketIntelligenceResponse,
    ModeSwitchRequest,
    ModelDriftCheckResponse,
    ModelRegistryItemResponse,
    MultiTimeframeReportSchema,
    OrderLifecycleEventResponse,
    OrderSubmitRequest,
    OrderSubmitResponse,
    PromoteModelRequest,
    ReconcileSummaryResponse,
    ReplayEvaluationResponse,
    ReplayStartRequest,
    ReplayStateResponse,
    ReplayStepRequest,
    RiskPolicySchema,
    RollbackModelRequest,
    SelectStrategyRequest,
    SignalResponse,
    StrategyParameters,
    StrategySelectionReportSchema,
    SystemDiagnosticsResponse,
    TradingPerformanceResponse,
    TradingStateResponse,
    TrainModelRequest,
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
    adapter_type = settings.broker_adapter.strip().lower()
    if adapter_type == "http":
        connector = HttpBrokerConnector(
            base_url=settings.broker_base_url,
            api_token=settings.broker_api_token,
            timeout_seconds=settings.broker_timeout_seconds,
            allow_live=settings.allow_live_trading,
        )
    elif adapter_type == "iqoption":
        connector = IQOptionBrokerConnector(
            email=settings.iqoption_email,
            password=settings.iqoption_password,
            balance_mode=settings.iqoption_balance_mode,
            initial_balance=10000.0,
        )
    else:
        connector = SimulationConnector(initial_balance=10000.0)
    strategy_engine = StrategySelectionEngine()
    replay_engine = SimulatorReplayEngine()
    artifacts_dir = Path(settings.model_artifacts_dir)
    ml_inference = MLSignalInferenceService(artifacts_dir=artifacts_dir)
    execution_engine = TradeExecutionEngine(
        connector=connector,
        allow_live_trading=settings.allow_live_trading,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        ensure_schema_migrations(engine)
        if settings.broker_adapter.strip().lower() in {"http", "iqoption"}:
            await connector.connect()
        db = SessionLocal()
        try:
            seed_initial_records(db)
        finally:
            db.close()
        yield
        if settings.broker_adapter.strip().lower() in {"http", "iqoption"}:
            await connector.disconnect()

    application = FastAPI(
        title=settings.app_name,
        version="9.0.0",
        description="IQ Trading AI Hybrid FastAPI Backend (Phase 9 Broker Contract & Hardening)",
        lifespan=lifespan,
    )

    application.state.settings = settings
    application.state.connector = connector
    application.state.strategy_engine = strategy_engine
    application.state.replay_engine = replay_engine
    application.state.ml_inference = ml_inference
    application.state.execution_engine = execution_engine
    if custom_settings is not None:
        application.dependency_overrides[get_settings] = lambda: custom_settings

    origins = [origin.strip() for origin in settings.allowed_origins.split(",") if origin.strip() and origin.strip() != "*"]
    if origins:
        application.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "PUT"],
            allow_headers=["Authorization", "Content-Type"],
        )

    @application.middleware("http")
    async def capture_backend_processing_time(request, call_next):
        started = time.perf_counter()
        succeeded = True
        try:
            response = await call_next(request)
            succeeded = response.status_code < 500
            return response
        except Exception:
            succeeded = False
            raise
        finally:
            path = request.url.path
            if path not in {"/api/v1/latency/summary", "/api/v1/latency/history", "/api/v1/connection/status", "/api/v1/health"} and path.startswith("/api/"):
                elapsed_ms = (time.perf_counter() - started) * 1000.0
                db_session, db_generator = _open_ws_db()
                try:
                    state = db_session.get(TradingStateModel, 1)
                    db_session.add(LatencySampleModel(
                        sample_id=uuid.uuid4().hex,
                        recorded_at=datetime.now(timezone.utc),
                        metric_name="backend_processing",
                        duration_ms=elapsed_ms,
                        success=succeeded,
                        timed_out=False,
                        source="backend_middleware",
                        connector=connector.name,
                        account_mode=(state.execution_mode if state else "UNKNOWN"),
                        connection_generation=0,
                    ))
                    db_session.commit()
                except Exception:
                    db_session.rollback()
                finally:
                    db_generator.close()

    @application.get(
        "/api/v1/connection/status",
        dependencies=[Depends(verify_bearer_token)],
        tags=["Connection Intelligence"],
    )
    async def get_connection_status(db: Session = Depends(get_db)) -> Dict[str, object]:
        state = TradeExecutionEngine.get_or_create_state(db)
        connected_flag = bool(connector.connected)
        verified = bool(getattr(connector, "session_verified", connected_flag))
        broker_mode = getattr(connector, "account_mode", None)
        if isinstance(connector, IQOptionBrokerConnector):
            broker_mode = broker_mode or "UNVERIFIED"
            verified = bool(verified and broker_mode == "PRACTICE")
        return {
            "connector": connector.name,
            "execution_mode": state.execution_mode,
            "account_mode": broker_mode or ("SIMULATION" if isinstance(connector, SimulationConnector) else "UNKNOWN"),
            "connector_connected": connected_flag,
            "session_verified": verified,
            "connection_state": "CONNECTED" if connected_flag and verified else ("DISCONNECTED" if not connected_flag else "UNVERIFIED"),
            "new_orders_allowed_by_connection_monitor": bool(connected_flag and verified),
            "real_account_locked": True,
            "live_authorized": False,
            "measurement_source": "broker_session_and_account_profile",
            "note": "IQ Option Practice account is verified; REAL account selection and execution are locked." if isinstance(connector, IQOptionBrokerConnector) and verified else "Broker connection/account mode is not verified.",
        }

    @application.get(
        "/api/v1/latency/summary",
        dependencies=[Depends(verify_bearer_token)],
        tags=["Connection Intelligence"],
    )
    async def get_latency_summary(
        window: str = Query(default="5m", pattern="^(5m|1h|24h)$"),
        db: Session = Depends(get_db),
    ) -> Dict[str, object]:
        window_minutes = {"5m": 5, "1h": 60, "24h": 1440}[window]
        cutoff = datetime.now(timezone.utc) - timedelta(minutes=window_minutes)
        rows = (
            db.query(LatencySampleModel)
            .filter(LatencySampleModel.recorded_at >= cutoff)
            .order_by(LatencySampleModel.recorded_at.asc())
            .limit(5000)
            .all()
        )
        metrics = {}
        for metric in ("backend_processing", "app_backend_rtt", "broker_rtt", "order_ack"):
            samples = [
                LatencySample(metric=row.metric_name, duration_ms=row.duration_ms,
                              success=row.success, source=row.source)
                for row in rows if row.metric_name == metric
            ]
            summary = summarize_latency(samples, metric)
            metrics[metric] = {
                "sample_count": summary.sample_count,
                "success_count": summary.success_count,
                "failure_count": summary.failure_count,
                "min_ms": summary.min_ms,
                "p50_ms": summary.p50_ms,
                "p95_ms": summary.p95_ms,
                "max_ms": summary.max_ms,
                "jitter_ms": summary.jitter_ms,
                "timeout_rate": summary.timeout_rate,
                "available": summary.success_count > 0,
            }
        return {
            "window": window,
            "window_minutes": window_minutes,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "metrics": metrics,
            "broker_rtt_status": "NOT_MEASURED",
            "order_ack_status": "NOT_MEASURED",
            "note": "Only backend processing time is currently sampled automatically. Missing metrics are not estimated.",
        }

    @application.get(
        "/api/v1/latency/history",
        dependencies=[Depends(verify_bearer_token)],
        tags=["Connection Intelligence"],
    )
    async def get_latency_history(
        metric: str = Query(default="backend_processing", pattern="^(backend_processing|app_backend_rtt|broker_rtt|order_ack)$"),
        limit: int = Query(default=100, ge=1, le=1000),
        db: Session = Depends(get_db),
    ) -> List[Dict[str, object]]:
        rows = (
            db.query(LatencySampleModel)
            .filter(LatencySampleModel.metric_name == metric)
            .order_by(LatencySampleModel.recorded_at.desc())
            .limit(limit)
            .all()
        )
        return [{
            "recorded_at": row.recorded_at.isoformat(),
            "metric": row.metric_name,
            "duration_ms": row.duration_ms,
            "success": row.success,
            "source": row.source,
            "connector": row.connector,
            "account_mode": row.account_mode,
        } for row in rows]

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
        limit: int = Query(default=80, ge=25, le=500),
        db: Session = Depends(get_db),
    ) -> List[CandleSchema]:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=limit)
        valid, reason = MarketIntelligenceEngine.validate_candles(candles)
        if not valid:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)
        HistoricalCandleRepository.upsert_candles(db, instrument, timeframe, candles, source=connector.name)
        return [CandleSchema(**c) for c in candles]

    @application.get(
        "/api/v1/market/iqair",
        response_model=IQAirResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Market Data"],
    )
    async def get_iqair_data(
        city: str = Query(default="London", min_length=2, max_length=50),
        state: str = Query(default="London", min_length=2, max_length=50),
        country: str = Query(default="United Kingdom", min_length=2, max_length=50),
    ) -> IQAirResponse:
        client = IQAirClient(api_key=settings.iqair_api_key if hasattr(settings, "iqair_api_key") else None)
        data = await client.get_city_air_quality(city=city, state=state, country=country)
        return IQAirResponse(**data)

    @application.get(
        "/api/v1/candles/history",
        response_model=List[CandleSchema],
        dependencies=[Depends(verify_bearer_token)],
        tags=["Market Data"],
    )
    async def get_candle_history(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern=r"^(1m|5m|15m)$"),
        limit: int = Query(default=120, ge=25, le=600),
        db: Session = Depends(get_db),
    ) -> List[CandleSchema]:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        stored = HistoricalCandleRepository.get_candles(db, instrument, timeframe, limit=limit)
        if len(stored) < limit:
            seeded = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=limit)
            valid, reason = MarketIntelligenceEngine.validate_candles(seeded)
            if not valid:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)
            HistoricalCandleRepository.upsert_candles(db, instrument, timeframe, seeded, source=connector.name)
            stored = HistoricalCandleRepository.get_candles(db, instrument, timeframe, limit=limit)
        return [CandleSchema(**c) for c in stored]

    @application.post(
        "/api/v1/candles/history/ingest",
        response_model=CandleHistoryIngestResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Market Data"],
    )
    async def ingest_candle_history(
        payload: CandleHistoryIngestRequest,
        db: Session = Depends(get_db),
    ) -> CandleHistoryIngestResponse:
        if not INSTRUMENT_PATTERN.match(payload.instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        raw = [c.model_dump() for c in payload.candles]
        tf_sec = {"1m": 60, "5m": 300, "15m": 900}[payload.timeframe]
        quality = MarketIntelligenceEngine.inspect_candles(raw, expected_timeframe_sec=tf_sec)
        if not quality.valid:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=quality.reason)
        inserted = HistoricalCandleRepository.upsert_candles(
            db=db,
            instrument=payload.instrument,
            timeframe=payload.timeframe,
            candles=raw,
            source=payload.source,
        )
        total = HistoricalCandleRepository.count_candles(db, payload.instrument, payload.timeframe)
        return CandleHistoryIngestResponse(
            instrument=payload.instrument,
            timeframe=payload.timeframe,
            received_count=len(raw),
            inserted_count=inserted,
            total_stored=total,
        )

    @application.get(
        "/api/v1/signals/latest",
        response_model=SignalResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Intelligence"],
    )
    async def get_latest_signal(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern=r"^(1m|5m|15m)$"),
        db: Session = Depends(get_db),
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
        result = ml_inference.infer_signal(
            db=db,
            instrument=instrument,
            timeframe=timeframe,
            candles_1m=candles,
            snapshot_1m=snapshot,
            mtf_report=None,
            strategy=active,
        )
        return SignalResponse.from_domain(result)

    @application.get(
        "/api/v1/market/intelligence",
        response_model=MarketIntelligenceResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Intelligence"],
    )
    async def get_market_intelligence(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        db: Session = Depends(get_db),
    ) -> MarketIntelligenceResponse:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")

        candles_1m = await connector.get_candles(instrument=instrument, timeframe="1m", limit=120)
        candles_5m = await connector.get_candles(instrument=instrument, timeframe="5m", limit=80)
        candles_15m = await connector.get_candles(instrument=instrument, timeframe="15m", limit=80)

        quality = MarketIntelligenceEngine.inspect_candles(candles_1m, expected_timeframe_sec=60)
        if not quality.valid:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=quality.reason)

        HistoricalCandleRepository.upsert_candles(db, instrument, "1m", candles_1m, source=connector.name)
        HistoricalCandleRepository.upsert_candles(db, instrument, "5m", candles_5m, source=connector.name)
        HistoricalCandleRepository.upsert_candles(db, instrument, "15m", candles_15m, source=connector.name)

        active = replace(strategy_engine.get_active_strategy(), instrument=instrument, timeframe="1m")
        snap_1m = TechnicalAnalysisEngine.analyze(candles_1m, active)
        mtf = MultiTimeframeEngine.evaluate(
            instrument=instrument,
            candles_by_tf={"1m": candles_1m, "5m": candles_5m, "15m": candles_15m},
            strategy=active,
        )
        signal = ml_inference.infer_signal(
            db=db,
            instrument=instrument,
            timeframe="1m",
            candles_1m=candles_1m,
            snapshot_1m=snap_1m,
            mtf_report=mtf,
            strategy=active,
        )

        return MarketIntelligenceResponse(
            instrument=instrument,
            data_quality=DataQualityReportSchema.from_domain(quality),
            mtf_report=MultiTimeframeReportSchema.from_domain(mtf),
            signal=SignalResponse.from_domain(signal),
        )

    @application.post(
        "/api/v1/ml/train",
        response_model=ModelRegistryItemResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Machine Learning"],
    )
    async def train_ml_model(
        payload: TrainModelRequest,
        db: Session = Depends(get_db),
    ) -> ModelRegistryItemResponse:
        if not INSTRUMENT_PATTERN.match(payload.instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")

        candles_1m = HistoricalCandleRepository.get_candles(
            db, payload.instrument, payload.timeframe, limit=payload.total_bars
        )
        if len(candles_1m) < payload.total_bars:
            seeded = await connector.get_candles(
                instrument=payload.instrument, timeframe=payload.timeframe, limit=payload.total_bars
            )
            HistoricalCandleRepository.upsert_candles(db, payload.instrument, payload.timeframe, seeded, source=connector.name)
            candles_1m = HistoricalCandleRepository.get_candles(
                db, payload.instrument, payload.timeframe, limit=payload.total_bars
            )

        active = replace(strategy_engine.get_active_strategy(), instrument=payload.instrument, timeframe=payload.timeframe)
        gate_policy = ValidationGatePolicy(
            max_brier_score=payload.max_brier_score,
            max_ece=payload.max_ece,
            min_accuracy=payload.min_accuracy,
        )
        try:
            outcome = ModelTrainer.train_and_validate(
                instrument=payload.instrument,
                timeframe=payload.timeframe,
                candles_1m=candles_1m,
                strategy=active,
                artifacts_dir=artifacts_dir,
                algorithm=payload.algorithm,
                payout_rate=payload.payout_rate,
                execution_delay_bars=payload.execution_delay_bars,
                gate_policy=gate_policy,
            )
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

        row = ModelRegistryService.record_training_outcome(db, outcome)
        return ModelRegistryItemResponse.from_orm_model(row)

    @application.get(
        "/api/v1/ml/models",
        response_model=List[ModelRegistryItemResponse],
        dependencies=[Depends(verify_bearer_token)],
        tags=["Machine Learning"],
    )
    async def list_ml_models(
        instrument: str | None = Query(default=None, min_length=3, max_length=20),
        limit: int = Query(default=50, ge=1, le=200),
        db: Session = Depends(get_db),
    ) -> List[ModelRegistryItemResponse]:
        if instrument is not None and not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        rows = ModelRegistryService.list_models(db, instrument=instrument, limit=limit)
        return [ModelRegistryItemResponse.from_orm_model(r) for r in rows]

    @application.get(
        "/api/v1/ml/active",
        response_model=ModelRegistryItemResponse | None,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Machine Learning"],
    )
    async def get_active_ml_model(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern=r"^(1m|5m|15m)$"),
        db: Session = Depends(get_db),
    ) -> ModelRegistryItemResponse | None:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        row = ModelRegistryService.get_active_model(db, instrument=instrument, timeframe=timeframe)
        return ModelRegistryItemResponse.from_orm_model(row) if row is not None else None

    @application.post(
        "/api/v1/ml/models/{model_id}/promote",
        response_model=ModelRegistryItemResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Machine Learning"],
    )
    async def promote_ml_model(
        model_id: str,
        payload: PromoteModelRequest,
        db: Session = Depends(get_db),
    ) -> ModelRegistryItemResponse:
        try:
            row = ModelRegistryService.promote_model(
                db=db,
                model_id=model_id,
                artifacts_dir=artifacts_dir,
                owner_confirmed=payload.owner_confirmed,
            )
            ml_inference.clear_cache()
            return ModelRegistryItemResponse.from_orm_model(row)
        except PermissionError as exc:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc)) from exc
        except (KeyError, FileNotFoundError) as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    @application.post(
        "/api/v1/ml/models/{model_id}/drift-check",
        response_model=ModelDriftCheckResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Machine Learning"],
    )
    async def check_ml_model_drift(
        model_id: str,
        db: Session = Depends(get_db),
    ) -> ModelDriftCheckResponse:
        target = db.get(ModelRegistryModel, model_id)
        if target is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Model '{model_id}' not found.")

        recent_candles = await connector.get_candles(
            instrument=target.instrument,
            timeframe=target.timeframe,
            limit=120,
        )
        orders = (
            db.query(OrderRecordModel)
            .filter(OrderRecordModel.instrument == target.instrument)
            .all()
        )
        active = replace(
            strategy_engine.get_active_strategy(),
            instrument=target.instrument,
            timeframe=target.timeframe,
        )
        try:
            updated_row, report = ModelRegistryService.evaluate_model_drift(
                db=db,
                model_id=model_id,
                recent_candles=recent_candles,
                orders=orders,
                strategy=active,
            )
        except KeyError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

        return ModelDriftCheckResponse(
            model_id=report.model_id,
            drift_status=report.drift_status,
            drift_score=report.drift_score,
            feature_drift_score=report.feature_drift_score,
            drifted_features=report.drifted_features,
            live_settled_trades=report.live_settled_trades,
            live_win_rate=report.live_win_rate,
            baseline_win_rate=report.baseline_win_rate,
            win_rate_drop=report.win_rate_drop,
            drift_notes=report.drift_notes,
            recommend_rollback=report.recommend_rollback,
            checked_at=updated_row.last_drift_checked_at or updated_row.created_at,
        )

    @application.post(
        "/api/v1/ml/models/{model_id}/rollback",
        response_model=ModelRegistryItemResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Machine Learning"],
    )
    async def rollback_ml_model(
        model_id: str,
        payload: RollbackModelRequest,
        db: Session = Depends(get_db),
    ) -> ModelRegistryItemResponse:
        try:
            row = ModelRegistryService.rollback_model(
                db=db,
                target_model_id=model_id,
                artifacts_dir=artifacts_dir,
                owner_confirmed=payload.owner_confirmed,
                reason=payload.reason,
            )
            ml_inference.clear_cache()
            return ModelRegistryItemResponse.from_orm_model(row)
        except PermissionError as exc:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc)) from exc
        except (KeyError, FileNotFoundError) as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    @application.post(
        "/api/v1/replay/start",
        response_model=ReplayEvaluationResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Replay"],
    )
    async def start_replay(
        payload: ReplayStartRequest,
        db: Session = Depends(get_db),
    ) -> ReplayEvaluationResponse:
        if not INSTRUMENT_PATTERN.match(payload.instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        if payload.initial_window > payload.total_bars:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="initial_window cannot exceed total_bars.",
            )

        stored_1m = HistoricalCandleRepository.get_candles(db, payload.instrument, "1m", limit=payload.total_bars)
        if len(stored_1m) < payload.total_bars:
            seeded = await connector.get_candles(instrument=payload.instrument, timeframe="1m", limit=payload.total_bars)
            HistoricalCandleRepository.upsert_candles(db, payload.instrument, "1m", seeded, source=connector.name)
            stored_1m = HistoricalCandleRepository.get_candles(db, payload.instrument, "1m", limit=payload.total_bars)

        try:
            replay_engine.start(
                instrument=payload.instrument,
                candles_1m=stored_1m,
                initial_window=payload.initial_window,
            )
            active = replace(strategy_engine.get_active_strategy(), instrument=payload.instrument, timeframe="1m")
            eval_res = replay_engine.evaluate_current(active)
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

        return ReplayEvaluationResponse(
            replay_state=ReplayStateResponse.from_domain(eval_res.state),
            data_quality=DataQualityReportSchema.from_domain(eval_res.data_quality),
            mtf_report=MultiTimeframeReportSchema.from_domain(eval_res.mtf_report),
            signal=SignalResponse.from_domain(eval_res.signal),
        )

    @application.post(
        "/api/v1/replay/step",
        response_model=ReplayEvaluationResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Replay"],
    )
    async def step_replay(payload: ReplayStepRequest) -> ReplayEvaluationResponse:
        try:
            replay_engine.step(payload.steps)
            state = replay_engine.get_state()
            active = replace(strategy_engine.get_active_strategy(), instrument=state.instrument, timeframe="1m")
            eval_res = replay_engine.evaluate_current(active)
        except (RuntimeError, ValueError) as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

        return ReplayEvaluationResponse(
            replay_state=ReplayStateResponse.from_domain(eval_res.state),
            data_quality=DataQualityReportSchema.from_domain(eval_res.data_quality),
            mtf_report=MultiTimeframeReportSchema.from_domain(eval_res.mtf_report),
            signal=SignalResponse.from_domain(eval_res.signal),
        )

    @application.post(
        "/api/v1/replay/reset",
        response_model=ReplayEvaluationResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Replay"],
    )
    async def reset_replay() -> ReplayEvaluationResponse:
        try:
            replay_engine.reset()
            state = replay_engine.get_state()
            active = replace(strategy_engine.get_active_strategy(), instrument=state.instrument, timeframe="1m")
            eval_res = replay_engine.evaluate_current(active)
        except RuntimeError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

        return ReplayEvaluationResponse(
            replay_state=ReplayStateResponse.from_domain(eval_res.state),
            data_quality=DataQualityReportSchema.from_domain(eval_res.data_quality),
            mtf_report=MultiTimeframeReportSchema.from_domain(eval_res.mtf_report),
            signal=SignalResponse.from_domain(eval_res.signal),
        )

    @application.get(
        "/api/v1/replay/state",
        response_model=ReplayStateResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Replay"],
    )
    async def get_replay_state() -> ReplayStateResponse:
        return ReplayStateResponse.from_domain(replay_engine.get_state())

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
        "/api/v1/strategies",
        response_model=StrategySelectionReportSchema,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Strategy"],
    )
    async def list_strategy_modules(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern=r"^(1m|5m|15m)$"),
        db: Session = Depends(get_db),
    ) -> StrategySelectionReportSchema:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        candles = HistoricalCandleRepository.get_candles(db, instrument, timeframe, limit=120)
        if len(candles) < 60:
            candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=120)
        active = replace(strategy_engine.get_active_strategy(), instrument=instrument, timeframe=timeframe)
        snap = TechnicalAnalysisEngine.analyze(candles, active)
        orders = db.query(OrderRecordModel).order_by(OrderRecordModel.created_at.desc()).limit(100).all()
        report = strategy_engine.build_report(candles=candles, current_regime=snap.regime, orders=orders)
        return StrategySelectionReportSchema.from_domain(report)

    @application.post(
        "/api/v1/strategies/select",
        response_model=StrategySelectionReportSchema,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Strategy"],
    )
    async def select_strategy_module(
        payload: SelectStrategyRequest,
        db: Session = Depends(get_db),
    ) -> StrategySelectionReportSchema:
        if not INSTRUMENT_PATTERN.match(payload.instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        candles = HistoricalCandleRepository.get_candles(db, payload.instrument, payload.timeframe, limit=120)
        if len(candles) < 60:
            candles = await connector.get_candles(instrument=payload.instrument, timeframe=payload.timeframe, limit=120)
        active = replace(strategy_engine.get_active_strategy(), instrument=payload.instrument, timeframe=payload.timeframe)
        snap = TechnicalAnalysisEngine.analyze(candles, active)
        orders = db.query(OrderRecordModel).order_by(OrderRecordModel.created_at.desc()).limit(100).all()
        try:
            report = strategy_engine.select_strategy(
                candles=candles,
                current_regime=snap.regime,
                strategy_id=payload.strategy_id,
                auto_select=payload.auto_select,
                orders=orders,
            )
        except KeyError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
        return StrategySelectionReportSchema.from_domain(report)

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
            unresolved_orders_count=TradeExecutionEngine.count_unresolved_orders(db),
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
        return [TradeExecutionEngine.to_order_response(r) for r in rows]

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
        return TradeExecutionEngine.to_order_response(row)

    @application.get(
        "/api/v1/orders/{order_id}/events",
        response_model=List[OrderLifecycleEventResponse],
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def get_order_lifecycle_events(
        order_id: str,
        db: Session = Depends(get_db),
    ) -> List[OrderLifecycleEventResponse]:
        row = db.get(OrderRecordModel, order_id)
        if row is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Order {order_id} not found.")
        return TradeExecutionEngine.list_order_events(db, order_id)

    @application.post(
        "/api/v1/trading/reconcile",
        response_model=ReconcileSummaryResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def reconcile_all_unresolved_orders(
        db: Session = Depends(get_db),
    ) -> ReconcileSummaryResponse:
        return await execution_engine.reconcile_unresolved_orders(db)

    @application.post(
        "/api/v1/orders/{order_id}/reconcile",
        response_model=ReconcileSummaryResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Execution"],
    )
    async def reconcile_single_order(
        order_id: str,
        db: Session = Depends(get_db),
    ) -> ReconcileSummaryResponse:
        row = db.get(OrderRecordModel, order_id)
        if row is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Order {order_id} not found.")
        return await execution_engine.reconcile_unresolved_orders(db, target_order_id=order_id)

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
        if payload.mode == ExecutionMode.LIVE:
            readiness = LiveReadinessEvaluator.evaluate(
                db=db,
                connector=connector,
                settings=settings,
                data_quality_ok=True,
                owner_confirmed=payload.explicit_live_confirmation,
            )
            if not readiness["overall_ready"]:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="LIVE Gate blocked: " + "; ".join(readiness["blocking_reasons"]),
                )

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

    @application.get(
        "/api/v1/trading/performance",
        response_model=TradingPerformanceResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Analytics"],
    )
    async def get_trading_performance(
        mode: str = Query(default="SIMULATION", pattern="^(SIMULATION|LIVE|DEMO|ALL)$"),
        db: Session = Depends(get_db),
    ) -> TradingPerformanceResponse:
        orders = db.query(OrderRecordModel).order_by(OrderRecordModel.created_at.asc()).all()
        report = LearningEvaluationEngine.build_performance_report(orders, mode_filter=mode)
        return TradingPerformanceResponse(**report)

    @application.post(
        "/api/v1/backtest/run",
        response_model=BacktestRunResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Analytics"],
    )
    async def run_walk_forward_backtest(
        payload: BacktestRunRequest,
        db: Session = Depends(get_db),
    ) -> BacktestRunResponse:
        if not INSTRUMENT_PATTERN.match(payload.instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        candles = HistoricalCandleRepository.get_candles(
            db, payload.instrument, payload.timeframe, limit=payload.lookback_bars
        )
        if len(candles) < payload.lookback_bars:
            candles = await connector.get_candles(
                instrument=payload.instrument,
                timeframe=payload.timeframe,
                limit=payload.lookback_bars,
            )
            HistoricalCandleRepository.upsert_candles(
                db, payload.instrument, payload.timeframe, candles, source=connector.name
            )
        payout = INSTRUMENT_SPECS.get(payload.instrument, INSTRUMENT_SPECS["EUR/USD"])["payout"]
        try:
            res = WalkForwardBacktestEngine.run_backtest(
                candles=candles,
                instrument=payload.instrument,
                timeframe=payload.timeframe,
                strategy_id=payload.strategy_id,
                stake_amount=payload.stake_amount,
                payout_rate=payout,
                expiry_bars=payload.expiry_bars,
            )
        except KeyError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        return BacktestRunResponse(**res)

    @application.get(
        "/api/v1/trading/live-readiness",
        response_model=LiveReadinessResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Safety"],
    )
    async def get_live_readiness(db: Session = Depends(get_db)) -> LiveReadinessResponse:
        data_ok = True
        try:
            candles = await connector.get_candles(instrument="EUR/USD", timeframe="1m", limit=40)
            valid, _ = MarketIntelligenceEngine.validate_candles(candles)
            data_ok = valid
        except Exception:
            data_ok = False
        report = LiveReadinessEvaluator.evaluate(
            db=db,
            connector=connector,
            settings=settings,
            data_quality_ok=data_ok,
            owner_confirmed=False,
        )
        state = TradeExecutionEngine.get_or_create_state(db)
        report["current_mode"] = state.execution_mode
        return LiveReadinessResponse(**report)

    @application.get(
        "/api/v1/system/diagnostics",
        response_model=SystemDiagnosticsResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["System"],
    )
    async def get_system_diagnostics(
        instrument: str = Query(default="EUR/USD", min_length=3, max_length=20),
        timeframe: str = Query(default="1m", pattern="^(1m|5m|15m)$"),
        db: Session = Depends(get_db),
    ) -> SystemDiagnosticsResponse:
        if not INSTRUMENT_PATTERN.match(instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        report = await SystemDiagnosticsEngine.run_diagnostics(
            db=db,
            connector=connector,
            settings=settings,
            instrument=instrument,
            timeframe=timeframe,
        )
        state = TradeExecutionEngine.get_or_create_state(db)
        report["live_readiness"]["current_mode"] = state.execution_mode
        return SystemDiagnosticsResponse(**report)

    @application.post(
        "/api/v1/trading/broker-dry-run",
        response_model=BrokerDryRunResponse,
        dependencies=[Depends(verify_bearer_token)],
        tags=["Safety"],
    )
    async def run_broker_dry_run(
        payload: BrokerDryRunRequest | None = None,
    ) -> BrokerDryRunResponse:
        target_instrument = payload.instrument if payload is not None else "EUR/USD"
        if not INSTRUMENT_PATTERN.match(target_instrument):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid instrument format.")
        report = await SystemDiagnosticsEngine.run_broker_dry_run(
            connector=connector,
            settings=settings,
            instrument=target_instrument,
        )
        return BrokerDryRunResponse(**report)

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

    def _is_ws_authorized(websocket: WebSocket) -> bool:
        expected_token = settings.api_bearer_token.strip()
        if not expected_token:
            return True
        auth_header = websocket.headers.get("authorization", "")
        token = auth_header[7:].strip() if auth_header.lower().startswith("bearer ") else ""
        return bool(token and hmac.compare_digest(token.encode("utf-8"), expected_token.encode("utf-8")))

    def _open_ws_db() -> tuple[Session, object]:
        provider = application.dependency_overrides.get(get_db, get_db)
        gen = provider()
        return next(gen), gen

    @application.websocket("/ws/signals")
    async def signals_stream(
        websocket: WebSocket,
        instrument: str = Query(default="EUR/USD"),
        timeframe: str = Query(default="1m"),
    ) -> None:
        if not _is_ws_authorized(websocket):
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return
        if not INSTRUMENT_PATTERN.match(instrument) or not TIMEFRAME_PATTERN.match(timeframe):
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

        await websocket.accept()
        try:
            while True:
                candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=80)
                active = replace(strategy_engine.get_active_strategy(), instrument=instrument, timeframe=timeframe)
                snap = TechnicalAnalysisEngine.analyze(candles, active)
                db, gen = _open_ws_db()
                try:
                    report = strategy_engine.build_report(candles=candles, current_regime=snap.regime)
                    sig = ml_inference.infer_signal(
                        db=db,
                        instrument=instrument,
                        timeframe=timeframe,
                        candles_1m=candles,
                        snapshot_1m=snap,
                        mtf_report=None,
                        strategy=active,
                    )
                finally:
                    gen.close()

                await websocket.send_json(
                    {
                        "type": "signal",
                        "instrument": instrument,
                        "timeframe": timeframe,
                        "selected_strategy_id": report.selected_strategy_id,
                        "selected_strategy_name": report.selected_strategy_name,
                        "signal": SignalResponse.from_domain(sig).model_dump(mode="json"),
                    }
                )
                await asyncio.sleep(2.5)
        except WebSocketDisconnect:
            return

    @application.websocket("/ws/system")
    async def system_stream(websocket: WebSocket) -> None:
        if not _is_ws_authorized(websocket):
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

        await websocket.accept()
        try:
            while True:
                db, gen = _open_ws_db()
                try:
                    state = TradeExecutionEngine.get_or_create_state(db)
                    policy = TradeExecutionEngine.get_or_create_policy(db)
                    unresolved = TradeExecutionEngine.count_unresolved_orders(db)
                    mode_val = state.execution_mode
                    emergency = policy.emergency_stopped
                    active_ml = (
                        db.query(ModelRegistryModel)
                        .filter(ModelRegistryModel.status == "ACTIVE")
                        .order_by(ModelRegistryModel.promoted_at.desc())
                        .first()
                    )
                    drift_status = (active_ml.drift_status if active_ml is not None else "HEALTHY") or "HEALTHY"
                    drift_score = float(active_ml.drift_score) if active_ml is not None else 0.0
                    drift_notes = (active_ml.drift_notes if active_ml is not None else "") or ""
                finally:
                    gen.close()

                await websocket.send_json(
                    {
                        "type": "system",
                        "execution_mode": mode_val,
                        "connector": connector.name,
                        "connector_connected": connector.connected,
                        "live_authorized": bool(connector.live_authorized and settings.allow_live_trading),
                        "emergency_stopped": emergency,
                        "unresolved_orders_count": unresolved,
                        "selected_strategy_id": strategy_engine.selected_strategy_id,
                        "auto_select_strategy": strategy_engine.auto_select_enabled,
                        "active_model_drift_status": drift_status,
                        "active_model_drift_score": drift_score,
                        "active_model_drift_notes": drift_notes,
                    }
                )
                await asyncio.sleep(3.0)
        except WebSocketDisconnect:
            return

    return application


app = create_app()
