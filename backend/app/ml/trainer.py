from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Sequence
import uuid

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from app.domain import ModelStatus, StrategyConfig
from app.ml.backtesting import BacktestReport, WalkForwardBacktester
from app.ml.drift import compute_feature_baseline, dumps_baseline_stats
from app.ml.features import FEATURE_NAMES, FeatureEngineeringEngine
from app.ml.splitting import ChronologicalSplitter


@dataclass(frozen=True)
class ValidationGatePolicy:
    max_brier_score: float = 0.265
    max_ece: float = 0.20
    min_accuracy: float = 0.48


@dataclass(frozen=True)
class TrainingOutcome:
    model_id: str
    instrument: str
    timeframe: str
    algorithm: str
    status: ModelStatus
    train_samples: int
    validation_samples: int
    test_samples: int
    backtest_report: BacktestReport
    artifact_filename: str
    validation_notes: str
    dataset_version: str = "ds_v2_unknown"
    feature_count: int = len(FEATURE_NAMES)
    baseline_stats_json: str = "{}"


class PlattCalibratedBundle:
    """Stores a fitted base pipeline and an out-of-sample Platt sigmoid calibrator."""

    def __init__(
        self,
        base_pipeline: Pipeline,
        calibrator: LogisticRegression | None,
        algorithm: str,
        feature_names: List[str],
    ) -> None:
        self.base_pipeline = base_pipeline
        self.calibrator = calibrator
        self.algorithm = algorithm
        self.feature_names = feature_names

    @staticmethod
    def _to_logits(probs_up: np.ndarray) -> np.ndarray:
        clipped = np.clip(probs_up, 1e-6, 1.0 - 1e-6)
        return np.log(clipped / (1.0 - clipped)).reshape(-1, 1)

    def predict_raw_up(self, X: np.ndarray) -> np.ndarray:
        proba = self.base_pipeline.predict_proba(X)
        return proba[:, 1]

    def predict_calibrated_up(self, X: np.ndarray) -> np.ndarray:
        raw_up = self.predict_raw_up(X)
        if self.calibrator is None:
            return raw_up
        logits = self._to_logits(raw_up)
        return self.calibrator.predict_proba(logits)[:, 1]


class ModelTrainer:
    """Trains Scikit-learn classifiers using chronological splits and out-of-sample calibration."""

    @staticmethod
    def _build_pipeline(algorithm: str) -> Pipeline:
        if algorithm == "xgb_boosting":
            try:
                from xgboost import XGBClassifier
                clf: Any = XGBClassifier(
                    max_depth=4,
                    learning_rate=0.06,
                    n_estimators=100,
                    random_state=42,
                    eval_metric="logloss",
                )
            except ImportError:
                clf = HistGradientBoostingClassifier(
                    max_depth=4,
                    learning_rate=0.06,
                    max_iter=80,
                    min_samples_leaf=15,
                    random_state=42,
                )
            return Pipeline([("scaler", StandardScaler()), ("clf", clf)])
        if algorithm == "hist_gradient_boosting":
            clf = HistGradientBoostingClassifier(
                max_depth=4,
                learning_rate=0.06,
                max_iter=80,
                min_samples_leaf=15,
                random_state=42,
            )
            return Pipeline([("scaler", StandardScaler()), ("clf", clf)])
        if algorithm == "random_forest":
            clf = RandomForestClassifier(
                n_estimators=120,
                max_depth=6,
                min_samples_leaf=10,
                random_state=42,
                n_jobs=-1,
            )
            return Pipeline([("scaler", StandardScaler()), ("clf", clf)])
        if algorithm == "logistic_regression":
            clf = LogisticRegression(C=1.0, max_iter=500, random_state=42)
            return Pipeline([("scaler", StandardScaler()), ("clf", clf)])
        raise ValueError(
            f"Unsupported algorithm '{algorithm}'. Use 'logistic_regression', 'hist_gradient_boosting', 'random_forest', or 'xgb_boosting'."
        )

    @classmethod
    def _fit_calibrated_bundle(
        cls,
        X_train: np.ndarray,
        y_train: np.ndarray,
        X_val: np.ndarray,
        y_val: np.ndarray,
        algorithm: str,
    ) -> PlattCalibratedBundle:
        if len(np.unique(y_train)) < 2:
            raise ValueError("Training split must contain both upward and downward classes.")

        pipeline = cls._build_pipeline(algorithm)
        pipeline.fit(X_train, y_train)

        calibrator: LogisticRegression | None = None
        if len(np.unique(y_val)) >= 2 and len(y_val) >= 10:
            raw_val = pipeline.predict_proba(X_val)[:, 1]
            logits_val = PlattCalibratedBundle._to_logits(raw_val)
            calibrator = LogisticRegression(C=1.0, max_iter=300, random_state=42)
            calibrator.fit(logits_val, y_val)

        return PlattCalibratedBundle(
            base_pipeline=pipeline,
            calibrator=calibrator,
            algorithm=algorithm,
            feature_names=list(FEATURE_NAMES),
        )

    @classmethod
    def train_and_validate(
        cls,
        instrument: str,
        timeframe: str,
        candles_1m: Sequence[Dict[str, float]],
        strategy: StrategyConfig,
        artifacts_dir: Path,
        algorithm: str = "logistic_regression",
        payout_rate: float = 0.85,
        execution_delay_bars: int = 1,
        gate_policy: ValidationGatePolicy | None = None,
    ) -> TrainingOutcome:
        gate = gate_policy or ValidationGatePolicy()
        dataset = FeatureEngineeringEngine.build_dataset(
            candles_1m=candles_1m,
            strategy=strategy,
            horizon_bars=1,
            execution_delay_bars=execution_delay_bars,
        )
        dataset_version = FeatureEngineeringEngine.compute_dataset_version(
            candles_1m=candles_1m,
            feature_names=dataset.feature_names,
        )

        X_all = np.asarray(dataset.features, dtype=float)
        y_all = np.asarray(dataset.labels, dtype=int)
        r_all = np.asarray(dataset.future_returns, dtype=float)

        # 1. Walk-forward out-of-sample evaluation across chronological folds
        folds = ChronologicalSplitter.generate_walk_forward_folds(
            timestamps=dataset.timestamps,
            n_splits=3,
            min_train_size=120,
        )

        oos_probs: List[float] = []
        oos_labels: List[int] = []
        oos_returns: List[float] = []

        for fold in folds:
            # Verify strict chronological boundary before fitting
            assert fold.train_end_ts < fold.val_start_ts <= fold.val_end_ts < fold.test_start_ts
            bundle = cls._fit_calibrated_bundle(
                X_all[fold.train_indices],
                y_all[fold.train_indices],
                X_all[fold.val_indices],
                y_all[fold.val_indices],
                algorithm=algorithm,
            )
            preds = bundle.predict_calibrated_up(X_all[fold.test_indices])
            oos_probs.extend(float(p) for p in preds)
            oos_labels.extend(int(y) for y in y_all[fold.test_indices])
            oos_returns.extend(float(r) for r in r_all[fold.test_indices])

        wf_report = WalkForwardBacktester.evaluate(
            probs=oos_probs,
            y_true=oos_labels,
            future_returns=oos_returns,
            trade_amount=strategy.trade_amount,
            payout_rate=payout_rate,
        )

        # 2. Train final candidate model on chronological train/val split
        split = ChronologicalSplitter.split_dataset(dataset.timestamps, train_ratio=0.65, val_ratio=0.20)
        final_bundle = cls._fit_calibrated_bundle(
            X_all[split.train_indices],
            y_all[split.train_indices],
            X_all[split.val_indices],
            y_all[split.val_indices],
            algorithm=algorithm,
        )

        # Compute training baseline statistics for future drift detection (no leakage)
        train_rows = [dataset.features[i] for i in split.train_indices]
        baseline_stats = compute_feature_baseline(train_rows, dataset.feature_names)
        baseline_stats_json = dumps_baseline_stats(baseline_stats)

        # 3. Apply Out-of-Sample Walk-Forward Validation Gate (never auto-deploy to ACTIVE)
        reasons: List[str] = []
        if wf_report.brier_score > gate.max_brier_score:
            reasons.append(f"Brier {wf_report.brier_score:.4f} > {gate.max_brier_score:.4f}")
        if wf_report.ece > gate.max_ece:
            reasons.append(f"ECE {wf_report.ece:.4f} > {gate.max_ece:.4f}")
        if wf_report.accuracy < gate.min_accuracy:
            reasons.append(f"OOS accuracy {wf_report.accuracy:.4f} < {gate.min_accuracy:.4f}")

        if reasons:
            status = ModelStatus.REJECTED
            notes = f"[{dataset_version}] Rejected by walk-forward gate: " + "; ".join(reasons)
        else:
            status = ModelStatus.VALIDATED
            notes = (
                f"[{dataset_version}] Passed walk-forward gate ({len(folds)} folds): "
                f"OOS Acc={wf_report.accuracy:.3f}, Brier={wf_report.brier_score:.4f}, ECE={wf_report.ece:.4f}"
            )

        # 4. Save artifact safely inside confined artifacts directory
        model_id = f"mdl_{uuid.uuid4().hex[:12]}"
        artifact_filename = f"{model_id}.joblib"
        base_dir = artifacts_dir.resolve()
        base_dir.mkdir(parents=True, exist_ok=True)
        target_path = (base_dir / artifact_filename).resolve()
        if not target_path.is_relative_to(base_dir):
            raise ValueError("Unsafe model artifact path detected.")
        joblib.dump(final_bundle, target_path)

        return TrainingOutcome(
            model_id=model_id,
            instrument=instrument,
            timeframe=timeframe,
            algorithm=algorithm,
            status=status,
            train_samples=len(split.train_indices),
            validation_samples=len(split.val_indices),
            test_samples=len(oos_labels),
            backtest_report=wf_report,
            artifact_filename=artifact_filename,
            validation_notes=notes[:512],
            dataset_version=dataset_version,
            feature_count=len(dataset.feature_names),
            baseline_stats_json=baseline_stats_json,
        )
