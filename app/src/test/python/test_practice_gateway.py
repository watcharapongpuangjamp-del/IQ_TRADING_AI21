import math
import time
import unittest

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
        self.assertEqual(gateway.state, "CONNECTED_READ_ONLY")
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
