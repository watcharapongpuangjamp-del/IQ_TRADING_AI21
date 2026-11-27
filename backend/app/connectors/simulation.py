import math
import random
import time
import uuid
from typing import Dict, List, Optional, Set

from app.connectors.base import BrokerConnector, ConnectorOrderResult

INSTRUMENT_SPECS: Dict[str, Dict[str, float]] = {
    "EUR/USD": {"base": 1.08450, "vol": 0.00045, "payout": 0.85},
    "GBP/USD": {"base": 1.26820, "vol": 0.00060, "payout": 0.84},
    "USD/JPY": {"base": 153.420, "vol": 0.07500, "payout": 0.83},
    "BTC/USD": {"base": 94250.0, "vol": 145.000, "payout": 0.80},
    "XAU/USD": {"base": 2642.50, "vol": 1.85000, "payout": 0.82},
}

TIMEFRAME_SECONDS: Dict[str, int] = {
    "1m": 60,
    "5m": 300,
    "15m": 900,
}


class SimulationConnector(BrokerConnector):
    """Deterministic-capable Simulation Connector supporting full 10-state reconciliation."""

    def __init__(self, initial_balance: float = 10000.0) -> None:
        self._connected: bool = True
        self._balance: float = initial_balance
        self._orders: Dict[str, ConnectorOrderResult] = {}
        self._orders_by_correlation: Dict[str, ConnectorOrderResult] = {}
        self._deferred_settlements: Dict[str, ConnectorOrderResult] = {}
        self._settled_order_ids: Set[str] = set()
        self._queued_behaviors: List[str] = []
        self._persistent_reconcile_unknown: bool = False
        self._candle_cache: Dict[str, List[Dict[str, float]]] = {}

    @property
    def name(self) -> str:
        return "simulation_connector"

    @property
    def connected(self) -> bool:
        return self._connected

    @property
    def live_authorized(self) -> bool:
        # Simulation connector never authorizes LIVE real-money trading
        return False

    def get_capabilities(self) -> List[str]:
        return [
            "SIMULATION_STREAM",
            "MARKET_DATA",
            "CANDLES",
            "PAPER_ORDERS",
            "RISK_VALIDATION",
            "RECONCILIATION",
        ]

    def queue_simulation_behavior(self, behavior: str, persistent_unknown: bool = False) -> None:
        """
        Queue next submit_order behavior for deterministic reconciliation testing:
        SETTLED | PENDING | TIMEOUT | REJECTED | FAILED
        """
        self._queued_behaviors.append(behavior.upper())
        self._persistent_reconcile_unknown = persistent_unknown

    def set_persistent_unknown(self, enabled: bool) -> None:
        self._persistent_reconcile_unknown = enabled

    async def connect(self) -> bool:
        self._connected = True
        return True

    async def disconnect(self) -> None:
        self._connected = False

    async def get_balance(self) -> float:
        return round(self._balance, 2)

    async def get_market_data(self, instrument: str) -> Dict[str, float]:
        candles = await self.get_candles(instrument, "1m", limit=2)
        last = candles[-1]
        return {
            "price": last["close"],
            "timestamp": last["timestamp"],
            "payout_rate": INSTRUMENT_SPECS.get(instrument, INSTRUMENT_SPECS["EUR/USD"])["payout"],
        }

    async def get_candles(self, instrument: str, timeframe: str, limit: int = 80) -> List[Dict[str, float]]:
        cache_key = f"{instrument}:{timeframe}"
        if cache_key not in self._candle_cache:
            self._candle_cache[cache_key] = self._generate_initial_candles(instrument, timeframe, max(limit, 80))
        elif len(self._candle_cache[cache_key]) < limit:
            # A prior small request may have seeded a shorter cache. Rebuild enough
            # chronological history instead of returning a truncated backtest window.
            self._candle_cache[cache_key] = self._generate_initial_candles(instrument, timeframe, limit)
        else:
            self._append_or_update_tick(cache_key, instrument, timeframe)
        return list(self._candle_cache[cache_key][-limit:])

    def _apply_settlement_to_balance(self, order_id: str, pnl: float) -> None:
        if order_id not in self._settled_order_ids:
            self._settled_order_ids.add(order_id)
            self._balance = max(0.0, round(self._balance + pnl, 2))

    async def submit_order(
        self,
        instrument: str,
        timeframe: str,
        direction: str,
        amount: float,
        expiry_seconds: int,
        correlation_id: Optional[str] = None,
    ) -> ConnectorOrderResult:
        if not self._connected:
            raise ConnectionError("Simulation connector is disconnected.")

        corr_id = correlation_id or f"corr_{uuid.uuid4().hex[:12]}"
        if corr_id in self._orders_by_correlation:
            return self._orders_by_correlation[corr_id]

        candles = await self.get_candles(instrument, timeframe, limit=5)
        entry_price = candles[-1]["close"]
        spec = INSTRUMENT_SPECS.get(instrument, INSTRUMENT_SPECS["EUR/USD"])
        order_id = f"sim_{uuid.uuid4().hex[:12]}"

        behavior = self._queued_behaviors.pop(0) if self._queued_behaviors else "SETTLED"

        if behavior == "REJECTED":
            rejected = ConnectorOrderResult(
                order_id=order_id,
                broker_order_id=order_id,
                correlation_id=corr_id,
                status="REJECTED",
                lifecycle_state="REJECTED",
                settlement_outcome=None,
                entry_price=entry_price,
                exit_price=entry_price,
                pnl=0.0,
                message=f"Simulated broker rejected {direction} order",
            )
            self._orders[order_id] = rejected
            self._orders_by_correlation[corr_id] = rejected
            return rejected

        if behavior == "FAILED":
            failed = ConnectorOrderResult(
                order_id=order_id,
                broker_order_id=order_id,
                correlation_id=corr_id,
                status="FAILED",
                lifecycle_state="FAILED",
                settlement_outcome=None,
                entry_price=entry_price,
                exit_price=0.0,
                pnl=0.0,
                message=f"Simulated broker execution failure for {direction}",
            )
            self._orders[order_id] = failed
            self._orders_by_correlation[corr_id] = failed
            return failed

        rng = random.Random()
        drift = (rng.random() - 0.48) * spec["vol"] * 1.4
        exit_price = max(entry_price + drift, spec["base"] * 0.5)
        if abs(exit_price - entry_price) < 1e-8:
            exit_price = entry_price + spec["vol"] * 0.25

        won = (exit_price > entry_price) if direction == "CALL" else (exit_price < entry_price)
        pnl = round(amount * spec["payout"], 2) if won else round(-amount, 2)
        outcome = "WON" if won else "LOST"

        settled_result = ConnectorOrderResult(
            order_id=order_id,
            broker_order_id=order_id,
            correlation_id=corr_id,
            status=outcome,
            lifecycle_state="SETTLED",
            settlement_outcome=outcome,
            entry_price=entry_price,
            exit_price=exit_price,
            pnl=pnl,
            message=f"Simulated {direction} settled as {outcome}",
        )

        if behavior in ("PENDING", "TIMEOUT"):
            self._deferred_settlements[order_id] = settled_result
            self._deferred_settlements[corr_id] = settled_result
            pending_result = ConnectorOrderResult(
                order_id=order_id,
                broker_order_id=order_id,
                correlation_id=corr_id,
                status="PENDING",
                lifecycle_state="PENDING",
                settlement_outcome=None,
                entry_price=entry_price,
                exit_price=0.0,
                pnl=0.0,
                message=f"Simulated {direction} awaiting settlement",
            )
            self._orders[order_id] = pending_result
            self._orders_by_correlation[corr_id] = pending_result
            if behavior == "TIMEOUT":
                raise TimeoutError(f"Simulated broker timeout while acknowledging order {corr_id}")
            return pending_result

        self._apply_settlement_to_balance(order_id, pnl)
        self._orders[order_id] = settled_result
        self._orders_by_correlation[corr_id] = settled_result
        return settled_result

    async def get_order_status(self, order_id: str) -> ConnectorOrderResult:
        if order_id not in self._orders:
            raise KeyError(f"Order {order_id} not found")
        return self._orders[order_id]

    async def get_settlement_result(self, order_id: str) -> ConnectorOrderResult:
        return await self.get_order_status(order_id)

    async def reconcile_order(
        self,
        order_id: str,
        correlation_id: Optional[str] = None,
    ) -> ConnectorOrderResult:
        if not self._connected:
            raise ConnectionError("Simulation connector is disconnected.")
        if self._persistent_reconcile_unknown:
            raise TimeoutError("Simulated persistent broker timeout during reconciliation.")

        deferred = self._deferred_settlements.pop(order_id, None)
        if deferred is None and correlation_id:
            deferred = self._deferred_settlements.pop(correlation_id, None)
        if deferred is not None:
            self._deferred_settlements.pop(deferred.order_id, None)
            if deferred.correlation_id:
                self._deferred_settlements.pop(deferred.correlation_id, None)
            self._apply_settlement_to_balance(deferred.order_id, deferred.pnl)
            self._orders[deferred.order_id] = deferred
            if deferred.correlation_id:
                self._orders_by_correlation[deferred.correlation_id] = deferred
            return deferred

        if order_id in self._orders:
            return self._orders[order_id]
        if correlation_id and correlation_id in self._orders_by_correlation:
            return self._orders_by_correlation[correlation_id]

        raise KeyError(f"Order {order_id} (correlation={correlation_id}) not found for reconciliation")

    def _generate_initial_candles(self, instrument: str, timeframe: str, count: int) -> List[Dict[str, float]]:
        spec = INSTRUMENT_SPECS.get(instrument, INSTRUMENT_SPECS["EUR/USD"])
        tf_sec = TIMEFRAME_SECONDS.get(timeframe, 60)
        now_ms = int(time.time() * 1000)
        rng = random.Random(hash((instrument, timeframe)) & 0xFFFFFFFF)

        candles: List[Dict[str, float]] = []
        price = spec["base"]
        for i in range(count, 0, -1):
            ts = now_ms - i * tf_sec * 1000
            wave = math.sin(i * 0.22) * spec["vol"] * 0.9
            noise = (rng.random() - 0.5) * spec["vol"] * 2.1
            open_p = price
            close_p = max(open_p + wave + noise, spec["base"] * 0.5)
            high_p = max(open_p, close_p) + rng.random() * spec["vol"] * 0.85
            low_p = min(open_p, close_p) - rng.random() * spec["vol"] * 0.85
            volume = 120.0 + rng.random() * 450.0
            candles.append(
                {
                    "timestamp": ts,
                    "open": round(open_p, 6),
                    "high": round(high_p, 6),
                    "low": round(low_p, 6),
                    "close": round(close_p, 6),
                    "volume": round(volume, 2),
                }
            )
            price = close_p
        return candles

    def _append_or_update_tick(self, cache_key: str, instrument: str, timeframe: str) -> None:
        candles = self._candle_cache[cache_key]
        spec = INSTRUMENT_SPECS.get(instrument, INSTRUMENT_SPECS["EUR/USD"])
        tf_ms = TIMEFRAME_SECONDS.get(timeframe, 60) * 1000
        last = candles[-1]
        now_ms = int(time.time() * 1000)
        delta = (random.random() - 0.5) * spec["vol"] * 1.1
        next_close = max(last["close"] + delta, spec["base"] * 0.5)

        if now_ms - last["timestamp"] >= tf_ms:
            candles.append(
                {
                    "timestamp": now_ms,
                    "open": last["close"],
                    "high": round(max(last["close"], next_close) + random.random() * spec["vol"] * 0.2, 6),
                    "low": round(min(last["close"], next_close) - random.random() * spec["vol"] * 0.2, 6),
                    "close": round(next_close, 6),
                    "volume": round(90.0 + random.random() * 200.0, 2),
                }
            )
            if len(candles) > 600:
                candles.pop(0)
        else:
            last["high"] = round(max(last["high"], next_close), 6)
            last["low"] = round(min(last["low"], next_close), 6)
            last["close"] = round(next_close, 6)
            last["volume"] = round(last["volume"] + random.random() * 25.0, 2)
