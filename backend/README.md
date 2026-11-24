# IQ Trading AI — Python FastAPI Backend (Phase 1 & Phase 2)

Modular quantitative trading intelligence backend built with FastAPI, Pydantic v2, SQLAlchemy, and independent domain engines.

## Architecture

- `app/domain.py`: Pure-Python domain models and enums (`ExecutionMode`, `TradeDirection`, `MarketRegime`, `AlignmentState`, `StrategyConfig`, `DataQualityReport`, `MultiTimeframeReport`, `ReplayState`, `RiskPolicyConfig`).
- `app/connectors/`: Broker abstraction (`BrokerConnector`) and deterministic-capable `SimulationConnector` (`live_authorized = False`).
- `app/repositories/historical_candles.py`: SQLAlchemy historical OHLCV candle store with composite uniqueness `(instrument, timeframe, timestamp)` and chronological query slices.
- `app/engines/`:
  - `market_intelligence.py` — OHLCV candle validation, gap/duplicate/outlier inspection, and timeframe resampling (`1m -> 5m / 15m`).
  - `technical_analysis.py` — RSI, MACD, EMA, SMA, Bollinger Bands, ATR, support/resistance, candlestick patterns, and market regime classification.
  - `multi_timeframe.py` — Multi-timeframe trend confirmation (`1m` primary confirmed by `5m` and `15m`).
  - `signal_engine.py` — Multi-evidence `CALL` / `PUT` / `NO_TRADE` signals with calibrated win probability separated from model confidence and MTF conflict gating.
  - `replay_engine.py` — Deterministic chronological Simulator Replay cursor (`ts <= cursor_ts`) preventing look-ahead bias.
  - `strategy_engine.py` — Active strategy configuration manager.
  - `risk_engine.py` — Independent pre-trade Risk Guard (max per trade, daily loss, consecutive losses, daily trade count, portfolio exposure, loss cooldown, emergency stop, live lockout).
  - `execution_engine.py` — 11-stage execution pipeline, `ExecutionModeController` (`SIMULATION` default), idempotency protection, and connection-failure lockout.
  - `learning_engine.py` — Performance summary metrics (`win_rate`, `profit_factor`, `max_drawdown`) and Phase 3/6 out-of-sample gating baseline.
- `app/main.py`: FastAPI REST API (`/api/v1/*`) + WebSocket tick stream (`/ws/market/{instrument}`).

## Phase 2 Endpoints

- `GET /api/v1/candles/history` — Fetch chronological OHLCV history stored in SQLite (auto-seeds simulation data if empty).
- `POST /api/v1/candles/history/ingest` — Validate and upsert historical candles idempotently.
- `GET /api/v1/market/intelligence` — Full `1m` + `5m` + `15m` multi-timeframe technical intelligence, data quality report, and gated `CALL`/`PUT`/`NO_TRADE` signal.
- `POST /api/v1/replay/start`, `POST /api/v1/replay/step`, `POST /api/v1/replay/reset`, `GET /api/v1/replay/state` — Chronological simulator replay without look-ahead bias.

## Local Setup (Python 3.12+)

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## Automated Tests

Pure-Python engine unit tests (`unittest`):

```bash
cd backend
python3 -m unittest discover -s tests -p "test_engines_unittest.py" -v
```

Full FastAPI + SQLite + WebSocket integration tests (`pytest`):

```bash
cd backend
pytest -v
```
