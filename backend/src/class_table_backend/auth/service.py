from __future__ import annotations

import uuid

from sqlalchemy.orm import Session

from class_table_backend.auth.passwords import hash_password
from class_table_backend.persistence.repositories import AdminUserRepository
from class_table_backend.persistence.tables import AdminUserRow


def ensure_admin_user(
    session: Session,
    *,
    username: str,
    password: str,
) -> tuple[AdminUserRow, bool]:
    """Create the admin user if missing.

    Never overwrites the password of an existing user with the same username.
    Returns ``(user, created)``.
    """
    repo = AdminUserRepository(session)
    existing = repo.get_by_username(username)
    if existing is not None:
        return existing, False
    user = repo.create(
        user_id=str(uuid.uuid4()),
        username=username,
        password_hash=hash_password(password),
    )
    return user, True
