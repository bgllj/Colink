from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.persistence.tables import MeetingOccurrenceRow

SAMPLE_PATH = Path(__file__).resolve().parents[2] / "samples" / "excel" / "25计科9(1).xls"

requires_sample = pytest.mark.skipif(
    not SAMPLE_PATH.is_file(),
    reason="本地样例课表未提供（samples/excel 不入库）",
)


def _sample_bytes() -> bytes:
    return SAMPLE_PATH.read_bytes()


def _upload(
    client: TestClient,
    auth_headers: dict[str, str],
    data: bytes | None = None,
    filename: str = "25计科9(1).xls",
):
    payload = _sample_bytes() if data is None else data
    return client.post(
        "/imports",
        files={"file": (filename, payload, "application/vnd.ms-excel")},
        headers=auth_headers,
    )


@requires_sample
def test_upload_sample_returns_import_id(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = _upload(client, auth_headers)

    assert response.status_code == 200
    body = response.json()
    assert body["import_id"]
    assert body["status"] in ("PARSED", "NEEDS_REVIEW")
    assert isinstance(body["issues"], list)
    assert body["duplicate_of"] is None


@requires_sample
def test_preview_returns_rows(client: TestClient, auth_headers: dict[str, str]) -> None:
    import_id = _upload(client, auth_headers).json()["import_id"]

    response = client.get(f"/imports/{import_id}", headers=auth_headers)

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


@requires_sample
def test_list_rows_paginates(client: TestClient, auth_headers: dict[str, str]) -> None:
    import_id = _upload(client, auth_headers).json()["import_id"]

    response = client.get(
        f"/imports/{import_id}/rows", params={"limit": 5}, headers=auth_headers
    )

    assert response.status_code == 200
    body = response.json()
    assert body["import_id"] == import_id
    assert body["total"] >= 1
    assert body["limit"] == 5
    assert 1 <= len(body["rows"]) <= 5


@requires_sample
def test_confirm_twice_keeps_meeting_count_stable(
    client: TestClient,
    auth_headers: dict[str, str],
    session_factory: sessionmaker[Session],
) -> None:
    import_id = _upload(client, auth_headers).json()["import_id"]

    first = client.post(f"/imports/{import_id}/confirm", headers=auth_headers)
    assert first.status_code == 200
    body = first.json()
    assert body["import_id"] == import_id
    assert body["status"] == "CONFIRMED"
    assert body["rows_confirmed"] > 0

    with session_factory() as session:
        meetings = session.query(MeetingOccurrenceRow).count()
    assert meetings > 0

    second = client.post(f"/imports/{import_id}/confirm", headers=auth_headers)
    assert second.status_code == 200
    assert second.json()["status"] == "CONFIRMED"

    with session_factory() as session:
        assert session.query(MeetingOccurrenceRow).count() == meetings


def test_unknown_import_returns_404(client: TestClient, auth_headers: dict[str, str]) -> None:
    assert client.get("/imports/no-such-import", headers=auth_headers).status_code == 404
    assert client.get("/imports/no-such-import/rows", headers=auth_headers).status_code == 404
    assert client.post("/imports/no-such-import/confirm", headers=auth_headers).status_code == 404


def test_confirm_rejects_failed_batch(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = _upload(client, auth_headers, data=b"not a workbook", filename="notes.txt")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED"
    import_id = body["import_id"]

    confirm = client.post(f"/imports/{import_id}/confirm", headers=auth_headers)
    assert confirm.status_code == 200
    result = confirm.json()
    assert result["status"] == "FAILED"
    codes = {issue["code"] for issue in result["issues"]}
    assert "IMPORT_NOT_CONFIRMABLE" in codes


@requires_sample
def test_schedule_readable_after_confirm(client: TestClient, auth_headers: dict[str, str]) -> None:
    assert client.get("/schedule").json()["courses"] == []

    import_id = _upload(client, auth_headers).json()["import_id"]
    confirmed = client.post(f"/imports/{import_id}/confirm", headers=auth_headers)
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
