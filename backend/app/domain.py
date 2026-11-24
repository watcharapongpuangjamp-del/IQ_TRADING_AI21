from dataclasses import dataclass, field
from enum import Enum
from typing import List


class ExecutionMode(str, Enum):
    SIMULATION = "SIMULATION"
    DEMO = "DEMO"
    LIVE = "LIVE"


class TradeDirection(str, Enum):
    CALL = "CALL"
    PUT = "PUT"
    NO_TRADE = "NO_TRADE"


class TradeStatus(str, Enum):
    OPEN = "OPEN"
    WON = "WON"
    LOST = "LOST"
    REJECTED = "REJECTED"
    CANCELLED = "CANCELLED"


class MarketRegime(str, Enum):
    TRENDING_UP = "TRENDING_UP"
    TRENDING_DOWN = "TRENDING_DOWN"
    RANGING = "RANGING"
    HIGH_VOLATILITY = "HIGH_VOLATILITY"


@dataclass
class StrategyConfig:
    name: str = "EMA-RSI Momentum"
    instrument: str = "EUR/USD"
    timeframe: str = "1m"
    trade_amount: float = 25.0
    expiry_seconds: int = 60
    rsi_period: int = 14
    rsi_oversold: int = 30
    rsi_overbought: int = 70
    ema_fast: int = 9
    ema_slow: int = 21
    macd_fast: int = 12
    macd_slow: int = 26
    macd_signal: int = 9
    bb_period: int = 20
    bb_std_dev: float = 2.0
    min_confidence: int = 65


@dataclass(frozen=True)
class TechnicalSnapshot:
    rsi: float
    macd_line: float
    macd_signal: float
    macd_histogram: float
    ema_fast: float
    ema_slow: float
    bb_upper: float
    bb_middle: float
    bb_lower: float
    atr: float
    volatility_pct: float
    trend_strength: float
    support_level: float
    resistance_level: float
    candlestick_pattern: str
    regime: MarketRegime


@dataclass(frozen=True)
class SignalResult:
    instrument: str
    timeframe: str
    direction: TradeDirection
    calibrated_probability: float
    confidence_score: int
    supporting_evidence: List[str] = field(default_factory=list)
    contradicting_evidence: List[str] = field(default_factory=list)
    market_regime: MarketRegime = MarketRegime.RANGING
    risk_assessment: str = ""
    explanation: str = ""
    expires_at_ms: int = 0
    technical_snapshot: TechnicalSnapshot | None = None


@dataclass
class RiskPolicyConfig:
    max_trade_amount: float = 100.0
    max_daily_loss: float = 250.0
    max_consecutive_losses: int = 3
    max_daily_trades: int = 30
    max_portfolio_exposure: float = 500.0
    cooldown_minutes: int = 15
    emergency_stopped: bool = False
    auto_trade_enabled: bool = False
