from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings, get_settings
from app.engines.broker_dry_run import verify_bearer_credentials

bearer_scheme = HTTPBearer(auto_error=False)


def verify_bearer_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    settings: Settings = Depends(get_settings),
) -> None:
    presented = credentials.credentials if credentials is not None else None
    ok, code, detail = verify_bearer_credentials(
        presented_token=presented,
        expected_token=settings.api_bearer_token,
        app_env=settings.app_env,
    )
    if not ok:
        raise HTTPException(status_code=code, detail=detail)
