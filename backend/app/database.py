from sqlalchemy import create_engine, text
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from app.config import get_settings


class Base(DeclarativeBase):
    pass


def build_engine(database_url: str | None = None):
    url = database_url or get_settings().database_url
    connect_args = {"check_same_thread": False} if url.startswith("sqlite") else {}
    return create_engine(url, connect_args=connect_args, future=True)


engine = build_engine()
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def ensure_schema_migrations(target_engine) -> None:
    """Create missing tables and idempotently backfill new SQLite columns on existing DBs."""
    Base.metadata.create_all(bind=target_engine)
    if target_engine.dialect.name != "sqlite":
        return

    with target_engine.begin() as conn:
        cols_res = conn.execute(text("PRAGMA table_info(orders)")).fetchall()
        existing_cols = {row[1] for row in cols_res}
        migrations = [
            ("correlation_id", "VARCHAR(64)"),
            ("broker_order_id", "VARCHAR(64)"),
            ("lifecycle_state", "VARCHAR(24) NOT NULL DEFAULT 'SETTLED'"),
            ("settlement_outcome", "VARCHAR(20)"),
            ("retry_count", "INTEGER NOT NULL DEFAULT 0"),
            ("error_detail", "VARCHAR(512) NOT NULL DEFAULT ''"),
            ("reconciled_at", "DATETIME"),
        ]
        for col_name, col_def in migrations:
            if col_name not in existing_cols:
                conn.execute(text(f"ALTER TABLE orders ADD COLUMN {col_name} {col_def}"))
        conn.execute(
            text("UPDATE orders SET correlation_id = 'corr_' || order_id WHERE correlation_id IS NULL OR correlation_id = ''")
        )

        mr_cols_res = conn.execute(text("PRAGMA table_info(model_registry)")).fetchall()
        mr_existing = {row[1] for row in mr_cols_res}
        mr_migrations = [
            ("dataset_version", "VARCHAR(64) NOT NULL DEFAULT 'ds_v2_unknown'"),
            ("feature_count", "INTEGER NOT NULL DEFAULT 17"),
            ("baseline_stats_json", "TEXT NOT NULL DEFAULT '{}'"),
            ("drift_status", "VARCHAR(24) NOT NULL DEFAULT 'HEALTHY'"),
            ("drift_score", "FLOAT NOT NULL DEFAULT 0.0"),
            ("live_settled_trades", "INTEGER NOT NULL DEFAULT 0"),
            ("live_win_rate", "FLOAT NOT NULL DEFAULT 0.0"),
            ("drift_notes", "VARCHAR(512) NOT NULL DEFAULT ''"),
            ("last_drift_checked_at", "DATETIME"),
            ("rolled_back_from_model_id", "VARCHAR(64)"),
        ]
        for col_name, col_def in mr_migrations:
            if col_name not in mr_existing:
                conn.execute(text(f"ALTER TABLE model_registry ADD COLUMN {col_name} {col_def}"))


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
