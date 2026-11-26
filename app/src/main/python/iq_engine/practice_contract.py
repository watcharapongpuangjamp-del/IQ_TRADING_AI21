"""Fail-closed contract for a future IQAir Practice adapter.

This module deliberately does not open a broker connection or submit orders.
It validates broker-reported identity and account data before Kotlin can use it.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
from typing import Any, Mapping


class BrokerContractError(ValueError):
    """Raised when broker data is missing, inconsistent, or unsafe."""


@dataclass(frozen=True)
class PracticeAccount:
    account_id: str
    currency: str
    balance: float
    available_balance: float
    is_demo: bool
    account_type: str = "PRACTICE"

    def to_payload(self) -> dict[str, Any]:
        return {
            "brokerAccountId": self.account_id,
            "accountType": self.account_type,
            "currency": self.currency,
            "balance": self.balance,
            "availableBalance": self.available_balance,
            "isDemo": self.is_demo,
        }


def parse_practice_account(profile: Mapping[str, Any], balances: Mapping[str, Any],
                           selected_balance_id: int | str) -> PracticeAccount:
    """Build a Practice snapshot from broker profile + balances responses.

    IQAir documents balance type 4 as PRACTICE and type 1 as REAL. The selected
    balance must be present in both responses and explicitly report type 4.
    """
    profile_balances = profile.get("balances")
    balance_rows = balances.get("msg")
    if not isinstance(profile_balances, list) or not isinstance(balance_rows, list):
        raise BrokerContractError("Broker profile/balance payload is malformed.")

    profile_row = next((row for row in profile_balances
                        if str(row.get("id")) == str(selected_balance_id)), None)
    amount_row = next((row for row in balance_rows
                       if str(row.get("id")) == str(selected_balance_id)), None)
    if profile_row is None or amount_row is None:
        raise BrokerContractError("Selected balance is not confirmed by broker.")
    if profile_row.get("type") != 4:
        raise BrokerContractError("Selected account is not broker-reported Practice.")
    if profile_row.get("type") == 1:
        raise BrokerContractError("Real account data is forbidden in Practice adapter.")

    account_id = str(profile_row.get("id", "")).strip()
    currency = str(profile_row.get("currency", "")).strip()
    balance = amount_row.get("amount")
    available = amount_row.get("available", balance)

    if not account_id or not currency:
        raise BrokerContractError("Broker account ID and currency are required.")
    try:
        balance = float(balance)
        available = float(available)
    except (TypeError, ValueError) as exc:
        raise BrokerContractError("Broker balance is not numeric.") from exc
    if not isfinite(balance) or not isfinite(available) or balance < 0 or available < 0:
        raise BrokerContractError("Broker balance is invalid.")

    return PracticeAccount(
        account_id=account_id,
        currency=currency,
        balance=balance,
        available_balance=available,
        is_demo=True,
    )


def assert_practice_mode(mode: str) -> None:
    """Reject any account mode other than an explicit broker-reported Practice."""
    if str(mode).upper() != "PRACTICE":
        raise BrokerContractError("Practice-only adapter rejected non-Practice mode.")
