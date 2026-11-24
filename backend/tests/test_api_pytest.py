from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.config import Settings
from app.database import Base, get_db
from app.main import create_app, seed_initial_records


@pytest.fixture()
def client(tmp_path: Path):
    db_file = tmp_path / "test_iq_trading.db"
    test_engine = create_engine(f"sqlite:///{db_file}", connect_args={"check_same_thread": False}, future=True)
    TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)
    Base.metadata.create_all(bind=test_engine)

    with TestingSessionLocal() as db:
        seed_initial_records(db)

    settings = Settings(
        app_env="test",
        database_url=f"sqlite:///{db_file}",
        allow_live_trading=False,
        api_bearer_token="",
    )
    app = create_app(custom_settings=settings)

    def override_get_db():
        db = TestingSessionLocal()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def secured_client(tmp_path: Path):
    db_file = tmp_path / "test_secured.db"
    test_engine = create_engine(f"sqlite:///{db_file}", connect_args={"check_same_thread": False}, future=True)
    TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)
    Base.metadata.create_all(bind=test_engine)

    with TestingSessionLocal() as db:
        seed_initial_records(db)

    settings = Settings(
        app_env="production",
        database_url=f"sqlite:///{db_file}",
        allow_live_trading=False,
        api_bearer_token="test-secret-token-12345",
    )
    app = create_app(custom_settings=settings)

    def override_get_db():
        db = TestingSessionLocal()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client


def test_health_endpoint_defaults_to_simulation(client: TestClient) -> None:
    res = client.get("/api/v1/health")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "ok"
    assert data["connector"] == "simulation_connector"
    assert data["execution_mode"] == "SIMULATION"
    assert data["live_authorized"] is False


def test_candles_and_signal_pipeline(client: TestClient) -> None:
    candles_res = client.get("/api/v1/candles", params={"instrument": "EUR/USD", "timeframe": "1m", "limit": 60})
    assert candles_res.status_code == 200
    assert len(candles_res.json()) == 60

    sig_res = client.get("/api/v1/signals/latest", params={"instrument": "EUR/USD", "timeframe": "1m"})
    assert sig_res.status_code == 200
    sig = sig_res.json()
    assert sig["direction"] in ("CALL", "PUT", "NO_TRADE")
    assert 0.0 <= sig["calibrated_probability"] <= 1.0
    assert 0 <= sig["confidence_score"] <= 100
    assert sig["technical_snapshot"]["sma_fast"] > 0


def test_historical_candle_persistence_and_idempotent_ingest(client: TestClient) -> None:
    hist_res = client.get("/api/v1/candles/history", params={"instrument": "EUR/USD", "timeframe": "1m", "limit": 60})
    assert hist_res.status_code == 200
    candles = hist_res.json()
    assert len(candles) == 60

    ingest_res = client.post(
        "/api/v1/candles/history/ingest",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "source": "pytest_replay",
            "candles": candles,
        },
    )
    assert ingest_res.status_code == 200
    assert ingest_res.json()["inserted_count"] == 0
    assert ingest_res.json()["total_stored"] >= 60


def test_multi_timeframe_market_intelligence(client: TestClient) -> None:
    res = client.get("/api/v1/market/intelligence", params={"instrument": "EUR/USD"})
    assert res.status_code == 200
    body = res.json()
    assert body["data_quality"]["valid"] is True
    assert len(body["mtf_report"]["timeframe_summaries"]) == 3
    tfs = [item["timeframe"] for item in body["mtf_report"]["timeframe_summaries"]]
    assert tfs == ["1m", "5m", "15m"]
    assert body["signal"]["direction"] in ("CALL", "PUT", "NO_TRADE")


def test_simulator_replay_lifecycle(client: TestClient) -> None:
    start_res = client.post(
        "/api/v1/replay/start",
        json={"instrument": "EUR/USD", "total_bars": 420, "initial_window": 375},
    )
    assert start_res.status_code == 200
    start_data = start_res.json()
    assert start_data["replay_state"]["active"] is True
    assert start_data["replay_state"]["cursor_index"] == 374

    step_res = client.post("/api/v1/replay/step", json={"steps": 5})
    assert step_res.status_code == 200
    assert step_res.json()["replay_state"]["cursor_index"] == 379

    reset_res = client.post("/api/v1/replay/reset")
    assert reset_res.status_code == 200
    assert reset_res.json()["replay_state"]["cursor_index"] == 374

    state_res = client.get("/api/v1/replay/state")
    assert state_res.status_code == 200
    assert state_res.json()["active"] is True


def test_order_execution_and_idempotency(client: TestClient) -> None:
    payload = {
        "instrument": "EUR/USD",
        "timeframe": "1m",
        "direction": "CALL",
        "amount": 25.0,
        "expiry_seconds": 60,
        "mode": "SIMULATION",
        "idempotency_key": "idem-order-key-0001",
    }
    first = client.post("/api/v1/orders", json=payload)
    assert first.status_code == 200
    first_data = first.json()
    assert first_data["status"] in ("WON", "LOST")

    replay = client.post("/api/v1/orders", json=payload)
    assert replay.status_code == 200
    assert replay.json()["order_id"] == first_data["order_id"]


def test_live_mode_is_locked_by_default(client: TestClient) -> None:
    mode_res = client.post("/api/v1/mode", json={"mode": "LIVE", "explicit_live_confirmation": True})
    assert mode_res.status_code == 403

    order_res = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "CALL",
            "amount": 20.0,
            "expiry_seconds": 60,
            "mode": "LIVE",
            "idempotency_key": "idem-live-blocked-001",
        },
    )
    assert order_res.status_code == 403


def test_emergency_stop_blocks_orders(client: TestClient) -> None:
    stop_res = client.post("/api/v1/risk/emergency-stop")
    assert stop_res.status_code == 200
    assert stop_res.json()["emergency_stopped"] is True

    order_res = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "PUT",
            "amount": 15.0,
            "expiry_seconds": 60,
            "mode": "SIMULATION",
            "idempotency_key": "idem-emergency-stop-001",
        },
    )
    assert order_res.status_code == 400


def test_bearer_auth_enforced_when_configured(secured_client: TestClient) -> None:
    unauth = secured_client.get("/api/v1/state")
    assert unauth.status_code == 401

    wrong = secured_client.get("/api/v1/state", headers={"Authorization": "Bearer wrong"})
    assert wrong.status_code == 401

    ok = secured_client.get("/api/v1/state", headers={"Authorization": "Bearer test-secret-token-12345"})
    assert ok.status_code == 200


def test_websocket_market_stream(client: TestClient) -> None:
    with client.websocket_connect("/ws/market/EUR/USD?timeframe=1m") as ws:
        msg = ws.receive_json()
        assert msg["type"] == "tick"
        assert msg["instrument"] == "EUR/USD"
        assert "close" in msg["candle"]
