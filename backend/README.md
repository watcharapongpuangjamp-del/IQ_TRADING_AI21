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

- Set `BROKER_ADAPTER=iqoption` and `IQOPTION_BALANCE_MODE=PRACTICE`; leave `IQOPTION_EMAIL` and `IQOPTION_PASSWORD` empty.
- Configure a strong `API_BEARER_TOKEN` and expose the backend through HTTPS. The Android Connect screen sends credentials to the authenticated `POST /api/v1/iqoption/connect` endpoint only after enforcing HTTPS (localhost is allowed for local development).
- The backend holds the submitted credentials in process memory for the active session only. It does not write them to SQLite, environment files, logs, or cloud storage; disconnect clears them. Android can optionally remember them in the device's Android Keystore AES-256-GCM vault.
- The authenticated `POST /api/v1/iqoption/disconnect` endpoint closes the broker session and returns the app/backend execution mode to SIMULATION.
- Keep `ALLOW_LIVE_TRADING=false`. The connector's `live_authorized` property is hard-coded false and the execution-mode controller rejects `LIVE` regardless of configuration.
- The app switches to `DEMO` (displayed as PRACTICE) only after the broker session reports a verified `PRACTICE` account. `SIMULATION` remains a separate internal simulator.

The first integration slice replaces the former random/mock IQ Option adapter with broker-backed login, Practice balance verification, broker candles, Turbo/Binary CALL/PUT order submission, broker order identifiers, settlement lookup, and read-only reconciliation. A submission is initially recorded as `PENDING`; the backend must confirm the final result from IQ Option before updating the risk ledger. An uncertain acknowledgement is never blindly resubmitted.

The supplied package also implements Digital Options, Forex margin, Crypto margin, CFD margin, asset metadata, and streaming helpers. Those trading modes require additional order-schema, direction, leverage, margin-risk, close-position, and Android UI contracts before they can safely be enabled through this application's existing CALL/PUT binary-options execution pipeline. They are not advertised as executable capabilities in the current connector. Keep them disabled until their end-to-end tests and reconciliation paths are implemented.

The package is unofficial and talks to an undocumented broker protocol. It can break when IQ Option changes its backend. CI tests use a fake client and never log in or place real broker orders.

### Android Practice login flow

1. Configure the backend with `BROKER_ADAPTER=iqoption`, `IQOPTION_BALANCE_MODE=PRACTICE`, and a strong API bearer token.
2. Configure the Android app's FastAPI URL and Bearer Token. Use HTTPS for remote hosts.
3. Open Connect & SQLite, enter the IQ Option email/password, and tap **Connect Practice**.
4. The backend logs in, explicitly selects PRACTICE, verifies the broker-reported account mode, and reads the broker balance before returning success. It never selects REAL.
5. Once verified, the app changes the execution mode to PRACTICE/DEMO. Existing order authorization, market-open/payout checks, risk guard, idempotency, and reconciliation remain in the execution path. Only the existing Turbo/Binary CALL/PUT order path is executable.
6. The app's Simulation balance remains separate from the broker Practice balance. Disconnect returns the execution mode to Simulation.

The connect endpoint does not place a test order automatically. A Practice trade is sent only when the user initiates a trade from the terminal or explicitly enables the configured auto-trading workflow. CI uses mocked broker clients; an actual IQ Option login/order still requires a user-run smoke test with their own Practice account.
 
## Trading Terminal: Explicit Modes and Asset Directory

The terminal keeps independent dimensions separate:

- **Account / execution mode:** `SIMULATION`, verified `PRACTICE`, and `REAL (LOCKED)`.
- **Operation mode:** `MANUAL` or `AUTO`, derived from the risk policy's auto-trade setting.
- **AI workflow state:** `LEARNING` and `TRAINING` lifecycle labels; an idle state must be shown as `IDLE`, not implied to be actively training.
- **Research state:** `BACKTEST` / replay is separate from a broker account and cannot place broker orders.
- **Order state:** `CREATED`, `VALIDATED`, `AUTHORIZED`, `SUBMITTED`, `ACCEPTED`, `PENDING`, `SETTLED`, `REJECTED`, `FAILED`, and `UNKNOWN` remain distinct.

The terminal reads `GET /api/v1/state` for account, session, operation and workflow labels. It reads `GET /api/v1/assets/catalog` for broker-reported product types and assets. Catalog rows include product type, symbol, OPEN/CLOSED/UNKNOWN status, payout rate/percent, minimum/maximum stake, leverage when supplied, and the metadata source.

Product types are normalized from the package's asset metadata (Turbo, Binary, Digital, Forex, Crypto and CFD; Blitz and Futures are listed only if the broker API actually returns them). Missing product types are explicitly returned in `unavailable_product_types`; absence is not represented as an active market. Payout is displayed only when the broker reports it. Simulation-only instrument payout assumptions are labelled as estimates and must never be presented as broker payout.

The current order execution path remains Turbo/Binary CALL/PUT. Digital, Blitz, Futures and margin products are directory-only until their order and position lifecycles are fully implemented and tested. REAL remains hard-locked in the UI and backend.
