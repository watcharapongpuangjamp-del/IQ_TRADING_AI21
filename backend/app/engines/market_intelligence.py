from typing import Dict, List, Tuple


class MarketIntelligenceEngine:
    """Validates OHLCV candle integrity and classifies market structure."""

    @staticmethod
    def validate_candles(candles: List[Dict[str, float]]) -> Tuple[bool, str]:
        if len(candles) < 25:
            return False, "Insufficient candles (minimum 25 required)."
        prev_ts = 0
        for idx, c in enumerate(candles):
            ts = int(c.get("timestamp", 0))
            o = float(c.get("open", 0))
            h = float(c.get("high", 0))
            l = float(c.get("low", 0))
            cl = float(c.get("close", 0))
            if ts <= 0 or ts < prev_ts:
                return False, f"Invalid or non-monotonic timestamp at index {idx}."
            if min(o, h, l, cl) <= 0:
                return False, f"Non-positive OHLC price at index {idx}."
            if h < max(o, cl) or l > min(o, cl) or h < l:
                return False, f"Corrupted OHLC bounds at index {idx}."
            prev_ts = ts
        return True, "Market data validated."
