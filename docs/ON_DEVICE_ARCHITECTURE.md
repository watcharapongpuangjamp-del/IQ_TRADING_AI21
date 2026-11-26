# IQ Trading AI — On-Device Architecture (Option B)

## Goal

Run the UI, local database, technical analysis, strategy evaluation, risk checks,
and Python inference inside the Android APK. No FastAPI server or Replit
deployment is required during normal on-device operation.

Android remains the application and lifecycle owner. Embedded CPython is an
execution component, not a second application server.

## Verified current repository state

- Android uses Kotlin, Jetpack Compose, Room/SQLite, Retrofit and OkHttp.
- The Local-Only profile is the local source of truth.
- Android currently contains a FastAPI client for optional external backend calls.
- backend/app/main.py constructs SimulationConnector directly; this is not a
  real IQ Option connector.
- The Python backend depends on FastAPI, Uvicorn, SQLAlchemy, NumPy,
  scikit-learn and joblib. Do not copy the server dependency set wholesale
  into the APK.

## Target component ownership

| Component | Runtime | Responsibility |
|---|---|---|
| UI, lifecycle, permissions | Kotlin Android | Screens, notifications, service lifecycle |
| Local persistence | Room / SQLite | Trades, candles, risk policy and settings |
| Python runtime | Chaquopy CPython | Strategy research, analytics and model inference |
| Final risk gate | Kotlin Android | Enforce hard limits before any order |
| Broker transport | Native Android networking initially | WebSocket/HTTPS, TLS, timeouts and reconnect |
| Broker adapter | Isolated module | Account session, balance, instruments, orders and settlement |
| Background execution | Android foreground service, subject to OS policy | User-visible active trading session |

## Why FastAPI is not embedded

FastAPI and Uvicorn expose an HTTP server. Starting a local server inside the
same APK adds ports, lifecycle complexity and attack surface without helping
Kotlin call Python. Kotlin should call Python directly through Chaquopy.
Keep FastAPI optional for a future PC dashboard or remote monitoring client.

## Python embedding

Chaquopy embeds CPython in the APK and exposes Python modules to Kotlin.
The initial integration uses Python 3.12 and a dependency-free engine scaffold.
Native numerical and ML packages will be introduced individually only after
ABI, wheel, APK-size, memory and runtime tests pass.

The initial build targets arm64-v8a phones and x86_64 emulators. More ABIs can
be added later, at the cost of a larger APK.

## Runtime and reliability constraints

1. Network access is still required to connect to IQ Option. Local-only means
   no external application server or cloud database, not offline broker access.
2. Android may stop background work because of memory pressure, battery policy,
   force-stop, reboot, network changes or OEM power management.
3. A foreground service with a persistent notification improves visibility
   and process priority, but does not guarantee uninterrupted 24/7 execution.
4. Android 15 limits dataSync foreground services to six hours per 24-hour
   period. Do not use dataSync as an indefinite trading-service type.
5. Start an active trading session only from an explicit user action. On
   disconnect, app restart or uncertain order acknowledgement, stop new orders
   and reconcile broker state before resuming.
6. Persist each order intent and correlation ID before sending it. Never retry
   a timed-out order blindly; reconcile first to prevent duplicate orders.
7. The final Kotlin risk gate rejects orders when balance, connection,
   candle freshness, market status, expiry, amount or loss limits are unknown.

## IQ Option integration boundary

The community iqoptionapi project is unofficial. Its public repository labels
it for study and warns against using it on a real account. It must not be
treated as a stable or officially supported broker API. Validate protocol,
dependencies, Android compatibility, authentication, 2FA, session renewal,
demo account, order lifecycle, settlement and reconnect behavior before use.
Do not enable real-money execution merely because login or market data works.

Keep the connector replaceable and preserve SimulationConnector for tests.

## Delivery phases

- B1: embed CPython, verify Kotlin-to-Python calls and build APK.
- B2: port pure Python strategy and analytics modules; Kotlin remains the
  authoritative hard-risk gate.
- B3: implement broker session and market-data adapter, demo-only.
- B4: implement idempotent order submission, status and reconciliation,
  demo-only.
- B5: add user-started foreground session, notification controls, reconnect
  policy and battery/OEM behavior tests.
- B6: complete end-to-end Demo soak tests before considering Real mode.

## Acceptance criteria

- APK launches without Replit or FastAPI configured.
- Python runtime health check passes on a physical arm64 Android device.
- Kotlin can pass candle JSON into Python and receive deterministic output.
- Room remains the source of truth for local records.
- No broker password is logged or committed.
- Simulation remains available and Real mode remains locked until connector,
  risk-gate and reconciliation tests pass.
