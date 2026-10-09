from __future__ import annotations

from datetime import date

import pytest
from sqlalchemy.orm import Session

from class_table_backend.persistence.db import get_engine, get_session_factory
from class_table_backend.persistence.repositories import ImportRepository
from class_table_backend.persistence.tables import (
    Base,
    CourseRow,
    MeetingOccurrenceRow,
    MeetingWeekRow,
)


@pytest.fixture
def session() -> Session:
    engine = get_engine("sqlite://")
    Base.metadata.create_all(engine)
    factory = get_session_factory(engine)
    session = factory()
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


def _batch_kwargs() -> dict:
    return {
        "file_hash": "a" * 64,
        "original_filename": "timetable.xlsx",
        "status": "PARSED",
        "profile": "chengdu_wenli_v1",
        "academic_year": "2025-2026",
        "semester_name": "秋季",
        "start_date": date(2025, 9, 1),
        "class_name": "计科2501",
        "max_week": 16,
        "meta_json": {"source": "test"},
    }


def _row_payload(**overrides: object) -> dict:
    payload: dict = {
        "row_status": "PARSED",
        "selected": 1,
        "course_code": "CS101",
        "course_name": "程序设计",
        "weekday": 1,
        "period_start": 1,
        "period_end": 2,
        "week_text": "1-16周单周",
        "week_ranges_json": [{"start": 1, "end": 16, "parity": "ODD"}],
        "room_text": "教A101",
        "sheet": "Sheet1",
        "coordinate": "B3",
        "line_index": 0,
        "raw_line": "程序设计 1-16周单周 教A101",
        "issues_json": [],
    }
    payload.update(overrides)
    return payload


def test_confirm_rows_writes_course_meetings_and_weeks(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs())
    repo.replace_rows(batch.id, [_row_payload()])
    session.commit()

    confirmed = repo.confirm_rows(batch.id)
    session.commit()

    assert confirmed.status == "CONFIRMED"
    courses = session.query(CourseRow).all()
    assert len(courses) == 1
    assert courses[0].course_code == "CS101"
    assert courses[0].name == "程序设计"

    meetings = session.query(MeetingOccurrenceRow).all()
    assert len(meetings) == 1
    assert meetings[0].weekday == 1
    assert meetings[0].period_start == 1
    assert meetings[0].period_end == 2
    assert meetings[0].room_text == "教A101"
    assert meetings[0].course_id == courses[0].id

    weeks = (
        session.query(MeetingWeekRow)
        .order_by(MeetingWeekRow.week_no)
        .all()
    )
    assert [w.week_no for w in weeks] == [1, 3, 5, 7, 9, 11, 13, 15]


def test_confirm_rows_twice_does_not_duplicate_meetings(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs())
    repo.replace_rows(batch.id, [_row_payload()])
    session.commit()

    repo.confirm_rows(batch.id)
    session.commit()
    repo.confirm_rows(batch.id)
    session.commit()

    assert session.query(CourseRow).count() == 1
    assert session.query(MeetingOccurrenceRow).count() == 1
    assert session.query(MeetingWeekRow).count() == 8


def test_confirm_rows_keeps_multi_room_lines(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs())
    repo.replace_rows(
        batch.id,
        [
            _row_payload(),
            _row_payload(
                room_text="教B202",
                coordinate="B4",
                line_index=1,
                raw_line="程序设计 1-16周单周 教B202",
                week_ranges_json=[{"start": 1, "end": 16, "parity": "ALL"}],
            ),
        ],
    )
    session.commit()

    repo.confirm_rows(batch.id)
    session.commit()

    meetings = session.query(MeetingOccurrenceRow).order_by(
        MeetingOccurrenceRow.line_index
    ).all()
    assert [m.room_text for m in meetings] == ["教A101", "教B202"]
    assert session.query(CourseRow).count() == 1

    all_weeks = (
        session.query(MeetingWeekRow)
        .filter(MeetingWeekRow.meeting_id == meetings[1].id)
        .order_by(MeetingWeekRow.week_no)
        .all()
    )
    assert [w.week_no for w in all_weeks] == list(range(1, 17))


def test_confirm_rows_skips_unselected_and_non_parsed_rows(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs())
    repo.replace_rows(
        batch.id,
        [
            _row_payload(selected=0, coordinate="C1"),
            _row_payload(row_status="NEEDS_REVIEW", coordinate="C2"),
            _row_payload(coordinate="C3"),
        ],
    )
    session.commit()

    repo.confirm_rows(batch.id)
    session.commit()

    assert session.query(MeetingOccurrenceRow).count() == 1
    meeting = session.query(MeetingOccurrenceRow).one()
    assert meeting.coordinate == "C3"
