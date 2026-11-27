"""On-device IQAir integration boundary.
This stage exposes Practice account discovery and balance reads only.
No order-placement method is exposed to Kotlin.
"""
from __future__ import annotations
import json
import ssl
import threading
import time
from typing import Any
import certifi
from iqair.api import IQOptionAPI
from iqair.client import IQOptionClient
from iqair.ws.client import WebsocketClient
import iqair.global_value as global_value
from .practice_gateway import PracticeReadOnlyGateway

_TLS_PATCHED = False
_GATEWAY: PracticeReadOnlyGateway | None = None
_GATEWAY_LOCK = threading.Lock()

def _install_verified_tls() -> None:
    """Replace iqair 1.0.0's CERT_NONE defaults before creating any client."""
    global _TLS_PATCHED
    if _TLS_PATCHED:
        return
    original_init = IQOptionAPI.__init__
    def verified_init(self: IQOptionAPI, *args: Any, **kwargs: Any) -> None:
        original_init(self, *args, **kwargs)
        self.session.verify = certifi.where()
    def verified_start_websocket(self: IQOptionAPI) -> tuple[bool, str | None]:
        global_value.check_websocket_if_connect = None
        global_value.check_websocket_if_error = False
        global_value.websocket_error_reason = None
        self.websocket_client = WebsocketClient(self)
        tls = {"cert_reqs": ssl.CERT_REQUIRED, "check_hostname": True, "ca_certs": certifi.where()}
        self.websocket_thread = threading.Thread(
            target=self.websocket.run_forever, kwargs={"sslopt": tls},
            name="iqair-websocket", daemon=True)
        self.websocket_thread.start()
        deadline = time.monotonic() + 15.0
        while time.monotonic() < deadline:
            if global_value.check_websocket_if_error:
                return False, str(global_value.websocket_error_reason or "WebSocket TLS/connect error")
            if global_value.check_websocket_if_connect == 0:
                return False, "WebSocket connection closed."
            if global_value.check_websocket_if_connect == 1:
                return True, None
            time.sleep(0.05)
        try:
            self.websocket.close()
        except Exception:
            pass
        return False, "WebSocket connection timed out."
    IQOptionAPI.__init__ = verified_init
    IQOptionAPI.start_websocket = verified_start_websocket
    _TLS_PATCHED = True

def _get_gateway() -> PracticeReadOnlyGateway:
    global _GATEWAY
    with _GATEWAY_LOCK:
        if _GATEWAY is None:
            _install_verified_tls()
            _GATEWAY = PracticeReadOnlyGateway(IQOptionClient, timeout_seconds=20.0)
        return _GATEWAY

def connect_practice_readonly_json(email: str, password: str) -> str:
    try:
        snapshot = _get_gateway().connect_readonly(email, password)
        payload = {"ok": True, "state": "CONNECTED_READ_ONLY", "accountType": snapshot.account_type,
                   "balance": snapshot.balance, "currency": snapshot.currency, "accountId": snapshot.account_id,
                   "ordersEnabled": False, "tlsVerification": "ENABLED"}
    except Exception as exc:
        payload = {"ok": False, "state": _GATEWAY.state if _GATEWAY is not None else "ERROR",
                   "error": str(exc)[:240], "ordersEnabled": False}
    return json.dumps(payload, separators=(",", ":"), allow_nan=False)

def disconnect_json() -> str:
    try:
        _get_gateway().disconnect()
        payload = {"ok": True, "state": _get_gateway().state, "ordersEnabled": False}
    except Exception as exc:
        payload = {"ok": False, "state": _GATEWAY.state if _GATEWAY else "ERROR",
                   "error": str(exc)[:240], "ordersEnabled": False}
    return json.dumps(payload, separators=(",", ":"))

def adapter_status_json() -> str:
    return json.dumps({"provider": "iqair", "version": "1.0.0",
        "state": _GATEWAY.state if _GATEWAY is not None else "DISCONNECTED",
        "accountMode": "PRACTICE_ONLY", "readOnly": True, "ordersEnabled": False,
        "tlsVerification": "ENABLED"}, separators=(",", ":"))
