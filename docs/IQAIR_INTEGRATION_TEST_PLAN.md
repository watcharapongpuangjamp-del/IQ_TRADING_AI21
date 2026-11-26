# IQAir 1.0.0 Integration & Device Test Plan

## Current status

The app branch contains the on-device CPython/Chaquopy scaffold and Kotlin account authorization boundary. This change adds a **broker-payload contract test**, not a live IQ Option connection. IQAir 1.0.0 is an unofficial client for an undocumented WebSocket API and must not be treated as an authorized production integration.

## What this test suite verifies

- Broker profile must identify the selected balance.
- IQAir balance type `4` must be explicitly reported before a Practice snapshot is accepted.
- A balance record must match the selected profile balance ID.
- Currency, account ID, numeric balance and available balance must be valid.
- Real, unknown, missing, mismatched or malformed account data fails closed.

## Known blockers before live adapter enablement

The supplied IQAir 1.0.0 source uses module-global `iqair.global_value.balance_id`. Multiple client instances can therefore interfere with each other. Several methods also contain unbounded busy-wait loops, including portions of `connect()`, `get_balances()`, and `reset_practice_balance()`. Do not call these methods on Android's main thread or expose them to the UI until the fork has bounded timeouts, deterministic shutdown, and single-session ownership.

The package stores the email/password on the client object. Android integration must resolve credentials from Android Keystore-backed storage at runtime and must never persist them in source, preferences, logs, crash reports, or CI.

## Test stages

1. **CI / host tests:** Python unit tests and Kotlin unit tests; build Debug APK.
2. **Physical ARM64 runtime:** install Debug APK, verify embedded CPython starts, call `iq_engine.engine.engine_status`, and run candle validation offline.
3. **Practice read-only integration:** only after the IQAir fork is patched for bounded waits and global session isolation; verify broker-reported account type and balance against the Practice account shown in the official app.
4. **Practice order test:** separate explicit opt-in test, minimal test stake, broker-reported Practice confirmation immediately before order submission, idempotency key, order status polling with timeout, and post-trade reconciliation. Never run this in ordinary CI.
5. **Real trading:** disabled. No real-account adapter or order path is included in this test scope.

## Device test record

Record device model, Android version, ABI, APK commit SHA, Python runtime status, connection outcome, broker-reported account type, balance timestamp, reconnect outcome, and test result. Redact account identifiers and never attach credentials or session cookies.
