import math
import time
import unittest
from types import SimpleNamespace

from iq_engine.practice_gateway import GatewayError, PracticeReadOnlyGateway


class FakeApi:
    def __init__(self):
        self.closed = 0

    def close(self):
        self.closed += 1


class FakeClient:
    def __init__(self, email, password, mode="PRACTICE", delay=0,
                 connect_result=(True, None), balance=10000.0,
                 balance_type=4, currency="USD", selected_id=44):
        self.mode = mode
        self.delay = delay
        self.connect_result = connect_result
        self.balance = balance
        self.balance_type = balance_type
        self.currency = currency
        self.selected_id = selected_id
        self.api = FakeApi()
        self.broker = None

    def connect(self):
        if self.delay:
            time.sleep(self.delay)
        return self.connect_result

    def change_balance(self, mode):
        self.mode = mode

    def get_balance_mode(self):
        return self.mode

    def get_profile_ansyc(self):
        return {"balances": [{"id": 44, "type": self.balance_type,
                              "currency": self.currency}]}

    def get_balance_id(self):
        return self.selected_id

    def get_balance(self):
        return self.balance


class PracticeGatewayTests(unittest.TestCase):
    def test_practice_read_only_connect(self):
        gateway = PracticeReadOnlyGateway(FakeClient)
        snapshot = gateway.connect_readonly("user", "secret")
        self.assertEqual(snapshot.account_type, "PRACTICE")
        self.assertEqual(snapshot.balance, 10000.0)
        self.assertEqual(snapshot.currency, "USD")
        self.assertEqual(snapshot.account_id, "44")
        self.assertEqual(gateway.state, "CONNECTED_PRACTICE")
        gateway.disconnect()
        self.assertEqual(gateway.state, "DISCONNECTED")

    def test_real_account_is_rejected_and_partial_session_closed(self):
        clients = []

        def factory(email, password):
            client = FakeClient(email, password, mode="REAL", balance_type=1)
            clients.append(client)
            return client

        gateway = PracticeReadOnlyGateway(factory)
        with self.assertRaisesRegex(GatewayError, "Practice"):
            gateway.connect_readonly("user", "secret")
        self.assertEqual(gateway.state, "ERROR")
        self.assertEqual(clients[0].api.closed, 1)

    def test_connection_must_be_explicitly_confirmed(self):
        for result in (None, False, (False, "rejected"), (None, None)):
            gateway = PracticeReadOnlyGateway(
                lambda email, password, result=result:
                    FakeClient(email, password, connect_result=result)
            )
            with self.subTest(result=result):
                with self.assertRaisesRegex(GatewayError, "explicitly confirm"):
                    gateway.connect_readonly("user", "secret")

    def test_balance_type_must_be_integer_four(self):
        gateway = PracticeReadOnlyGateway(
            lambda email, password: FakeClient(email, password, balance_type="4")
        )
        with self.assertRaisesRegex(GatewayError, "explicitly Practice"):
            gateway.connect_readonly("user", "secret")

    def test_missing_currency_is_rejected(self):
        gateway = PracticeReadOnlyGateway(
            lambda email, password: FakeClient(email, password, currency="")
        )
        with self.assertRaisesRegex(GatewayError, "currency"):
            gateway.connect_readonly("user", "secret")

    def test_invalid_balances_are_rejected(self):
        for balance in (-1, float("nan"), float("inf"), "not-a-number"):
            gateway = PracticeReadOnlyGateway(
                lambda email, password, balance=balance:
                    FakeClient(email, password, balance=balance)
            )
            with self.subTest(balance=balance):
                with self.assertRaises(GatewayError):
                    gateway.connect_readonly("user", "secret")

    def test_force_reconnect_closes_current_device_session_and_resets_gateway(self):
        clients = []
        def factory(email, password):
            client = FakeClient(email, password)
            clients.append(client)
            return client
        gateway = PracticeReadOnlyGateway(factory)
        gateway.connect_readonly("user", "secret")
        gateway.force_reconnect()
        self.assertEqual(clients[0].api.closed, 1)
        self.assertEqual(gateway.state, "DISCONNECTED")
        gateway.connect_readonly("user", "secret")
        self.assertEqual(gateway.state, "CONNECTED_PRACTICE")

    def test_force_reconnect_recovers_clean_error_state(self):
        gateway = PracticeReadOnlyGateway(
            lambda email, password: FakeClient(email, password, mode="REAL", balance_type=1)
        )
        with self.assertRaises(GatewayError):
            gateway.connect_readonly("user", "secret")
        self.assertEqual(gateway.state, "ERROR")
        gateway.force_reconnect()
        self.assertEqual(gateway.state, "DISCONNECTED")


    def test_practice_order_requires_verified_practice_and_settles(self):
        gateway = PracticeReadOnlyGateway(FakeClient)
        client_snapshot = gateway.connect_readonly("user", "secret")
        # Replace only the test client's broker interface with a deterministic fake.
        class FakeTrade:
            order_id = "order-123"
            def wait(self, timeout=None, poll_interval=1):
                return SimpleNamespace(
                    trade_state=SimpleNamespace(value="won"),
                    pnl_net=8.5,
                )
        class FakeBroker:
            def trade(self, **kwargs):
                self.kwargs = kwargs
                return FakeTrade()
        gateway._client.broker = FakeBroker()
        result = gateway.submit_practice_order("EURUSD", "CALL", 10, 1)
        self.assertEqual(result["orderId"], "order-123")
        self.assertEqual(result["outcome"], "WIN")
        self.assertEqual(result["pnl"], 8.5)
        self.assertEqual(result["balanceBefore"], 10000.0)
        self.assertEqual(result["balanceAfter"], 10000.0)
        self.assertEqual(result["source"], "BROKER_CONFIRMED")
        self.assertEqual(gateway.state, "CONNECTED_PRACTICE")
        gateway.disconnect()

    def test_ambiguous_order_result_locks_additional_orders(self):
        gateway = PracticeReadOnlyGateway(FakeClient)
        gateway.connect_readonly("user", "secret")
        class FakeTrade:
            order_id = "order-ambiguous"
            def wait(self, timeout=None, poll_interval=1):
                raise TimeoutError("not settled")
        class FakeBroker:
            def trade(self, **kwargs):
                return FakeTrade()
        gateway._client.broker = FakeBroker()
        with self.assertRaises(GatewayError):
            gateway.submit_practice_order("EURUSD", "PUT", 10, 1)
        self.assertEqual(gateway.state, "ORDER_UNKNOWN")
        with self.assertRaisesRegex(GatewayError, "unresolved outcome"):
            gateway.submit_practice_order("EURUSD", "CALL", 10, 1)

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
