from datetime import datetime, timezone
from pathlib import Path
import re
from typing import Dict, List, Optional, Sequence, Tuple

from sqlalchemy.orm import Session

from app.domain import ModelStatus, StrategyConfig
from app.ml.drift import (
    DriftEvaluationReport,
    evaluate_model_drift as compute_drift_report,
    loads_baseline_stats,
)
from app.ml.features import FEATURE_NAMES, FeatureEngineeringEngine
from app.ml.trainer import TrainingOutcome
from app.models import ModelRegistryModel

SAFE_ARTIFACT_NAME = re.compile(r"^mdl_[a-f0-9]{12}\.joblib$")


class ModelRegistryService:
    """
    Controlled Model Registry backed by SQLAlchemy and confined server-side artifacts.
    Prevents automatic promotion or automatic rollback of ACTIVE/LIVE models.
    """

    @staticmethod
    def resolve_safe_artifact_path(artifacts_dir: Path, filename: str) -> Path:
        if not SAFE_ARTIFACT_NAME.match(filename):
            raise ValueError("Invalid model artifact filename format.")
        base_dir = artifacts_dir.resolve()
        candidate = (base_dir / filename).resolve()
        if not candidate.is_relative_to(base_dir):
            raise ValueError("Model artifact path escaped base directory.")
        return candidate

    @staticmethod
    def record_training_outcome(db: Session, outcome: TrainingOutcome) -> ModelRegistryModel:
        row = ModelRegistryModel(
            model_id=outcome.model_id,
            instrument=outcome.instrument,
            timeframe=outcome.timeframe,
            algorithm=outcome.algorithm,
            status=outcome.status.value,
            train_samples=outcome.train_samples,
            validation_samples=outcome.validation_samples,
            test_samples=outcome.test_samples,
            out_of_sample_accuracy=outcome.backtest_report.accuracy,
            brier_score=outcome.backtest_report.brier_score,
            log_loss=outcome.backtest_report.log_loss,
            ece=outcome.backtest_report.ece,
            simulated_trades=outcome.backtest_report.total_trades,
            simulated_win_rate=outcome.backtest_report.win_rate,
            simulated_net_pnl=outcome.backtest_report.net_pnl,
            artifact_filename=outcome.artifact_filename,
            validation_notes=outcome.validation_notes,
            dataset_version=outcome.dataset_version,
            feature_count=outcome.feature_count,
            baseline_stats_json=outcome.baseline_stats_json,
            drift_status="HEALTHY",
            drift_score=0.0,
            live_settled_trades=0,
            live_win_rate=0.0,
            drift_notes="Baseline captured at training",
        )
        db.add(row)
        db.commit()
        db.refresh(row)
        return row

    @staticmethod
    def list_models(
        db: Session,
        instrument: Optional[str] = None,
        limit: int = 50,
    ) -> List[ModelRegistryModel]:
        q = db.query(ModelRegistryModel)
        if instrument:
            q = q.filter(ModelRegistryModel.instrument == instrument)
        return q.order_by(ModelRegistryModel.created_at.desc()).limit(limit).all()

    @staticmethod
    def get_active_model(db: Session, instrument: str, timeframe: str) -> Optional[ModelRegistryModel]:
        return (
            db.query(ModelRegistryModel)
            .filter(
                ModelRegistryModel.instrument == instrument,
                ModelRegistryModel.timeframe == timeframe,
                ModelRegistryModel.status == ModelStatus.ACTIVE.value,
            )
            .order_by(ModelRegistryModel.promoted_at.desc())
            .first()
        )

    @classmethod
    def promote_model(
        cls,
        db: Session,
        model_id: str,
        artifacts_dir: Path,
        owner_confirmed: bool = False,
    ) -> ModelRegistryModel:
        if not owner_confirmed:
            raise PermissionError("Explicit human owner confirmation is required to promote a model.")

        row = db.get(ModelRegistryModel, model_id)
        if row is None:
            raise KeyError(f"Model '{model_id}' not found.")

        if row.status not in (ModelStatus.VALIDATED.value, ModelStatus.ACTIVE.value):
            raise ValueError(
                f"Model '{model_id}' has status '{row.status}' and cannot be promoted without passing walk-forward validation."
            )

        artifact_path = cls.resolve_safe_artifact_path(artifacts_dir, row.artifact_filename)
        if not artifact_path.exists():
            raise FileNotFoundError(f"Artifact file missing for model '{model_id}'.")

        # Demote any previously active model for the same (instrument, timeframe)
        previous_active = (
            db.query(ModelRegistryModel)
            .filter(
                ModelRegistryModel.instrument == row.instrument,
                ModelRegistryModel.timeframe == row.timeframe,
                ModelRegistryModel.status == ModelStatus.ACTIVE.value,
                ModelRegistryModel.model_id != row.model_id,
            )
            .all()
        )
        for prev in previous_active:
            prev.status = ModelStatus.VALIDATED.value

        row.status = ModelStatus.ACTIVE.value
        row.promoted_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(row)
        return row

    @classmethod
    def evaluate_model_drift(
        cls,
        db: Session,
        model_id: str,
        recent_candles: Sequence[Dict[str, float]],
        orders: Sequence[object],
        strategy: StrategyConfig,
    ) -> Tuple[ModelRegistryModel, DriftEvaluationReport]:
        row = db.get(ModelRegistryModel, model_id)
        if row is None:
            raise KeyError(f"Model '{model_id}' not found.")

        baseline_stats = loads_baseline_stats(row.baseline_stats_json)
        feature_width = max(1, min(len(FEATURE_NAMES), int(row.feature_count or len(FEATURE_NAMES))))
        expected_names = FEATURE_NAMES[:feature_width]

        recent_matrix = FeatureEngineeringEngine.extract_recent_feature_matrix(
            candles_1m=recent_candles,
            strategy=strategy,
            max_samples=30,
        )
        aligned_recent = [vec[:feature_width] for vec in recent_matrix]
        baseline_win_rate = (
            float(row.simulated_win_rate)
            if int(row.simulated_trades or 0) > 0
            else float(row.out_of_sample_accuracy or 0.5)
        )

        report = compute_drift_report(
            model_id=row.model_id,
            instrument=row.instrument,
            baseline_stats=baseline_stats,
            baseline_win_rate=baseline_win_rate,
            recent_features=aligned_recent,
            feature_names=expected_names,
            orders=orders,
            promoted_at=row.promoted_at,
        )

        # Update telemetry columns ONLY — NEVER automatically demote or swap ACTIVE model
        row.drift_status = report.drift_status.value
        row.drift_score = report.drift_score
        row.live_settled_trades = report.live_settled_trades
        row.live_win_rate = report.live_win_rate
        row.drift_notes = report.drift_notes[:512]
        row.last_drift_checked_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(row)
        return row, report

    @classmethod
    def rollback_model(
        cls,
        db: Session,
        target_model_id: str,
        artifacts_dir: Path,
        owner_confirmed: bool = False,
        reason: Optional[str] = None,
    ) -> ModelRegistryModel:
        if not owner_confirmed:
            raise PermissionError("Explicit human owner confirmation is required to rollback a model.")

        row = db.get(ModelRegistryModel, target_model_id)
        if row is None:
            raise KeyError(f"Model '{target_model_id}' not found.")

        if row.status != ModelStatus.VALIDATED.value:
            raise ValueError(
                f"Rollback target '{target_model_id}' must have status VALIDATED (current: '{row.status}')."
            )

        artifact_path = cls.resolve_safe_artifact_path(artifacts_dir, row.artifact_filename)
        if not artifact_path.exists():
            raise FileNotFoundError(f"Artifact file missing for rollback target '{target_model_id}'.")

        previous_active = (
            db.query(ModelRegistryModel)
            .filter(
                ModelRegistryModel.instrument == row.instrument,
                ModelRegistryModel.timeframe == row.timeframe,
                ModelRegistryModel.status == ModelStatus.ACTIVE.value,
                ModelRegistryModel.model_id != row.model_id,
            )
            .all()
        )
        prev_active_id = previous_active[0].model_id if previous_active else None
        for prev in previous_active:
            prev.status = ModelStatus.VALIDATED.value

        row.status = ModelStatus.ACTIVE.value
        row.promoted_at = datetime.now(timezone.utc)
        row.rolled_back_from_model_id = prev_active_id
        rollback_reason = (reason or "Manual drift/performance rollback").strip()
        audit_suffix = f" | Rollback from {prev_active_id or 'none'}: {rollback_reason}"
        row.validation_notes = (row.validation_notes + audit_suffix)[:512]

        db.commit()
        db.refresh(row)
        return row
