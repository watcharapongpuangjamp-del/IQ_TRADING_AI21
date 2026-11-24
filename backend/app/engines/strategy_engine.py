from app.domain import StrategyConfig


class StrategySelectionEngine:
    """Manages active strategy parameters and validation."""

    def __init__(self) -> None:
        self._active_strategy = StrategyConfig()

    def get_active_strategy(self) -> StrategyConfig:
        return self._active_strategy

    def update_strategy(self, params: StrategyConfig) -> StrategyConfig:
        self._active_strategy = params
        return self._active_strategy
