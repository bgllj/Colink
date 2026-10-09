from __future__ import annotations

from collections.abc import Callable, Iterator

from fastapi import FastAPI
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.api.routes_imports import get_session
from class_table_backend.api.routes_imports import router as imports_router
from class_table_backend.api.routes_schedule import router as schedule_router


def _session_dependency(factory: sessionmaker[Session]) -> Callable[[], Iterator[Session]]:
    def dependency() -> Iterator[Session]:
        session = factory()
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    return dependency


def create_app(session_factory: sessionmaker[Session] | None = None) -> FastAPI:
    app = FastAPI(title="class-table-backend")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(imports_router)
    app.include_router(schedule_router)

    if session_factory is not None:
        app.dependency_overrides[get_session] = _session_dependency(session_factory)

    return app
