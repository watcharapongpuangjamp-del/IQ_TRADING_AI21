# IQ Trading AI — Python FastAPI Backend (Phase 1 Foundation)

Modular quantitative trading intelligence backend built with FastAPI, Pydantic v2, SQLAlchemy, and independent domain engines.

## Architecture

- `app/domain.py`: Pure-Python domain models and enums (`ExecutionMode`, `TradeDirection`, `MarketRegime`, `StrategyConfig`, `RiskPolicyConfig`).
- `app/connectors/`: Broker abstraction (`BrokerConnector`) and deterministic-capable `SimulationConnector` (`live_authorized = False`).
- `app/engines/`:
  - `market_intelligence.py` — OHLCV candle validation and structural checks.
  - `technical_analysis.py` — RSI, MACD, EMA, Bollinger Bands, ATR, candlestick pattern, and market regime classification.
  - `signal_engine.py` — Multi-evidence `CALL` / `PUT` / `NO_TRADE` signals with calibrated win probability separated from model confidence.
  - `strategy_engine.py` — Active strategy configuration manager.
  - `risk_engine.py` — Independent pre-trade Risk Guard (max per trade, daily loss, consecutive losses, daily trade count, portfolio exposure, loss cooldown, emergency stop, live lockout).
  - `execution_engine.py` — 11-stage execution pipeline, `ExecutionModeController` (`SIMULATION` default), idempotency protection, and connection-failure lockout.
  - `learning_engine.py` — Phase 1 evaluation summary metrics (`win_rate`, `profit_factor`, `max_drawdown`) and out-of-sample gating baseline.
- `app/main.py`: FastAPI REST API (`/api/v1/*`) + WebSocket tick stream (`/ws/market/{instrument}`).

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
