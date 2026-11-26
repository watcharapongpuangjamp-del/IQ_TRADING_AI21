import threading
import time
import unittest

from iq_engine.practice_gateway import GatewayError, PracticeReadOnlyGateway


class FakeApi:
    def close(self):
        return None


class FakeClient:
    def __init__(self, email, password, mode="PRACTICE", delay=0):
        self.mode = mode
        self.delay = delay
        self.api = FakeApi()

    def connect(self):
        if self.delay:
            time.sleep(self.delay)
        return True, None

    def get_balance_mode(self):
        return self.mode

    def get_profile_ansyc(self):
        return {"balances": [{"id": 44, "type": 4 if self.mode == "PRACTICE" else 1,
                              "currency": "USD"}]}

    def get_balance_id(self):
        return 44

    def get_balance(self):
        return 10000.0


class PracticeGatewayTests(unittest.TestCase):
    def test_practice_read_only_connect(self):
        gateway = PracticeReadOnlyGateway(FakeClient)
        snapshot = gateway.connect_readonly("user", "secret")
        self.assertEqual(snapshot.account_type, "PRACTICE")
        self.assertEqual(snapshot.balance, 10000.0)
        self.assertEqual(gateway.state, "CONNECTED_READ_ONLY")

    def test_real_account_is_rejected(self):
        gateway = PracticeReadOnlyGateway(
            lambda email, password: FakeClient(email, password, mode="REAL")
        )
        with self.assertRaises(GatewayError):
            gateway.connect_readonly("user", "secret")
        self.assertEqual(gateway.state, "ERROR")

    def test_order_submission_is_disabled(self):
        gateway = PracticeReadOnlyGateway(FakeClient)
        with self.assertRaises(GatewayError):
            gateway.submit_order("EURUSD", 1)

    def test_account_switch_is_disabled(self):
        gateway = PracticeReadOnlyGateway(FakeClient)
        with self.assertRaises(GatewayError):
            gateway.switch_account("REAL")

    def test_timeout_poisons_gateway_and_never_reuses_it(self):
        gateway = PracticeReadOnlyGateway(
            lambda email, password: FakeClient(email, password, delay=0.15),
            timeout_seconds=0.02,
        )
        with self.assertRaisesRegex(GatewayError, "timed out"):
            gateway.connect_readonly("user", "secret")
        self.assertEqual(gateway.state, "POISONED")
        with self.assertRaisesRegex(GatewayError, "poisoned"):
            gateway.connect_readonly("user", "secret")


if __name__ == "__main__":
    unittest.main()
