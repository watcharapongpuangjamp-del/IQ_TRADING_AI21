# Multi-Model Agent Architecture and Model-Native Protocol

Status: design baseline for a later phase. No autonomous execution is enabled by this document.

## Agent roles
- Research Agent: retrieves and normalizes market/news/context data.
- Market Feature Agent: computes indicators, candle features, volatility and regime labels.
- Strategy Agent: evaluates candidate setups and emits a structured signal.
- Risk Agent: independently validates exposure, stake, loss limits and trading permissions.
- Execution Agent: the only component allowed to submit an order, and only after explicit policy approval.
- Learning Agent: evaluates completed outcomes and manages datasets/checkpoints; it cannot submit orders.

## Communication model
Agents communicate through a typed machine protocol, not free-form human prose. Use Protocol Buffers (or a versioned CBOR/MessagePack schema) for compact, validated messages. Each message carries:
- schema_version
- message_id and correlation_id
- sender_agent and recipient_agent
- task_type
- event_time_utc and received_time_utc
- instrument and timeframe where applicable
- payload with typed fields and explicit units
- confidence/calibration metadata where applicable
- provenance/source identifiers
- expiry and sequence number

Natural-language explanations are optional UI output only; they are not the control channel.

## Important model interoperability constraint
A model's private hidden-state/latent representation is not automatically a shared language. Different model families may have incompatible tokenizers, embedding dimensions, vector spaces, and training objectives. Direct latent-to-latent communication is permitted only after defining and training compatible adapters and validating them on held-out tasks. The safe default is a compact typed machine-readable protocol, not arbitrary token streams.

## Decision and execution separation
- Strategy Agent proposes; Risk Agent independently approves or rejects.
- Execution Agent enforces risk policy again immediately before submission.
- Missing, stale, malformed, or contradictory messages fail closed.
- No agent can bypass daily loss limits, trade caps, cooldowns, or emergency stop.
- Phase 1 remains manual trading; multi-model agents begin in offline/replay or shadow mode in a later phase.

## Example signal contract (conceptual)
```json
{
  "schema_version": 1,
  "message_id": "generated-unique-id",
  "correlation_id": "market-cycle-id",
  "sender_agent": "strategy",
  "recipient_agent": "risk",
  "task_type": "trade_candidate",
  "event_time_utc": "2026-11-27T00:00:00Z",
  "instrument": "EURUSD",
  "timeframe": "M1",
  "payload": {
    "direction": "CALL",
    "confidence": 0.71,
    "expiry_seconds": 60,
    "requested_stake": 1.0
  },
  "expires_at_utc": "2026-11-27T00:00:02Z",
  "sequence": 42
}
```

## Evaluation before activation
- Contract/schema compatibility tests.
- Agent identity and authorization tests.
- Deterministic replay tests with recorded market data.
- Out-of-distribution and disagreement handling.
- Calibration and risk-adjusted performance evaluation.
- Shadow-mode comparison against manual decisions.
- No live order authority until separately approved and tested.
