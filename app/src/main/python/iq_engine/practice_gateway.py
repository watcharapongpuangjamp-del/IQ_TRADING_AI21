"""Single-session, bounded, read-only gateway for the optional IQAir client.

This is intentionally not a trading adapter. IQAir 1.0.0 contains blocking waits
and module-global balance selection; every call is isolated on a daemon worker,
serialized, and the session is poisoned after a timeout. A timed-out worker
cannot be forcibly killed by Python, so this gateway never retries/reuses it.
"""
from __future__ import annotations

from dataclasses import dataclass
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

    def __init__(self, client_factory: Callable[..., Any], timeout_seconds: float = 12.0):
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        self._factory = client_factory
        self._timeout = timeout_seconds
        self._lock = threading.Lock()
        self._client: Any | None = None
        self._state = "DISCONNECTED"
        self._poisoned = False

    @property
    def state(self) -> str:
        return self._state

    def _bounded(self, fn: Callable[[], Any], label: str) -> Any:
        result: list[Any] = []
        failure: list[BaseException] = []
        done = threading.Event()

        def run() -> None:
            try:
                result.append(fn())
            except BaseException as exc:  # relay worker exception to caller
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
        return result[0] if result else None

    def connect_readonly(self, email: str, password: str) -> ReadOnlySnapshot:
        if not email or not password:
            raise GatewayError("Credentials are required.")
        with self._lock:
            if self._poisoned:
                raise GatewayError("Session is poisoned; create a new gateway after process restart.")
            if self._state != "DISCONNECTED":
                raise GatewayError("Only one active account session is supported.")
            self._state = "CONNECTING"
            try:
                client = self._factory(email, password)
                connected = self._bounded(client.connect, "connect")
                if isinstance(connected, tuple) and connected and connected[0] is not True:
                    raise GatewayError("Broker rejected the connection.")
                mode = self._bounded(client.get_balance_mode, "account-mode")
                if str(mode).upper() != "PRACTICE":
                    raise GatewayError("Broker did not confirm Practice account; access denied.")
                profile = self._bounded(client.get_profile_ansyc, "profile")
                if not isinstance(profile, dict):
                    raise GatewayError("Broker profile response is malformed.")
                balances = profile.get("balances")
                if not isinstance(balances, list):
                    raise GatewayError("Broker profile has no balance list.")
                selected_id = client.get_balance_id()
                row = next((item for item in balances if str(item.get("id")) == str(selected_id)), None)
                if row is None or row.get("type") != 4:
                    raise GatewayError("Selected balance is not explicitly Practice.")
                amount = self._bounded(client.get_balance, "balance")
                amount = float(amount)
                if amount < 0 or amount != amount or amount in (float("inf"), float("-inf")):
                    raise GatewayError("Broker returned an invalid balance.")
                self._client = client
                self._state = "CONNECTED_READ_ONLY"
                return ReadOnlySnapshot(
                    account_type="PRACTICE",
                    balance=amount,
                    currency=str(row.get("currency") or ""),
                    account_id=str(selected_id),
                )
            except BaseException:
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
                self._state = "DISCONNECTED"

    def submit_order(self, *_: Any, **__: Any) -> None:
        raise GatewayError("Order submission is disabled in the read-only integration stage.")

    def switch_account(self, *_: Any, **__: Any) -> None:
        raise GatewayError("Account switching is disabled; disconnect and restart the session.")
