# Adaptive Plugin Architecture (APA)

Status: architecture foundation / Phase 1. Scope: add extensibility without replacing the existing trading core.

## Design goals

- Preserve the current FastAPI, `BrokerConnector`, `TradeExecutionEngine`, strategy, ML, persistence, and risk-engine contracts.
- Keep a usable built-in default profile when no optional plugin is installed or enabled.
- Allow optional plugins to be registered, enabled, disabled, upgraded, rolled back, and removed independently.
- Keep plugin metadata and capability routing separate from trading decisions.
- Plugin installation or Apply never authorizes real-money trading.
- Fail closed when execution provider state is ambiguous.

## Layers

1. **Trading Core:** existing execution, risk, strategy, market-intelligence, persistence, and API services. Core contracts remain authoritative.
2. **Default Capability Set:** shipped built-in capabilities. The current `SimulationConnector` remains the default broker implementation; existing engines remain core services rather than being falsely represented as independently removable plugins.
3. **Plugin Manager:** manifest validation, registration, lifecycle state, dependency checks, and version history.
4. **Capability Router:** resolves a requested capability to one compatible enabled provider. Exactly one execution provider may be active per account/session.
5. **Sandbox Runtime:** future isolated worker process for custom code. No arbitrary Python `exec`, `eval`, dynamic import, or in-process execution of user code.
6. **Approval and Policy Gate:** separately authorizes sensitive capabilities, especially broker credentials, order submission, and LIVE execution.

## Default and optional behavior

- Fresh installation starts with the built-in default profile and Simulation mode.
- Built-in core capabilities cannot be removed or disabled through the optional plugin manager.
- Optional plugins start registered/disabled and are not executable until validation and explicit enablement.
- Removing an optional plugin must not delete trade history, risk policy, credentials, or core data.
- Data-provider fallback may use a configured safe provider. Execution failover first reconciles every in-flight order; never blindly replay an order on another provider.

## Plugin contract

Each package will eventually provide a signed/hashed manifest with stable `plugin_id`, semantic version, display name, declared capabilities, API contract/app compatibility, dependencies, permissions, artifact digest, provenance, and health-check metadata.

Providers adapt to existing typed contracts. Broker execution adapters implement `BrokerConnector`; they cannot bypass `TradeExecutionEngine` or `RiskPolicy`.

## Code Block / Apply lifecycle

`REQUESTED -> DRAFT -> STATIC_VALIDATION -> SANDBOX_TEST -> REVIEWED -> ENABLED`

Failure transitions to `REJECTED` or `QUARANTINED`; it must not partially replace the active provider. Apply stores a candidate version and test evidence. Activation is a separate explicit action. Rollback restores the last known-good version.

The initial implementation supports manifest registration and lifecycle metadata only. It intentionally does not execute arbitrary user code or dynamically install Python packages. Sandbox execution, signing, dependency isolation, and UI controls are later phases.

## Security boundaries

- Sandbox runs outside the FastAPI trading process with OS/container restrictions, resource limits, restricted filesystem, and network allowlists.
- Secrets are never passed to plugin source or persisted in manifests. Broker credentials remain in a dedicated vault and are exposed only through narrowly scoped broker operations.
- Capabilities are least-privilege and deny-by-default. `order.submit`, `account.read`, and `execution.live` are distinct permissions.
- LIVE authorization remains independent of plugin state and requires explicit policy approval.
- Logs redact credentials, tokens, and session identifiers.
- Artifact integrity, dependency pinning, provenance, and rollback are required before production activation.

## API selection modes

- `DEFAULT`: built-in Simulation connector and core services.
- `MANUAL`: user selects a compatible provider.
- `AUTO`: choose only among providers that passed capability and health checks.
- `HYBRID`: separate providers may serve market-data and account/execution roles, while one execution provider is active for a given account/session.

A provider may claim a capability only after conformance tests pass. UI labels distinguish `implemented`, `validated`, and `connected`.

## Compatibility with current repository

The current backend creates `SimulationConnector` in `backend/app/main.py`; the abstract broker contract is in `backend/app/connectors/base.py`. Phase 1 leaves these execution paths unchanged. The new `backend/app/plugins` package is a non-invasive foundation for manifests and lifecycle metadata. Later phases can introduce a provider factory and authenticated management endpoints behind tests.

## Rollout

- **P1 — Foundation:** manifests, immutable built-in entries, registry lifecycle, tests, and documentation. No execution-path changes.
- **P2 — Provider integration:** provider factory and selection config; retain Simulation as default; add connector conformance tests.
- **P3 — Sandbox:** isolated worker protocol, signed artifacts, resource/network policy, code-block validation, and audit trail.
- **P4 — Android UI:** plugin catalog, capability requests, code editor, Apply/Validate/Enable/Disable/Rollback flows.
- **P5 — IQ Option adapter:** implement and validate adapters against a Practice account; LIVE remains separately gated.

## Phase 1 acceptance criteria

- Existing trading routes and engine initialization are unchanged.
- Built-in registry entries cannot be disabled or removed.
- Optional plugins can be registered, enabled, disabled, and removed as metadata.
- Invalid manifests and duplicate IDs are rejected.
- No user-supplied source is imported or executed.
- Automated tests cover lifecycle and immutability.
