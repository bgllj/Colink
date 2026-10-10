from __future__ import annotations

from collections.abc import Iterator

from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.persistence.db import get_session_factory

_default_session_factory: sessionmaker[Session] | None = None


def get_session() -> Iterator[Session]:
    global _default_session_factory
    if _default_session_factory is None:
        _default_session_factory = get_session_factory()
    session = _default_session_factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
