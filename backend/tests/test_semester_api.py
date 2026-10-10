from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.persistence.repositories import ImportRepository
from class_table_backend.persistence.tables import (
    ClassRow,
    CourseRow,
    ImportBatchRow,
    MeetingOccurrenceRow,
)


def _seed_class_with_schedule(
    session: Session,
    *,
    class_id: str = "cls-sem-1",
    class_name: str = "计科2501",
    confirmed: bool = True,
    start_date: date | None = date(2026, 8, 31),
) -> str:
    session.add(ClassRow(id=class_id, name=class_name))
    session.flush()
    course = CourseRow(class_id=class_id, course_code="1001", name="高等数学")
    session.add(course)
    session.flush()
    session.add(
        MeetingOccurrenceRow(
            course_id=course.id,
            weekday=1,
            period_start=1,
            period_end=2,
            week_text="1-4",
        )
    )
    session.flush()
    if confirmed:
        ImportRepository(session).create_batch(
            file_hash="b" * 64,
            original_filename="sample.xls",
            status="CONFIRMED",
            class_id=class_id,
            academic_year="2026-2027",
            semester_name="第一学期",
            start_date=start_date,
            max_week=17,
        )
    session.commit()
    return class_id


def test_update_semester_start_date_requires_admin(client: TestClient) -> None:
    response = client.patch(
        "/classes/any/semester",
        json={"start_date": "2026-09-01"},
    )
    assert response.status_code == 401


def test_update_semester_start_date(
    client: TestClient,
    session_factory: sessionmaker[Session],
    auth_headers: dict[str, str],
) -> None:
    with session_factory() as session:
        class_id = _seed_class_with_schedule(session)

    response = client.patch(
        f"/classes/{class_id}/semester",
        json={"start_date": "2026-09-07"},
        headers=auth_headers,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["start_date"] == "2026-09-07"
    assert body["max_week"] == 17
    assert body["academic_year"] == "2026-2027"

    schedule = client.get(f"/classes/{class_id}/schedule").json()
    assert schedule["semester"]["start_date"] == "2026-09-07"


def test_update_semester_start_date_can_clear(
    client: TestClient,
    session_factory: sessionmaker[Session],
    auth_headers: dict[str, str],
) -> None:
    with session_factory() as session:
        class_id = _seed_class_with_schedule(session)

    response = client.patch(
        f"/classes/{class_id}/semester",
        json={"start_date": None},
        headers=auth_headers,
    )

    assert response.status_code == 200
    assert response.json()["start_date"] is None


def test_update_semester_start_date_unknown_class(
    client: TestClient,
    auth_headers: dict[str, str],
) -> None:
    response = client.patch(
        "/classes/missing/semester",
        json={"start_date": "2026-09-01"},
        headers=auth_headers,
    )
    assert response.status_code == 404
    assert "班级不存在" in response.json()["detail"]


def test_update_semester_start_date_without_confirmed_schedule(
    client: TestClient,
    session_factory: sessionmaker[Session],
    auth_headers: dict[str, str],
) -> None:
    with session_factory() as session:
        class_id = _seed_class_with_schedule(session, confirmed=False)

    response = client.patch(
        f"/classes/{class_id}/semester",
        json={"start_date": "2026-09-01"},
        headers=auth_headers,
    )
    assert response.status_code == 404
    assert "尚无已确认课表" in response.json()["detail"]


def test_update_semester_start_date_rejects_invalid_date(
    client: TestClient,
    session_factory: sessionmaker[Session],
    auth_headers: dict[str, str],
) -> None:
    with session_factory() as session:
        class_id = _seed_class_with_schedule(session)

    response = client.patch(
        f"/classes/{class_id}/semester",
        json={"start_date": "not-a-date"},
        headers=auth_headers,
    )
    assert response.status_code == 422


def test_update_semester_start_date_requires_field(
    client: TestClient,
    session_factory: sessionmaker[Session],
    auth_headers: dict[str, str],
) -> None:
    with session_factory() as session:
        class_id = _seed_class_with_schedule(session)

    response = client.patch(
        f"/classes/{class_id}/semester",
        json={},
        headers=auth_headers,
    )
    assert response.status_code == 422


def test_update_semester_start_date_touches_latest_confirmed_batch(
    client: TestClient,
    session_factory: sessionmaker[Session],
    auth_headers: dict[str, str],
) -> None:
    with session_factory() as session:
        class_id = _seed_class_with_schedule(session, confirmed=False)
        older = ImportRepository(session).create_batch(
            file_hash="b" * 64,
            original_filename="sample.xls",
            status="CONFIRMED",
            class_id=class_id,
            start_date=date(2026, 8, 24),
            max_week=17,
        )
        older.created_at = datetime.now(UTC) - timedelta(days=1)
        newer = ImportRepository(session).create_batch(
            file_hash="c" * 64,
            original_filename="sample2.xls",
            status="CONFIRMED",
            class_id=class_id,
            start_date=date(2026, 8, 31),
            max_week=18,
        )
        newer.created_at = datetime.now(UTC)
        session.commit()

    response = client.patch(
        f"/classes/{class_id}/semester",
        json={"start_date": "2026-09-14"},
        headers=auth_headers,
    )
    assert response.status_code == 200
    assert response.json()["start_date"] == "2026-09-14"
    assert response.json()["max_week"] == 18

    with session_factory() as session:
        batches = list(
            session.scalars(
                select(ImportBatchRow)
                .where(ImportBatchRow.class_id == class_id)
                .order_by(ImportBatchRow.created_at.desc())
            )
        )
        assert batches[0].start_date == date(2026, 9, 14)
        assert batches[1].start_date == date(2026, 8, 24)
