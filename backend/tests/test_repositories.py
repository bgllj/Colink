from __future__ import annotations

from datetime import date

import pytest
from sqlalchemy.orm import Session

from class_table_backend.persistence.db import get_engine, get_session_factory
from class_table_backend.persistence.repositories import (
    ClassRepository,
    ImportConfirmError,
    ImportRepository,
)
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


def _batch_kwargs(**overrides: object) -> dict:
    payload: dict = {
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
    payload.update(overrides)
    return payload


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


def test_class_create_get_list_rename_delete(session: Session) -> None:
    classes = ClassRepository(session)
    created = classes.create(name="计科2501", grade="2025", major="计算机")
    session.commit()

    assert classes.get_by_id(created.id) is not None
    assert classes.get_by_name("计科2501") is not None
    assert [c.name for c in classes.list_all()] == ["计科2501"]

    classes.rename(created.id, "计科2502")
    session.commit()
    assert classes.get_by_id(created.id).name == "计科2502"

    classes.delete(created.id)
    session.commit()
    assert classes.get_by_id(created.id) is None


def test_class_delete_cascades_schedule(session: Session) -> None:
    classes = ClassRepository(session)
    imports = ImportRepository(session)
    cls = classes.create(name="计科2501")
    batch = imports.create_batch(**_batch_kwargs(class_id=cls.id))
    imports.replace_rows(batch.id, [_row_payload()])
    session.commit()
    imports.confirm_rows(batch.id, class_id=cls.id)
    session.commit()

    assert session.query(CourseRow).count() == 1
    classes.delete(cls.id)
    session.commit()

    assert session.query(CourseRow).count() == 0
    assert session.query(MeetingOccurrenceRow).count() == 0
    assert session.query(MeetingWeekRow).count() == 0
    assert session.query(Base.metadata.tables["import_batch"]).count() == 0


def test_confirm_rows_writes_course_meetings_and_weeks(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs())
    repo.replace_rows(batch.id, [_row_payload()])
    session.commit()

    confirmed = repo.confirm_rows(batch.id, class_name="计科2501")
    session.commit()

    assert confirmed.status == "CONFIRMED"
    assert confirmed.class_id is not None
    courses = session.query(CourseRow).all()
    assert len(courses) == 1
    assert courses[0].course_code == "CS101"
    assert courses[0].name == "程序设计"
    assert courses[0].class_id == confirmed.class_id

    meetings = session.query(MeetingOccurrenceRow).all()
    assert len(meetings) == 1
    assert meetings[0].weekday == 1
    assert meetings[0].period_start == 1
    assert meetings[0].period_end == 2
    assert meetings[0].room_text == "教A101"
    assert meetings[0].course_id == courses[0].id

    weeks = session.query(MeetingWeekRow).order_by(MeetingWeekRow.week_no).all()
    assert [w.week_no for w in weeks] == [1, 3, 5, 7, 9, 11, 13, 15]


def test_confirm_rows_twice_does_not_duplicate_meetings(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs())
    repo.replace_rows(batch.id, [_row_payload()])
    session.commit()

    repo.confirm_rows(batch.id, class_name="计科2501")
    session.commit()
    repo.confirm_rows(batch.id, class_name="计科2501")
    session.commit()

    assert session.query(CourseRow).count() == 1
    assert session.query(MeetingOccurrenceRow).count() == 1
    assert session.query(MeetingWeekRow).count() == 8


def test_confirm_rows_requires_target_class(session: Session) -> None:
    repo = ImportRepository(session)
    batch = repo.create_batch(**_batch_kwargs(class_name=None))
    repo.replace_rows(batch.id, [_row_payload()])
    session.commit()

    with pytest.raises(ImportConfirmError) as exc:
        repo.confirm_rows(batch.id)
    assert exc.value.code == "CLASS_REQUIRED"


def test_confirm_rows_conflict_on_existing_schedule(session: Session) -> None:
    repo = ImportRepository(session)
    classes = ClassRepository(session)
    cls = classes.create(name="计科2501")
    batch1 = repo.create_batch(**_batch_kwargs(file_hash="a" * 64, class_id=cls.id))
    repo.replace_rows(batch1.id, [_row_payload()])
    session.commit()
    repo.confirm_rows(batch1.id, class_id=cls.id)
    session.commit()

    batch2 = repo.create_batch(**_batch_kwargs(file_hash="b" * 64, class_id=cls.id))
    repo.replace_rows(batch2.id, [_row_payload(coordinate="Z9")])
    session.commit()

    with pytest.raises(ImportConfirmError) as exc:
        repo.confirm_rows(batch2.id, class_id=cls.id)
    assert exc.value.code == "SCHEDULE_EXISTS"

    repo.confirm_rows(batch2.id, class_id=cls.id, replace=True)
    session.commit()
    assert session.query(MeetingOccurrenceRow).count() == 1
    assert session.query(MeetingOccurrenceRow).one().coordinate == "Z9"


def test_same_course_code_in_different_classes(session: Session) -> None:
    repo = ImportRepository(session)
    classes = ClassRepository(session)
    c1 = classes.create(name="计科2501")
    c2 = classes.create(name="计科2502")
    b1 = repo.create_batch(**_batch_kwargs(file_hash="a" * 64, class_id=c1.id))
    b2 = repo.create_batch(**_batch_kwargs(file_hash="b" * 64, class_id=c2.id))
    repo.replace_rows(b1.id, [_row_payload()])
    repo.replace_rows(b2.id, [_row_payload()])
    session.commit()
    repo.confirm_rows(b1.id, class_id=c1.id)
    repo.confirm_rows(b2.id, class_id=c2.id)
    session.commit()

    assert session.query(CourseRow).count() == 2
    codes = {(c.class_id, c.course_code) for c in session.query(CourseRow).all()}
    assert codes == {(c1.id, "CS101"), (c2.id, "CS101")}


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

    repo.confirm_rows(batch.id, class_name="计科2501")
    session.commit()

    meetings = (
        session.query(MeetingOccurrenceRow).order_by(MeetingOccurrenceRow.line_index).all()
    )
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

    repo.confirm_rows(batch.id, class_name="计科2501")
    session.commit()

    assert session.query(MeetingOccurrenceRow).count() == 1
    meeting = session.query(MeetingOccurrenceRow).one()
    assert meeting.coordinate == "C3"


def test_load_schedule_is_class_scoped(session: Session) -> None:
    from class_table_backend.persistence.repositories import ScheduleRepository

    repo = ImportRepository(session)
    classes = ClassRepository(session)
    c1 = classes.create(name="计科2501")
    c2 = classes.create(name="计科2502")
    b1 = repo.create_batch(**_batch_kwargs(file_hash="a" * 64, class_id=c1.id))
    b2 = repo.create_batch(**_batch_kwargs(file_hash="b" * 64, class_id=c2.id))
    repo.replace_rows(b1.id, [_row_payload()])
    repo.replace_rows(
        b2.id,
        [_row_payload(course_code="MA201", course_name="高数", coordinate="D1")],
    )
    session.commit()
    repo.confirm_rows(b1.id, class_id=c1.id)
    repo.confirm_rows(b2.id, class_id=c2.id)
    session.commit()

    schedules = ScheduleRepository(session)
    s1 = schedules.load_schedule(c1.id)
    s2 = schedules.load_schedule(c2.id)
    assert [c["course_code"] for c in s1] == ["CS101"]
    assert [c["course_code"] for c in s2] == ["MA201"]
    assert schedules.load_semester_meta(c1.id)["academic_year"] == "2025-2026"
