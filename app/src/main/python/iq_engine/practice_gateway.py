"""Single-session, bounded, read-only gateway for IQAir.

IQAir 1.0.0 contains blocking waits and module-global balance selection.
Every broker call is isolated on a daemon worker and the session is poisoned
after a timeout because Python cannot safely terminate a blocked worker.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
import threading
from typing import Any, Callable


class GatewayError(RuntimeError):
    pass


@dataclass(frozen=True)
class ReadOnlySnapshot:
    account_type: str
    balance: float
    currency: str
    account_id: str


class PracticeReadOnlyGateway:
    """One active IQAir session, Practice only, read-only, with hard caller timeout."""

    def __init__(self, client_factory: Callable[..., Any], timeout_seconds: float = 20.0):
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        self._factory = client_factory
        self._timeout = timeout_seconds
        self._lock = threading.Lock()
        self._client: Any | None = None
        self._state = "DISCONNECTED"
        self._poisoned = False
        self._pending_order_id: str | None = None

    @property
    def state(self) -> str:
        with self._lock:
            return self._state

    def _bounded(self, fn: Callable[[], Any], label: str) -> Any:
        result: list[Any] = []
        failure: list[Exception] = []
        done = threading.Event()

        def run() -> None:
            try:
                result.append(fn())
            except Exception as exc:
                failure.append(exc)
            finally:
                done.set()

        worker = threading.Thread(target=run, name="iqair-" + label, daemon=True)
        worker.start()
        if not done.wait(self._timeout):
            self._poisoned = True
            self._state = "POISONED"
            raise GatewayError(f"{label} timed out; session is poisoned and cannot be reused")
        if failure:
            raise GatewayError(f"{label} failed: {type(failure[0]).__name__}") from failure[0]
        if not result:
            raise GatewayError(f"{label} returned no result")
        return result[0]

    @staticmethod
    def _connection_confirmed(result: Any) -> bool:
        return result is True or (isinstance(result, tuple) and len(result) >= 1 and result[0] is True)

    def _close_partial_client(self, client: Any) -> None:
        if self._poisoned:
            return
        close = getattr(getattr(client, "api", None), "close", None)
        if callable(close):
            try:
                self._bounded(close, "cleanup")
            except GatewayError:
                self._poisoned = True
                self._state = "POISONED"

    def connect_readonly(self, email: str, password: str) -> ReadOnlySnapshot:
        if not isinstance(email, str) or not email.strip() or not isinstance(password, str) or not password:
            raise GatewayError("Credentials are required.")
        with self._lock:
            if self._poisoned:
                raise GatewayError("Session is poisoned; restart the app before retrying.")
            if self._state != "DISCONNECTED":
                raise GatewayError("Only one active account session is supported.")

            self._state = "CONNECTING"
            client: Any | None = None
            try:
                client = self._factory(email, password)
                connected = self._bounded(client.connect, "connect")
                if not self._connection_confirmed(connected):
                    raise GatewayError("Broker did not explicitly confirm connection success.")

                # Explicitly select Practice before reading balance. Never trust
                # whichever balance happened to be selected by a prior session.
                self._bounded(lambda: client.change_balance("PRACTICE"), "select-practice")
                mode = self._bounded(client.get_balance_mode, "account-mode")
                if str(mode).strip().upper() != "PRACTICE":
                    raise GatewayError("Broker did not confirm Practice account; access denied.")

                profile = self._bounded(client.get_profile_ansyc, "profile")
                if not isinstance(profile, dict) or not isinstance(profile.get("balances"), list):
                    raise GatewayError("Broker profile response is malformed.")

                selected_id = self._bounded(client.get_balance_id, "selected-account-id")
                if selected_id is None or str(selected_id).strip() == "":
                    raise GatewayError("Broker did not identify the selected account balance.")
                row = next(
                    (item for item in profile["balances"]
                     if isinstance(item, dict) and str(item.get("id")) == str(selected_id)),
                    None,
                )
                if row is None or type(row.get("type")) is not int or row["type"] != 4:
                    raise GatewayError("Selected balance is not explicitly Practice.")
                currency = row.get("currency")
                if not isinstance(currency, str) or not currency.strip():
                    raise GatewayError("Broker did not provide a valid account currency.")

                amount = self._bounded(client.get_balance, "balance")
                try:
                    amount = float(amount)
                except (TypeError, ValueError, OverflowError) as exc:
                    raise GatewayError("Broker returned a non-numeric balance.") from exc
                if not isfinite(amount) or amount < 0:
                    raise GatewayError("Broker returned an invalid balance.")

                self._client = client
                self._state = "CONNECTED_PRACTICE"
                return ReadOnlySnapshot("PRACTICE", amount, currency.strip(), str(selected_id))
            except Exception:
                if client is not None and not self._poisoned:
                    self._close_partial_client(client)
                if not self._poisoned:
                    self._state = "ERROR"
                raise

    def disconnect(self) -> None:
        with self._lock:
            client = self._client
            self._client = None
            if self._poisoned:
                self._state = "POISONED"
                return
            if client is None:
                self._state = "DISCONNECTED"
                return
            try:
                self._bounded(client.api.close, "disconnect")
            finally:
                if not self._poisoned:
                    self._state = "DISCONNECTED"

    def force_reconnect(self) -> None:
        """Close this app's owned session and reset recoverable local gateway state."""
        with self._lock:
            if self._poisoned or self._state == "POISONED":
                raise GatewayError("Session is poisoned; restart the app before retrying.")
            client = self._client
            self._client = None
            if client is not None:
                self._bounded(client.api.close, "force-reconnect-disconnect")
            self._state = "DISCONNECTED"


    def submit_practice_order(
        self, asset: str, direction: str, amount: float, expiry_minutes: int
    ) -> dict[str, Any]:
        """Place and settle one Turbo Practice order; ambiguous outcomes lock trading."""
        from iqair.broker import TradeMode, TradeSide

        if not isinstance(asset, str) or not asset.strip():
            raise GatewayError("Asset is required.")
        direction = str(direction).strip().upper()
        if direction not in {"CALL", "PUT"}:
            raise GatewayError("Direction must be CALL or PUT.")
        try:
            amount = float(amount)
        except (TypeError, ValueError, OverflowError) as exc:
            raise GatewayError("Stake must be numeric.") from exc
        if not isfinite(amount) or amount <= 0:
            raise GatewayError("Stake must be greater than zero.")
        if type(expiry_minutes) is not int or expiry_minutes not in {1, 5}:
            raise GatewayError("Practice test supports 1- or 5-minute expiry only.")

        with self._lock:
            if self._poisoned:
                raise GatewayError("Session is poisoned; restart the app before retrying.")
            if self._pending_order_id is not None:
                raise GatewayError(
                    "An order has an unresolved outcome; reconcile it before another order."
                )
            if self._state != "CONNECTED_PRACTICE":
                raise GatewayError("A verified Practice session is required.")
            client = self._client
            if client is None:
                raise GatewayError("Practice client is not connected.")

            mode = self._bounded(client.get_balance_mode, "pre-order-account-mode")
            if str(mode).strip().upper() != "PRACTICE":
                raise GatewayError("Practice verification failed; order blocked.")
            balance_before = float(self._bounded(client.get_balance, "pre-order-balance"))
            if not isfinite(balance_before) or balance_before < amount:
                raise GatewayError("Stake exceeds the verified Practice balance.")

            side = TradeSide.CALL if direction == "CALL" else TradeSide.PUT
            try:
                trade = self._bounded(
                    lambda: client.broker.trade(
                        mode=TradeMode.TURBO,
                        asset=asset.strip(),
                        side=side,
                        amount=amount,
                        expiration=expiry_minutes,
                    ),
                    "place-practice-order",
                )
            except Exception:
                # A timeout/error can occur after the broker accepted the order.
                self._pending_order_id = "UNKNOWN"
                self._state = "ORDER_UNKNOWN"
                raise

            order_id = str(getattr(trade, "order_id", "") or "")
            if not order_id:
                self._pending_order_id = "UNKNOWN"
                self._state = "ORDER_UNKNOWN"
                raise GatewayError("Broker returned no order ID; trading is locked.")

            self._pending_order_id = order_id
            self._state = "ORDER_PENDING"
            try:
                settled = self._bounded(
                    lambda: trade.wait(timeout=expiry_minutes * 60 + 45, poll_interval=1),
                    "wait-practice-settlement",
                )
                state_obj = getattr(settled, "trade_state", None)
                broker_state = str(getattr(state_obj, "value", state_obj)).lower()
                if broker_state not in {"won", "lost", "sold", "expired"}:
                    raise GatewayError("Broker has not confirmed a terminal result.")

                pnl_value = getattr(settled, "pnl_net", None)
                if pnl_value is None:
                    pnl_value = getattr(settled, "pnl", None)
                if pnl_value is None:
                    raise GatewayError("Broker settlement omitted realized P&L.")
                pnl = float(pnl_value)
                if not isfinite(pnl):
                    raise GatewayError("Broker returned invalid realized P&L.")

                balance_after = float(self._bounded(client.get_balance, "post-order-balance"))
                if not isfinite(balance_after) or balance_after < 0:
                    raise GatewayError("Broker returned invalid post-trade balance.")

                if broker_state == "won":
                    outcome = "WIN"
                elif broker_state == "lost":
                    outcome = "LOSS"
                elif abs(pnl) < 1e-9:
                    outcome = "DRAW"
                else:
                    outcome = "WIN" if pnl > 0 else "LOSS"

                result = {
                    "ok": True,
                    "orderId": order_id,
                    "asset": asset.strip(),
                    "direction": direction,
                    "amount": amount,
                    "expiryMinutes": expiry_minutes,
                    "brokerState": broker_state.upper(),
                    "outcome": outcome,
                    "pnl": pnl,
                    "balanceBefore": balance_before,
                    "balanceAfter": balance_after,
                    "balanceDelta": balance_after - balance_before,
                    "accountType": "PRACTICE",
                    "source": "BROKER_CONFIRMED",
                }
                self._pending_order_id = None
                self._state = "CONNECTED_PRACTICE"
                return result
            except Exception:
                # Never auto-retry an order with an unknown settlement outcome.
                self._state = "ORDER_UNKNOWN"
                raise

    def submit_order(self, *_: Any, **__: Any) -> None:
        raise GatewayError("Use submit_practice_order with explicit Practice verification.")


    def switch_account(self, *_: Any, **__: Any) -> None:
        raise GatewayError("Account switching is disabled; disconnect before reconnecting.")
