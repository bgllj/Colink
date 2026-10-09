from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass

import jwt

logger = logging.getLogger(__name__)

DEFAULT_TTL_SECONDS = 3600
DEV_FALLBACK_SECRET = "dev-only-insecure-admin-jwt-secret-change-me"
_WARNED_DEV_SECRET = False


class TokenError(Exception):
    """Raised when a token cannot be decoded or is otherwise invalid."""


@dataclass(frozen=True)
class AdminClaims:
    subject: str
    username: str
    expires_at: int
    issued_at: int


def _warn_dev_secret_once() -> None:
    global _WARNED_DEV_SECRET
    if not _WARNED_DEV_SECRET:
        logger.warning(
            "ADMIN_JWT_SECRET 未设置，正在使用不安全的开发回退密钥；请勿用于生产环境。"
        )
        _WARNED_DEV_SECRET = True


def resolve_secret(explicit: str | None = None) -> str:
    if explicit:
        return explicit
    secret = os.environ.get("ADMIN_JWT_SECRET")
    if secret:
        return secret
    _warn_dev_secret_once()
    return DEV_FALLBACK_SECRET


def resolve_ttl_seconds(explicit: int | None = None) -> int:
    if explicit is not None:
        return explicit
    raw = os.environ.get("ADMIN_JWT_TTL_SECONDS")
    if not raw:
        return DEFAULT_TTL_SECONDS
    try:
        ttl = int(raw)
    except ValueError:
        logger.warning("ADMIN_JWT_TTL_SECONDS 不是整数，使用默认 %s 秒。", DEFAULT_TTL_SECONDS)
        return DEFAULT_TTL_SECONDS
    if ttl < 1:
        logger.warning("ADMIN_JWT_TTL_SECONDS 必须为正数，使用默认 %s 秒。", DEFAULT_TTL_SECONDS)
        return DEFAULT_TTL_SECONDS
    return ttl


def issue_token(
    *,
    user_id: str,
    username: str,
    secret: str | None = None,
    ttl_seconds: int | None = None,
    now: int | None = None,
) -> str:
    """Issue an HS256 JWT for the given admin user."""
    actual_secret = resolve_secret(secret)
    ttl = resolve_ttl_seconds(ttl_seconds)
    issued_at = int(time.time()) if now is None else int(now)
    payload = {
        "sub": user_id,
        "username": username,
        "iat": issued_at,
        "exp": issued_at + ttl,
    }
    return jwt.encode(payload, actual_secret, algorithm="HS256")


def decode_token(
    token: str,
    *,
    secret: str | None = None,
) -> AdminClaims:
    """Decode and validate *token*; raise :class:`TokenError` on any failure."""
    actual_secret = resolve_secret(secret)
    try:
        payload = jwt.decode(token, actual_secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError as exc:
        raise TokenError("token 已过期") from exc
    except jwt.InvalidTokenError as exc:
        raise TokenError("token 无效") from exc

    subject = payload.get("sub")
    username = payload.get("username")
    if not isinstance(subject, str) or not subject:
        raise TokenError("token 缺少有效的 sub")
    if not isinstance(username, str):
        username = ""
    expires_at = payload.get("exp")
    issued_at = payload.get("iat")
    return AdminClaims(
        subject=subject,
        username=username,
        expires_at=int(expires_at) if expires_at is not None else 0,
        issued_at=int(issued_at) if issued_at is not None else 0,
    )
