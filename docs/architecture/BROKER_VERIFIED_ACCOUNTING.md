# Broker-Verified Transaction Accounting

Status: approved architecture requirement; implementation pending
Target: Standalone Android + local SQLite/Room
Broker scope currently proven in source: IQ Option unofficial `iqair==1.0.0`, PRACTICE only
Security boundary: REAL remains hard-locked; no REAL order path is introduced by this document.

## 1. Non-negotiable accounting principles

1. Broker-originated facts are the source of truth for accepted orders, settlement and broker balance.
2. A displayed payout quote is not realized profit. Store it as a time-stamped quote snapshot and never substitute it for settlement.
3. No AI component may create, edit, infer or overwrite financial ledger facts.
4. Every financial amount carries currency, sign, source, event time, ingestion time and verification state.
5. Unknown, missing, duplicated or contradictory broker facts remain explicitly unresolved. Never fill gaps with estimates.
6. Simulation, Practice and any future Real ledger are isolated by account identity and mode.
7. Preserve immutable raw broker evidence (redacted of credentials/secrets) and append-only normalized events. Corrections are compensating events, not destructive edits.
8. All calculations use decimal fixed-point arithmetic, not binary floating point.

## 2. Evidence levels

- `BROKER_CONFIRMED`: directly returned by broker for the specific transaction/event.
- `BROKER_DERIVED`: deterministic calculation from broker-confirmed facts with lineage.
- `LOCAL_OBSERVED`: locally observed balance snapshot or app event, not independently authoritative.
- `UNVERIFIED`: source, completeness or mapping cannot be established.
- `CONFLICT`: two authoritative observations disagree.

Only BROKER_CONFIRMED and BROKER_DERIVED records with complete lineage may contribute to verified realized-performance metrics. UNVERIFIED/CONFLICT blocks readiness evaluation and reconciliation completion.

## 3. Transaction lifecycle

`INTENT_CREATED -> SUBMISSION_SENT -> BROKER_ACCEPTED -> OPEN/PENDING -> SETTLED`

Alternative terminal/exception states: `REJECTED`, `CANCELLED`, `REFUNDED`, `UNKNOWN`, `RECONCILIATION_REQUIRED`.

- Create a local intent and idempotency key before network submission.
- Persist submission attempt and monotonic timing before sending.
- Persist broker order/transaction ID immediately on acknowledgement.
- If acknowledgement times out, mark UNKNOWN and reconcile by broker identifiers/history. Never blindly resubmit.
- Settlement is final only when the broker confirms terminal state and monetary result.
- A local timeout, UI result or signal is never settlement evidence.

## 4. Payout and realized return

Store separate values:
- `quoted_payout_rate`: broker quote captured for the exact asset/contract/expiry at quote time.
- `accepted_payout_rate`: broker-confirmed rate associated with the accepted order, only if API provides it.
- `stake_amount`: broker-accepted stake.
- `gross_credit_amount`: broker-confirmed total credit, if provided.
- `net_profit_amount`: broker-confirmed net P&L, if provided.
- `fees_amount`, `refund_amount`, `currency`.
- `settlement_outcome`, `settled_at`, `evidence_level`.

For a contract whose broker semantics are verified as stake returned plus profit:
- realized net = gross credit - stake - fees.
- realized payout rate = realized net / stake.
Do not apply this formula if the API's gross-credit semantics are unknown. Prefer broker-provided net P&L. A win/loss label alone is insufficient to establish a monetary result.

Display quoted payout and realized return in separate fields. Never call the quote "actual payout".

## 5. Performance metrics

Calculate from settled, reconciled, same-currency ledger entries only:
- total accepted stake and settled count;
- gross wins, gross losses, fees/refunds and net realized P&L;
- realized ROI = net realized P&L / total settled stake;
- win rate, with DRAW/REFUND reported separately;
- profit factor = gross positive net outcomes / absolute gross negative net outcomes; undefined if denominator is zero;
- expectancy per settled trade and per unit stake;
- equity curve and peak-to-trough drawdown from broker-confirmed balance/equity events;
- payout distribution by asset, contract type, expiry and time window.

Never blend open/pending orders with settled results. Report sample count, date range, currency, evidence coverage and unresolved transaction count beside each metric. If the denominator is zero or evidence incomplete, return null/UNAVAILABLE, not zero or a fabricated estimate.

## 6. Broker balance and cash-flow reconciliation

Represent balance movement as separate event classes:
- `TRADE_SETTLEMENT` (win/loss/refund/fee);
- `DEPOSIT`;
- `WITHDRAWAL`;
- `BONUS_OR_PROMOTION`;
- `BROKER_ADJUSTMENT`;
- `UNKNOWN_BALANCE_MOVEMENT`.

Reconcile opening balance + confirmed credits - confirmed debits = closing balance, by currency and account. Do not classify a balance increase as a deposit unless the broker transaction history explicitly identifies it. Trading profit is not a capital injection. A withdrawal is not a trading loss.

If broker deposit/withdrawal history is unavailable through the supported API, the app must label cash-flow provenance as UNVERIFIED, request a supported statement/manual evidence workflow if designed, and block a claim of complete source-of-funds reconciliation. A balance delta alone cannot prove whether the cause was profit, deposit, bonus or adjustment.

## 7. Proposed local schema

- `broker_accounts`: account_key, broker, account_mode, currency, broker_account_ref_hash, verification_state.
- `broker_events`: event_id, broker_event_id, account_key, event_type, broker_time, received_time, raw_payload_redacted, payload_hash, evidence_level, source_version.
- `broker_orders`: local_order_id, broker_order_id, idempotency_key, account_key, asset, product, direction, expiry, currency, accepted_stake, quoted_payout_rate, accepted_payout_rate, lifecycle_state.
- `broker_settlements`: settlement_id, broker_order_id, outcome, gross_credit, net_profit, fees, refund, currency, settled_at, evidence_level, source_event_id.
- `broker_balance_snapshots`: snapshot_id, account_key, broker_time, received_time, balance, equity, currency, evidence_level.
- `capital_ledger`: ledger_id, account_key, capital_epoch_id, event_type, amount_signed, currency, source_event_id, evidence_level, occurred_at.
- `reconciliation_runs`: run_id, account_key, start/end, expected_balance, broker_balance, difference, unresolved_count, status.
- `performance_snapshots`: snapshot_id, capital_epoch_id, calculation_version, as_of, metrics_json, evidence_coverage, status.

Use unique constraints on broker/account/event IDs and idempotency keys. Store monetary values as integer minor units where currency precision is fixed, or a database NUMERIC/Decimal representation. Never use Float for money.

## 8. Reconciliation policy

- Reconcile after each settlement, reconnect, balance snapshot and before any readiness evaluation.
- Support delayed and out-of-order events; deduplicate by stable broker event/transaction ID.
- Re-run reconciliation idempotently.
- A discrepancy above currency precision tolerance produces CONFLICT and blocks new automatic orders and readiness scoring until resolved.
- Preserve each reconciliation result and its evidence references.
- Never force the local balance to match by silently overwriting ledger entries.

## 9. Current source-code capability boundary

The inspected `backend/app/connectors/iqoption_broker.py` currently:
- verifies PRACTICE and reads broker balance;
- reads payout from asset metadata;
- submits Turbo/Binary CALL/PUT and records broker order ID;
- refreshes a Trade object and reads `pnl_net` (fallback `pnl`) for terminal states.

The inspected connector does not demonstrate a complete broker cash transaction feed for deposits, withdrawals, bonuses and adjustments, nor a broker-supplied accepted payout field bound to each order. Those capabilities must be proven against the actual `iqair==1.0.0` package and broker responses before being marked supported. In-memory `_orders` metadata is not durable recovery evidence after process death.

## 10. Acceptance tests

1. Quote payout differs from settled net result: metrics use settlement, quote remains historical.
2. WIN with missing monetary settlement: not counted as verified profit.
3. LOSS with broker net P&L: exact signed amount is recorded.
4. DRAW/REFUND/partial refund/fee: classified separately and reconciled.
5. Duplicate and out-of-order broker events: no double counting.
6. Acknowledgement timeout: UNKNOWN, no blind resubmission.
7. App restart with pending order: recovery via durable broker identifiers/history.
8. Deposit vs trading profit vs bonus: distinct source classifications; balance delta alone stays UNVERIFIED.
9. Balance mismatch: CONFLICT; readiness blocked.
10. Currency mismatch: never aggregate across currencies without an explicit, versioned FX conversion policy.
11. Decimal precision and rounding tests for all supported currencies.
12. SIMULATION and PRACTICE ledgers never mix.

No readiness score may use incomplete or unreconciled transaction data.
