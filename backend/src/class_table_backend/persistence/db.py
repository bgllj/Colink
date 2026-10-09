from __future__ import annotations

import os
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

DEFAULT_DATABASE_URL = "sqlite:///./class_table.db"


def _resolve_url(url: str | None) -> str:
    return url or os.environ.get("DATABASE_URL") or DEFAULT_DATABASE_URL


def get_engine(url: str | None = None) -> Engine:
    db_url = _resolve_url(url)
    kwargs: dict[str, Any] = {}
    if db_url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
        if db_url == "sqlite://" or ":memory:" in db_url:
            kwargs["poolclass"] = StaticPool
    return create_engine(db_url, **kwargs)


def get_session_factory(engine: Engine | None = None) -> sessionmaker[Session]:
    resolved = engine if engine is not None else get_engine()
    return sessionmaker(bind=resolved, expire_on_commit=False, class_=Session)


@contextmanager
def session_scope(
    engine: Engine | None = None,
    session_factory: sessionmaker[Session] | None = None,
) -> Iterator[Session]:
    factory = session_factory if session_factory is not None else get_session_factory(engine)
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
