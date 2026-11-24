from typing import Dict, List, Optional, Sequence
from sqlalchemy.orm import Session

from app.models import HistoricalCandleModel


class HistoricalCandleRepository:
    """Parameterized SQLAlchemy repository for chronological OHLCV candle storage."""

    @staticmethod
    def upsert_candles(
        db: Session,
        instrument: str,
        timeframe: str,
        candles: Sequence[Dict[str, float]],
        source: str = "simulation",
    ) -> int:
        if not candles:
            return 0

        timestamps = [int(c["timestamp"]) for c in candles]
        existing_rows = (
            db.query(HistoricalCandleModel)
            .filter(
                HistoricalCandleModel.instrument == instrument,
                HistoricalCandleModel.timeframe == timeframe,
                HistoricalCandleModel.timestamp.in_(timestamps),
            )
            .all()
        )
        existing_by_ts = {row.timestamp: row for row in existing_rows}

        inserted = 0
        for c in candles:
            ts = int(c["timestamp"])
            row = existing_by_ts.get(ts)
            if row is not None:
                row.open = float(c["open"])
                row.high = float(c["high"])
                row.low = float(c["low"])
                row.close = float(c["close"])
                row.volume = float(c.get("volume", 0.0))
                row.source = source
            else:
                new_row = HistoricalCandleModel(
                    instrument=instrument,
                    timeframe=timeframe,
                    timestamp=ts,
                    open=float(c["open"]),
                    high=float(c["high"]),
                    low=float(c["low"]),
                    close=float(c["close"]),
                    volume=float(c.get("volume", 0.0)),
                    source=source,
                )
                db.add(new_row)
                existing_by_ts[ts] = new_row
                inserted += 1

        db.commit()
        return inserted

    @staticmethod
    def get_candles(
        db: Session,
        instrument: str,
        timeframe: str,
        limit: int = 200,
        max_timestamp: Optional[int] = None,
    ) -> List[Dict[str, float]]:
        query = db.query(HistoricalCandleModel).filter(
            HistoricalCandleModel.instrument == instrument,
            HistoricalCandleModel.timeframe == timeframe,
        )
        if max_timestamp is not None:
            query = query.filter(HistoricalCandleModel.timestamp <= max_timestamp)

        rows = (
            query.order_by(HistoricalCandleModel.timestamp.desc())
            .limit(limit)
            .all()
        )
        rows.reverse()
        return [
            {
                "timestamp": r.timestamp,
                "open": r.open,
                "high": r.high,
                "low": r.low,
                "close": r.close,
                "volume": r.volume,
            }
            for r in rows
        ]

    @staticmethod
    def count_candles(db: Session, instrument: str, timeframe: str) -> int:
        return (
            db.query(HistoricalCandleModel)
            .filter(
                HistoricalCandleModel.instrument == instrument,
                HistoricalCandleModel.timeframe == timeframe,
            )
            .count()
        )
