"""IQ Option connector backed by the user-supplied iqair 1.0.0 package.

Safety contract:
- This connector is Practice-only. It never selects or submits to REAL.
- The broker session is considered connected only after login, explicit PRACTICE
  selection, profile verification, and a readable Practice balance.
- The iqair client is synchronous; all network calls run in worker threads.
"""
import asyncio
import math
import time
from typing import Any, Dict, List, Optional

from app.connectors.base import BrokerConnector, ConnectorOrderResult

try:
    from iqair.client import IQOptionClient
    from iqair.broker import TradeMode, TradeSide, Trade
    from iqair.models import TradeState
except ImportError as exc:  # pragma: no cover - exercised in deployment config
    IQOptionClient = None
    TradeMode = TradeSide = Trade = TradeState = None
    _IQAIR_IMPORT_ERROR = exc
else:
    _IQAIR_IMPORT_ERROR = None


_TIMEFRAME_SECONDS = {"1m": 60, "5m": 300, "15m": 900}
_INSTRUMENT_TYPES = (
    "turbo-option", "binary-option", "digital-option",
    "marginal-forex", "marginal-cfd", "marginal-crypto",
)


class IQOptionBrokerConnector(BrokerConnector):
    """Practice-only adapter for iqair's IQOptionClient."""

    def __init__(
        self,
        email: str = "",
        password: str = "",
        balance_mode: str = "PRACTICE",
        initial_balance: float = 0.0,
        timeout_seconds: float = 20.0,
    ) -> None:
        requested_mode = (balance_mode or "PRACTICE").strip().upper()
        if requested_mode != "PRACTICE":
            raise ValueError("IQ Option connector is Practice-only; REAL account mode is locked.")
        self._email = email.strip()
        self._password = password
        self._timeout = max(3.0, float(timeout_seconds))
        self._client = None
        self._connected = False
        self._verified = False
        self._balance = float(initial_balance)
        self._account_mode = "DISCONNECTED"
        self._orders: Dict[str, Dict[str, Any]] = {}
        self._lock = asyncio.Lock()

    @property
    def name(self) -> str:
        return "IQ Option / iqair (PRACTICE)"

    @property
    def connected(self) -> bool:
        return bool(self._connected and self._verified and self._client is not None)

    @property
    def session_verified(self) -> bool:
        return self.connected

    @property
    def account_mode(self) -> str:
        return self._account_mode

    @property
    def live_authorized(self) -> bool:
        # Deliberately false, including when server settings are misconfigured.
        return False

    def get_capabilities(self) -> List[str]:
        return [
            "IQOPTION_UNOFFICIAL_API",
            "PRACTICE_ACCOUNT_ONLY",
            "DEMO_ORDERS",
            "BROKER_BALANCE",
            "BROKER_CANDLES",
            "TURBO_OPTIONS",
            "BINARY_OPTIONS",
            "ASSET_DIRECTORY",
            "POSITIONS_AND_HISTORY",
            "BROKER_SETTLEMENT_RECONCILIATION",
            "REAL_ACCOUNT_LOCKED",
        ]

    async def _run(self, fn, *args):
        return await asyncio.wait_for(asyncio.to_thread(fn, *args), timeout=self._timeout)

    def _require_client(self):
        if not self.connected:
            raise ConnectionError("IQ Option Practice session is not verified.")
        return self._client

    def _verify_practice_sync(self) -> float:
        client = self._client
        if client is None or not client.check_connect():
            raise ConnectionError("IQ Option session is not connected.")
        # Re-select Practice before any broker operation. Never pass REAL.
        client.change_balance("PRACTICE")
        actual_mode = client.get_balance_mode()
        if actual_mode != "PRACTICE":
            raise PermissionError(f"Practice account verification failed (reported mode: {actual_mode!r}).")
        balance = client.get_balance()
        if balance is None or not math.isfinite(float(balance)):
            raise RuntimeError("IQ Option did not return a valid Practice balance.")
        self._account_mode = "PRACTICE"
        self._balance = float(balance)
        return self._balance

    async def connect(self) -> bool:
        if _IQAIR_IMPORT_ERROR is not None:
            raise RuntimeError("iqair==1.0.0 is missing. Install backend requirements.") from _IQAIR_IMPORT_ERROR
        if not self._email or not self._password:
            raise ValueError("IQOPTION_EMAIL and IQOPTION_PASSWORD must be configured locally.")
        async with self._lock:
            self._connected = False
            self._verified = False
            self._account_mode = "CONNECTING"
            self._client = IQOptionClient(self._email, self._password)
            try:
                ok, reason = await self._run(self._client.connect)
                if not ok:
                    raise ConnectionError(f"IQ Option login failed: {reason}")
                balance = await self._run(self._verify_practice_sync)
                if balance < 0:
                    raise RuntimeError("IQ Option returned an invalid Practice balance.")
                self._connected = True
                self._verified = True
                return True
            except Exception:
                try:
                    await self._run(self._client.api.close)
                except Exception:
                    pass
                self._client = None
                self._connected = False
                self._verified = False
                self._account_mode = "DISCONNECTED"
                raise

    async def disconnect(self) -> None:
        client = self._client
        self._connected = False
        self._verified = False
        self._account_mode = "DISCONNECTED"
        self._client = None
        if client is not None:
            try:
                await self._run(client.api.close)
            except Exception:
                pass

    @staticmethod
    def _ticker(instrument: str) -> str:
        return instrument.replace("/", "").replace(" ", "").upper()

    async def _assert_practice(self):
        self._require_client()
        mode = await self._run(self._client.get_balance_mode)
        if mode != "PRACTICE":
            # Attempt to recover only by explicitly selecting Practice.
            await self._run(self._client.change_balance, "PRACTICE")
            mode = await self._run(self._client.get_balance_mode)
        if mode != "PRACTICE":
            self._connected = False
            self._verified = False
            self._account_mode = "UNVERIFIED"
            raise PermissionError("Refusing broker operation: account mode is not verified as PRACTICE.")

    async def get_balance(self) -> float:
        await self._assert_practice()
        balance = await self._run(self._client.get_balance)
        if balance is None or not math.isfinite(float(balance)):
            raise RuntimeError("Invalid balance returned by IQ Option.")
        self._balance = float(balance)
        return round(self._balance, 2)

    async def get_asset_metadata(self) -> Dict[str, Any]:
        """Return iqair's unified six-category asset directory (read-only)."""
        await self._assert_practice()
        metadata = await self._run(self._client.get_asset_metadata)
        if not isinstance(metadata, dict):
            raise RuntimeError("IQ Option returned an invalid asset directory.")
        return metadata

    async def get_market_data(self, instrument: str) -> Dict[str, float]:
        candles = await self.get_candles(instrument, "1m", limit=2)
        if not candles:
            raise RuntimeError(f"No broker candle data returned for {instrument}.")
        latest = candles[-1]
        return {
            "price": float(latest["close"]),
            "timestamp": float(latest["timestamp"]),
            "source": "iqair_practice_market_data",
        }

    async def get_candles(self, instrument: str, timeframe: str, limit: int = 80) -> List[Dict[str, float]]:
        await self._assert_practice()
        if timeframe not in _TIMEFRAME_SECONDS:
            raise ValueError(f"Unsupported timeframe: {timeframe}")
        ticker = self._ticker(instrument)
        endtime = int(time.time())
        raw = await self._run(
            self._client.get_candles,
            ticker,
            _TIMEFRAME_SECONDS[timeframe],
            int(limit),
            endtime,
        )
        if not isinstance(raw, list):
            raise RuntimeError(f"Unexpected candle response for {ticker}.")
        candles = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            timestamp = item.get("from", item.get("to", item.get("at")))
            if timestamp is None:
                continue
            # iqair returns candle window times in epoch seconds.
            ts = int(timestamp)
            candles.append({
                "timestamp": ts * 1000,
                "open": float(item["open"]),
                "high": float(item.get("max", item.get("high"))),
                "low": float(item.get("min", item.get("low"))),
                "close": float(item["close"]),
                "volume": float(item.get("volume") or 0.0),
            })
        candles.sort(key=lambda row: row["timestamp"])
        if not candles:
            raise RuntimeError(f"No valid candles returned for {ticker}.")
        return candles[-limit:]

    def _place_option_sync(self, instrument: str, direction: str, amount: float, expiry_minutes: int):
        self._verify_practice_sync()
        mode = TradeMode.TURBO if expiry_minutes <= 5 else TradeMode.BINARY
        side = TradeSide.CALL if direction == "CALL" else TradeSide.PUT
        return self._client.broker.trade(
            mode=mode,
            asset=self._ticker(instrument),
            side=side,
            amount=amount,
            expiration=expiry_minutes,
        )

    async def submit_order(
        self,
        instrument: str,
        timeframe: str,
        direction: str,
        amount: float,
        expiry_seconds: int,
        correlation_id: Optional[str] = None,
    ) -> ConnectorOrderResult:
        await self._assert_practice()
        if direction not in ("CALL", "PUT"):
            raise ValueError("IQ Option options execution accepts CALL or PUT only.")
        if amount <= 0 or amount > await self.get_balance():
            raise ValueError("Invalid stake or insufficient Practice balance.")
        if expiry_seconds < 60 or expiry_seconds > 3600 or expiry_seconds % 60:
            raise ValueError("Option expiry must be 60-3600 seconds in whole-minute increments.")
        market = await self.get_market_data(instrument)
        expiry_minutes = expiry_seconds // 60
        try:
            trade = await self._run(
                self._place_option_sync, instrument, direction, float(amount), expiry_minutes
            )
        except TimeoutError as exc:
            # Submission may have reached the broker. Caller must reconcile; never retry blindly.
            raise TimeoutError(f"IQ Option acknowledgement timed out; reconciliation required: {exc}") from exc
        broker_order_id = str(trade.order_id)
        local_order_id = f"iqair_{broker_order_id}"
        self._orders[local_order_id] = {
            "broker_order_id": broker_order_id,
            "mode": trade.mode,
            "instrument": instrument,
            "entry_price": float(market["price"]),
            "correlation_id": correlation_id,
        }
        return ConnectorOrderResult(
            order_id=local_order_id,
            broker_order_id=broker_order_id,
            correlation_id=correlation_id or local_order_id,
            status="PENDING",
            lifecycle_state="PENDING",
            entry_price=float(market["price"]),
            exit_price=0.0,
            pnl=0.0,
            message="Order accepted by IQ Option Practice; awaiting broker settlement.",
        )

    def _find_trade_sync(self, broker_order_id: str, preferred_mode=None):
        self._verify_practice_sync()
        modes = [preferred_mode] if preferred_mode is not None else [
            TradeMode.TURBO, TradeMode.BINARY, TradeMode.DIGITAL,
            TradeMode.FOREX, TradeMode.CFD, TradeMode.CRYPTO,
        ]
        for mode in modes:
            try:
                trade = Trade(self._client.broker, order_id=int(broker_order_id), mode=mode, asset=None)
                snapshot = trade.refresh()
                if snapshot is not None:
                    return mode, snapshot
            except Exception:
                continue
        return None, None

    async def _lookup_order(self, order_id: str):
        key = order_id if order_id in self._orders else next(
            (k for k, v in self._orders.items() if v["broker_order_id"] == str(order_id)), order_id
        )
        meta = self._orders.get(key, {})
        broker_id = str(meta.get("broker_order_id", order_id))
        mode = meta.get("mode")
        found_mode, snapshot = await self._run(self._find_trade_sync, broker_id, mode)
        return key, meta, found_mode, snapshot

    async def get_order_status(self, order_id: str) -> ConnectorOrderResult:
        await self._assert_practice()
        key, meta, mode, snapshot = await self._lookup_order(order_id)
        if snapshot is None:
            return ConnectorOrderResult(
                order_id=key,
                broker_order_id=str(meta.get("broker_order_id", order_id)),
                correlation_id=meta.get("correlation_id"),
                status="UNKNOWN",
                lifecycle_state="UNKNOWN",
                entry_price=float(meta.get("entry_price", 0.0)),
                message="Order not yet visible in broker positions/history; reconciliation required.",
            )
        state = snapshot.trade_state
        if state in (TradeState.WON, TradeState.LOST, TradeState.SOLD, TradeState.EXPIRED):
            pnl_value = snapshot.pnl_net if snapshot.pnl_net is not None else snapshot.pnl
            pnl = float(pnl_value or 0.0)
            if state == TradeState.WON:
                outcome = "WON"
            elif state == TradeState.LOST:
                outcome = "LOST"
            else:
                # SOLD/EXPIRED positions are classified from broker-reported net P&L.
                outcome = "WON" if pnl > 0 else "LOST"
            return ConnectorOrderResult(
                order_id=key,
                broker_order_id=str(meta.get("broker_order_id", order_id)),
                correlation_id=meta.get("correlation_id"),
                status=outcome,
                lifecycle_state="SETTLED",
                settlement_outcome=outcome,
                entry_price=float(meta.get("entry_price", 0.0)),
                exit_price=float(snapshot.current_price or 0.0),
                pnl=pnl,
                message=f"Settlement confirmed by IQ Option Practice ({state.value}).",
            )
        return ConnectorOrderResult(
            order_id=key,
            broker_order_id=str(meta.get("broker_order_id", order_id)),
            correlation_id=meta.get("correlation_id"),
            status="PENDING",
            lifecycle_state="PENDING",
            entry_price=float(meta.get("entry_price", 0.0)),
            message="Position remains open at IQ Option Practice.",
        )

    async def get_settlement_result(self, order_id: str) -> ConnectorOrderResult:
        return await self.get_order_status(order_id)

    async def reconcile_order(
        self,
        order_id: str,
        correlation_id: Optional[str] = None,
    ) -> ConnectorOrderResult:
        result = await self.get_order_status(order_id)
        if result.lifecycle_state == "UNKNOWN" and correlation_id:
            # No blind resubmission: broker lookup remains read-only.
            result = ConnectorOrderResult(
                order_id=result.order_id,
                broker_order_id=result.broker_order_id,
                correlation_id=correlation_id,
                status="UNKNOWN",
                lifecycle_state="UNKNOWN",
                entry_price=result.entry_price,
                message="Broker has not exposed this order yet; do not resubmit automatically.",
            )
        return result
