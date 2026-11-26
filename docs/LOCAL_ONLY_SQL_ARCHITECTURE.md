# Local-Only SQL Architecture

## Product decision

IQ Trading AI is a local-first, local-only trading application. SQLite through Android Room is the sole source of truth for user trading data. Firebase Authentication, Firestore, Cloud Backup, cloud sync queues, and remote user-profile storage are not part of the target architecture.

The Python FastAPI service remains an optional execution/AI connector for broker operations, live market streams, and compute-heavy inference. It is not the system of record and must not become a substitute cloud database. If the backend is unavailable, local strategy, risk configuration, history, and simulation must remain usable.

## Local SQL schema introduced

Room database schema version 3 adds:
- `local_user_profile`: local display name, execution mode, simulation balance.
- `local_strategy_configs`: strategy parameters and active state.
- `local_risk_policies`: risk limits, emergency stop, auto-trade state.
- `local_daily_pnl_summaries`: daily performance aggregates.

Existing tables remain:
- `local_trade_logs`: trade history.
- `cached_candles`: bounded market cache.
- `sync_queue`: retained temporarily for migration compatibility only. It must not be processed or sent anywhere in the Local-Only release.

`LocalTradingRepository` is the new local-only data-access boundary and contains no Firebase or HTTP dependencies.

## Remaining removal and integration work

1. Rewire TradingViewModel and UI state to `LocalTradingRepository`; remove every Firestore repository call and offline sync queue processor.
2. Replace Google/Firebase sign-in with local app startup and a local profile. No account is required to use local trading/simulation.
3. Remove Firebase bootstrap, Firestore/Auth imports, Google sign-in screens, Firebase Gradle plugins/dependencies, google-services configuration, and Firebase-only secrets from build workflows.
4. Remove Cloud Backup/Sync UI and cloud terminology. Replace it with local database status, local export/import and encrypted local backup controls.
5. Migrate existing local trade cache and preserve user records. Do not silently delete old sync queue records during migration.
6. Verify Android backup exclusions, encrypted local export, database migration tests, offline behavior, and a clean APK build.

## Data boundary

Allowed outbound data to the optional Python connector is limited to what a user action or active trading operation requires (market subscription, order request, risk guard state, and inference input). Do not send local trade history, account profile, strategy library, risk history, or learning dataset for persistence. Do not transmit broker credentials except through the separately designed broker connector security flow; never store them in local app preferences or SQL in plaintext.

## Acceptance criteria

- App opens and operates in local simulation without Firebase configuration or Google login.
- App can create, edit, list, and delete strategies and risk policies with airplane mode enabled.
- Every trade and daily summary is persisted in Room and survives process death/restart.
- No Firebase SDK initialization, Auth, Firestore listener, Firestore read/write, sync worker, or cloud backup call is present in the release path.
- Python connector being offline does not prevent local SQL access or simulation.
- Existing database upgrades preserve local trade history and do not trigger destructive fallback migrations.
