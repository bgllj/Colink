from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.api.app import create_app
from class_table_backend.auth.service import ensure_admin_user
from class_table_backend.persistence.db import get_engine, get_session_factory
from class_table_backend.persistence.tables import Base

TEST_JWT_SECRET = "test-admin-jwt-secret-0123456789abcdef"
TEST_ADMIN_USERNAME = "admin"
TEST_ADMIN_PASSWORD = "s3cret-password"


@pytest.fixture
def session_factory() -> Iterator[sessionmaker[Session]]:
    engine = get_engine("sqlite://")
    Base.metadata.create_all(engine)
    factory = get_session_factory(engine)
    try:
        yield factory
    finally:
        engine.dispose()


@pytest.fixture
def jwt_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ADMIN_JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("ADMIN_JWT_TTL_SECONDS", "3600")


@pytest.fixture
def client(
    session_factory: sessionmaker[Session],
    jwt_env: None,
) -> Iterator[TestClient]:
    app = create_app(session_factory=session_factory)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def admin_credentials() -> dict[str, str]:
    return {"username": TEST_ADMIN_USERNAME, "password": TEST_ADMIN_PASSWORD}


@pytest.fixture
def admin_user(
    session_factory: sessionmaker[Session],
    admin_credentials: dict[str, str],
) -> str:
    with session_factory() as session:
        user, _created = ensure_admin_user(
            session,
            username=admin_credentials["username"],
            password=admin_credentials["password"],
        )
        session.commit()
        return user.id


@pytest.fixture
def auth_headers(
    client: TestClient,
    admin_user: str,
    admin_credentials: dict[str, str],
) -> dict[str, str]:
    response = client.post("/auth/login", json=admin_credentials)
    assert response.status_code == 200, response.text
    token = response.json()["access_token"]
    return {"Authorization": f"Bearer {token}"}
