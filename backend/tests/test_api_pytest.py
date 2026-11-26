from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.config import Settings
from app.database import Base, get_db
from app.main import create_app, seed_initial_records
from app.models import RiskPolicyModel


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
        model_artifacts_dir=str(tmp_path / "models"),
    )
    app = create_app(custom_settings=settings)

    def override_get_db():
        db = TestingSessionLocal()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    app.state.test_session_factory = TestingSessionLocal
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
        model_artifacts_dir=str(tmp_path / "models"),
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


def disable_loss_cooldown_for_lifecycle_test(client: TestClient) -> None:
    """Keep lifecycle tests independent from randomized simulated WIN/LOSS outcomes."""
    with client.app.state.test_session_factory() as db:
        policy = db.get(RiskPolicyModel, 1)
        assert policy is not None
        policy.cooldown_minutes = 0
        db.commit()


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


def test_ml_training_walk_forward_validation_and_controlled_promotion(client: TestClient) -> None:
    # 1. Train candidate model with lenient test gate -> VALIDATED, NOT auto-promoted
    train_res = client.post(
        "/api/v1/ml/train",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "algorithm": "logistic_regression",
            "total_bars": 320,
            "max_brier_score": 0.45,
            "max_ece": 0.45,
            "min_accuracy": 0.40,
        },
    )
    assert train_res.status_code == 200
    model = train_res.json()
    assert model["status"] == "VALIDATED"
    assert model["train_samples"] > 0
    assert model["test_samples"] > 0

    # Active model must still be None before owner promotion
    active_before = client.get("/api/v1/ml/active", params={"instrument": "EUR/USD", "timeframe": "1m"})
    assert active_before.status_code == 200
    assert active_before.json() is None

    # 2. Promoting without owner_confirmed=True is blocked (403)
    unconfirmed = client.post(
        f"/api/v1/ml/models/{model['model_id']}/promote",
        json={"owner_confirmed": False},
    )
    assert unconfirmed.status_code == 403

    # 3. Promoting with owner_confirmed=True activates model and powers inference
    promoted = client.post(
        f"/api/v1/ml/models/{model['model_id']}/promote",
        json={"owner_confirmed": True},
    )
    assert promoted.status_code == 200
    assert promoted.json()["status"] == "ACTIVE"

    active_after = client.get("/api/v1/ml/active", params={"instrument": "EUR/USD", "timeframe": "1m"})
    assert active_after.status_code == 200
    assert active_after.json()["model_id"] == model["model_id"]

    intel = client.get("/api/v1/market/intelligence", params={"instrument": "EUR/USD"})
    assert intel.status_code == 200
    sig = intel.json()["signal"]
    assert any(model["model_id"] in ev for ev in sig["supporting_evidence"])


def test_ml_validation_gate_rejects_failing_model_and_blocks_promotion(client: TestClient) -> None:
    rejected_res = client.post(
        "/api/v1/ml/train",
        json={
            "instrument": "GBP/USD",
            "timeframe": "1m",
            "algorithm": "hist_gradient_boosting",
            "total_bars": 300,
            "min_accuracy": 0.99,
        },
    )
    assert rejected_res.status_code == 200
    rejected = rejected_res.json()
    assert rejected["status"] == "REJECTED"

    promote_blocked = client.post(
        f"/api/v1/ml/models/{rejected['model_id']}/promote",
        json={"owner_confirmed": True},
    )
    assert promote_blocked.status_code == 400


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


def test_order_lifecycle_10_states_audit_events(client: TestClient) -> None:
    res = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "CALL",
            "amount": 25.0,
            "expiry_seconds": 60,
            "mode": "SIMULATION",
            "idempotency_key": "idem-lifecycle-events-001",
        },
    )
    assert res.status_code == 200
    order = res.json()
    assert order["lifecycle_state"] == "SETTLED"
    assert order["correlation_id"].startswith("corr_")
    assert order["requires_reconciliation"] is False

    events_res = client.get(f"/api/v1/orders/{order['order_id']}/events")
    assert events_res.status_code == 200
    states = [e["to_state"] for e in events_res.json()]
    assert states == ["CREATED", "VALIDATED", "AUTHORIZED", "SUBMITTED", "ACCEPTED", "SETTLED"]


def test_unknown_timeout_never_resubmits_and_blocks_new_orders_until_reconciled(client: TestClient) -> None:
    disable_loss_cooldown_for_lifecycle_test(client)
    connector = client.app.state.connector
    connector.queue_simulation_behavior("TIMEOUT", persistent_unknown=True)

    payload = {
        "instrument": "EUR/USD",
        "timeframe": "1m",
        "direction": "PUT",
        "amount": 20.0,
        "expiry_seconds": 60,
        "mode": "SIMULATION",
        "idempotency_key": "idem-timeout-unknown-001",
    }
    first = client.post("/api/v1/orders", json=payload)
    assert first.status_code == 200
    first_data = first.json()
    assert first_data["lifecycle_state"] == "UNKNOWN"
    assert first_data["status"] == "UNKNOWN"
    assert first_data["requires_reconciliation"] is True

    # Retrying with the exact same idempotency_key MUST NOT resubmit to broker
    dup = client.post("/api/v1/orders", json=payload)
    assert dup.status_code == 200
    assert dup.json()["order_id"] == first_data["order_id"]
    assert dup.json()["lifecycle_state"] == "UNKNOWN"
    assert "blocked" in (dup.json()["message"] or "").lower()

    # Opening a new trade while UNKNOWN order cannot reconcile MUST fail closed (400)
    blocked = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "CALL",
            "amount": 15.0,
            "expiry_seconds": 60,
            "mode": "SIMULATION",
            "idempotency_key": "idem-blocked-while-unknown-002",
        },
    )
    assert blocked.status_code == 400
    assert "UNKNOWN" in blocked.json()["detail"]

    # Recover broker connectivity and reconcile
    connector.set_persistent_unknown(False)
    rec = client.post("/api/v1/trading/reconcile")
    assert rec.status_code == 200
    rec_data = rec.json()
    assert rec_data["settled_count"] == 1
    assert rec_data["unknown_count"] == 0

    events_res = client.get(f"/api/v1/orders/{first_data['order_id']}/events")
    assert "UNKNOWN" in [e["to_state"] for e in events_res.json()]
    assert [e["to_state"] for e in events_res.json()][-1] == "SETTLED"

    # Subsequent trade now succeeds cleanly
    next_order = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "CALL",
            "amount": 15.0,
            "expiry_seconds": 60,
            "mode": "SIMULATION",
            "idempotency_key": "idem-after-reconcile-003",
        },
    )
    assert next_order.status_code == 200
    assert next_order.json()["lifecycle_state"] == "SETTLED"


def test_pending_order_auto_reconciles_before_new_trade_without_double_counting(client: TestClient) -> None:
    disable_loss_cooldown_for_lifecycle_test(client)
    connector = client.app.state.connector
    connector.queue_simulation_behavior("PENDING")

    pending_res = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "CALL",
            "amount": 25.0,
            "expiry_seconds": 60,
            "mode": "SIMULATION",
            "idempotency_key": "idem-pending-first-001",
        },
    )
    assert pending_res.status_code == 200
    pending_order = pending_res.json()
    assert pending_order["lifecycle_state"] == "PENDING"
    assert pending_order["requires_reconciliation"] is True

    state_before = client.get("/api/v1/state").json()
    assert state_before["unresolved_orders_count"] == 1
    assert state_before["total_trades_today"] == 0

    # Submitting a new order auto-reconciles the PENDING order first, then opens the new order
    second_res = client.post(
        "/api/v1/orders",
        json={
            "instrument": "EUR/USD",
            "timeframe": "1m",
            "direction": "PUT",
            "amount": 25.0,
            "expiry_seconds": 60,
            "mode": "SIMULATION",
            "idempotency_key": "idem-second-after-pending-002",
        },
    )
    assert second_res.status_code == 200
    assert second_res.json()["lifecycle_state"] == "SETTLED"

    reconciled_first = client.get(f"/api/v1/orders/{pending_order['order_id']}").json()
    assert reconciled_first["lifecycle_state"] == "SETTLED"
    assert reconciled_first["settlement_outcome"] in ("WON", "LOST")

    state_after = client.get("/api/v1/state").json()
    assert state_after["unresolved_orders_count"] == 0
    assert state_after["total_trades_today"] == 2

    # Extra reconcile pass must be a no-op with zero double counting
    noop = client.post("/api/v1/trading/reconcile").json()
    assert noop["checked_count"] == 0
    assert client.get("/api/v1/state").json()["total_trades_today"] == 2


def test_phase6_strategies_endpoints_and_expanded_indicators(client: TestClient) -> None:
    sig_res = client.get("/api/v1/signals/latest", params={"instrument": "EUR/USD", "timeframe": "1m"})
    assert sig_res.status_code == 200
    snap = sig_res.json()["technical_snapshot"]
    assert "adx" in snap
    assert "stochastic_k" in snap
    assert "vwap" in snap
    assert "vwap_reliable" in snap

    list_res = client.get("/api/v1/strategies", params={"instrument": "EUR/USD", "timeframe": "1m"})
    assert list_res.status_code == 200
    body = list_res.json()
    assert len(body["strategies"]) == 8
    assert body["auto_selected"] is True
    assert sum(1 for s in body["strategies"] if s["selected"]) == 1

    pin_res = client.post(
        "/api/v1/strategies/select",
        json={"strategy_id": "rsi_reversal", "auto_select": False, "instrument": "EUR/USD", "timeframe": "1m"},
    )
    assert pin_res.status_code == 200
    assert pin_res.json()["selected_strategy_id"] == "rsi_reversal"
    assert pin_res.json()["auto_selected"] is False

    bad_res = client.post(
        "/api/v1/strategies/select",
        json={"strategy_id": "does_not_exist", "auto_select": False},
    )
    assert bad_res.status_code == 404


def test_phase6_signal_and_system_websockets(client: TestClient) -> None:
    with client.websocket_connect("/ws/signals?instrument=EUR/USD&timeframe=1m") as ws_sig:
        sig_msg = ws_sig.receive_json()
        assert sig_msg["type"] == "signal"
        assert sig_msg["instrument"] == "EUR/USD"
        assert "direction" in sig_msg["signal"]

    with client.websocket_connect("/ws/system") as ws_sys:
        sys_msg = ws_sys.receive_json()
        assert sys_msg["type"] == "system"
        assert sys_msg["execution_mode"] == "SIMULATION"
        assert "selected_strategy_id" in sys_msg

