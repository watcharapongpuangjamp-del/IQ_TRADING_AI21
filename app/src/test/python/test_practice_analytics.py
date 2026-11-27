import unittest

from iq_engine.practice_analytics import summarize_trades


class PracticeAnalyticsTests(unittest.TestCase):
    def test_win_draw_loss_rates_and_streaks(self):
        rows = [
            {"outcome": "WIN", "pnl": 8, "balanceAfter": 1008},
            {"outcome": "WIN", "pnl": 8, "balanceAfter": 1016},
            {"outcome": "DRAW", "pnl": 0, "balanceAfter": 1016},
            {"outcome": "LOSS", "pnl": -10, "balanceAfter": 1006},
            {"outcome": "LOSS", "pnl": -10, "balanceAfter": 996},
            {"outcome": "LOSS", "pnl": -10, "balanceAfter": 986},
            {"outcome": "PENDING", "pnl": 0},
        ]
        result = summarize_trades(rows, starting_balance=1000)
        self.assertEqual(result["totalTrades"], 6)
        self.assertEqual(result["wins"], 2)
        self.assertEqual(result["draws"], 1)
        self.assertEqual(result["losses"], 3)
        self.assertAlmostEqual(result["winRate"], 2 / 6 * 100)
        self.assertAlmostEqual(result["decisiveWinRate"], 2 / 5 * 100)
        self.assertEqual(result["netPnl"], -14)
        self.assertEqual(result["maxWinStreak"], 2)
        self.assertEqual(result["maxLossStreak"], 3)
        self.assertEqual(result["currentLossStreak"], 3)
        self.assertEqual(result["maxDrawdown"], 30)
        self.assertAlmostEqual(result["maxDrawdownPct"], 30 / 1016 * 100)

    def test_empty_and_unsettled_are_not_counted(self):
        result = summarize_trades([{"outcome": "PENDING", "pnl": None}], 500)
        self.assertEqual(result["totalTrades"], 0)
        self.assertEqual(result["winRate"], 0)
        self.assertEqual(result["netPnl"], 0)
        self.assertEqual(result["latestBalance"], None)


if __name__ == "__main__":
    unittest.main()
