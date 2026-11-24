from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Dict, List


@dataclass(frozen=True)
class ConnectorOrderResult:
    order_id: str
    status: str
    entry_price: float
    exit_price: float = 0.0
    pnl: float = 0.0
    message: str = ""


class BrokerConnector(ABC):
    """Independent broker connector abstraction isolating execution from broker specifics."""

    @property
    @abstractmethod
    def name(self) -> str:
        ...

    @property
    @abstractmethod
    def connected(self) -> bool:
        ...

    @property
    @abstractmethod
    def live_authorized(self) -> bool:
        ...

    @abstractmethod
    def get_capabilities(self) -> List[str]:
        ...

    @abstractmethod
    async def connect(self) -> bool:
        ...

    @abstractmethod
    async def disconnect(self) -> None:
        ...

    @abstractmethod
    async def get_market_data(self, instrument: str) -> Dict[str, float]:
        ...

    @abstractmethod
    async def get_candles(self, instrument: str, timeframe: str, limit: int = 80) -> List[Dict[str, float]]:
        ...

    @abstractmethod
    async def get_balance(self) -> float:
        ...

    @abstractmethod
    async def submit_order(
        self,
        instrument: str,
        timeframe: str,
        direction: str,
        amount: float,
        expiry_seconds: int,
    ) -> ConnectorOrderResult:
        ...

    @abstractmethod
    async def get_order_status(self, order_id: str) -> ConnectorOrderResult:
        ...

    @abstractmethod
    async def get_settlement_result(self, order_id: str) -> ConnectorOrderResult:
        ...
