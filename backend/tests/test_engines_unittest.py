import asyncio
import unittest

from app.connectors.simulation import SimulationConnector
from app.domain import (
    AlignmentState,
    ExecutionMode,
    RiskPolicyConfig,
    StrategyConfig,
    TradeDirection,
)
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.multi_timeframe import MultiTimeframeEngine
from app.engines.replay_engine import SimulatorReplayEngine
from app.engines.risk_engine import RiskManagementEngine
from app.engines.signal_engine import AISignalEngine
from app.engines.strategy_engine import StrategySelectionEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine


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

        # Higher timeframes strongly bearish while 1m is bullish -> CONFLICTING -> forced NO_TRADE
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
            )
        )
        self.assertIn(res.status, ("WON", "LOST"))
        fetched = asyncio.run(self.connector.get_settlement_result(res.order_id))
        self.assertEqual(fetched.order_id, res.order_id)


if __name__ == "__main__":
    unittest.main()
