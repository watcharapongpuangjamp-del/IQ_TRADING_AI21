# IQ Trading AI — Phase 1 Implementation Baseline

Status: Approved scope; implementation in progress  
Branch: `phase1/manual-trading-foundation`

## Phase 1 objective

Deliver a dependable manual-trading terminal backed by the user's IQ Option account and platform data. Practice is the default and the only account mode permitted to submit orders during the initial validation phase. Real-account connection/display may be developed behind a disabled execution gate; real-money order submission is not part of the initial Phase 1 acceptance.

## In-scope capabilities

1. IQ Option account sign-in/session lifecycle, with credentials and session material kept off GitHub and out of logs.
2. Explicit account-mode display and switching; Practice selected by default. Fail closed if account type cannot be verified.
3. Actual account balance, currency, server timestamp, freshness and manual/automatic synchronization.
4. Practice balance reset through a verified platform operation only; never emulate a reset locally.
5. Live instrument catalog from the platform, including instrument identifier, product type, OTC flag, tradability/open status and payout/expiry metadata only when supplied by the platform.
6. Live quote/candle stream and history with source timestamps, receive timestamps, latency and gap detection.
7. Manual Practice order entry: instrument, direction, stake and supported expiry/timeframe; order acknowledgement, lifecycle, settlement and reconciliation.
8. Trading terminal display: price, candle timeframe, expiry/countdown, active orders and completed results.
9. Time reliability: UTC for internal timestamps, IANA `Asia/Bangkok` for display, broker-clock offset sampling, stale detection, and monotonic countdowns.
10. Connection reliability: heartbeat, reconnect with backoff, stream freshness and reconcile-before-resubmit for uncertain orders.
11. Debug APK build and device validation. No Play Store/AAB work is required.

## Explicitly out of scope for Phase 1

- AI-driven automatic trade decisions or execution.
- Strategy profiles, adaptive learning and DQN training.
- Real-money order submission.
- Synthetic prices or locally fabricated tradable asset lists presented as platform data.

## Implementation rules

- Keep broker-specific behavior behind `BrokerConnector`; do not let UI or execution engines call an unofficial broker library directly.
- The existing `SimulationConnector` remains a test fixture only. Label its data as simulation and never present it as live IQ Option data.
- A real connector must be capability-gated and fail closed until login, account mode, balance, instruments, quotes and order reconciliation are verified against the platform.
- Do not blindly retry an order after timeout. Reconcile the original intent first.
- Treat OTC and non-OTC as distinct market types; never mix their data silently.
- Do not assume every product supports the same candle timeframe or expiration values. Use platform-provided capabilities.
- Keep credentials, cookies, tokens, and account identifiers out of source control, analytics and ordinary logs.

## Delivery sequence

### 1A — Reliability and contracts
- Standardize time and clock synchronization.
- Add connector capability and data provenance contracts.
- Add tests for timestamps, stale data, reconnect state and idempotent order intents.

### 1B — Read-only Practice connection
- Login and session validation.
- Practice account verification and balance synchronization.
- Live asset catalog, quotes and candles.

### 1C — Manual Practice trading
- Supported product/timeframe/expiry discovery.
- Manual order submission and lifecycle reconciliation.
- Active trade display and settlement verification.

### 1D — Android terminal and APK validation
- Connect screen, account mode, balance, asset list, chart and manual order panel.
- Build Debug APK, install and verify on an ARM64 Android device.

Real-account display is enabled only after the read-only connector passes verification. Real-money execution remains disabled pending a separate explicit authorization and safety review.
