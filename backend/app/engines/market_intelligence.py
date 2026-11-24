import math
from typing import Dict, List, Optional, Tuple

from app.domain import DataQualityReport


class MarketIntelligenceEngine:
    """Validates OHLCV candle integrity, detects gaps/outliers, and resamples timeframes."""

    @staticmethod
    def validate_candles(candles: List[Dict[str, float]]) -> Tuple[bool, str]:
        report = MarketIntelligenceEngine.inspect_candles(candles)
        return report.valid, report.reason

    @staticmethod
    def inspect_candles(
        candles: List[Dict[str, float]],
        expected_timeframe_sec: Optional[int] = None,
    ) -> DataQualityReport:
        count = len(candles)
        if count < 25:
            return DataQualityReport(
                valid=False,
                reason="Insufficient candles (minimum 25 required).",
                candle_count=count,
            )

        prev_ts = 0
        gap_count = 0
        outlier_count = 0
        duplicate_count = 0
        expected_ms = expected_timeframe_sec * 1000 if expected_timeframe_sec else None

        for idx, c in enumerate(candles):
            ts = int(c.get("timestamp", 0))
            o = float(c.get("open", 0))
            h = float(c.get("high", 0))
            l = float(c.get("low", 0))
            cl = float(c.get("close", 0))
            vol = float(c.get("volume", 0.0))

            if not all(math.isfinite(val) for val in (o, h, l, cl, vol)):
                return DataQualityReport(
                    valid=False,
                    reason=f"Non-finite OHLCV value at index {idx}.",
                    candle_count=count,
                    gap_count=gap_count,
                    outlier_count=outlier_count,
                    duplicate_count=duplicate_count,
                )

            if ts <= 0:
                return DataQualityReport(
                    valid=False,
                    reason=f"Invalid or non-monotonic timestamp at index {idx}.",
                    candle_count=count,
                )

            if idx > 0 and ts == prev_ts:
                duplicate_count += 1
                return DataQualityReport(
                    valid=False,
                    reason=f"Duplicate timestamp at index {idx}.",
                    candle_count=count,
                    duplicate_count=duplicate_count,
                )

            if ts < prev_ts:
                return DataQualityReport(
                    valid=False,
                    reason=f"Invalid or non-monotonic timestamp at index {idx}.",
                    candle_count=count,
                )

            if min(o, h, l, cl) <= 0 or vol < 0:
                return DataQualityReport(
                    valid=False,
                    reason=f"Non-positive OHLC price at index {idx}.",
                    candle_count=count,
                )

            if h < max(o, cl) or l > min(o, cl) or h < l:
                return DataQualityReport(
                    valid=False,
                    reason=f"Corrupted OHLC bounds at index {idx}.",
                    candle_count=count,
                )

            if expected_ms is not None and idx > 0:
                if (ts - prev_ts) > int(expected_ms * 2.5):
                    gap_count += 1

            # Flag extreme single-bar spike (> 15% intrabar range)
            if o > 0 and ((h - l) / o) > 0.15:
                outlier_count += 1

            prev_ts = ts

        return DataQualityReport(
            valid=True,
            reason="Market data validated.",
            candle_count=count,
            gap_count=gap_count,
            outlier_count=outlier_count,
            duplicate_count=duplicate_count,
        )

    @staticmethod
    def aggregate_candles(candles: List[Dict[str, float]], target_minutes: int) -> List[Dict[str, float]]:
        """Resamples lower-timeframe OHLCV candles into higher timeframe buckets chronologically."""
        if not candles or target_minutes <= 1:
            return [dict(c) for c in candles]

        bucket_ms = target_minutes * 60_000
        grouped: List[Dict[str, float]] = []
        current_bucket: Optional[int] = None

        for c in sorted(candles, key=lambda x: int(x["timestamp"])):
            ts = int(c["timestamp"])
            bucket_ts = (ts // bucket_ms) * bucket_ms
            o = float(c["open"])
            h = float(c["high"])
            l = float(c["low"])
            cl = float(c["close"])
            vol = float(c.get("volume", 0.0))

            if current_bucket is None or bucket_ts != current_bucket:
                current_bucket = bucket_ts
                grouped.append(
                    {
                        "timestamp": bucket_ts,
                        "open": round(o, 6),
                        "high": round(h, 6),
                        "low": round(l, 6),
                        "close": round(cl, 6),
                        "volume": round(vol, 2),
                    }
                )
            else:
                last = grouped[-1]
                last["high"] = round(max(last["high"], h), 6)
                last["low"] = round(min(last["low"], l), 6)
                last["close"] = round(cl, 6)
                last["volume"] = round(last["volume"] + vol, 2)

        return grouped
