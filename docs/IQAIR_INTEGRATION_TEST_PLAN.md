# IQAir bounded Practice read-only gateway

## Implementation boundary

`iq_engine.practice_gateway.PracticeReadOnlyGateway` is an optional, dependency-injected boundary around IQAir. It does not import IQAir at module import time and can be unit-tested with a fake client. The caller supplies the IQAir client factory only in an environment where the package is installed.

## Safety properties

- One account session per gateway; no concurrent IQAir client instances.
- Every blocking client call is run on a daemon worker and has a bounded caller wait.
- A timeout poisons the gateway. It must not be reused or automatically reconnected because Python cannot safely kill a blocked worker thread.
- The broker must explicitly report `PRACTICE`; profile balance type must equal `4`.
- Read-only balance/profile access only. Order submission and account switching always raise.
- No credential persistence or logging is performed by this module.

## Important limitation

A caller timeout does not terminate the underlying worker thread. A hung IQAir WebSocket call may remain alive until process termination. Therefore this gateway is a containment layer for controlled experiments, not proof that IQAir is safe or production-ready. The actual IQAir source still needs a maintained fork with bounded internal waits, instance-scoped balance state, TLS certificate verification, deterministic socket shutdown, and physical ARM64 validation before it is enabled in the Android app.

## Credentials

Resolve credentials from Android Keystore-backed storage immediately before connection. Do not store passwords in Room, DataStore, files, build config, source control, logs, or CI variables. Never request or paste credentials into ChatGPT.
