import json
import unittest

from iq_engine.engine import ENGINE_VERSION, analyze_candles_json, engine_status


def candle(open_, high, low, close):
    return {"open": open_, "high": high, "low": low, "close": close}


class EngineStatusTests(unittest.TestCase):
    def test_status_reports_embedded_runtime(self):
        status = engine_status()
        self.assertEqual(status["runtime"], "embedded-cpython")
        self.assertEqual(status["engine_version"], ENGINE_VERSION)
        self.assertTrue(status["ready"])


class CandleAnalysisTests(unittest.TestCase):
    def test_up_direction(self):
        payload = [candle(10, 12, 9, 11), candle(11, 13, 10, 12.5)]
        result = json.loads(analyze_candles_json(json.dumps(payload)))
        self.assertTrue(result["valid"])
        self.assertEqual(result["direction"], "UP")
        self.assertEqual(result["candles"], 2)
        self.assertEqual(result["last_change"], 1.5)

    def test_down_direction(self):
        payload = [candle(12, 13, 11, 12), candle(12, 12.5, 9, 10)]
        result = json.loads(analyze_candles_json(json.dumps(payload)))
        self.assertEqual(result["direction"], "DOWN")

    def test_flat_direction(self):
        payload = [candle(10, 11, 9, 10), candle(10, 12, 8, 10)]
        result = json.loads(analyze_candles_json(json.dumps(payload)))
        self.assertEqual(result["direction"], "FLAT")

    def test_rejects_invalid_json(self):
        result = json.loads(analyze_candles_json("{not-json"))
        self.assertFalse(result["valid"])
        self.assertIn("error", result)

    def test_requires_two_or_more_candles(self):
        result = json.loads(analyze_candles_json(json.dumps([candle(1, 2, 0, 1)])))
        self.assertFalse(result["valid"])
        self.assertIn("two candles", result["error"])

    def test_rejects_non_object_candle(self):
        result = json.loads(analyze_candles_json(json.dumps([{}, "bad"])))
        self.assertFalse(result["valid"])

    def test_rejects_invalid_high_and_low(self):
        invalid_high = [candle(10, 9, 8, 9), candle(9, 10, 8, 9)]
        invalid_low = [candle(10, 11, 10.5, 10.8), candle(10.8, 11, 10, 10.9)]
        self.assertFalse(json.loads(analyze_candles_json(json.dumps(invalid_high)))["valid"])
        self.assertFalse(json.loads(analyze_candles_json(json.dumps(invalid_low)))["valid"])

    def test_rejects_non_finite_values(self):
        payload = '[{"open":1,"high":2,"low":0,"close":NaN},{"open":1,"high":2,"low":0,"close":1}]'
        result = json.loads(analyze_candles_json(payload))
        self.assertFalse(result["valid"])
        self.assertIn("non-finite", result["error"])

    def test_rejects_missing_ohlc_field(self):
        payload = [{"open": 1, "high": 2, "low": 0}, candle(1, 2, 0, 1)]
        result = json.loads(analyze_candles_json(json.dumps(payload)))
        self.assertFalse(result["valid"])


if __name__ == "__main__":
    unittest.main()
