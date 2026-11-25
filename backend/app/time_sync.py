"""Clock synchronization primitives for broker-connected trading.

All internal timestamps are timezone-aware UTC. Local time zones are presentation
concerns only; countdowns use a monotonic clock and are not affected by wall-clock
adjustments.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from statistics import median
from typing import Deque
from zoneinfo import ZoneInfo

UTC = timezone.utc
BANGKOK = ZoneInfo("Asia/Bangkok")


def require_aware_utc(value: datetime) -> datetime:
    """Reject ambiguous naive datetimes and normalize aware values to UTC."""
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must include a timezone")
    return value.astimezone(UTC)


def parse_server_timestamp(value: str | datetime) -> datetime:
    """Parse an ISO-8601 broker timestamp and normalize it to UTC."""
    if isinstance(value, datetime):
        return require_aware_utc(value)
    normalized = value.strip()
    if normalized.endswith(("Z", "z")):
        normalized = normalized[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError("server timestamp must be valid ISO-8601") from exc
    return require_aware_utc(parsed)


def format_bangkok(value: datetime) -> str:
    """Format an aware timestamp for the Asia/Bangkok UI."""
    return require_aware_utc(value).astimezone(BANGKOK).isoformat()


@dataclass(frozen=True)
class ClockStatus:
    synchronized: bool
    stale: bool
    offset_ms: float | None
    round_trip_ms: float | None
    sample_count: int
    last_server_time_utc: str | None
    last_observed_at_utc: str | None


class ServerClockSynchronizer:
    """Estimate broker-server offset using the request/response midpoint.

    Call observe() only after receiving a trustworthy timestamp from the broker.
    A device timezone setting is never used to interpret broker timestamps.
    """

    def __init__(
        self,
        max_samples: int = 9,
        stale_after_seconds: float = 30.0,
        max_acceptable_rtt_ms: float = 5000.0,
    ) -> None:
        if max_samples < 1:
            raise ValueError("max_samples must be at least 1")
        if stale_after_seconds <= 0:
            raise ValueError("stale_after_seconds must be positive")
        self._offsets_ms: Deque[float] = deque(maxlen=max_samples)
        self._stale_after_seconds = stale_after_seconds
        self._max_acceptable_rtt_ms = max_acceptable_rtt_ms
        self._last_server_time: datetime | None = None
        self._last_observed_at: datetime | None = None
        self._last_observed_monotonic: float | None = None
        self._last_rtt_ms: float | None = None

    def observe(
        self,
        server_time: str | datetime,
        *,
        request_started_utc: datetime,
        response_received_utc: datetime,
        request_started_monotonic: float,
        response_received_monotonic: float,
    ) -> ClockStatus:
        server_utc = parse_server_timestamp(server_time)
        started_utc = require_aware_utc(request_started_utc)
        received_utc = require_aware_utc(response_received_utc)
        rtt_ms = (response_received_monotonic - request_started_monotonic) * 1000.0
        if rtt_ms < 0:
            raise ValueError("response monotonic time precedes request start")
        if rtt_ms > self._max_acceptable_rtt_ms:
            return self.status(now_monotonic=response_received_monotonic)

        midpoint = started_utc + (received_utc - started_utc) / 2
        offset_ms = (server_utc - midpoint).total_seconds() * 1000.0

        self._offsets_ms.append(offset_ms)
        self._last_server_time = server_utc
        self._last_observed_at = received_utc
        self._last_observed_monotonic = response_received_monotonic
        self._last_rtt_ms = rtt_ms
        return self.status(now_monotonic=response_received_monotonic)

    def status(self, *, now_monotonic: float) -> ClockStatus:
        stale = (
            self._last_observed_monotonic is None
            or now_monotonic - self._last_observed_monotonic > self._stale_after_seconds
        )
        return ClockStatus(
            synchronized=bool(self._offsets_ms) and not stale,
            stale=stale,
            offset_ms=median(self._offsets_ms) if self._offsets_ms else None,
            round_trip_ms=self._last_rtt_ms,
            sample_count=len(self._offsets_ms),
            last_server_time_utc=(
                self._last_server_time.isoformat() if self._last_server_time else None
            ),
            last_observed_at_utc=(
                self._last_observed_at.isoformat() if self._last_observed_at else None
            ),
        )

    def estimated_server_now(
        self, local_now_utc: datetime, *, now_monotonic: float
    ) -> datetime:
        status = self.status(now_monotonic=now_monotonic)
        if not status.synchronized or status.offset_ms is None:
            raise RuntimeError("broker clock is not synchronized or sample is stale")
        local_utc = require_aware_utc(local_now_utc)
        return local_utc + timedelta(milliseconds=status.offset_ms)


class MonotonicCountdown:
    """Countdown unaffected by device timezone or wall-clock corrections."""

    def __init__(self) -> None:
        self._deadline: float | None = None

    def start(self, duration_seconds: float, *, now_monotonic: float) -> None:
        if duration_seconds <= 0:
            raise ValueError("duration_seconds must be positive")
        self._deadline = now_monotonic + duration_seconds

    def remaining(self, *, now_monotonic: float) -> float:
        if self._deadline is None:
            raise RuntimeError("countdown has not been started")
        return max(0.0, self._deadline - now_monotonic)
