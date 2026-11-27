# Capital Lock & Capital Epoch Policy

Status: approved architecture requirement; implementation pending
Applies to: Practice assessment now; any future account assessment only after separate release approval.
REAL account authorization remains hard-locked.

## 1. Purpose

A readiness evaluation must represent performance achieved using one fixed, declared starting-capital basis. New external capital must never be used to hide losses, repair a failed run or preserve the prior run's score.

## 2. Capital epoch

Every evaluation run has a unique immutable `capital_epoch_id` and stores:
- broker/account identity and account mode;
- currency;
- initial capital and the broker-confirmed opening balance;
- epoch start time and policy/calculation versions;
- capital source evidence and reconciliation status;
- terminal reason and end time, when closed.

The initial capital cannot be edited after the epoch starts. A correction requires closing the epoch and creating a new epoch with an auditable correction reason; never rewrite historical starting capital.

## 3. What changes an epoch

A confirmed external deposit, manual balance reset, account replacement, account-mode replacement, currency change or unexplained capital increase closes the current epoch and requires a fresh assessment epoch.

- Trading profit, trading loss and settlement credits/debits remain trading performance events; they do not create a new epoch.
- A withdrawal is a cash-flow event, not a trading loss. It must be evaluated separately against the capital policy. If it changes the declared evaluation capital basis or the account cannot prove the movement, suspend the epoch and require review/restart rather than misclassifying it.
- Bonuses, promotional credits, broker corrections and transfers must be separately classified. They cannot silently enlarge eligible starting capital.
- Balance snapshots alone do not prove the source of a balance movement.

## 4. Exhaustion and preservation stops

Close an epoch when:
- broker-confirmed available balance reaches zero or the minimum executable stake cannot be met;
- the configured capital preservation/drawdown limit is breached;
- capital provenance becomes unresolved or conflicting;
- broker/account identity changes;
- required reconciliation cannot be completed within policy.

On trigger:
1. disable Auto Trade and reject new automatic orders;
2. preserve unresolved orders for reconciliation (do not abandon open broker positions);
3. revoke/suspend any future authorization state;
4. close and freeze the epoch with exact trigger evidence;
5. require explicit user review before starting a new Practice epoch.

Do not force-cancel broker positions unless a separate, broker-supported risk action is explicitly implemented and tested.

## 5. New capital means a completely new assessment

When a confirmed deposit or qualifying reset occurs:
- close the prior epoch as `CAPITAL_CHANGE`;
- create a new epoch with a new ID and broker-confirmed opening balance;
- reset assessment counters and readiness metrics to zero for the new epoch: eligible settled trades, observation duration, win rate, profit factor, expectancy, drawdown series, risk violations, validation evidence and readiness score;
- require fresh out-of-sample and robustness evidence when the strategy/model/policy changed; historical research may be retained as reference but cannot substitute for the new epoch's required live-Practice evidence;
- retain every previous epoch and its ledger permanently for audit. Never delete old results.

No capital addition may be netted into the prior epoch's capital or performance denominator.

## 6. External funding detection limitation

The app cannot prevent a user from depositing through the broker's website or another device. It can only enforce a local trading lock when it detects a confirmed event or an unexplained balance/provenance discrepancy. If the broker API does not expose deposit/withdrawal history, the app must not claim guaranteed detection. Require a verified broker statement or other supported evidence workflow; until then mark funding provenance UNVERIFIED and block readiness approval. No user-entered declaration alone can be presented as broker-confirmed evidence.

## 7. State machine

`NO_EPOCH -> EPOCH_ACTIVE -> PRESERVATION_STOP | CAPITAL_EXHAUSTED | CAPITAL_CHANGE | DATA_UNVERIFIED -> EPOCH_CLOSED -> NEW_EPOCH_PENDING -> EPOCH_ACTIVE`

A new epoch must not become active while the old epoch has unresolved orders, unreconciled balance or conflicting cash-flow evidence.

## 8. Suggested data model

`capital_epochs`:
- epoch_id (UUID primary key)
- account_key, account_mode, currency
- initial_capital_minor_units
- opening_balance_minor_units
- started_at, ended_at
- status, close_reason
- policy_version, calculation_version
- source_evidence_event_id
- previous_epoch_id
- immutable_record_hash

`capital_epoch_events`:
- event_id, epoch_id, event_type, amount_minor_units, currency
- broker_event_id, evidence_level, occurred_at, received_at
- decision, decision_reason, policy_version

Use append-only records, unique broker event IDs, idempotent event processing and transactional epoch close/create. Enforce immutability in repository/service layer and SQLite triggers where appropriate. Store a hash chain or signed digest for tamper-evidence; do not claim this makes local data impossible to tamper with.

## 9. Acceptance tests

- Profit credit does not reset an epoch.
- Confirmed deposit closes old epoch and creates a fresh epoch with zero readiness metrics.
- Balance increase with unknown source blocks assessment; it is not assumed to be profit or deposit.
- Withdrawal is not recorded as trading loss.
- Bonus and adjustment cannot silently become initial capital.
- Zero balance and preservation-limit breach stop Auto Trade.
- Process restart preserves epoch and unresolved state.
- Duplicate funding event does not create multiple epochs.
- Old epoch history remains readable and immutable.
- Account/currency changes cannot reuse previous epoch metrics.
- Any missing evidence fails closed.
