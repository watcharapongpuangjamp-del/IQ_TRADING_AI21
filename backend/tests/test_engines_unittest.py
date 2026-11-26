import asyncio
import unittest

from app.connectors.simulation import SimulationConnector
from app.domain import (
    AlignmentState,
    DriftStatus,
    ExecutionMode,
    MarketRegime,
    OrderLifecycleState,
    RiskPolicyConfig,
    StrategyConfig,
    TradeDirection,
    TradeStatus,
)
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.multi_timeframe import MultiTimeframeEngine
from app.engines.replay_engine import SimulatorReplayEngine
from app.engines.risk_engine import RiskManagementEngine
from app.engines.signal_engine import AISignalEngine
from app.engines.strategy_engine import StrategySelectionEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine
from app.ml.backtesting import WalkForwardBacktester
from app.ml.drift import (
    compute_feature_baseline,
    compute_feature_drift,
    dumps_baseline_stats,
    evaluate_model_drift,
    loads_baseline_stats,
)
from app.ml.features import FEATURE_NAMES, FeatureEngineeringEngine
from app.ml.splitting import ChronologicalSplitter


def _build_trending_candles(
    count: int,
    step: float,
    start_price: float = 1.0800,
    start_ts: int = 1_700_000_000_000,
    tf_ms: int = 60_000,
):
    candles = []
    price = start_price
    for i in range(count):
        open_p = price
        close_p = max(0.01, open_p + step)
        high_p = max(open_p, close_p) + abs(step) * 0.25
        low_p = max(0.005, min(open_p, close_p) - abs(step) * 0.25)
        candles.append(
            {
                "timestamp": start_ts + i * tf_ms,
                "open": round(open_p, 6),
                "high": round(high_p, 6),
                "low": round(low_p, 6),
                "close": round(close_p, 6),
                "volume": 150.0 + i,
            }
        )
        price = close_p
    return candles


class TestPurePythonEngines(unittest.TestCase):
    def setUp(self) -> None:
        self.connector = SimulationConnector(initial_balance=10000.0)
        self.strategy = StrategyConfig(instrument="EUR/USD", timeframe="1m")

    def test_simulation_connector_candles_and_live_lock(self) -> None:
        self.assertEqual(self.connector.name, "simulation_connector")
        self.assertTrue(self.connector.connected)
        self.assertFalse(self.connector.live_authorized)
        self.assertIn("SIMULATION_STREAM", self.connector.get_capabilities())

        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=60))
        self.assertEqual(len(candles), 60)
        valid, reason = MarketIntelligenceEngine.validate_candles(candles)
        self.assertTrue(valid, reason)

    def test_market_intelligence_inspects_corruption_duplicates_and_gaps(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=30))
        bad = [dict(c) for c in candles]
        bad[-1]["high"] = bad[-1]["low"] - 0.01
        valid, reason = MarketIntelligenceEngine.validate_candles(bad)
        self.assertFalse(valid)
        self.assertIn("Corrupted OHLC", reason)

        dup = [dict(c) for c in candles]
        dup[-1]["timestamp"] = dup[-2]["timestamp"]
        dup_report = MarketIntelligenceEngine.inspect_candles(dup, expected_timeframe_sec=60)
        self.assertFalse(dup_report.valid)
        self.assertEqual(dup_report.duplicate_count, 1)

        gapped = [dict(c) for c in candles]
        gapped[-1]["timestamp"] = gapped[-2]["timestamp"] + 300_000
        gap_report = MarketIntelligenceEngine.inspect_candles(gapped, expected_timeframe_sec=60)
        self.assertTrue(gap_report.valid)
        self.assertGreaterEqual(gap_report.gap_count, 1)

        too_few, _ = MarketIntelligenceEngine.validate_candles(candles[:10])
        self.assertFalse(too_few)

    def test_candle_aggregation_1m_to_5m(self) -> None:
        base_ts = 1_700_000_100_000 - (1_700_000_100_000 % 300_000)
        bars_1m = _build_trending_candles(10, step=0.0001, start_ts=base_ts, tf_ms=60_000)
        bars_5m = MarketIntelligenceEngine.aggregate_candles(bars_1m, target_minutes=5)
        self.assertEqual(len(bars_5m), 2)
        self.assertEqual(bars_5m[0]["open"], bars_1m[0]["open"])
        self.assertEqual(bars_5m[0]["close"], bars_1m[4]["close"])
        self.assertAlmostEqual(
            bars_5m[0]["high"],
            max(b["high"] for b in bars_1m[:5]),
            places=6,
        )

    def test_technical_analysis_sma_and_signal_calibration(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=80))
        snapshot = TechnicalAnalysisEngine.analyze(candles, self.strategy)
        self.assertGreaterEqual(snapshot.rsi, 0.0)
        self.assertLessEqual(snapshot.rsi, 100.0)
        self.assertGreater(snapshot.sma_fast, 0.0)
        self.assertGreater(snapshot.sma_slow, 0.0)
        self.assertGreaterEqual(snapshot.bb_upper, snapshot.bb_middle)
        self.assertGreaterEqual(snapshot.bb_middle, snapshot.bb_lower)
        self.assertGreater(snapshot.atr, 0.0)

        signal = AISignalEngine.evaluate(candles, snapshot, self.strategy)
        self.assertIn(signal.direction, (TradeDirection.CALL, TradeDirection.PUT, TradeDirection.NO_TRADE))
        self.assertGreaterEqual(signal.calibrated_probability, 0.46)
        self.assertLessEqual(signal.calibrated_probability, 0.70)
        self.assertGreaterEqual(signal.confidence_score, 10)
        self.assertLessEqual(signal.confidence_score, 96)
        self.assertNotEqual(round(signal.calibrated_probability * 100), signal.confidence_score)

    def test_multi_timeframe_alignment_and_conflict_gate(self) -> None:
        bull_1m = _build_trending_candles(45, step=0.00015, tf_ms=60_000)
        bull_5m = _build_trending_candles(45, step=0.00025, tf_ms=300_000)
        bull_15m = _build_trending_candles(45, step=0.00035, tf_ms=900_000)

        aligned_report = MultiTimeframeEngine.evaluate(
            instrument="EUR/USD",
            candles_by_tf={"1m": bull_1m, "5m": bull_5m, "15m": bull_15m},
            strategy=self.strategy,
        )
        self.assertEqual(aligned_report.alignment_state, AlignmentState.ALIGNED_BULLISH)
        self.assertGreaterEqual(aligned_report.alignment_score, 85)
        self.assertEqual(len(aligned_report.timeframe_summaries), 3)

        bear_5m = _build_trending_candles(45, step=-0.00025, start_price=1.2000, tf_ms=300_000)
        bear_15m = _build_trending_candles(45, step=-0.00035, start_price=1.2500, tf_ms=900_000)
        conflict_report = MultiTimeframeEngine.evaluate(
            instrument="EUR/USD",
            candles_by_tf={"1m": bull_1m, "5m": bear_5m, "15m": bear_15m},
            strategy=self.strategy,
        )
        self.assertEqual(conflict_report.alignment_state, AlignmentState.CONFLICTING)
        snap_1m = TechnicalAnalysisEngine.analyze(bull_1m, self.strategy)
        gated_signal = AISignalEngine.evaluate_with_mtf(bull_1m, snap_1m, conflict_report, self.strategy)
        self.assertEqual(gated_signal.direction, TradeDirection.NO_TRADE)

    def test_replay_engine_enforces_zero_look_ahead_bias(self) -> None:
        series_1m = _build_trending_candles(420, step=0.00005, tf_ms=60_000)
        replay = SimulatorReplayEngine()
        start_state = replay.start(instrument="EUR/USD", candles_1m=series_1m, initial_window=375)
        self.assertTrue(start_state.active)
        self.assertEqual(start_state.cursor_index, 374)

        slices = replay.visible_candles()
        self.assertEqual(len(slices["1m"]), 375)
        self.assertTrue(all(int(c["timestamp"]) <= start_state.cursor_timestamp for c in slices["1m"]))

        stepped = replay.step(10)
        self.assertEqual(stepped.cursor_index, 384)
        eval_res = replay.evaluate_current(self.strategy)
        self.assertEqual(len(eval_res.mtf_report.timeframe_summaries), 3)

        reset_state = replay.reset()
        self.assertEqual(reset_state.cursor_index, 374)

    def test_feature_engineering_is_strictly_causal(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=180))
        ds_orig = FeatureEngineeringEngine.build_dataset(candles, self.strategy, horizon_bars=1, execution_delay_bars=1)

        # Mutate ONLY the last 5 bars — earlier feature rows must remain bit-for-bit identical
        mutated = [dict(c) for c in candles]
        for j in range(-5, 0):
            mutated[j]["close"] = mutated[j]["close"] * 1.12
            mutated[j]["high"] = max(mutated[j]["high"], mutated[j]["close"])
        ds_mut = FeatureEngineeringEngine.build_dataset(mutated, self.strategy, horizon_bars=1, execution_delay_bars=1)

        self.assertEqual(len(ds_orig.features), len(ds_mut.features))
        self.assertEqual(ds_orig.features[:-10], ds_mut.features[:-10])

    def test_chronological_splitter_and_walk_forward_folds(self) -> None:
        timestamps = [1_700_000_000_000 + i * 60_000 for i in range(250)]
        split = ChronologicalSplitter.split_dataset(timestamps)
        self.assertLess(split.train_end_ts, split.val_start_ts)
        self.assertLess(split.val_end_ts, split.test_start_ts)

        folds = ChronologicalSplitter.generate_walk_forward_folds(timestamps, n_splits=3, min_train_size=120)
        self.assertGreaterEqual(len(folds), 2)
        for fold in folds:
            self.assertLess(fold.train_end_ts, fold.val_start_ts)
            self.assertLess(fold.val_end_ts, fold.test_start_ts)

    def test_walk_forward_backtester_metrics_and_no_trade_skips(self) -> None:
        probs = [0.72, 0.51, 0.25, 0.68, 0.49]
        y_true = [1, 1, 0, 0, 0]
        returns = [0.002, 0.001, -0.002, -0.0015, -0.001]
        report = WalkForwardBacktester.evaluate(
            probs=probs,
            y_true=y_true,
            future_returns=returns,
            trade_amount=25.0,
            payout_rate=0.85,
            entry_threshold=0.55,
        )
        self.assertEqual(report.total_predictions, 5)
        self.assertEqual(report.skipped_signals, 2)  # 0.51 and 0.49 skipped as NO_TRADE
        self.assertEqual(report.total_trades, 3)
        self.assertEqual(report.wins, 2)
        self.assertEqual(report.losses, 1)
        self.assertLess(report.brier_score, 0.25)

    def test_signal_returns_no_trade_when_confidence_threshold_high(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=80))
        snapshot = TechnicalAnalysisEngine.analyze(candles, self.strategy)
        strict_strategy = StrategyConfig(instrument="EUR/USD", timeframe="1m", min_confidence=99)
        signal = AISignalEngine.evaluate(candles, snapshot, strict_strategy)
        self.assertEqual(signal.direction, TradeDirection.NO_TRADE)

    def test_strategy_selection_engine_updates(self) -> None:
        engine = StrategySelectionEngine()
        updated = StrategyConfig(name="Conservative BB", instrument="GBP/USD", timeframe="5m", min_confidence=72)
        saved = engine.update_strategy(updated)
        self.assertEqual(saved.name, "Conservative BB")
        self.assertEqual(engine.get_active_strategy().instrument, "GBP/USD")

    def test_risk_engine_blocks_violations_and_locks_live(self) -> None:
        policy = RiskPolicyConfig(
            max_trade_amount=100.0,
            max_daily_loss=200.0,
            max_consecutive_losses=3,
            max_daily_trades=10,
            max_portfolio_exposure=300.0,
            cooldown_minutes=15,
            emergency_stopped=False,
        )

        ok = RiskManagementEngine.validate_order(
            policy=policy,
            total_trades_today=2,
            net_pnl_today=-20.0,
            consecutive_losses=1,
            open_exposure=0.0,
            requested_amount=25.0,
            direction=TradeDirection.CALL,
            mode=ExecutionMode.SIMULATION,
            live_authorized=False,
            last_loss_epoch_ms=1000,
            now_ms=1000 + 20 * 60_000,
        )
        self.assertTrue(ok.allowed, ok.reason)

        live_blocked = RiskManagementEngine.validate_order(
            policy=policy,
            total_trades_today=0,
            net_pnl_today=0.0,
            consecutive_losses=0,
            open_exposure=0.0,
            requested_amount=25.0,
            direction=TradeDirection.CALL,
            mode=ExecutionMode.LIVE,
            live_authorized=False,
            last_loss_epoch_ms=None,
        )
        self.assertFalse(live_blocked.allowed)
        self.assertIn("LIVE", live_blocked.reason)

        stopped = RiskManagementEngine.validate_order(
            policy=RiskPolicyConfig(emergency_stopped=True),
            total_trades_today=0,
            net_pnl_today=0.0,
            consecutive_losses=0,
            open_exposure=0.0,
            requested_amount=10.0,
            direction=TradeDirection.PUT,
            mode=ExecutionMode.SIMULATION,
            live_authorized=False,
            last_loss_epoch_ms=None,
        )
        self.assertFalse(stopped.allowed)

        daily_loss = RiskManagementEngine.validate_order(
            policy=policy,
            total_trades_today=4,
            net_pnl_today=-205.0,
            consecutive_losses=0,
            open_exposure=0.0,
            requested_amount=10.0,
            direction=TradeDirection.CALL,
            mode=ExecutionMode.SIMULATION,
            live_authorized=False,
            last_loss_epoch_ms=None,
        )
        self.assertFalse(daily_loss.allowed)

        cooldown = RiskManagementEngine.validate_order(
            policy=policy,
            total_trades_today=1,
            net_pnl_today=-25.0,
            consecutive_losses=1,
            open_exposure=0.0,
            requested_amount=25.0,
            direction=TradeDirection.CALL,
            mode=ExecutionMode.SIMULATION,
            live_authorized=False,
            last_loss_epoch_ms=10_000_000,
            now_ms=10_060_000,
        )
        self.assertFalse(cooldown.allowed)
        self.assertIn("cooldown", cooldown.reason.lower())

    def test_simulation_connector_order_lifecycle(self) -> None:
        res = asyncio.run(
            self.connector.submit_order(
                instrument="EUR/USD",
                timeframe="1m",
                direction="CALL",
                amount=25.0,
                expiry_seconds=60,
                correlation_id="corr_test_001",
            )
        )
        self.assertIn(res.status, ("WON", "LOST"))
        self.assertEqual(res.lifecycle_state, "SETTLED")
        self.assertEqual(res.correlation_id, "corr_test_001")
        fetched = asyncio.run(self.connector.get_settlement_result(res.order_id))
        self.assertEqual(fetched.order_id, res.order_id)

    def test_simulation_connector_pending_timeout_and_reconciliation(self) -> None:
        start_balance = asyncio.run(self.connector.get_balance())

        # 1. Deferred PENDING settlement resolves once via reconcile_order
        self.connector.queue_simulation_behavior("PENDING")
        pending_res = asyncio.run(
            self.connector.submit_order(
                instrument="EUR/USD",
                timeframe="1m",
                direction="CALL",
                amount=20.0,
                expiry_seconds=60,
                correlation_id="corr_pending_001",
            )
        )
        self.assertEqual(pending_res.lifecycle_state, "PENDING")
        self.assertEqual(asyncio.run(self.connector.get_balance()), start_balance)

        reconciled = asyncio.run(
            self.connector.reconcile_order(pending_res.order_id, correlation_id="corr_pending_001")
        )
        self.assertEqual(reconciled.lifecycle_state, "SETTLED")
        self.assertIn(reconciled.settlement_outcome, ("WON", "LOST"))
        balance_after_pending = asyncio.run(self.connector.get_balance())
        self.assertAlmostEqual(balance_after_pending, start_balance + reconciled.pnl, places=2)

        # Idempotent second reconcile must NOT double-credit/debit balance
        reconciled_again = asyncio.run(
            self.connector.reconcile_order(pending_res.order_id, correlation_id="corr_pending_001")
        )
        self.assertEqual(reconciled_again.order_id, reconciled.order_id)
        self.assertAlmostEqual(asyncio.run(self.connector.get_balance()), balance_after_pending, places=2)

        # 2. Transport TIMEOUT with persistent_unknown blocks reconciliation until cleared
        self.connector.queue_simulation_behavior("TIMEOUT", persistent_unknown=True)
        with self.assertRaises(TimeoutError):
            asyncio.run(
                self.connector.submit_order(
                    instrument="EUR/USD",
                    timeframe="1m",
                    direction="PUT",
                    amount=15.0,
                    expiry_seconds=60,
                    correlation_id="corr_timeout_002",
                )
            )
        with self.assertRaises(TimeoutError):
            asyncio.run(self.connector.reconcile_order("ord_missing", correlation_id="corr_timeout_002"))

        self.connector.set_persistent_unknown(False)
        settled_timeout = asyncio.run(
            self.connector.reconcile_order("ord_missing", correlation_id="corr_timeout_002")
        )
        self.assertEqual(settled_timeout.lifecycle_state, "SETTLED")
        self.assertEqual(settled_timeout.correlation_id, "corr_timeout_002")

    def test_adx_stochastic_vwap_and_expanded_regimes(self) -> None:
        strong_bull = _build_trending_candles(60, step=0.0012, start_price=1.05, tf_ms=60_000)
        snap = TechnicalAnalysisEngine.analyze(strong_bull, self.strategy)
        self.assertGreater(snap.adx, 20.0)
        self.assertGreater(snap.plus_di, snap.minus_di)
        self.assertGreaterEqual(snap.stochastic_k, 0.0)
        self.assertLessEqual(snap.stochastic_k, 100.0)
        self.assertGreaterEqual(snap.stochastic_d, 0.0)
        self.assertLessEqual(snap.stochastic_d, 100.0)
        self.assertTrue(snap.vwap_reliable)
        self.assertGreater(snap.vwap, 0.0)
        self.assertIn(
            snap.regime,
            (MarketRegime.STRONG_TREND, MarketRegime.WEAK_TREND, MarketRegime.BREAKOUT, MarketRegime.HIGH_VOLATILITY),
        )

        # Zero-volume data must flag vwap_reliable = False safely without division by zero
        zero_vol = [{**c, "volume": 0.0} for c in strong_bull]
        vwap_val, reliable = TechnicalAnalysisEngine.calculate_vwap(zero_vol)
        self.assertFalse(reliable)
        self.assertGreater(vwap_val, 0.0)

    def test_eight_strategy_modules_and_regime_selection(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=120))
        engine = StrategySelectionEngine()
        report_trend = engine.select_strategy(
            candles=candles,
            current_regime=MarketRegime.STRONG_TREND,
            auto_select=True,
        )
        self.assertEqual(len(report_trend.strategies), 8)
        self.assertTrue(report_trend.auto_selected)
        self.assertEqual(sum(1 for s in report_trend.strategies if s.selected), 1)
        for mod in report_trend.strategies:
            self.assertGreaterEqual(mod.win_rate, 0.0)
            self.assertLessEqual(mod.win_rate, 100.0)
            self.assertGreaterEqual(mod.profit_factor, 0.0)
            self.assertGreaterEqual(mod.max_drawdown, 0.0)
            self.assertGreaterEqual(mod.sample_count, 0)

        pinned = engine.select_strategy(
            candles=candles,
            current_regime=MarketRegime.SIDEWAYS,
            strategy_id="bb_mean_reversion",
            auto_select=False,
        )
        self.assertFalse(pinned.auto_selected)
        self.assertEqual(pinned.selected_strategy_id, "bb_mean_reversion")
        self.assertEqual(engine.get_active_strategy().name, "Bollinger Mean Reversion")

    def test_phase7_17_features_dataset_version_and_drift_detection(self) -> None:
        self.assertEqual(len(FEATURE_NAMES), 17)
        for col in ("adx_norm", "di_spread", "stoch_k_norm", "stoch_d_norm", "vwap_dist_pct"):
            self.assertIn(col, FEATURE_NAMES)

        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=180))
        v1 = FeatureEngineeringEngine.compute_dataset_version(candles, FEATURE_NAMES)
        v2 = FeatureEngineeringEngine.compute_dataset_version(candles, FEATURE_NAMES)
        self.assertTrue(v1.startswith("ds_v2_"))
        self.assertEqual(v1, v2)

        ds = FeatureEngineeringEngine.build_dataset(candles, self.strategy, horizon_bars=1, execution_delay_bars=1)
        self.assertEqual(len(ds.feature_names), 17)
        self.assertEqual(len(ds.features[0]), 17)

        baseline = compute_feature_baseline(ds.features[:80], FEATURE_NAMES)
        reloaded = loads_baseline_stats(dumps_baseline_stats(baseline))
        self.assertEqual(set(reloaded.keys()), set(FEATURE_NAMES))

        # In-distribution check should remain low
        low_score, low_drifted = compute_feature_drift(reloaded, ds.features[:30], FEATURE_NAMES)
        self.assertLess(low_score, 0.35)
        self.assertEqual(low_drifted, [])

        # Strongly shifted distribution must trigger feature drift
        shifted = [[val + 4.5 for val in row] for row in ds.features[-20:]]
        high_score, high_drifted = compute_feature_drift(reloaded, shifted, FEATURE_NAMES)
        self.assertGreaterEqual(high_score, 0.65)
        self.assertTrue(len(high_drifted) > 0)

        # Fake order records: 10 settled losses + PENDING/UNKNOWN orders that MUST be ignored
        class FakeOrder:
            def __init__(self, instrument: str, lifecycle_state: str, status: str, settlement_outcome: str | None):
                self.instrument = instrument
                self.lifecycle_state = lifecycle_state
                self.status = status
                self.settlement_outcome = settlement_outcome
                self.created_at = None

        orders = [
            FakeOrder("EUR/USD", OrderLifecycleState.SETTLED.value, TradeStatus.LOST.value, TradeStatus.LOST.value)
            for _ in range(9)
        ] + [
            FakeOrder("EUR/USD", OrderLifecycleState.SETTLED.value, TradeStatus.WON.value, TradeStatus.WON.value),
            FakeOrder("EUR/USD", OrderLifecycleState.PENDING.value, TradeStatus.PENDING.value, None),
            FakeOrder("EUR/USD", OrderLifecycleState.UNKNOWN.value, TradeStatus.UNKNOWN.value, None),
            FakeOrder("EUR/USD", OrderLifecycleState.FAILED.value, TradeStatus.FAILED.value, None),
            FakeOrder("GBP/USD", OrderLifecycleState.SETTLED.value, TradeStatus.WON.value, TradeStatus.WON.value),
        ]

        report = evaluate_model_drift(
            model_id="mdl_test000001",
            instrument="EUR/USD",
            baseline_stats=reloaded,
            baseline_win_rate=0.62,
            recent_features=ds.features[-20:],
            feature_names=FEATURE_NAMES,
            orders=orders,
        )
        # Strictly counts the 10 settled EUR/USD orders (1 win / 10 settled = 0.10)
        self.assertEqual(report.live_settled_trades, 10)
        self.assertAlmostEqual(report.live_win_rate, 0.10, places=4)
        self.assertEqual(report.drift_status, DriftStatus.DRIFT_DETECTED)
        self.assertTrue(report.recommend_rollback)


if __name__ == "__main__":
    unittest.main()
