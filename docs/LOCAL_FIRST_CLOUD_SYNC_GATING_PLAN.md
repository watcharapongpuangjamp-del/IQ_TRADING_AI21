# Local-First Cloud Sync Gate — implementation requirements

Status: security hardening in progress. This document is an engineering gate, not a claim that Cloud Sync is already disabled.

## Findings from source audit

The current app has several independent Firestore paths. A single toggle in the Settings screen is insufficient unless every path checks the same persisted policy.

- `TradingViewModel` starts Firestore profile/strategy/risk/trade/summary observers during StateFlow initialization.
- `TradingViewModel.init` calls `ensureUserBootstrap` and periodically flushes the offline queue.
- Trade creation/settlement and risk/strategy/profile/balance/daily-summary updates call `TradingCloudRepository` directly.
- `OfflineSyncQueueManager.flushPending` dispatches queued payloads to Firestore.
- Settings currently exposes a manual "Backup Now" action and describes automatic Cloud sync.

## Required design

1. Persist `cloud_sync_enabled` in app-private DataStore. Default MUST be false for fresh installs.
2. One shared gate must guard all Firestore reads, writes, listeners, bootstrap, and queue flushes. A UI toggle alone is not a security boundary.
3. When disabled:
   - Do not initialize Firestore listeners or bootstrap remote documents.
   - Do not invoke any Firestore write method.
   - Keep trade history, strategies, risk policy, and summaries local.
   - Keep queued records local; do not flush them in background.
   - Disable "Backup Now" and show "Cloud Sync ปิดอยู่".
4. When enabled, show a clear confirmation describing which data categories leave the device; only then start listeners and sync.
5. Turning sync off must stop listeners/jobs immediately and prevent subsequent writes. It must not silently delete remote data.
6. Provide separate, explicit actions for clearing local data and deleting remote account data. Remote deletion must require confirmation and must never happen as a side effect of turning sync off.
7. Broker connectivity and market-data streaming are separate from Cloud Sync. Disabling Cloud Sync must not disable IQ Option connectivity or the Python backend needed for live quotes/trading.
8. Never upload broker passwords, cookies, session tokens, API keys, or Android Keystore material.

## Acceptance tests

- Fresh install reports Cloud Sync disabled.
- With Cloud Sync disabled, instrumented tests verify zero Firestore reads/writes/listeners and zero queue dispatches.
- Enqueuing local trades while disabled retains them locally and does not mark them synced.
- Enabling sync requires explicit user action and starts sync only after confirmation.
- Disabling sync cancels listeners and background flushes; local data remains intact.
- Manual Backup Now cannot bypass the gate.
- Market stream/backend connectivity remains functional while Cloud Sync is disabled.

## Release gate

Do not publish a new test APK as "Local-First complete" until every direct Firestore call in `TradingViewModel`, `TradingCloudRepository`, and `OfflineSyncQueueManager` is routed through the same gate and the acceptance tests pass.
