import unittest

from iq_engine.practice_contract import (
    BrokerContractError,
    assert_practice_mode,
    parse_practice_account,
)


class PracticeContractTests(unittest.TestCase):
    def setUp(self):
        self.profile = {
            "balances": [{"id": 123, "type": 4, "currency": "USD"}]
        }
        self.balances = {"msg": [{"id": 123, "amount": 10000, "available": 9800}]}

    def test_accepts_broker_reported_practice_account(self):
        account = parse_practice_account(self.profile, self.balances, 123)
        self.assertEqual(account.account_type, "PRACTICE")
        self.assertTrue(account.is_demo)
        self.assertEqual(account.balance, 10000)
        self.assertEqual(account.available_balance, 9800)

    def test_rejects_real_account(self):
        self.profile["balances"][0]["type"] = 1
        with self.assertRaises(BrokerContractError):
            parse_practice_account(self.profile, self.balances, 123)

    def test_rejects_unmatched_balance(self):
        with self.assertRaises(BrokerContractError):
            parse_practice_account(self.profile, self.balances, 999)

    def test_rejects_malformed_payload(self):
        with self.assertRaises(BrokerContractError):
            parse_practice_account({}, {}, 123)

    def test_rejects_non_finite_balance(self):
        self.balances["msg"][0]["amount"] = float("nan")
        with self.assertRaises(BrokerContractError):
            parse_practice_account(self.profile, self.balances, 123)

    def test_mode_guard_is_practice_only(self):
        assert_practice_mode("PRACTICE")
        for mode in ("REAL", "UNKNOWN", "", "None"):
            with self.subTest(mode=mode):
                with self.assertRaises(BrokerContractError):
                    assert_practice_mode(mode)


if __name__ == "__main__":
    unittest.main()
