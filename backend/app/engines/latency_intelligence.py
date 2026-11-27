"""Latency measurement aggregation and simulation-profile calibration.

This module is deliberately side-effect free: it does not probe networks,
send broker requests, or delay live order execution.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import ceil
from statistics import mean
from typing import Iterable, Sequence


@dataclass(frozen=True)
class LatencySample:
    metric: str
    duration_ms: float | None
    success: bool = True
    source: str = "unknown"

    def __post_init__(self) -> None:
        if not self.metric.strip():
            raise ValueError("metric must not be empty")
        if self.duration_ms is not None and self.duration_ms < 0:
            raise ValueError("duration_ms must be non-negative")
        if self.success and self.duration_ms is None:
            raise ValueError("successful samples require duration_ms")


@dataclass(frozen=True)
class LatencySummary:
    metric: str
    sample_count: int
    success_count: int
    failure_count: int
    min_ms: float | None
    p50_ms: float | None
    p95_ms: float | None
    max_ms: float | None
    jitter_ms: float | None
    timeout_rate: float


@dataclass(frozen=True)
class SimulationLatencyProfile:
    metric: str
    sample_count: int
    p50_ms: float
    p95_ms: float
    jitter_ms: float
    timeout_rate: float
    profile_version: int = 1


def _percentile(values: Sequence[float], percentile: float) -> float:
    """Nearest-rank percentile; percentile must be in [0, 100]."""
    if not values:
        raise ValueError("values must not be empty")
    if not 0 <= percentile <= 100:
        raise ValueError("percentile must be between 0 and 100")
    ordered = sorted(values)
    rank = max(1, ceil((percentile / 100.0) * len(ordered)))
    return ordered[rank - 1]


def summarize_latency(samples: Iterable[LatencySample], metric: str) -> LatencySummary:
    selected = [s for s in samples if s.metric == metric]
    successful = [float(s.duration_ms) for s in selected if s.success and s.duration_ms is not None]
    failures = len(selected) - len(successful)

    if successful:
        jitter = mean(abs(b - a) for a, b in zip(successful, successful[1:])) if len(successful) > 1 else 0.0
        min_ms = min(successful)
        p50_ms = _percentile(successful, 50)
        p95_ms = _percentile(successful, 95)
        max_ms = max(successful)
    else:
        jitter = min_ms = p50_ms = p95_ms = max_ms = None

    return LatencySummary(
        metric=metric,
        sample_count=len(selected),
        success_count=len(successful),
        failure_count=failures,
        min_ms=min_ms,
        p50_ms=p50_ms,
        p95_ms=p95_ms,
        max_ms=max_ms,
        jitter_ms=jitter,
        timeout_rate=(failures / len(selected)) if selected else 0.0,
    )


def build_simulation_profile(
    samples: Iterable[LatencySample],
    metric: str,
    *,
    minimum_samples: int = 20,
    profile_version: int = 1,
) -> SimulationLatencyProfile:
    """Build a profile only from sufficient successful measurements.

    Failed probes contribute to timeout_rate, but never become fabricated
    delay values. This profile is for simulation/replay only.
    """
    summary = summarize_latency(samples, metric)
    if summary.success_count < minimum_samples:
        raise ValueError(
            f"insufficient successful samples: {summary.success_count}/{minimum_samples}"
        )
    assert summary.p50_ms is not None
    assert summary.p95_ms is not None
    assert summary.jitter_ms is not None
    return SimulationLatencyProfile(
        metric=metric,
        sample_count=summary.sample_count,
        p50_ms=summary.p50_ms,
        p95_ms=summary.p95_ms,
        jitter_ms=summary.jitter_ms,
        timeout_rate=summary.timeout_rate,
        profile_version=profile_version,
    )
