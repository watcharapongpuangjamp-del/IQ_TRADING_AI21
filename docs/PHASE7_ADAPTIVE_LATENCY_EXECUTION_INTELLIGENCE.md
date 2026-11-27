# Phase 7 — Adaptive Latency & Execution Intelligence

Status: implementation specification / first implementation checkpoint  
Target branch: `feature/phase7-adaptive-latency-intelligence`  
Base: `cef397638ca9145ddacd295c610b301488e99a14`  
Scope: local-first telemetry, latency analysis, simulation calibration, and risk advisories. This phase must not enable REAL-account trading.

## 1. Goals

1. Measure network-to-backend latency independently from broker latency.
2. Measure broker request/response and order lifecycle timings using monotonic clocks.
3. Maintain one authoritative telemetry pipeline; Android displays and consumes its state rather than duplicating broker probes.
4. Persist telemetry locally and expose summaries to Android.
5. Calibrate simulation timing from observed distributions (P50/P95/jitter), never from a single average.
6. Keep broker orders, simulation orders, and their balances strictly separated.
7. Fail closed when broker connectivity or order state is uncertain.

## 2. Existing-code findings that constrain implementation

- Android already has `TradingViewModel`, `BackendConnectorClient`, `BackendHealthState`, and WebSocket state in `TerminalUiState`. Reuse these integration points; do not create a second Android heartbeat loop.
- Backend FastAPI is created in `backend/app/main.py`; connector construction is centralized there.
- `backend/app/connectors/iqoption_broker.py` currently contains simulated behavior: `connect()` sets a local flag, market data/candles are randomized, and order outcomes are randomized. It is not evidence of a live IQ Option session or live Practice order execution.
- SQLite/SQLAlchemy is already used by the backend and Android Room is version 4. Add migrations; do not reset either database.
- The current IQ Option connector reports REAL mode as `live_authorized`; this must not be treated as proof of a real broker connection. Keep live authorization disabled until a separately verified, supported broker implementation passes explicit integration tests.

## 3. Measurement model

Use UTC wall-clock timestamps for persisted event time and a monotonic clock for elapsed durations. Never calculate elapsed latency by subtracting wall-clock timestamps.

| Metric | Start | End | Source |
|---|---|---|---|
| App-to-backend RTT | Android request send | backend response received | Android monotonic clock |
| Backend processing | request accepted | response emitted | backend monotonic clock |
| Broker RTT | broker request send | broker response received | backend monotonic clock |
| Order acknowledgment | submit request send | broker acceptance/ID received | backend monotonic clock |
| Order settlement | broker accepted | broker final settlement event | broker event timestamps, if provided |
| Reconnect duration | connection lost detected | session verified and reconciled | backend monotonic clock |

ICMP ping is optional diagnostic data only. It may be unavailable on Android networks and does not represent broker latency. Prefer a lightweight authenticated backend health probe for app-to-backend RTT. Measure broker RTT only through a documented non-trading operation or native WebSocket ping/pong supported by the connector. Do not generate dummy orders to measure latency.

## 4. Sampling and aggregation

- Health probe target interval: 5 seconds while the app is foregrounded and a backend session is active. Respect Android lifecycle/network constraints; do not promise exact 5-second scheduling in background.
- Broker heartbeat: use protocol-native ping/pong or a safe read-only request; apply timeout and bounded exponential backoff after repeated failures.
- Record successful and failed samples, timeout counts, packet loss where measurable, reconnect duration, and sample source.
- Maintain rolling windows (for example 5-minute and 1-hour) with count, min, median/P50, P95, max, jitter, and timeout rate.
- Mark a metric unavailable when there are insufficient samples. Never substitute fabricated values.
- Tag every sample with connector type, connection generation, account mode (PRACTICE/REAL/UNKNOWN), and source. Do not persist credentials, tokens, or account secrets.

## 5. Connection state machine

States: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `DEGRADED`, `RECONNECTING`, `RECONCILING`.

- `CONNECTED` requires a verified connector session and a recent successful broker health response, not merely a local boolean.
- Transition to `DEGRADED` after configurable consecutive timeouts or excessive latency; transition to `RECONNECTING` when the session is lost.
- On broker disconnect, disable new broker submissions immediately.
- Simulation may continue only as an explicitly separate simulation session with its own balance, order IDs, and journal mode.
- Never represent a simulated order or balance as a broker order or broker balance.
- On reconnect, enter `RECONCILING`; verify account mode and balance, query unresolved orders, and reconcile each order before permitting new broker orders.
- If an order may have reached the broker but its acknowledgment was lost, preserve `UNKNOWN` and reconcile. Never blindly resubmit.
- Return to `CONNECTED` only after session verification and reconciliation succeed.

## 6. Simulation calibration

- Store a versioned latency profile from real observed samples: network RTT, broker RTT, order acknowledgment distribution, jitter, and timeout rate.
- Use empirical distributions or P50/P95 buckets rather than a single fixed average. Include sample count, measurement period, connector/account mode, and confidence/quality flag.
- Apply artificial delay and packet loss only inside Simulation/Replay. Never inject delay into live Practice or REAL order submission.
- Keep simulation outcome generation independent from latency. Latency must not manufacture wins/losses or alter broker results.
- AI may use connection/execution quality as a separate operational risk feature. Do not directly rewrite model win probability based on ping.

## 7. Local persistence

Add SQLAlchemy tables through an idempotent migration:
- `latency_samples`: id, recorded_at_utc, metric_name, duration_ms, success, timeout, source, connector, account_mode, connection_generation, metadata_json.
- `latency_profiles`: id, created_at_utc, window_start_utc, window_end_utc, connector, account_mode, sample_count, p50_ms, p95_ms, max_ms, jitter_ms, timeout_rate, profile_version.

Add Android Room entities only if the Android UI needs offline history/cache. If added, increment Room schema version and provide a non-destructive migration. The backend local SQLite database remains the authoritative aggregation store; Android should not independently probe the broker.

## 8. API contract proposal

- `GET /api/v1/connection/status`: connection state, connector name, account mode, last successful heartbeat, current generation, and whether new broker orders are allowed.
- `GET /api/v1/latency/summary?window=5m|1h`: metrics, sample counts, P50/P95, jitter, timeout rate, and freshness.
- `GET /api/v1/latency/history?metric=...`: bounded historical samples for charting.
- `GET /api/v1/latency/profile`: active versioned simulation profile.
- `POST /api/v1/simulation/latency-profile`: set a test profile; reject unless execution mode is SIMULATION.
All endpoints must follow existing authentication conventions and must not expose secrets.

## 9. Android UI

Add a Connection/Latency panel to the existing terminal:
- Broker state and account mode with explicit source labels.
- App-to-backend RTT, broker RTT, order acknowledgment P50/P95, jitter, timeout rate, last sample time.
- Clear `SIMULATION MODE` banner whenever simulation is active.
- Show `UNKNOWN / RECONCILING` for uncertain orders; do not show success until broker confirmation.
- No fabricated placeholder numbers; show “ยังไม่มีข้อมูล” / unavailable until measured.

## 10. Test plan and acceptance criteria

1. Unit tests for monotonic timing, aggregation, percentile calculation, jitter, timeout rate, and insufficient sample handling.
2. State-machine tests for disconnect, degraded state, reconnect, failed reconciliation, and successful reconciliation.
3. Verify no broker submission is possible while disconnected, degraded beyond policy, or reconciling.
4. Verify unknown orders are not resubmitted.
5. Verify simulation delay/packet loss affects simulation only and never the broker connector.
6. Verify Practice and Simulation balances/order journals never mix.
7. API tests for auth, response schemas, bounded history, and invalid simulation profile requests.
8. Android tests for state labels, stale-data indicator, simulation banner, and no-data rendering.
9. Migration tests from existing backend SQLite schema and Android Room v4 without data loss.
10. Hardware/network integration test with a verified supported Practice connector before any claim of real broker latency or order execution.

Acceptance: all tests pass; latency metrics carry source and freshness; no duplicate monitoring loops; no live-order side effects from measurement; and unresolved orders remain blocked until reconciled.

## 11. Out of scope

- Implementing or claiming a working live IQ Option protocol adapter in this phase.
- REAL-account authorization.
- Automatically increasing order size or changing strategy direction based on latency.
- Sending test trades solely to measure latency.
- Cloud persistence or Firebase; data remains local-first.
