from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session, sessionmaker

from class_table_backend.api.app import create_app
from class_table_backend.persistence.db import get_engine, get_session_factory
from class_table_backend.persistence.repositories import ImportRepository
from class_table_backend.persistence.tables import (
    Base,
    CourseRow,
    MeetingOccurrenceRow,
    MeetingWeekRow,
)


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


def _seed_schedule(session: Session) -> None:
    morning = CourseRow(course_code="1001", name="高等数学")
    lab = CourseRow(course_code="1002", name="程序设计实验")
    orphan = CourseRow(course_code="1003", name="无上课安排")
    session.add_all([morning, lab, orphan])
    session.flush()

    first = MeetingOccurrenceRow(
        course_id=morning.id,
        weekday=1,
        period_start=1,
        period_end=2,
        week_text="1-4",
        room_text="主教学楼A101",
        sheet="课表",
        coordinate="B3",
        line_index=0,
    )
    second = MeetingOccurrenceRow(
        course_id=lab.id,
        weekday=3,
        period_start=5,
        period_end=6,
        week_text="2,4",
        room_text="实验楼205",
        sheet="课表",
        coordinate="D7",
        line_index=1,
    )
    session.add_all([first, second])
    session.flush()

    for week_no in (1, 2, 3, 4):
        session.add(MeetingWeekRow(meeting_id=first.id, week_no=week_no))
    for week_no in (2, 4):
        session.add(MeetingWeekRow(meeting_id=second.id, week_no=week_no))
    session.commit()


def test_schedule_is_empty_before_any_confirmed_data(client: TestClient) -> None:
    response = client.get("/schedule")

    assert response.status_code == 200
    body = response.json()
    assert body["courses"] == []
    assert body["semester"] == {
        "start_date": None,
        "max_week": None,
        "academic_year": None,
        "semester_name": None,
    }


def test_schedule_returns_courses_with_meetings_and_expanded_weeks(
    client: TestClient, session_factory: sessionmaker[Session]
) -> None:
    with session_factory() as session:
        _seed_schedule(session)

    response = client.get("/schedule")

    assert response.status_code == 200
    body = response.json()
    assert [course["course_code"] for course in body["courses"]] == ["1001", "1002"]

    first = body["courses"][0]
    assert first["course_id"]
    assert first["name"] == "高等数学"
    assert len(first["meetings"]) == 1
    meeting = first["meetings"][0]
    assert meeting["weekday"] == 1
    assert meeting["period_start"] == 1
    assert meeting["period_end"] == 2
    assert meeting["week_text"] == "1-4"
    assert meeting["weeks"] == [1, 2, 3, 4]
    assert meeting["room_text"] == "主教学楼A101"
    assert meeting["sheet"] == "课表"
    assert meeting["coordinate"] == "B3"
    assert meeting["line_index"] == 0


def test_schedule_omits_courses_without_meetings(
    client: TestClient, session_factory: sessionmaker[Session]
) -> None:
    with session_factory() as session:
        _seed_schedule(session)

    codes = [course["course_code"] for course in client.get("/schedule").json()["courses"]]

    assert "1003" not in codes


def test_schedule_week_filter_keeps_only_matching_meetings(
    client: TestClient, session_factory: sessionmaker[Session]
) -> None:
    with session_factory() as session:
        _seed_schedule(session)

    body = client.get("/schedule", params={"week": 3}).json()

    codes = [course["course_code"] for course in body["courses"]]
    assert codes == ["1001"]
    assert body["courses"][0]["meetings"][0]["weeks"] == [1, 2, 3, 4]


def test_schedule_week_filter_with_no_match_returns_empty(
    client: TestClient, session_factory: sessionmaker[Session]
) -> None:
    with session_factory() as session:
        _seed_schedule(session)

    response = client.get("/schedule", params={"week": 9})

    assert response.status_code == 200
    assert response.json()["courses"] == []


def test_schedule_rejects_non_positive_week(client: TestClient) -> None:
    response = client.get("/schedule", params={"week": 0})

    assert response.status_code == 422


def test_schedule_includes_semester_meta_from_confirmed_batch(
    client: TestClient, session_factory: sessionmaker[Session]
) -> None:
    from datetime import date

    with session_factory() as session:
        batch = ImportRepository(session).create_batch(
            file_hash="a" * 64,
            original_filename="sample.xls",
            status="CONFIRMED",
            academic_year="2026-2027",
            semester_name="第一学期",
            start_date=date(2026, 8, 31),
            max_week=17,
        )
        session.commit()
        assert batch.id
        _seed_schedule(session)

    body = client.get("/schedule").json()
    assert body["semester"] == {
        "start_date": "2026-08-31",
        "max_week": 17,
        "academic_year": "2026-2027",
        "semester_name": "第一学期",
    }
