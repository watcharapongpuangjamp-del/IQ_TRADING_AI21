import unittest
from datetime import datetime, timedelta, timezone

from app.time_sync import (
    BANGKOK,
    MonotonicCountdown,
    ServerClockSynchronizer,
    format_bangkok,
    parse_server_timestamp,
    require_aware_utc,
)


class TimeSyncTests(unittest.TestCase):
    def test_rejects_naive_datetime(self):
        with self.assertRaises(ValueError):
            require_aware_utc(datetime(2026, 11, 25, 12, 0))

    def test_parses_z_timestamp_as_utc(self):
        value = parse_server_timestamp("2026-11-25T12:00:00Z")
        self.assertEqual(value.tzinfo, timezone.utc)
        self.assertEqual(value.hour, 12)

    def test_normalizes_offset_timestamp(self):
        value = parse_server_timestamp("2026-11-25T19:00:00+07:00")
        self.assertEqual(value, datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc))

    def test_formats_bangkok_time(self):
        value = datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc)
        self.assertEqual(format_bangkok(value), "2026-11-25T19:00:00+07:00")
        self.assertEqual(BANGKOK.key, "Asia/Bangkok")

    def test_estimates_server_offset_from_midpoint(self):
        sync = ServerClockSynchronizer()
        start = datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc)
        received = start + timedelta(seconds=0.2)
        server = start + timedelta(seconds=0.3)
        status = sync.observe(
            server,
            request_started_utc=start,
            response_received_utc=received,
            request_started_monotonic=10.0,
            response_received_monotonic=10.2,
        )
        self.assertTrue(status.synchronized)
        self.assertAlmostEqual(status.offset_ms, 200.0, delta=0.001)

    def test_rejects_stale_server_clock(self):
        sync = ServerClockSynchronizer(stale_after_seconds=5)
        start = datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc)
        sync.observe(
            start,
            request_started_utc=start,
            response_received_utc=start,
            request_started_monotonic=10.0,
            response_received_monotonic=10.0,
        )
        self.assertTrue(sync.status(now_monotonic=16.0).stale)
        with self.assertRaises(RuntimeError):
            sync.estimated_server_now(start, now_monotonic=16.0)

    def test_ignores_high_latency_sample(self):
        sync = ServerClockSynchronizer(max_acceptable_rtt_ms=100)
        start = datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc)
        status = sync.observe(
            start,
            request_started_utc=start,
            response_received_utc=start + timedelta(seconds=1),
            request_started_monotonic=1.0,
            response_received_monotonic=2.0,
        )
        self.assertEqual(status.sample_count, 0)
        self.assertFalse(status.synchronized)

    def test_monotonic_countdown(self):
        countdown = MonotonicCountdown()
        countdown.start(30, now_monotonic=100.0)
        self.assertEqual(countdown.remaining(now_monotonic=112.5), 17.5)
        self.assertEqual(countdown.remaining(now_monotonic=140.0), 0.0)


if __name__ == "__main__":
    unittest.main()
