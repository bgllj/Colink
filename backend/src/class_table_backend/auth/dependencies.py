from __future__ import annotations

from typing import Annotated

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from class_table_backend.api.deps import get_session
from class_table_backend.auth.tokens import TokenError, decode_token
from class_table_backend.persistence.repositories import AdminUserRepository
from class_table_backend.persistence.tables import AdminUserRow

_bearer_scheme = HTTPBearer(auto_error=False)

SessionDep = Annotated[Session, Depends(get_session)]


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(status_code=401, detail=detail)


def require_admin(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)],
    session: SessionDep,
) -> AdminUserRow:
    """Validate the Bearer token and return the corresponding admin user."""
    if credentials is None or not credentials.credentials:
        raise _unauthorized("未提供认证凭证")
    try:
        claims = decode_token(credentials.credentials)
    except TokenError as exc:
        raise _unauthorized("认证凭证无效或已过期") from exc
    user = AdminUserRepository(session).get_by_id(claims.subject)
    if user is None:
        raise _unauthorized("认证凭证无效或已过期")
    return user


AdminDep = Annotated[AdminUserRow, Depends(require_admin)]
