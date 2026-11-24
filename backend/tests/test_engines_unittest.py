import asyncio
import unittest

from app.connectors.simulation import SimulationConnector
from app.domain import ExecutionMode, RiskPolicyConfig, StrategyConfig, TradeDirection
from app.engines.market_intelligence import MarketIntelligenceEngine
from app.engines.risk_engine import RiskManagementEngine
from app.engines.signal_engine import AISignalEngine
from app.engines.strategy_engine import StrategySelectionEngine
from app.engines.technical_analysis import TechnicalAnalysisEngine


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

    def test_market_intelligence_rejects_corrupted_ohlc(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=30))
        bad = [dict(c) for c in candles]
        bad[-1]["high"] = bad[-1]["low"] - 0.01  # high < low
        valid, reason = MarketIntelligenceEngine.validate_candles(bad)
        self.assertFalse(valid)
        self.assertIn("Corrupted OHLC", reason)

        too_few, _ = MarketIntelligenceEngine.validate_candles(candles[:10])
        self.assertFalse(too_few)

    def test_technical_analysis_and_signal_calibration(self) -> None:
        candles = asyncio.run(self.connector.get_candles("EUR/USD", "1m", limit=80))
        snapshot = TechnicalAnalysisEngine.analyze(candles, self.strategy)
        self.assertGreaterEqual(snapshot.rsi, 0.0)
        self.assertLessEqual(snapshot.rsi, 100.0)
        self.assertGreaterEqual(snapshot.bb_upper, snapshot.bb_middle)
        self.assertGreaterEqual(snapshot.bb_middle, snapshot.bb_lower)
        self.assertGreater(snapshot.atr, 0.0)

        signal = AISignalEngine.evaluate(candles, snapshot, self.strategy)
        self.assertIn(signal.direction, (TradeDirection.CALL, TradeDirection.PUT, TradeDirection.NO_TRADE))
        self.assertGreaterEqual(signal.calibrated_probability, 0.46)
        self.assertLessEqual(signal.calibrated_probability, 0.70)
        self.assertGreaterEqual(signal.confidence_score, 10)
        self.assertLessEqual(signal.confidence_score, 96)
        # Probability must remain separated from raw confidence percentage
        self.assertNotEqual(round(signal.calibrated_probability * 100), signal.confidence_score)

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

        # Valid simulation order passes
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

        # LIVE locked when unauthorized
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

        # Emergency stop blocks order
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

        # Daily loss breach blocks order
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

        # Loss cooldown blocks immediate retry after a loss
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
