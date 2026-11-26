# Local-Only SQL Architecture

## Product decision

IQ Trading AI is a local-first, local-only trading application. SQLite through Android Room is the sole source of truth for user trading data. Firebase Authentication, Firestore, Cloud Backup, cloud sync queues, and remote user-profile storage are not part of the target architecture.

The Python FastAPI service remains an optional execution/AI connector for broker operations, live market streams, and compute-heavy inference. It is not the system of record and must not become a substitute cloud database. If the backend is unavailable, local strategy, risk configuration, history, and simulation must remain usable.

## Local SQL schema introduced

Room database schema version 4 contains:
- `local_user_profile`: local display name, execution mode, simulation balance.
- `local_strategy_configs`: strategy parameters and active state.
- `local_risk_policies`: risk limits, emergency stop, auto-trade state.
- `local_daily_pnl_summaries`: daily performance aggregates.

Existing tables remain:
- `local_trade_logs`: trade history.
- `cached_candles`: bounded market cache.
- `legacy_local_queue_archive`: preserves old unsent queue payloads locally for audit/recovery only; no worker or code path processes or transmits them. The old `sync_queue` table is removed.

`LocalTradingRepository` is the new local-only data-access boundary and contains no Firebase or HTTP dependencies.

## Completed in the current branch

- TradingViewModel reads and writes trading state through `LocalTradingRepository`.
- App startup uses a local profile; Google/Firebase sign-in screens and bootstrap code have been removed.
- Firestore repository, sync queue processor, Firebase Auth helper, and Firebase error handler have been removed.
- Firebase/Google sign-in Gradle dependencies, Google Services plugin, Firebase configuration files, and related CI secret setup have been removed.
- Settings now describes local SQL storage; the cloud backup card and Terminal sync queue banner are removed.
- Room migration 3→4 removes the cloud sync flag from trade rows, archives any old queued payloads locally, and removes the active sync queue table.
- A Robolectric test was added for local profile, strategy, risk policy, and trade persistence.

## Remaining validation and integration work

1. Run the complete Android unit test suite and build a fresh debug APK; inspect and fix any compile or migration failures.
2. Add explicit migration tests for existing version 2/3 databases, including preservation of trade rows and legacy queue archive rows.
3. Add local export/import with optional password-based encryption and explicit user-controlled backup location. No automatic cloud backup.
4. Verify local simulation continues when the device is in airplane mode; verify optional Python connector is isolated from the local database source of truth.
5. Install and test the resulting APK on Android, including process restart and database upgrade from an existing installation.

## Data boundary

Allowed outbound data to the optional Python connector is limited to what a user action or active trading operation requires (market subscription, order request, risk guard state, and inference input). Do not send local trade history, account profile, strategy library, risk history, or learning dataset for persistence. Do not transmit broker credentials except through the separately designed broker connector security flow; never store them in local app preferences or SQL in plaintext.

## Acceptance criteria

- App opens and operates in local simulation without Firebase configuration or Google login.
- App can create, edit, list, and delete strategies and risk policies with airplane mode enabled.
- Every trade and daily summary is persisted in Room and survives process death/restart.
- No Firebase SDK initialization, Auth, Firestore listener, Firestore read/write, sync worker, or cloud backup call is present in the release path.
- Python connector being offline does not prevent local SQL access or simulation.
- Existing database upgrades preserve local trade history and do not trigger destructive fallback migrations.
