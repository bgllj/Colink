from __future__ import annotations

import logging
import os
import sys

from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.auth.service import ensure_admin_user
from class_table_backend.persistence.db import get_session_factory, session_scope
from class_table_backend.persistence.tables import AdminUserRow

logger = logging.getLogger(__name__)

ENV_USERNAME = "ADMIN_BOOTSTRAP_USERNAME"
ENV_PASSWORD = "ADMIN_BOOTSTRAP_PASSWORD"


def bootstrap_admin(
    session: Session,
    *,
    username: str,
    password: str,
) -> tuple[AdminUserRow, bool]:
    """Create the admin *username* when absent; never overwrite an existing password."""
    user, created = ensure_admin_user(session, username=username, password=password)
    if created:
        logger.info("已创建管理员用户: %s", username)
    else:
        logger.info("管理员用户已存在，保留原密码: %s", username)
    return user, created


def bootstrap_from_env(
    session_factory: sessionmaker[Session] | None = None,
) -> tuple[AdminUserRow, bool] | None:
    """Bootstrap from ``ADMIN_BOOTSTRAP_USERNAME`` / ``ADMIN_BOOTSTRAP_PASSWORD``.

    Returns ``None`` when either environment variable is unset/empty.
    """
    username = os.environ.get(ENV_USERNAME)
    password = os.environ.get(ENV_PASSWORD)
    if not username or not password:
        return None
    factory = session_factory if session_factory is not None else get_session_factory()
    with session_scope(session_factory=factory) as session:
        return bootstrap_admin(session, username=username, password=password)


def main() -> int:
    """Entry point: ``python -m class_table_backend.auth.bootstrap``."""
    logging.basicConfig(level=logging.INFO)
    username = os.environ.get(ENV_USERNAME)
    password = os.environ.get(ENV_PASSWORD)
    if not username or not password:
        print(
            f"请先设置环境变量 {ENV_USERNAME} 与 {ENV_PASSWORD}。",
            file=sys.stderr,
        )
        return 1
    result = bootstrap_from_env()
    assert result is not None
    user, created = result
    if created:
        print(f"已创建管理员用户: {user.username}")
    else:
        print(f"管理员用户已存在，未修改密码: {user.username}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
