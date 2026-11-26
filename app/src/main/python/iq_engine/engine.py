"""Dependency-free starter engine for embedded CPython on Android."""
from __future__ import annotations

import json
import math
from typing import Any

ENGINE_VERSION = "0.1.0"


def engine_status() -> dict[str, Any]:
    return {
        "runtime": "embedded-cpython",
        "engine_version": ENGINE_VERSION,
        "ready": True,
    }


def analyze_candles_json(candles_json: str) -> str:
    """Validate OHLC candles and return a deterministic local snapshot."""
    try:
        candles = json.loads(candles_json)
        if not isinstance(candles, list) or len(candles) < 2:
            raise ValueError("At least two candles are required.")

        normalized = []
        for index, candle in enumerate(candles):
            if not isinstance(candle, dict):
                raise ValueError(f"Candle {index} must be an object.")
            values = {key: float(candle[key]) for key in ("open", "high", "low", "close")}
            if not all(math.isfinite(value) for value in values.values()):
                raise ValueError(f"Candle {index} contains a non-finite value.")
            if values["high"] < max(values.values()):
                raise ValueError(f"Candle {index} has an invalid high.")
            if values["low"] > min(values.values()):
                raise ValueError(f"Candle {index} has an invalid low.")
            normalized.append(values)

        closes = [item["close"] for item in normalized]
        change = closes[-1] - closes[-2]
        direction = "UP" if change > 0 else "DOWN" if change < 0 else "FLAT"
        return json.dumps({
            "valid": True,
            "candles": len(normalized),
            "last_close": closes[-1],
            "last_change": change,
            "direction": direction,
            "engine_version": ENGINE_VERSION,
        }, separators=(",", ":"), allow_nan=False)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        return json.dumps({
            "valid": False,
            "error": str(exc),
            "engine_version": ENGINE_VERSION,
        }, separators=(",", ":"))
