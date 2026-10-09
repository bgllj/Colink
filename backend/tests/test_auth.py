from __future__ import annotations

import time

import jwt
from conftest import TEST_ADMIN_USERNAME, TEST_JWT_SECRET
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.auth.bootstrap import bootstrap_admin
from class_table_backend.auth.passwords import hash_password, verify_password
from class_table_backend.auth.tokens import TokenError, decode_token, issue_token
from class_table_backend.persistence.repositories import AdminUserRepository
from class_table_backend.persistence.tables import AdminUserRow

# ---------------------------------------------------------------------------
# password hashing
# ---------------------------------------------------------------------------


def test_hash_password_roundtrip_and_wrong_password() -> None:
    stored = hash_password("correct-horse", iterations=1000)
    assert stored.startswith("pbkdf2_sha256$1000$")
    assert verify_password("correct-horse", stored) is True
    assert verify_password("wrong-password", stored) is False


def test_verify_password_rejects_malformed_stored() -> None:
    assert verify_password("x", "not-a-hash") is False
    assert verify_password("x", "bcrypt$1$aa$bb") is False


# ---------------------------------------------------------------------------
# login
# ---------------------------------------------------------------------------


def test_login_success_returns_token(
    client: TestClient,
    admin_user: str,
    admin_credentials: dict[str, str],
) -> None:
    response = client.post("/auth/login", json=admin_credentials)

    assert response.status_code == 200
    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["access_token"]

    claims = decode_token(body["access_token"], secret=TEST_JWT_SECRET)
    assert claims.subject == admin_user
    assert claims.username == TEST_ADMIN_USERNAME


def test_login_wrong_password_returns_401(
    client: TestClient,
    admin_user: str,
) -> None:
    response = client.post(
        "/auth/login",
        json={"username": TEST_ADMIN_USERNAME, "password": "not-the-password"},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "用户名或密码错误"


def test_login_unknown_user_returns_401(client: TestClient) -> None:
    response = client.post(
        "/auth/login",
        json={"username": "ghost", "password": "whatever"},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "用户名或密码错误"


def test_auth_me_requires_bearer(
    client: TestClient,
    auth_headers: dict[str, str],
    admin_user: str,
) -> None:
    assert client.get("/auth/me").status_code == 401

    response = client.get("/auth/me", headers=auth_headers)
    assert response.status_code == 200
    assert response.json() == {"id": admin_user, "username": TEST_ADMIN_USERNAME}


# ---------------------------------------------------------------------------
# imports protection
# ---------------------------------------------------------------------------


def test_imports_without_token_returns_401(client: TestClient) -> None:
    upload = client.post(
        "/imports", files={"file": ("a.xls", b"x", "application/octet-stream")}
    )
    assert upload.status_code == 401
    assert client.get("/imports/some-id").status_code == 401
    assert client.get("/imports/some-id/rows").status_code == 401
    assert client.post("/imports/some-id/confirm").status_code == 401


def test_imports_reject_invalid_token(client: TestClient) -> None:
    response = client.get("/imports/x", headers={"Authorization": "Bearer not-a-token"})
    assert response.status_code == 401


def test_upload_with_valid_token_processes_file(
    client: TestClient,
    auth_headers: dict[str, str],
) -> None:
    response = client.post(
        "/imports",
        files={"file": ("notes.txt", b"not a workbook", "application/octet-stream")},
        headers=auth_headers,
    )
    assert response.status_code == 200
    body = response.json()
    assert body["import_id"]
    assert body["status"] == "FAILED"

    preview = client.get(f"/imports/{body['import_id']}", headers=auth_headers)
    assert preview.status_code == 200
    assert preview.json()["import_id"] == body["import_id"]


def test_auth_failure_happens_before_file_processing(client: TestClient) -> None:
    # No token: must 401 even though the payload is a valid-looking upload.
    response = client.post(
        "/imports",
        files={"file": ("whatever.xls", b"\x00" * 32, "application/vnd.ms-excel")},
    )
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# public routes
# ---------------------------------------------------------------------------


def test_health_and_schedule_stay_public(client: TestClient) -> None:
    assert client.get("/health").status_code == 200
    assert client.get("/schedule").status_code == 200


# ---------------------------------------------------------------------------
# expired token
# ---------------------------------------------------------------------------


def test_expired_token_returns_401(client: TestClient, admin_user: str) -> None:
    expired = issue_token(
        user_id=admin_user,
        username=TEST_ADMIN_USERNAME,
        secret=TEST_JWT_SECRET,
        ttl_seconds=-10,
    )
    response = client.get("/auth/me", headers={"Authorization": f"Bearer {expired}"})
    assert response.status_code == 401

    imports_response = client.get("/imports/x", headers={"Authorization": f"Bearer {expired}"})
    assert imports_response.status_code == 401


def test_decode_token_rejects_expired() -> None:
    past = int(time.time()) - 100
    token = jwt.encode(
        {
            "sub": "u1",
            "username": "admin",
            "iat": past - 10,
            "exp": past,
        },
        TEST_JWT_SECRET,
        algorithm="HS256",
    )
    try:
        decode_token(token, secret=TEST_JWT_SECRET)
    except TokenError as exc:
        assert "过期" in str(exc)
    else:  # pragma: no cover
        raise AssertionError("expired token should raise TokenError")


# ---------------------------------------------------------------------------
# bootstrap
# ---------------------------------------------------------------------------


def test_bootstrap_creates_user_first_time_then_keeps_password(
    session_factory: sessionmaker[Session],
) -> None:
    with session_factory() as session:
        user, created = bootstrap_admin(
            session, username="boot-admin", password="first-password"
        )
        session.commit()
        assert created is True
        user_id = user.id
        original_hash = user.password_hash

    assert verify_password("first-password", original_hash)

    with session_factory() as session:
        user2, created2 = bootstrap_admin(
            session, username="boot-admin", password="second-password"
        )
        session.commit()
        assert created2 is False
        assert user2.id == user_id
        assert user2.password_hash == original_hash

    assert verify_password("first-password", original_hash)
    assert verify_password("second-password", original_hash) is False

    with session_factory() as session:
        assert session.query(AdminUserRow).count() == 1


def test_bootstrap_from_env_via_create_app(
    session_factory: sessionmaker[Session],
    monkeypatch,
) -> None:
    monkeypatch.setenv("ADMIN_BOOTSTRAP_USERNAME", "env-admin")
    monkeypatch.setenv("ADMIN_BOOTSTRAP_PASSWORD", "env-secret")
    monkeypatch.setenv("ADMIN_JWT_SECRET", TEST_JWT_SECRET)

    from class_table_backend.api.app import create_app

    create_app(session_factory=session_factory)

    with session_factory() as session:
        repo = AdminUserRepository(session)
        user = repo.get_by_username("env-admin")
        assert user is not None
        assert verify_password("env-secret", user.password_hash)

    # Second create_app must not overwrite the password.
    monkeypatch.setenv("ADMIN_BOOTSTRAP_PASSWORD", "changed-secret")
    create_app(session_factory=session_factory)

    with session_factory() as session:
        repo = AdminUserRepository(session)
        user = repo.get_by_username("env-admin")
        assert user is not None
        assert verify_password("env-secret", user.password_hash)
        assert verify_password("changed-secret", user.password_hash) is False
