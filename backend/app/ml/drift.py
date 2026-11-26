from dataclasses import dataclass
from datetime import datetime
import json
import math
from typing import Any, Dict, List, Sequence, Tuple

from app.domain import DriftStatus, OrderLifecycleState, TradeStatus


@dataclass(frozen=True)
class DriftEvaluationReport:
    model_id: str
    drift_status: DriftStatus
    drift_score: float
    feature_drift_score: float
    drifted_features: List[str]
    live_settled_trades: int
    live_win_rate: float
    baseline_win_rate: float
    win_rate_drop: float
    drift_notes: str
    recommend_rollback: bool


def compute_feature_baseline(
    features: Sequence[Sequence[float]],
    feature_names: Sequence[str],
) -> Dict[str, Dict[str, Any]]:
    """Computes pure-Python reference statistics (mean, std, min, max) from training split features."""
    if not features:
        return {}
    cols = min(len(feature_names), len(features[0]))
    stats: Dict[str, Dict[str, Any]] = {}
    n = len(features)
    for idx in range(cols):
        name = str(feature_names[idx])
        vals = [float(row[idx]) for row in features]
        mean = sum(vals) / n
        var = sum((v - mean) ** 2 for v in vals) / max(1, n)
        std = max(1e-4, math.sqrt(var))
        stats[name] = {
            "mean": round(mean, 6),
            "std": round(std, 6),
            "min": round(min(vals), 6),
            "max": round(max(vals), 6),
        }
    return stats


def dumps_baseline_stats(stats: Dict[str, Dict[str, Any]]) -> str:
    return json.dumps(stats, separators=(",", ":"), sort_keys=True)


def loads_baseline_stats(raw: str | None) -> Dict[str, Dict[str, Any]]:
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def compute_feature_drift(
    baseline: Dict[str, Dict[str, Any]],
    recent_features: Sequence[Sequence[float]],
    feature_names: Sequence[str],
) -> Tuple[float, List[str]]:
    """
    Computes normalized feature distribution shift using absolute z-score of recent mean
    plus variance expansion ratio vs. training baseline.
    """
    if not baseline or not recent_features:
        return 0.0, []

    feature_shifts: List[Tuple[str, float]] = []
    n = len(recent_features)
    cols = min(len(feature_names), len(recent_features[0]))

    for idx in range(cols):
        name = str(feature_names[idx])
        ref = baseline.get(name)
        if not ref:
            continue
        ref_mean = float(ref.get("mean", 0.0))
        ref_std = max(1e-4, float(ref.get("std", 1.0)))

        vals = [float(row[idx]) for row in recent_features]
        cur_mean = sum(vals) / n
        cur_var = sum((v - cur_mean) ** 2 for v in vals) / max(1, n)
        cur_std = math.sqrt(cur_var)

        mean_z = abs(cur_mean - ref_mean) / ref_std
        std_ratio = abs(math.log(max(0.25, min(4.0, cur_std / ref_std))))
        shift_index = 0.7 * mean_z + 0.3 * std_ratio
        feature_shifts.append((name, shift_index))

    if not feature_shifts:
        return 0.0, []

    avg_shift = sum(s for _, s in feature_shifts) / len(feature_shifts)
    drifted = [
        name
        for name, s in sorted(feature_shifts, key=lambda x: x[1], reverse=True)
        if s >= 0.85
    ][:5]
    # Normalize around [0.0, 2.0+] where >= 0.65 indicates clear distribution drift
    normalized_score = round(min(2.0, avg_shift / 1.5), 4)
    return normalized_score, drifted


def evaluate_settled_performance(
    orders: Sequence[object],
    instrument: str,
    baseline_win_rate: float,
    since_dt: datetime | None = None,
) -> Tuple[int, float, float]:
    """
    Evaluates live performance strictly from cleanly SETTLED (WON/LOST) orders.
    Never includes PENDING, UNKNOWN, CREATED, OPEN, REJECTED, or FAILED records.
    """
    settled_wins = 0
    settled_total = 0

    for order in orders:
        if getattr(order, "instrument", None) != instrument:
            continue

        lifecycle = getattr(order, "lifecycle_state", None)
        status = getattr(order, "status", None)
        outcome = getattr(order, "settlement_outcome", None) or status

        if lifecycle is not None and lifecycle != OrderLifecycleState.SETTLED.value:
            continue
        if outcome not in (TradeStatus.WON.value, TradeStatus.LOST.value):
            continue

        created_at = getattr(order, "created_at", None)
        if since_dt is not None and isinstance(created_at, datetime) and created_at < since_dt:
            continue

        settled_total += 1
        if outcome == TradeStatus.WON.value:
            settled_wins += 1

    if settled_total == 0:
        return 0, 0.0, 0.0

    live_win_rate = settled_wins / settled_total
    drop = max(0.0, baseline_win_rate - live_win_rate) if settled_total >= 8 else 0.0
    return settled_total, round(live_win_rate, 4), round(drop, 4)


def evaluate_model_drift(
    model_id: str,
    instrument: str,
    baseline_stats: Dict[str, Dict[str, Any]],
    baseline_win_rate: float,
    recent_features: Sequence[Sequence[float]],
    feature_names: Sequence[str],
    orders: Sequence[object],
    promoted_at: datetime | None = None,
) -> DriftEvaluationReport:
    feature_drift, drifted_features = compute_feature_drift(
        baseline=baseline_stats,
        recent_features=recent_features,
        feature_names=feature_names,
    )
    live_trades, live_wr, wr_drop = evaluate_settled_performance(
        orders=orders,
        instrument=instrument,
        baseline_win_rate=baseline_win_rate,
        since_dt=promoted_at,
    )

    perf_penalty = round(min(1.0, wr_drop / 0.20), 4) if live_trades >= 8 else 0.0
    combined_score = round(min(2.0, max(feature_drift, 0.6 * feature_drift + 0.6 * perf_penalty)), 4)

    if feature_drift >= 0.65 or (live_trades >= 8 and wr_drop >= 0.15):
        status = DriftStatus.DRIFT_DETECTED
        recommend_rollback = True
    elif feature_drift >= 0.35 or (live_trades >= 8 and wr_drop >= 0.08):
        status = DriftStatus.WARNING
        recommend_rollback = False
    else:
        status = DriftStatus.HEALTHY
        recommend_rollback = False

    note_parts: List[str] = []
    if drifted_features:
        note_parts.append("Feature shift: " + ", ".join(drifted_features))
    else:
        note_parts.append(f"Feature shift={feature_drift:.3f}")

    if live_trades > 0:
        note_parts.append(
            f"Settled live={live_trades} trades (Win {live_wr * 100:.1f}% vs base {baseline_win_rate * 100:.1f}%)"
        )
    else:
        note_parts.append("No settled post-promotion trades yet")

    if status == DriftStatus.DRIFT_DETECTED:
        note_parts.append("DRIFT ALERT: Manual rollback recommended (no auto-swap)")
    elif status == DriftStatus.WARNING:
        note_parts.append("Drift watch elevated; monitor next settled window")

    return DriftEvaluationReport(
        model_id=model_id,
        drift_status=status,
        drift_score=combined_score,
        feature_drift_score=feature_drift,
        drifted_features=drifted_features,
        live_settled_trades=live_trades,
        live_win_rate=live_wr,
        baseline_win_rate=baseline_win_rate,
        win_rate_drop=wr_drop,
        drift_notes=" | ".join(note_parts),
        recommend_rollback=recommend_rollback,
    )
