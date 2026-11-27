import unittest

from app.engines.latency_intelligence import (
    LatencySample,
    build_simulation_profile,
    summarize_latency,
)


class LatencyIntelligenceTests(unittest.TestCase):
    def test_summary_uses_successful_samples_and_reports_failures(self):
        samples = [
            LatencySample("broker_rtt", 10),
            LatencySample("broker_rtt", 20),
            LatencySample("broker_rtt", None, success=False),
            LatencySample("app_backend_rtt", 999),
        ]
        result = summarize_latency(samples, "broker_rtt")
        self.assertEqual(result.sample_count, 3)
        self.assertEqual(result.success_count, 2)
        self.assertEqual(result.failure_count, 1)
        self.assertEqual(result.min_ms, 10)
        self.assertEqual(result.p50_ms, 10)
        self.assertEqual(result.p95_ms, 20)
        self.assertEqual(result.jitter_ms, 10)
        self.assertAlmostEqual(result.timeout_rate, 1 / 3)

    def test_empty_metric_has_no_fabricated_latency(self):
        result = summarize_latency([], "broker_rtt")
        self.assertEqual(result.sample_count, 0)
        self.assertIsNone(result.p50_ms)
        self.assertIsNone(result.p95_ms)
        self.assertIsNone(result.jitter_ms)

    def test_profile_requires_minimum_successful_samples(self):
        samples = [LatencySample("order_ack", float(i)) for i in range(19)]
        with self.assertRaisesRegex(ValueError, "insufficient"):
            build_simulation_profile(samples, "order_ack", minimum_samples=20)

    def test_profile_is_derived_from_observed_distribution(self):
        samples = [LatencySample("order_ack", float(i)) for i in range(1, 21)]
        samples.append(LatencySample("order_ack", None, success=False))
        profile = build_simulation_profile(samples, "order_ack", minimum_samples=20)
        self.assertEqual(profile.sample_count, 21)
        self.assertEqual(profile.p50_ms, 10)
        self.assertEqual(profile.p95_ms, 19)
        self.assertEqual(profile.timeout_rate, 1 / 21)

    def test_negative_duration_is_rejected(self):
        with self.assertRaises(ValueError):
            LatencySample("broker_rtt", -1)


if __name__ == "__main__":
    unittest.main()
