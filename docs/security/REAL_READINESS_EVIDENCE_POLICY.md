# REAL Readiness — Evidence and Criteria Consistency Policy

Status: design requirement only; REAL is not implemented or enabled.
Depends on: Broker-Verified Accounting and Capital Epoch Policy.

## 1. Core rule

A numeric score is a summary, not authorization. Passing a weighted score cannot compensate for failure of a mandatory evidence gate. AI cannot approve REAL or change the policy.

## 2. Mandatory gates

All gates must pass for a user to become eligible for a separate, explicit future authorization review:

1. Account identity and PRACTICE mode are broker-verified.
2. All assessment-period orders have broker IDs and terminal settlement evidence; unresolved order count is zero.
3. Financial ledger is reconciled to broker balance; cash-flow provenance is complete enough for the policy. No UNVERIFIED/CONFLICT capital movement remains.
4. All performance metrics are computed from settled, same-currency, broker-verified events in the current capital epoch.
5. Minimum trade count and observation period are met; these are necessary but not sufficient.
6. Net expectancy is positive after payout, fees and refunds, with adequate uncertainty bounds and sample quality.
7. Profit factor and drawdown meet policy limits, calculated from verified net outcomes and the correct equity curve.
8. Out-of-sample / walk-forward and market-regime robustness tests pass without leakage or repeated test-set tuning.
9. Execution reliability and order-reconciliation tests pass; no unresolved unknown orders or critical operational failures.
10. Risk limits and emergency stop have been exercised in tests; no critical violations remain.
11. Critical knowledge questions and responsibility acknowledgement are completed by the user.
12. Assessment is current, policy/model versions match, and no invalidating drift or strategy change exists.

Any failed, missing, stale or contradictory evidence results in NOT_ELIGIBLE / REVIEW_REQUIRED, never an inferred pass.

## 3. Criteria consistency validation

Before any threshold is adopted, document:
- the risk being measured and why the metric represents it;
- exact formula, unit, currency, time window and inclusion/exclusion rules;
- data provenance and evidence level;
- minimum sample and statistical uncertainty;
- known biases, confounders and edge cases;
- threshold rationale and calibration dataset;
- test cases at boundary values and contradictory inputs;
- owner, policy version and review date.

Avoid double-counting correlated metrics (for example win rate, expectancy and profit factor). Report both absolute performance and risk-adjusted performance. Do not optimize thresholds on the same data used to claim validation. Thresholds such as 200 trades, 30 days, PF 1.20, MDD 10% and score 85/100 are provisional research hypotheses, not established safety guarantees; they must be validated and versioned before use.

## 4. Statistical quality requirements

- Use chronologically separated train/validation/test data and walk-forward evaluation.
- Prevent leakage across overlapping option expiries and overlapping labels.
- Report confidence intervals or bootstrap uncertainty for expectancy and win rate.
- Account for payout variability per trade; do not derive profitability from win rate alone.
- Report performance by asset, contract type, expiry, session and market regime, with sample counts.
- Include fees, refunds, early-sale outcomes and broker-reported net P&L.
- Include adverse execution, latency, rejected orders and missed/unknown outcomes in operational analysis.
- Guard against multiple testing, parameter mining and selection bias.
- Require a frozen candidate model/policy before final holdout evaluation.

## 5. Weighted score is subordinate

A future score may summarize non-critical dimensions, but:
- every mandatory gate is conjunctive;
- a score cannot override a failed gate;
- unavailable metrics are not zero and cannot be awarded points;
- no AI-driven self-scoring or policy edits;
- every result stores evidence references and policy/calculation/model versions.

The earlier 85/100 proposal and category weights are provisional and must not be hard-coded as a production approval threshold until calibrated and reviewed.

## 6. Authorization state

Suggested states:
`LOCKED`, `ASSESSMENT_REQUIRED`, `ASSESSING`, `NOT_ELIGIBLE`, `ELIGIBLE_PENDING_USER_CONFIRMATION`, `AUTHORIZED`, `SUSPENDED`, `EXPIRED`, `REVOKED`.

For the current release, the only permitted REAL state is `LOCKED`. Do not add a reachable REAL execution path, UI toggle, broker mode switch or authorization bypass. A future release must require a separate explicit approval and independent security review.

## 7. Continuous invalidation

Suspend future eligibility and disable Auto Trade when:
- capital epoch closes or changes;
- reconciliation conflict, unknown order or funding provenance gap appears;
- risk/drawdown stop triggers;
- model drift or material payout/execution distribution shift is detected;
- strategy, model, risk policy, connector or calculation version changes;
- evidence expires or becomes stale.

Reassessment must use a new evidence snapshot. Historical data remains available for audit but cannot be relabeled as current-epoch evidence.

## 8. Acceptance tests

- High score with one failed mandatory gate is NOT_ELIGIBLE.
- Missing settlement or unverified deposit blocks readiness.
- A capital epoch change resets all readiness counters.
- A strategy/model/policy version change invalidates prior approval.
- Correlated metrics do not double-award points.
- Test-set reuse or leakage causes validation failure.
- Confidence interval crossing zero does not count as proven positive expectancy under a policy requiring positive expectancy.
- AI cannot mutate policy or authorization state.
- Current release rejects every REAL order request and keeps live_authorized=false.
