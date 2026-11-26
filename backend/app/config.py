from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "IQ Trading AI Backend"
    app_env: str = "development"
    database_url: str = "sqlite:///./iq_trading.db"
    default_execution_mode: str = "SIMULATION"
    allow_live_trading: bool = False
    api_bearer_token: str = ""
    allowed_origins: str = ""
    model_artifacts_dir: str = "artifacts/models"
    broker_adapter: str = "simulation"
    broker_base_url: str = ""
    broker_api_token: str = ""
    broker_timeout_seconds: float = 10.0


@lru_cache
def get_settings() -> Settings:
    return Settings()
