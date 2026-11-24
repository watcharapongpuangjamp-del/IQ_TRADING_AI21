from dataclasses import dataclass
from typing import Dict, List, Optional
import uuid

from app.domain import (
    DataQualityReport,
    MultiTimeframeReport,
    ReplayState,
    SignalResult,
    StrategyConfig,
)
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.multi_timeframe import MultiTimeframeEngine
from app.engines.signal_engine import AISignalEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine


@dataclass(frozen=True)
class ReplayStepEvaluation:
    state: ReplayState
    data_quality: DataQualityReport
    mtf_report: MultiTimeframeReport
    signal: SignalResult


class SimulatorReplayEngine:
    """
    Deterministic chronological replay engine with strict timestamp cursor (`ts <= cursor_ts`)
    so future bars never leak into technical analysis or signals (zero look-ahead bias).
    """

    def __init__(self) -> None:
        self._session_id: str = ""
        self._instrument: str = "EUR/USD"
        self._timeframe: str = "1m"
        self._series_1m: List[Dict[str, float]] = []
        self._initial_cursor: int = 0
        self._cursor: int = 0
        self._active: bool = False

    def start(
        self,
        instrument: str,
        candles_1m: List[Dict[str, float]],
        initial_window: int = 60,
    ) -> ReplayState:
        sorted_1m = sorted((dict(c) for c in candles_1m), key=lambda x: int(x["timestamp"]))
        valid, reason = MarketIntelligenceEngine.validate_candles(sorted_1m)
        if not valid:
            raise ValueError(f"Cannot start replay with invalid 1m history: {reason}")

        warmup = max(25, min(initial_window, len(sorted_1m)))
        self._session_id = f"replay_{uuid.uuid4().hex[:10]}"
        self._instrument = instrument
        self._timeframe = "1m"
        self._series_1m = sorted_1m
        self._initial_cursor = warmup - 1
        self._cursor = self._initial_cursor
        self._active = True
        return self.get_state()

    def step(self, steps: int = 1) -> ReplayState:
        if not self._active:
            raise RuntimeError("No active replay session. Call start() first.")
        if steps < 1:
            raise ValueError("Replay step must be >= 1.")
        self._cursor = min(len(self._series_1m) - 1, self._cursor + steps)
        return self.get_state()

    def reset(self) -> ReplayState:
        if not self._active:
            raise RuntimeError("No active replay session. Call start() first.")
        self._cursor = self._initial_cursor
        return self.get_state()

    def get_state(self) -> ReplayState:
        cursor_ts = int(self._series_1m[self._cursor]["timestamp"]) if self._active and self._series_1m else 0
        return ReplayState(
            session_id=self._session_id,
            instrument=self._instrument,
            timeframe=self._timeframe,
            cursor_index=self._cursor,
            total_bars=len(self._series_1m),
            cursor_timestamp=cursor_ts,
            active=self._active,
        )

    def visible_candles(self) -> Dict[str, List[Dict[str, float]]]:
        if not self._active:
            raise RuntimeError("No active replay session.")
        cursor_ts = int(self._series_1m[self._cursor]["timestamp"])
        visible_1m = [dict(c) for c in self._series_1m if int(c["timestamp"]) <= cursor_ts]
        visible_5m = MarketIntelligenceEngine.aggregate_candles(visible_1m, 5)
        visible_15m = MarketIntelligenceEngine.aggregate_candles(visible_1m, 15)
        return {
            "1m": visible_1m,
            "5m": visible_5m,
            "15m": visible_15m,
        }

    def evaluate_current(self, strategy: Optional[StrategyConfig] = None) -> ReplayStepEvaluation:
        if not self._active:
            raise RuntimeError("No active replay session.")
        cfg = strategy or StrategyConfig(instrument=self._instrument, timeframe="1m")
        slices = self.visible_candles()
        candles_1m = slices["1m"]
        quality = MarketIntelligenceEngine.inspect_candles(candles_1m, expected_timeframe_sec=60)
        snap_1m = TechnicalAnalysisEngine.analyze(candles_1m, cfg)
        mtf = MultiTimeframeEngine.evaluate(self._instrument, slices, cfg)
        sig = AISignalEngine.evaluate_with_mtf(candles_1m, snap_1m, mtf, cfg)
        return ReplayStepEvaluation(
            state=self.get_state(),
            data_quality=quality,
            mtf_report=mtf,
            signal=sig,
        )
