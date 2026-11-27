# IQ Trading AI — Python FastAPI Backend (Phase 1–6)

Modular quantitative trading intelligence backend built with FastAPI, Pydantic v2, SQLAlchemy, NumPy, Scikit-learn, and independent domain engines.

## Architecture

- `app/domain.py`: Pure-Python domain models and enums (`ExecutionMode`, `TradeDirection`, `OrderLifecycleState`, `MarketRegime`, `AlignmentState`, `ModelStatus`, `StrategyConfig`, `StrategyModuleProfile`, `StrategySelectionReport`, `DataQualityReport`, `MultiTimeframeReport`, `ReplayState`, `RiskPolicyConfig`).
- `app/connectors/`: Broker abstraction (`BrokerConnector` with `reconcile_order`) and deterministic-capable `SimulationConnector` (`live_authorized = False`).
- `app/repositories/historical_candles.py`: SQLAlchemy historical OHLCV candle store with composite uniqueness `(instrument, timeframe, timestamp)` and chronological query slices.
- `app/engines/`:
  - `market_intelligence.py` — OHLCV candle validation, gap/duplicate/outlier inspection, and timeframe resampling (`1m -> 5m / 15m`).
  - `technical_analysis.py` — RSI, MACD, EMA, SMA, Bollinger Bands, ATR, ADX (`+DI`/`-DI`), Stochastic (`%K`/`%D`), VWAP (with volume guard), support/resistance, candlestick patterns, and 8-state Market Regime classification.
  - `multi_timeframe.py` — Multi-timeframe trend confirmation (`1m` primary confirmed by `5m` and `15m`).
  - `signal_engine.py` — Calibrated baseline `CALL` / `PUT` / `NO_TRADE` signals with MTF conflict gating.
  - `replay_engine.py` — Deterministic chronological Simulator Replay cursor (`ts <= cursor_ts`) preventing look-ahead bias.
  - `strategy_engine.py` — 8 modular quantitative strategies (`ema_trend_following`, `rsi_reversal`, `macd_momentum`, `bb_mean_reversion`, `breakout_detection`, `volatility_adaptive`, `mtf_confirmation`, `regime_adaptive`) with causal walk-forward metrics and regime-based AI Strategy Selector.
  - `risk_engine.py` — Independent pre-trade Risk Guard (max per trade, daily loss, consecutive losses, daily trade count, portfolio exposure, loss cooldown, emergency stop, live lockout).
  - `execution_engine.py` — 11-stage execution pipeline, 10-state `OrderLifecycleState` audit trail (`OrderLifecycleEventModel`), `ExecutionModeController` (`SIMULATION` default), `UNKNOWN` non-resubmission, and Reconcile-Before-Trade Risk Gate.
  - `learning_engine.py` — Performance summary metrics (`win_rate`, `profit_factor`, `max_drawdown`).
- `app/ml/` (Phase 3 ML Pipeline):
  - `features.py` — Strictly causal feature extraction with execution-delay-aware target alignment.
  - `splitting.py` — Chronological train/validation/test splits and expanding walk-forward folds (no random shuffling).
  - `backtesting.py` — Out-of-sample Brier score, log-loss, Expected Calibration Error (ECE), payout PnL, and transaction cost simulation.
  - `trainer.py` — Scikit-learn (`LogisticRegression`, `HistGradientBoostingClassifier`) + out-of-sample Platt sigmoid probability calibration + Walk-Forward Validation Gate.
  - `registry.py` — Controlled SQLAlchemy Model Registry (`CANDIDATE` / `VALIDATED` / `ACTIVE` / `REJECTED`) with owner-confirmed promotion and path-confined artifacts.
  - `inference.py` — `ACTIVE` model signal inference keeping `confidence_score` distinct from `calibrated_probability` and falling back safely when no model is promoted.
- `app/main.py`: FastAPI REST API (`/api/v1/*`) + WebSocket streams (`/ws/market/{instrument}`, `/ws/signals`, `/ws/system`).

## Phase 2–6 Endpoints

- `GET /api/v1/candles/history` & `POST /api/v1/candles/history/ingest`
- `GET /api/v1/market/intelligence` — Full `1m` + `5m` + `15m` multi-timeframe technical intelligence + ML/calibrated signal.
- `GET /api/v1/strategies` & `POST /api/v1/strategies/select` — 8 modular strategy walk-forward scorecards and regime-based auto/pinned selection.
- `POST /api/v1/replay/start`, `/step`, `/reset`, `GET /api/v1/replay/state`
- `POST /api/v1/ml/train` — Train & validate Scikit-learn + Platt calibration model chronologically (never auto-promotes).
- `GET /api/v1/ml/models`, `GET /api/v1/ml/active`, `POST /api/v1/ml/models/{model_id}/promote`
- `GET /api/v1/orders/{order_id}/events` — 10-state order lifecycle & correlation audit timeline.
- `POST /api/v1/trading/reconcile` & `POST /api/v1/orders/{order_id}/reconcile` — Broker reconciliation for `PENDING` / `UNKNOWN` orders.
- `WS /ws/market/{instrument}`, `WS /ws/signals`, `WS /ws/system` — Real-time market ticks, calibrated AI signals, and system/risk telemetry.

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

```bash
cd backend
PYTHONPATH=. python -m unittest discover -s tests -t . -p "test_engines_unittest.py" -v
PYTHONPATH=. python -m pytest -v
```

## IQ Option Practice Integration (iqair 1.0.0)

The `iqoption` adapter uses the user-supplied `iqair==1.0.0` package. It is intentionally Practice-only:

- Set `BROKER_ADAPTER=iqoption`.
- Set `IQOPTION_BALANCE_MODE=PRACTICE`.
- Supply `IQOPTION_EMAIL` and `IQOPTION_PASSWORD` only through the backend's local, git-ignored environment file or deployment secret store. Never commit credentials or paste them into chat.
- Keep `ALLOW_LIVE_TRADING=false`. The connector's `live_authorized` property is hard-coded false and the execution-mode controller rejects `LIVE` regardless of configuration.
- Switch the application execution mode to `DEMO` only after the broker session reports a verified `PRACTICE` account. `SIMULATION` remains a separate internal simulator.

The first integration slice replaces the former random/mock IQ Option adapter with broker-backed login, Practice balance verification, broker candles, Turbo/Binary CALL/PUT order submission, broker order identifiers, settlement lookup, and read-only reconciliation. A submission is initially recorded as `PENDING`; the backend must confirm the final result from IQ Option before updating the risk ledger. An uncertain acknowledgement is never blindly resubmitted.

The supplied package also implements Digital Options, Forex margin, Crypto margin, CFD margin, asset metadata, and streaming helpers. Those trading modes require additional order-schema, direction, leverage, margin-risk, close-position, and Android UI contracts before they can safely be enabled through this application's existing CALL/PUT binary-options execution pipeline. They are not advertised as executable capabilities in the current connector. Keep them disabled until their end-to-end tests and reconciliation paths are implemented.

The package is unofficial and talks to an undocumented broker protocol. It can break when IQ Option changes its backend. CI tests use a fake client and never log in or place real broker orders.
