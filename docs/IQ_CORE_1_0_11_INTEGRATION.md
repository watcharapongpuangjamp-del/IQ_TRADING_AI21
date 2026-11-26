# IQ Core 1.0.11 assessment and IQ_TRADING_AI21 integration boundary

## Scope

The supplied archive is an unofficial client library using IQ Option HTTP/WebSocket endpoints. The patch improves local asynchronous message handling and session cleanup. It does not establish that the broker protocol is officially supported, stable, or authorized for third-party live trading.

## Patch findings and changes

- Start the WebSocket receive loop before authentication/profile requests.
- Keep a bounded per-event backlog so early `profile` / `balances` messages are not lost before a consumer calls `receive()`.
- Register request waiters before sending the request.
- Clear outstanding waiters and close the underlying WebSocket during shutdown.
- Retain the active WebSocket in `AuthService`; `logout()` closes it as well as the HTTP session.
- Add regression tests for early event delivery, request/send races, and close cleanup.

## Android / Chaquopy integration boundary

Do not import this package directly into the production Android trading path yet. Its declared dependencies (`aiohttp`, `websockets`, `cryptography`) and its use of `asyncio` / network transports need a device-level compatibility spike on the target Chaquopy version and Android ABIs. Python networking must run off the Android main thread. Android Keystore-backed token storage should be implemented in Kotlin; do not use this package's file-based `TokenManager` for production credentials.

Recommended Kotlin boundary:

- `BrokerConnector`: connect, disconnect, fetch account snapshot, fetch candles, place order, query order, reconcile open orders.
- `AccountType`: `PRACTICE` / `REAL`.
- `ConnectionState`: `DISCONNECTED`, `CONNECTING`, `AUTH_REQUIRED`, `CONNECTED`, `RECONNECTING`, `ERROR`.
- `AccountSnapshot`: broker account ID (masked in UI/logs), verified account type, currency, balance, available balance, server timestamp, capabilities.
- Persist account selection and order intents locally, but never persist plaintext passwords or session tokens in Room, preferences, logs, or crash reports.

## Mandatory account isolation and live-trading gates

1. Treat the broker-reported account type and balance ID as authoritative. Never infer account type from the UI selector alone.
2. Keep Practice and Real sessions, balance snapshots, open-order state, and journals separate. Switching account is blocked while an order is unresolved.
3. No automatic fallback from Real to Practice or from Practice to Real.
4. On timeout after sending an order, reconcile by broker order ID / client intent before retrying. Never blindly resend an uncertain order.
5. The Kotlin risk gate remains authoritative; Python signals cannot bypass it.
6. Keep Real execution disabled until a broker-authorized integration path is documented, account type is verified from broker responses, and Practice end-to-end tests pass.

## What this patch does not prove

- Successful login against the live IQ Option service.
- Current validity of private endpoints or message schemas.
- Correctness of Practice/Real account switching on the broker.
- Successful order placement or settlement.
- Chaquopy compatibility on Android ARM64 / x86_64.

Those require controlled integration tests with a Practice account and must not be represented as passing based only on unit tests.
