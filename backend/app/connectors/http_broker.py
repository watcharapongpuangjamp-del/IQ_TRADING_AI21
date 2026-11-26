import asyncio
import json
import socket
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Callable, Dict, List, Optional, Tuple

from app.connectors.base import BrokerConnector, ConnectorOrderResult

TransportFn = Callable[
    [str, str, Optional[Dict[str, Any]], Dict[str, str], float],
    Tuple[int, Dict[str, Any]],
]


class HttpBrokerConnector(BrokerConnector):
    """
    Production-ready HTTP Broker Adapter Contract.
    - Fails closed whenever base_url/api_token are unconfigured or disconnected.
    - Enforces X-Idempotency-Key + correlation_id tracking (never blindly resubmits).
    - Redacts credentials from all errors and status messages.
    """

    def __init__(
        self,
        base_url: str = "",
        api_token: str = "",
        timeout_seconds: float = 10.0,
        allow_live: bool = False,
        transport: Optional[TransportFn] = None,
    ) -> None:
        self._base_url = base_url.strip().rstrip("/")
        self._api_token = api_token.strip()
        self._timeout_seconds = max(1.0, float(timeout_seconds))
        self._allow_live = bool(allow_live)
        self._transport = transport
        self._connected = False
        self._orders_by_id: Dict[str, ConnectorOrderResult] = {}
        self._orders_by_correlation: Dict[str, ConnectorOrderResult] = {}

    def _is_configured(self) -> bool:
        if not self._base_url or not self._api_token:
            return False
        parsed = urllib.parse.urlparse(self._base_url)
        if parsed.scheme == "https" and bool(parsed.netloc):
            return True
        if parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1"):
            return True
        return False

    def _redact(self, text: str) -> str:
        sanitized = text or ""
        if self._api_token and self._api_token in sanitized:
            sanitized = sanitized.replace(self._api_token, "[REDACTED]")
        return sanitized

    def _headers(self, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self._api_token}",
        }
        if extra:
            headers.update(extra)
        return headers

    def _default_transport(
        self,
        method: str,
        path: str,
        payload: Optional[Dict[str, Any]],
        headers: Dict[str, str],
        timeout_seconds: float,
    ) -> Tuple[int, Dict[str, Any]]:
        url = f"{self._base_url}{path}"
        data_bytes = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url=url, data=data_bytes, headers=headers, method=method.upper())
        try:
            with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
                raw = resp.read().decode("utf-8") if resp.readable() else "{}"
                parsed = json.loads(raw) if raw.strip() else {}
                if not isinstance(parsed, dict):
                    parsed = {"data": parsed}
                return int(getattr(resp, "status", 200)), parsed
        except urllib.error.HTTPError as exc:
            body_raw = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
            try:
                body_json = json.loads(body_raw) if body_raw.strip() else {}
            except Exception:
                body_json = {"detail": self._redact(body_raw[:300])}
            if not isinstance(body_json, dict):
                body_json = {"detail": str(body_json)}
            return int(exc.code), body_json
        except (TimeoutError, socket.timeout) as exc:
            raise TimeoutError(f"HTTP broker request timed out after {timeout_seconds:.1f}s") from exc
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, (TimeoutError, socket.timeout)):
                raise TimeoutError(f"HTTP broker request timed out after {timeout_seconds:.1f}s") from exc
            raise ConnectionError(f"HTTP broker unreachable: {self._redact(str(exc.reason))}") from exc

    async def _request(
        self,
        method: str,
        path: str,
        payload: Optional[Dict[str, Any]] = None,
        extra_headers: Optional[Dict[str, str]] = None,
    ) -> Tuple[int, Dict[str, Any]]:
        if not self._is_configured():
            raise ConnectionError("HttpBrokerConnector is not configured (requires HTTPS base_url and API token).")
        fn = self._transport or self._default_transport
        headers = self._headers(extra_headers)
        return await asyncio.to_thread(fn, method.upper(), path, payload, headers, self._timeout_seconds)

    @property
    def name(self) -> str:
        return "http_broker"

    @property
    def connected(self) -> bool:
        return self._connected and self._is_configured()

    @property
    def live_authorized(self) -> bool:
        return bool(self._allow_live and self.connected)

    def get_capabilities(self) -> List[str]:
        caps = ["MARKET_DATA", "CANDLES", "RECONCILIATION", "DEMO_ORDERS"]
        if self.live_authorized:
            caps.append("LIVE_ORDERS")
        return caps

    async def connect(self) -> bool:
        if not self._is_configured():
            self._connected = False
            return False
        try:
            status_code, body = await self._request("GET", "/v1/health")
            self._connected = 200 <= status_code < 300 and str(body.get("status", "ok")).lower() in ("ok", "ready", "healthy")
        except Exception:
            self._connected = False
        return self._connected

    async def disconnect(self) -> None:
        self._connected = False

    async def get_balance(self) -> float:
        if not self.connected:
            raise ConnectionError("HTTP broker is disconnected.")
        status_code, body = await self._request("GET", "/v1/account/balance")
        if status_code >= 400:
            raise RuntimeError(self._redact(str(body.get("detail", f"Broker balance error HTTP {status_code}"))))
        return round(float(body.get("balance", 0.0)), 2)

    async def get_market_data(self, instrument: str) -> Dict[str, float]:
        if not self.connected:
            raise ConnectionError("HTTP broker is disconnected.")
        q = urllib.parse.urlencode({"instrument": instrument})
        status_code, body = await self._request("GET", f"/v1/market/tick?{q}")
        if status_code >= 400:
            raise RuntimeError(self._redact(str(body.get("detail", f"Broker market tick error HTTP {status_code}"))))
        return {
            "price": float(body["price"]),
            "timestamp": float(body["timestamp"]),
            "payout_rate": float(body.get("payout_rate", 0.85)),
        }

    async def get_candles(self, instrument: str, timeframe: str, limit: int = 80) -> List[Dict[str, float]]:
        if not self.connected:
            raise ConnectionError("HTTP broker is disconnected.")
        q = urllib.parse.urlencode({"instrument": instrument, "timeframe": timeframe, "limit": limit})
        status_code, body = await self._request("GET", f"/v1/market/candles?{q}")
        if status_code >= 400:
            raise RuntimeError(self._redact(str(body.get("detail", f"Broker candles error HTTP {status_code}"))))
        raw_candles = body.get("candles") if isinstance(body, dict) else None
        if not isinstance(raw_candles, list):
            raise ValueError("Broker candles response missing 'candles' array.")
        return [
            {
                "timestamp": float(c["timestamp"]),
                "open": float(c["open"]),
                "high": float(c["high"]),
                "low": float(c["low"]),
                "close": float(c["close"]),
                "volume": float(c.get("volume", 0.0)),
            }
            for c in raw_candles
        ]

    def _parse_order_result(
        self,
        body: Dict[str, Any],
        fallback_order_id: str,
        fallback_correlation_id: Optional[str],
    ) -> ConnectorOrderResult:
        broker_oid = str(body.get("broker_order_id") or body.get("order_id") or "").strip()
        if not broker_oid:
            # Never assume HTTP 200 without order identifier means an accepted or settled fill
            return ConnectorOrderResult(
                order_id=fallback_order_id,
                broker_order_id=None,
                correlation_id=fallback_correlation_id,
                status="UNKNOWN",
                lifecycle_state="UNKNOWN",
                settlement_outcome=None,
                entry_price=float(body.get("entry_price", 0.0)),
                exit_price=float(body.get("exit_price", 0.0)),
                pnl=float(body.get("pnl", 0.0)),
                message="Broker response missing broker_order_id; marked UNKNOWN for reconciliation.",
            )

        raw_lifecycle = str(body.get("lifecycle_state") or "").upper().strip()
        raw_status = str(body.get("status") or "PENDING").upper().strip()
        raw_outcome = body.get("settlement_outcome")
        if raw_outcome is not None:
            raw_outcome = str(raw_outcome).upper().strip()

        if not raw_lifecycle:
            if raw_status in ("WON", "LOST", "SETTLED"):
                raw_lifecycle = "SETTLED"
            elif raw_status in ("REJECTED", "FAILED", "UNKNOWN", "PENDING"):
                raw_lifecycle = raw_status
            else:
                raw_lifecycle = "PENDING"

        if raw_lifecycle == "SETTLED" and not raw_outcome and raw_status in ("WON", "LOST"):
            raw_outcome = raw_status

        result = ConnectorOrderResult(
            order_id=broker_oid,
            broker_order_id=broker_oid,
            correlation_id=str(body.get("correlation_id") or fallback_correlation_id or "") or None,
            status=raw_status,
            lifecycle_state=raw_lifecycle,
            settlement_outcome=raw_outcome,
            entry_price=float(body.get("entry_price", 0.0)),
            exit_price=float(body.get("exit_price", 0.0)),
            pnl=round(float(body.get("pnl", 0.0)), 2),
            message=self._redact(str(body.get("message", ""))),
        )
        self._orders_by_id[broker_oid] = result
        if result.correlation_id:
            self._orders_by_correlation[result.correlation_id] = result
        return result

    async def submit_order(
        self,
        instrument: str,
        timeframe: str,
        direction: str,
        amount: float,
        expiry_seconds: int,
        correlation_id: Optional[str] = None,
    ) -> ConnectorOrderResult:
        if not self.connected:
            raise ConnectionError("HTTP broker is disconnected or unconfigured.")

        corr_id = correlation_id or f"corr_{uuid.uuid4().hex[:12]}"
        if corr_id in self._orders_by_correlation:
            return self._orders_by_correlation[corr_id]

        payload = {
            "instrument": instrument,
            "timeframe": timeframe,
            "direction": direction,
            "amount": amount,
            "expiry_seconds": expiry_seconds,
            "correlation_id": corr_id,
        }
        status_code, body = await self._request(
            "POST",
            "/v1/orders",
            payload=payload,
            extra_headers={"X-Idempotency-Key": corr_id},
        )
        if status_code >= 500:
            raise TimeoutError(f"Broker upstream HTTP {status_code}; awaiting reconciliation for {corr_id}")
        if status_code >= 400:
            detail = self._redact(str(body.get("detail") or body.get("message") or f"HTTP {status_code}"))
            rejected = ConnectorOrderResult(
                order_id=f"rej_{uuid.uuid4().hex[:10]}",
                broker_order_id=None,
                correlation_id=corr_id,
                status="REJECTED",
                lifecycle_state="REJECTED",
                settlement_outcome=None,
                entry_price=0.0,
                exit_price=0.0,
                pnl=0.0,
                message=f"Broker rejected order: {detail}",
            )
            self._orders_by_correlation[corr_id] = rejected
            return rejected

        return self._parse_order_result(
            body=body,
            fallback_order_id=f"unk_{uuid.uuid4().hex[:10]}",
            fallback_correlation_id=corr_id,
        )

    async def get_order_status(self, order_id: str) -> ConnectorOrderResult:
        if not self.connected:
            raise ConnectionError("HTTP broker is disconnected.")
        safe_id = urllib.parse.quote(order_id, safe="")
        status_code, body = await self._request("GET", f"/v1/orders/{safe_id}")
        if status_code == 404:
            raise KeyError(f"Order {order_id} not found on broker.")
        if status_code >= 400:
            raise RuntimeError(self._redact(str(body.get("detail", f"Broker status error HTTP {status_code}"))))
        return self._parse_order_result(body, fallback_order_id=order_id, fallback_correlation_id=None)

    async def get_settlement_result(self, order_id: str) -> ConnectorOrderResult:
        return await self.get_order_status(order_id)

    async def reconcile_order(
        self,
        order_id: str,
        correlation_id: Optional[str] = None,
    ) -> ConnectorOrderResult:
        if not self.connected:
            raise ConnectionError("HTTP broker is disconnected.")
        q = urllib.parse.urlencode({"correlation_id": correlation_id}) if correlation_id else ""
        safe_id = urllib.parse.quote(order_id, safe="")
        path = f"/v1/orders/{safe_id}/reconcile" + (f"?{q}" if q else "")
        status_code, body = await self._request("GET", path)
        if status_code == 404 and correlation_id:
            corr_q = urllib.parse.urlencode({"correlation_id": correlation_id})
            status_code, body = await self._request("GET", f"/v1/orders/by-correlation?{corr_q}")
        if status_code == 404:
            raise KeyError(f"Order {order_id} (correlation={correlation_id}) not found for reconciliation")
        if status_code >= 500:
            raise TimeoutError(f"Broker reconciliation upstream HTTP {status_code}")
        if status_code >= 400:
            raise RuntimeError(self._redact(str(body.get("detail", f"Reconciliation HTTP {status_code}"))))
        return self._parse_order_result(
            body=body,
            fallback_order_id=order_id,
            fallback_correlation_id=correlation_id,
        )
