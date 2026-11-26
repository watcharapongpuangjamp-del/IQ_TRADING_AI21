from datetime import datetime, timezone
import hmac
import time
from typing import Dict, List, Optional, Tuple

from app.connectors.base import BrokerConnector


def verify_bearer_credentials(
    presented_token: Optional[str],
    expected_token: str,
    app_env: str = "development",
) -> Tuple[bool, int, str]:
    """
    Pure-Python constant-time Bearer token verification.
    Fails closed in production when expected_token is missing.
    """
    expected = expected_token.strip()
    if not expected:
        if app_env == "production":
            return False, 500, "API_BEARER_TOKEN must be configured in production."
        return True, 200, "Local development mode (no API_BEARER_TOKEN configured)."

    token = (presented_token or "").strip()
    if not token:
        return False, 401, "Missing Bearer authorization token."

    if not hmac.compare_digest(token.encode("utf-8"), expected.encode("utf-8")):
        return False, 401, "Invalid Bearer authorization token."

    return True, 200, "Authorized"


async def run_broker_dry_run_probe(
    connector: BrokerConnector,
    allow_live_trading: bool,
    instrument: str = "EUR/USD",
) -> Dict[str, object]:
    """
    Non-executing broker readiness & credential dry-run check.
    Probes connectivity, market-tick latency, and account balance while
    preserving SIMULATION lock without submitting any order.
    """
    t0 = time.perf_counter()
    caps = list(connector.get_capabilities())
    live_auth = bool(connector.live_authorized and allow_live_trading)
    simulation_locked = not live_auth
    market_price = 0.0
    balance = 0.0
    connected = bool(connector.connected)
    dry_run_passed = False

    if not connected:
        status_detail = (
            f"Connector '{connector.name}' is disconnected; "
            "execution remains locked to SIMULATION."
        )
    else:
        detail_parts: List[str] = []
        try:
            tick = await connector.get_market_data(instrument=instrument)
            market_price = float(tick.get("price", 0.0))
            balance = float(await connector.get_balance())
            dry_run_passed = market_price > 0.0
            if dry_run_passed:
                detail_parts.append(
                    f"Non-executing probe passed on '{connector.name}' "
                    f"({instrument}={market_price:.5f}, balance=${balance:.2f})."
                )
            else:
                detail_parts.append(
                    f"Broker tick returned non-positive price ({market_price}); dry-run failed."
                )
        except Exception as exc:
            detail_parts.append(f"Broker probe failed: {exc}")
            dry_run_passed = False

        if simulation_locked:
            detail_parts.append("SIMULATION lock active (LIVE orders disabled).")
        else:
            detail_parts.append("LIVE capability armed on server (requires 5-step confirmation).")
        status_detail = " ".join(detail_parts)

    latency_ms = round((time.perf_counter() - t0) * 1000.0, 2)
    return {
        "connector_name": connector.name,
        "connected": connected,
        "dry_run_passed": dry_run_passed,
        "live_authorized": live_auth,
        "simulation_locked": simulation_locked,
        "latency_ms": latency_ms,
        "market_price": market_price,
        "balance": balance,
        "capabilities": caps,
        "status_detail": status_detail,
        "checked_at": datetime.now(timezone.utc),
    }
