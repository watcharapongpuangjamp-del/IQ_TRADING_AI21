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


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
