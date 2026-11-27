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
                self._state = "CONNECTED_READ_ONLY"
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

    def submit_order(self, *_: Any, **__: Any) -> None:
        raise GatewayError("Order submission is disabled in this read-only integration stage.")

    def switch_account(self, *_: Any, **__: Any) -> None:
        raise GatewayError("Account switching is disabled; disconnect before reconnecting.")
