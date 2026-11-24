from dataclasses import replace
from pathlib import Path
from typing import Dict, List, Optional

import joblib
import numpy as np
from sqlalchemy.orm import Session

from app.domain import (
    AlignmentState,
    MultiTimeframeReport,
    SignalResult,
    StrategyConfig,
    TechnicalSnapshot,
    TradeDirection,
)
from app.engines.signal_engine import AISignalEngine
from app.ml.features import FeatureEngineeringEngine
from app.ml.registry import ModelRegistryService
from app.ml.trainer import PlattCalibratedBundle


class MLSignalInferenceService:
    """
    Executes calibrated ML signal inference using the ACTIVE validated model when available,
    keeping model confidence distinct from calibrated probability and respecting NO_TRADE + MTF gates.
    """

    def __init__(self, artifacts_dir: Path) -> None:
        self._artifacts_dir = artifacts_dir
        self._cache: Dict[str, PlattCalibratedBundle] = {}

    def _load_bundle(self, model_id: str, artifact_filename: str) -> PlattCalibratedBundle:
        if model_id in self._cache:
            return self._cache[model_id]
        path = ModelRegistryService.resolve_safe_artifact_path(self._artifacts_dir, artifact_filename)
        bundle = joblib.load(path)
        self._cache[model_id] = bundle
        return bundle

    def infer_signal(
        self,
        db: Session,
        instrument: str,
        timeframe: str,
        candles_1m: List[Dict[str, float]],
        snapshot_1m: TechnicalSnapshot,
        mtf_report: Optional[MultiTimeframeReport],
        strategy: StrategyConfig,
    ) -> SignalResult:
        active_row = ModelRegistryService.get_active_model(db, instrument=instrument, timeframe=timeframe)
        if active_row is None or len(candles_1m) < 35:
            if mtf_report is not None:
                return AISignalEngine.evaluate_with_mtf(candles_1m, snapshot_1m, mtf_report, strategy)
            return AISignalEngine.evaluate(candles_1m, snapshot_1m, strategy)

        bundle = self._load_bundle(active_row.model_id, active_row.artifact_filename)
        vec = FeatureEngineeringEngine.extract_latest_features(candles_1m, strategy)
        X_latest = np.asarray([vec], dtype=float)
        prob_up = float(bundle.predict_calibrated_up(X_latest)[0])
        prob_down = 1.0 - prob_up

        # Calibrated probability of the favored side
        favored_bull = prob_up >= prob_down
        calibrated_prob = round(min(0.95, max(0.50, prob_up if favored_bull else prob_down)), 4)

        # Confidence score reflects margin from 0.50 uncertainty, intentionally distinct from win probability
        edge_margin = abs(prob_up - 0.5) * 2.0  # 0.0 to 1.0
        confidence = int(max(15, min(95, round(50.0 + edge_margin * 45.0))))

        baseline = AISignalEngine.evaluate(candles_1m, snapshot_1m, strategy)
        supporting = list(baseline.supporting_evidence)
        contradicting = list(baseline.contradicting_evidence)

        supporting.insert(
            0,
            f"ML Model {active_row.model_id} ({active_row.algorithm}) calibrated P(win)={calibrated_prob:.3f}",
        )

        if confidence < strategy.min_confidence:
            direction = TradeDirection.NO_TRADE
        else:
            direction = TradeDirection.CALL if favored_bull else TradeDirection.PUT

        if mtf_report is not None:
            if (
                direction == TradeDirection.CALL
                and mtf_report.alignment_state == AlignmentState.ALIGNED_BULLISH
            ) or (
                direction == TradeDirection.PUT
                and mtf_report.alignment_state == AlignmentState.ALIGNED_BEARISH
            ):
                confidence = min(96, confidence + 4)
                supporting.append(f"MTF aligned ({mtf_report.alignment_state.value})")
            elif mtf_report.alignment_state == AlignmentState.CONFLICTING:
                confidence = max(15, confidence - 18)
                contradicting.append("Higher timeframes (5m/15m) oppose ML direction")
                direction = TradeDirection.NO_TRADE

        explanation = (
            f"{direction.value} [ML:{active_row.model_id}]: Calibrated P(win)={calibrated_prob:.1%}, Confidence={confidence}%"
            if direction != TradeDirection.NO_TRADE
            else f"NO_TRADE [ML:{active_row.model_id}]: Calibrated edge below threshold (Confidence={confidence}% < {strategy.min_confidence}% or MTF conflict)"
        )
        risk_assessment = (
            "ML / MTF gate blocked execution (NO_TRADE)."
            if direction == TradeDirection.NO_TRADE
            else "ML inference passed gate; subject to RiskManagementEngine validation."
        )

        return replace(
            baseline,
            direction=direction,
            calibrated_probability=calibrated_prob,
            confidence_score=confidence,
            supporting_evidence=supporting,
            contradicting_evidence=contradicting,
            explanation=explanation,
            risk_assessment=risk_assessment,
        )
