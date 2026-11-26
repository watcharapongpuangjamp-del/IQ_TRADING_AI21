# Local Encrypted Internal Communication (LEIC)

Status: architecture baseline; implementation and device-level verification are still required.

## Security goals
- Keep application data inside the Android application sandbox and app-private Python working directory.
- Authenticate every cross-process endpoint before accepting a request.
- Encrypt cross-process payloads with authenticated encryption (AES-256-GCM).
- Bind ciphertext to protocol version, sender, recipient, message type, and sequence number using AEAD associated data.
- Reject malformed, expired, replayed, or unauthorized messages.
- Keep encryption keys out of source code, logs, preferences, and message payloads.

## Trust boundaries
1. Android components in the same app UID: use least-privilege APIs and avoid unnecessary IPC.
2. Android-to-Python local process boundary: use an authenticated local IPC channel. If a loopback socket is used, bind only to 127.0.0.1, require a per-install random credential, and use TLS or an AES-GCM message envelope. Never bind to 0.0.0.0.
3. Python modules: use explicit capability interfaces and typed request/response contracts; do not share unrestricted filesystem or database handles.
4. IQ Option network traffic: allow only the broker connector's required endpoints. Broker market/order traffic is distinct from private application data and must never contain local database contents, AI checkpoints, or unrelated user data.

## Cryptographic contract
- AEAD: AES-256-GCM with a unique 96-bit nonce per key.
- Key source: Android Keystore-backed key material; for a separate Python process, provision a short-lived session key through an authenticated channel. Do not hard-code or persist plaintext keys.
- AAD: protocol version + sender ID + recipient ID + message type + sequence number.
- Replay protection: monotonic per-session sequence numbers and bounded replay cache.
- Rotation: establish a new session key after reconnect, app restart, authentication change, or key-age threshold.
- Failure policy: fail closed; do not fall back to plaintext.

## Android controls
- Keep components non-exported unless explicitly required.
- Avoid exported ContentProviders, Services, Receivers, and debug endpoints.
- If IPC is needed, enforce signature-level permissions and verify caller UID/package.
- Keep data in app-private storage; disable Android backup and device transfer for secrets.
- Use Keystore for key encryption/wrapping. Keystore does not automatically encrypt Room database files.

## Database requirement
The current inspected Room database is not SQLCipher. Before claiming encrypted-at-rest SQL, migrate Room to a verified SQLCipher-compatible SQLite driver, derive/wrap its passphrase using Android Keystore, test migration and recovery, and ensure no plaintext database/WAL/journal copies remain.

## Verification gates
- Unit tests for encryption/decryption, tampering, wrong key, wrong recipient, stale version, and replay.
- Instrumented Android tests for exported components and app-private file access.
- Inspect network traffic to confirm private records and model artifacts never leave the device.
- Verify backup exclusion and inspect generated APK manifest.
- Test process death/reconnect and session-key rotation.
- No security-complete claim until these gates pass.
