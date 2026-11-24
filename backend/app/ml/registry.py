from datetime import datetime, timezone
from pathlib import Path
import re
from typing import List, Optional

from sqlalchemy.orm import Session

from app.domain import ModelStatus
from app.ml.trainer import TrainingOutcome
from app.models import ModelRegistryModel

SAFE_ARTIFACT_NAME = re.compile(r"^mdl_[a-f0-9]{12}\.joblib$")


class ModelRegistryService:
    """
    Controlled Model Registry backed by SQLAlchemy and confined server-side artifacts.
    Prevents automatic promotion of unvalidated or rejected models.
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
