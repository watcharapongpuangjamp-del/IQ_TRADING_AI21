from datetime import datetime, timezone
from typing import Dict, List

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.config import Settings
from app.connectors.base import BrokerConnector
from app.domain import ExecutionMode, RiskPolicyConfig, TradeDirection, UNRESOLVED_LIFECYCLE_STATES
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.risk_engine import RiskManagementEngine
from app.models import HistoricalCandleModel, ModelRegistryModel, OrderRecordModel, RiskPolicyModel


class LiveReadinessEvaluator:
    """
    Enforces the 5-step Master Prompt Live Readiness Gate before LIVE execution
    may ever be enabled. Defaults strictly to SIMULATION.
    """

    @staticmethod
    def evaluate(
        db: Session,
        connector: BrokerConnector,
        settings: Settings,
        data_quality_ok: bool = True,
        owner_confirmed: bool = False,
    ) -> Dict[str, object]:
        policy = db.get(RiskPolicyModel, 1)
        unresolved = int(
            db.query(func.count(OrderRecordModel.order_id))
            .filter(OrderRecordModel.lifecycle_state.in_(UNRESOLVED_LIFECYCLE_STATES))
            .scalar()
            or 0
        )

        # Gate 1: Broker integration verified & live-capable
        caps = connector.get_capabilities()
        broker_verified = bool(
            connector.connected
            and connector.live_authorized
            and settings.allow_live_trading
            and "LIVE_ORDERS" in caps
        )
        if not settings.allow_live_trading:
            broker_detail = "Server ALLOW_LIVE_TRADING=false (simulation-only lock active)."
        elif not connector.live_authorized or "LIVE_ORDERS" not in caps:
            broker_detail = f"Connector '{connector.name}' is not authorized for LIVE_ORDERS."
        elif not connector.connected:
            broker_detail = f"Connector '{connector.name}' is disconnected."
        else:
            broker_detail = f"Live broker '{connector.name}' connected and authorized."

        # Gate 2: Risk configuration valid & no emergency stop
        risk_valid = bool(
            policy is not None
            and not policy.emergency_stopped
            and policy.max_trade_amount > 0
            and policy.max_daily_loss > 0
            and policy.max_portfolio_exposure >= policy.max_trade_amount
            and policy.max_daily_trades > 0
        )
        if policy is None:
            risk_detail = "Risk policy missing; fail-closed lock active."
        elif policy.emergency_stopped:
            risk_detail = "Emergency Stop is active — clear emergency stop first."
        elif not risk_valid:
            risk_detail = "Risk caps invalid (exposure/amount/loss limits must be > 0)."
        else:
            risk_detail = (
                f"Active caps: max ${policy.max_trade_amount:.0f}/trade, "
                f"${policy.max_daily_loss:.0f}/day loss, cooldown {policy.cooldown_minutes}m."
            )

        # Gate 3: API Auth / Credential security valid
        has_token = bool(settings.api_bearer_token.strip())
        auth_valid = has_token
        auth_detail = (
            "API Bearer Token authentication active."
            if has_token
            else "Set API_BEARER_TOKEN on backend before authorizing LIVE execution."
        )

        # Gate 4: Execution & reconciliation tests healthy (0 unresolved orders + valid market data)
        exec_ok = (unresolved == 0) and data_quality_ok
        if unresolved > 0:
            exec_detail = f"{unresolved} unresolved PENDING/UNKNOWN order(s) require reconciliation."
        elif not data_quality_ok:
            exec_detail = "Market candle data quality check failed."
        else:
            exec_detail = "0 unresolved orders and candle validation passed."

        # Gate 5: Explicit user confirmation (evaluated dynamically on switch/submit)
        owner_detail = (
            "Explicit owner LIVE confirmation provided."
            if owner_confirmed
            else "Requires explicit two-step owner confirmation upon enabling LIVE."
        )

        gates = [
            {
                "gate_id": "broker_verified",
                "step_number": 1,
                "title": "1. Broker Integration Verified",
                "passed": broker_verified,
                "detail": broker_detail,
            },
            {
                "gate_id": "risk_config_valid",
                "step_number": 2,
                "title": "2. Risk Configuration Valid",
                "passed": risk_valid,
                "detail": risk_detail,
            },
            {
                "gate_id": "auth_and_vault_valid",
                "step_number": 3,
                "title": "3. API Auth & Credential Security",
                "passed": auth_valid,
                "detail": auth_detail,
            },
            {
                "gate_id": "execution_checks_passed",
                "step_number": 4,
                "title": "4. Execution & Reconciliation Clear",
                "passed": exec_ok,
                "detail": exec_detail,
            },
            {
                "gate_id": "owner_confirmed",
                "step_number": 5,
                "title": "5. Explicit Owner Confirmation",
                "passed": owner_confirmed,
                "detail": owner_detail,
            },
        ]

        prerequisites_ready = broker_verified and risk_valid and auth_valid and exec_ok
        overall_ready = prerequisites_ready and owner_confirmed
        blocking = [g["detail"] for g in gates if not g["passed"]]

        return {
            "prerequisites_ready": prerequisites_ready,
            "overall_ready": overall_ready,
            "current_mode": "SIMULATION",
            "gates": gates,
            "blocking_reasons": blocking,
            "evaluated_at": datetime.now(timezone.utc),
        }


class SystemDiagnosticsEngine:
    """Runs non-destructive backend subsystem & failure-guard diagnostics."""

    @staticmethod
    async def run_diagnostics(
        db: Session,
        connector: BrokerConnector,
        settings: Settings,
        instrument: str = "EUR/USD",
        timeframe: str = "1m",
    ) -> Dict[str, object]:
        checks: List[Dict[str, str]] = []

        # 1. SQLite Persistence Integrity
        order_count = int(db.query(func.count(OrderRecordModel.order_id)).scalar() or 0)
        candle_count = int(db.query(func.count(HistoricalCandleModel.id)).scalar() or 0)
        model_count = int(db.query(func.count(ModelRegistryModel.model_id)).scalar() or 0)
        checks.append(
            {
                "check_id": "sqlite_store",
                "title": "Backend SQLite Store",
                "status": "PASS",
                "detail": f"Orders: {order_count}, Candles: {candle_count}, ML Models: {model_count}",
            }
        )

        # 2. Market Data Quality Inspection
        data_ok = False
        try:
            candles = await connector.get_candles(instrument=instrument, timeframe=timeframe, limit=60)
            tf_sec = {"1m": 60, "5m": 300, "15m": 900}.get(timeframe, 60)
            dq = MarketIntelligenceEngine.inspect_candles(candles, expected_timeframe_sec=tf_sec)
            data_ok = dq.valid
            checks.append(
                {
                    "check_id": "market_data_quality",
                    "title": "Market Data Validation",
                    "status": "PASS" if dq.valid else "FAIL",
                    "detail": f"{dq.reason} (bars={dq.candle_count}, gaps={dq.gap_count}, dups={dq.duplicate_count})",
                }
            )
        except Exception as exc:
            checks.append(
                {
                    "check_id": "market_data_quality",
                    "title": "Market Data Validation",
                    "status": "FAIL",
                    "detail": f"Candle fetch failed: {exc}",
                }
            )

        # 3. Risk Engine Fail-Closed Probe (must reject emergency stop and oversized stake)
        probe_stop = RiskManagementEngine.validate_order(
            policy=RiskPolicyConfig(emergency_stopped=True),
            total_trades_today=0,
            net_pnl_today=0.0,
            consecutive_losses=0,
            open_exposure=0.0,
            requested_amount=10.0,
            direction=TradeDirection.CALL,
            mode=ExecutionMode.SIMULATION,
            live_authorized=False,
            last_loss_epoch_ms=None,
        )
        probe_live = RiskManagementEngine.validate_order(
            policy=RiskPolicyConfig(emergency_stopped=False),
            total_trades_today=0,
            net_pnl_today=0.0,
            consecutive_losses=0,
            open_exposure=0.0,
            requested_amount=10.0,
            direction=TradeDirection.CALL,
            mode=ExecutionMode.LIVE,
            live_authorized=False,
            last_loss_epoch_ms=None,
        )
        fail_closed_ok = (not probe_stop.allowed) and (not probe_live.allowed)
        checks.append(
            {
                "check_id": "risk_fail_closed",
                "title": "Independent Risk Fail-Closed Guard",
                "status": "PASS" if fail_closed_ok else "FAIL",
                "detail": (
                    "Verified emergency stop & unauthorized LIVE rejection."
                    if fail_closed_ok
                    else "Risk engine failed to block emergency/unauthorized probes!"
                ),
            }
        )

        # 4. Order Reconciliation Health
        unresolved = int(
            db.query(func.count(OrderRecordModel.order_id))
            .filter(OrderRecordModel.lifecycle_state.in_(UNRESOLVED_LIFECYCLE_STATES))
            .scalar()
            or 0
        )
        checks.append(
            {
                "check_id": "order_reconciliation",
                "title": "Order Lifecycle Reconciliation",
                "status": "PASS" if unresolved == 0 else "WARN",
                "detail": (
                    "0 unresolved PENDING/UNKNOWN orders."
                    if unresolved == 0
                    else f"{unresolved} unresolved order(s) awaiting broker reconciliation."
                ),
            }
        )

        # 5. ML Drift Posture
        active_ml = (
            db.query(ModelRegistryModel)
            .filter(ModelRegistryModel.status == "ACTIVE")
            .order_by(ModelRegistryModel.promoted_at.desc())
            .first()
        )
        if active_ml is None:
            checks.append(
                {
                    "check_id": "ml_drift_posture",
                    "title": "ML Model Drift Posture",
                    "status": "WARN",
                    "detail": "No ACTIVE ML model promoted; using calibrated quantitative heuristics.",
                }
            )
        else:
            d_status = (active_ml.drift_status or "HEALTHY").upper()
            checks.append(
                {
                    "check_id": "ml_drift_posture",
                    "title": "ML Model Drift Posture",
                    "status": "PASS" if d_status == "HEALTHY" else ("WARN" if d_status == "WARNING" else "FAIL"),
                    "detail": f"{active_ml.model_id} ({active_ml.algorithm}) -> {d_status} (score={active_ml.drift_score:.2f})",
                }
            )

        readiness = LiveReadinessEvaluator.evaluate(
            db=db,
            connector=connector,
            settings=settings,
            data_quality_ok=data_ok,
            owner_confirmed=False,
        )
        overall_status = "FAIL" if any(c["status"] == "FAIL" for c in checks) else (
            "WARN" if any(c["status"] == "WARN" for c in checks) else "PASS"
        )
        return {
            "overall_status": overall_status,
            "connector_name": connector.name,
            "connector_connected": connector.connected,
            "checks": checks,
            "live_readiness": readiness,
            "checked_at": datetime.now(timezone.utc),
        }
