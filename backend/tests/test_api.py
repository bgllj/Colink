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

    first = client.post(
        f"/imports/{import_id}/confirm",
        json={"class_name": "计科2501"},
        headers=auth_headers,
    )
    assert first.status_code == 200
    body = first.json()
    assert body["import_id"] == import_id
    assert body["status"] == "CONFIRMED"
    assert body["rows_confirmed"] > 0
    assert body["class_id"]

    with session_factory() as session:
        meetings = session.query(MeetingOccurrenceRow).count()
    assert meetings > 0

    second = client.post(
        f"/imports/{import_id}/confirm",
        json={"class_name": "计科2501"},
        headers=auth_headers,
    )
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
    import_id = _upload(client, auth_headers).json()["import_id"]
    confirmed = client.post(
        f"/imports/{import_id}/confirm",
        json={"class_name": "计科2501"},
        headers=auth_headers,
    )
    assert confirmed.status_code == 200
    assert confirmed.json()["rows_confirmed"] > 0
    class_id = confirmed.json()["class_id"]

    response = client.get(f"/classes/{class_id}/schedule")
    assert response.status_code == 200
    body = response.json()
    assert body["class_id"] == class_id
    assert body["class_name"] == "计科2501"
    courses = body["courses"]
    assert courses
    meeting = courses[0]["meetings"][0]
    assert meeting["weekday"] in range(1, 8)
    assert isinstance(meeting["weeks"], list)
    assert meeting["weeks"]
    assert body["semester"]["start_date"] is not None
    assert body["semester"]["max_week"] is not None


def test_classes_crud_and_auth(client: TestClient, auth_headers: dict[str, str]) -> None:
    # public list
    assert client.get("/classes").json() == []

    # admin create
    created = client.post(
        "/classes",
        json={"name": "计科2501", "grade": "2025"},
        headers=auth_headers,
    )
    assert created.status_code == 201
    class_id = created.json()["id"]

    # duplicate name
    dup = client.post("/classes", json={"name": "计科2501"}, headers=auth_headers)
    assert dup.status_code == 409

    # public list shows class
    listing = client.get("/classes").json()
    assert [c["name"] for c in listing] == ["计科2501"]

    # rename
    patched = client.patch(
        f"/classes/{class_id}",
        json={"name": "计科2502"},
        headers=auth_headers,
    )
    assert patched.status_code == 200
    assert patched.json()["name"] == "计科2502"

    # delete requires auth
    assert client.delete(f"/classes/{class_id}").status_code == 401
    deleted = client.delete(f"/classes/{class_id}", headers=auth_headers)
    assert deleted.status_code == 204
    assert client.get("/classes").json() == []


def test_admin_class_mutations_require_auth(client: TestClient) -> None:
    assert client.post("/classes", json={"name": "x"}).status_code == 401
    assert client.patch("/classes/x", json={"name": "y"}).status_code == 401
    assert client.delete("/classes/x").status_code == 401


def test_confirm_conflict_returns_409_then_replace(
    client: TestClient,
    auth_headers: dict[str, str],
    session_factory: sessionmaker[Session],
) -> None:
    from class_table_backend.persistence.repositories import ImportRepository

    def _seed_batch(file_hash: str) -> str:
        with session_factory() as session:
            batch = ImportRepository(session).create_batch(
                file_hash=file_hash,
                original_filename="t.xls",
                status="PARSED",
                class_name="计科2501",
                academic_year="2025-2026",
                semester_name="秋季",
                max_week=16,
            )
            ImportRepository(session).replace_rows(
                batch.id,
                [
                    {
                        "row_status": "PARSED",
                        "selected": 1,
                        "course_code": "CS101",
                        "course_name": "程序设计",
                        "weekday": 1,
                        "period_start": 1,
                        "period_end": 2,
                        "week_text": "1-16周",
                        "week_ranges_json": [{"start": 1, "end": 16, "parity": "ALL"}],
                        "room_text": "教A101",
                        "sheet": "S",
                        "coordinate": "B3",
                        "line_index": 0,
                        "raw_line": "x",
                        "issues_json": [],
                    }
                ],
            )
            session.commit()
            return batch.id

    id1 = _seed_batch("a" * 64)
    id2 = _seed_batch("b" * 64)

    first = client.post(
        f"/imports/{id1}/confirm",
        json={"class_name": "计科2501"},
        headers=auth_headers,
    )
    assert first.status_code == 200

    conflict = client.post(
        f"/imports/{id2}/confirm",
        json={"class_name": "计科2501"},
        headers=auth_headers,
    )
    assert conflict.status_code == 409
    assert conflict.json()["detail"]["code"] == "SCHEDULE_EXISTS"

    replaced = client.post(
        f"/imports/{id2}/confirm",
        json={"class_name": "计科2501", "replace": True},
        headers=auth_headers,
    )
    assert replaced.status_code == 200
    assert replaced.json()["status"] == "CONFIRMED"
