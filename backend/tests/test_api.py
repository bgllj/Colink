from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.api.app import create_app
from class_table_backend.persistence.db import get_engine, get_session_factory
from class_table_backend.persistence.tables import Base, MeetingOccurrenceRow

SAMPLE_PATH = Path(__file__).resolve().parents[2] / "excel样例" / "25计科9(1).xls"


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
def client(session_factory: sessionmaker[Session]) -> Iterator[TestClient]:
    app = create_app(session_factory=session_factory)
    with TestClient(app) as test_client:
        yield test_client


def _sample_bytes() -> bytes:
    return SAMPLE_PATH.read_bytes()


def _upload(client: TestClient, data: bytes | None = None, filename: str = "25计科9(1).xls"):
    payload = _sample_bytes() if data is None else data
    return client.post(
        "/imports",
        files={"file": (filename, payload, "application/vnd.ms-excel")},
    )


def test_upload_sample_returns_import_id(client: TestClient) -> None:
    response = _upload(client)

    assert response.status_code == 200
    body = response.json()
    assert body["import_id"]
    assert body["status"] in ("PARSED", "NEEDS_REVIEW")
    assert isinstance(body["issues"], list)
    assert body["duplicate_of"] is None


def test_preview_returns_rows(client: TestClient) -> None:
    import_id = _upload(client).json()["import_id"]

    response = client.get(f"/imports/{import_id}")

    assert response.status_code == 200
    body = response.json()
    assert body["import_id"] == import_id
    assert body["rows"]
    assert body["max_week"] is not None
    first = body["rows"][0]
    assert first["row_id"]
    assert first["course_name"]
    assert first["weekday"] is not None
    assert isinstance(first["week_ranges"], list)
    assert isinstance(first["issues"], list)


def test_list_rows_paginates(client: TestClient) -> None:
    import_id = _upload(client).json()["import_id"]

    response = client.get(f"/imports/{import_id}/rows", params={"limit": 5})

    assert response.status_code == 200
    body = response.json()
    assert body["import_id"] == import_id
    assert body["total"] >= 1
    assert body["limit"] == 5
    assert 1 <= len(body["rows"]) <= 5


def test_confirm_twice_keeps_meeting_count_stable(
    client: TestClient, session_factory: sessionmaker[Session]
) -> None:
    import_id = _upload(client).json()["import_id"]

    first = client.post(f"/imports/{import_id}/confirm")
    assert first.status_code == 200
    body = first.json()
    assert body["import_id"] == import_id
    assert body["status"] == "CONFIRMED"
    assert body["rows_confirmed"] > 0

    with session_factory() as session:
        meetings = session.query(MeetingOccurrenceRow).count()
    assert meetings > 0

    second = client.post(f"/imports/{import_id}/confirm")
    assert second.status_code == 200
    assert second.json()["status"] == "CONFIRMED"

    with session_factory() as session:
        assert session.query(MeetingOccurrenceRow).count() == meetings


def test_unknown_import_returns_404(client: TestClient) -> None:
    assert client.get("/imports/no-such-import").status_code == 404
    assert client.get("/imports/no-such-import/rows").status_code == 404
    assert client.post("/imports/no-such-import/confirm").status_code == 404


def test_confirm_rejects_failed_batch(client: TestClient) -> None:
    response = _upload(client, data=b"not a workbook", filename="notes.txt")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED"
    import_id = body["import_id"]

    confirm = client.post(f"/imports/{import_id}/confirm")
    assert confirm.status_code == 200
    result = confirm.json()
    assert result["status"] == "FAILED"
    codes = {issue["code"] for issue in result["issues"]}
    assert "IMPORT_NOT_CONFIRMABLE" in codes


def test_schedule_readable_after_confirm(client: TestClient) -> None:
    assert client.get("/schedule").json()["courses"] == []

    import_id = _upload(client).json()["import_id"]
    confirmed = client.post(f"/imports/{import_id}/confirm")
    assert confirmed.status_code == 200
    assert confirmed.json()["rows_confirmed"] > 0

    response = client.get("/schedule")
    assert response.status_code == 200
    body = response.json()
    courses = body["courses"]
    assert courses
    meeting = courses[0]["meetings"][0]
    assert meeting["weekday"] in range(1, 8)
    assert isinstance(meeting["weeks"], list)
    assert meeting["weeks"]
    assert body["semester"]["start_date"] is not None
    assert body["semester"]["max_week"] is not None
