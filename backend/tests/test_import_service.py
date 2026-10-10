from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy.orm import Session

from class_table_backend.import_flow.service import ImportService
from class_table_backend.import_flow.status import ImportStatus
from class_table_backend.persistence.db import get_engine, get_session_factory
from class_table_backend.persistence.tables import (
    Base,
    CourseRow,
    MeetingOccurrenceRow,
    MeetingWeekRow,
)

SAMPLE_PATH = Path(__file__).resolve().parents[2] / "samples" / "excel" / "25计科9(1).xls"

requires_sample = pytest.mark.skipif(
    not SAMPLE_PATH.is_file(),
    reason="本地样例课表未提供（samples/excel 不入库）",
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


def _sample_bytes() -> bytes:
    return SAMPLE_PATH.read_bytes()


def _counts(session: Session) -> tuple[int, int]:
    return session.query(CourseRow).count(), session.query(MeetingOccurrenceRow).count()


@requires_sample
def test_ingest_sample_and_preview_does_not_write_schedule(session: Session) -> None:
    service = ImportService(session)
    result = service.ingest("25计科9(1).xls", _sample_bytes())

    assert result["import_id"]
    assert result["status"] in (ImportStatus.PARSED, ImportStatus.NEEDS_REVIEW)
    assert result["max_week"] is not None and result["max_week"] > 0
    assert isinstance(result["issues"], list)

    preview = service.preview(result["import_id"])
    assert preview["import_id"] == result["import_id"]
    assert preview["rows"]
    assert preview["max_week"] == result["max_week"]
    first = preview["rows"][0]
    assert first["row_id"]
    assert first["status"] in ("PARSED", "NEEDS_REVIEW", "INVALID")
    assert first["course_name"]
    assert first["weekday"] is not None
    assert isinstance(first["week_ranges"], list)
    assert isinstance(first["issues"], list)

    courses, meetings = _counts(session)
    assert courses == 0
    assert meetings == 0


@requires_sample
def test_confirm_writes_rows_once(session: Session) -> None:
    service = ImportService(session)
    result = service.ingest("25计科9(1).xls", _sample_bytes())
    import_id = result["import_id"]

    confirmed = service.confirm(import_id, class_name="计科2501")
    assert confirmed["import_id"] == import_id
    assert confirmed["status"] == ImportStatus.CONFIRMED
    assert confirmed["rows_confirmed"] > 0
    assert confirmed["class_id"] is not None

    courses, meetings = _counts(session)
    assert courses > 0
    assert meetings > 0
    assert session.query(MeetingWeekRow).count() > 0

    again = service.confirm(import_id, class_name="计科2501")
    assert again["status"] == ImportStatus.CONFIRMED
    courses_after, meetings_after = _counts(session)
    assert courses_after == courses
    assert meetings_after == meetings


@requires_sample
def test_duplicate_ingest_returns_existing_import(session: Session) -> None:
    service = ImportService(session)
    data = _sample_bytes()
    first = service.ingest("25计科9(1).xls", data)
    second = service.ingest("25计科9(1).xls", data)

    assert second["duplicate_of"] == first["import_id"]
    assert second["import_id"] == first["import_id"]
    assert second["issues"] == []
    assert _counts(session) == (0, 0)


def test_ingest_rejects_oversized_upload(session: Session) -> None:
    service = ImportService(session)
    payload = b"x" * (5 * 1024 * 1024 + 1)
    result = service.ingest("huge.xls", payload)

    assert result["status"] == ImportStatus.FAILED
    codes = {issue["code"] for issue in result["issues"]}
    assert "FILE_TOO_LARGE" in codes


def test_ingest_rejects_unsupported_file_type(session: Session) -> None:
    service = ImportService(session)
    result = service.ingest("notes.txt", b"not a workbook")

    assert result["status"] == ImportStatus.FAILED
    codes = {issue["code"] for issue in result["issues"]}
    assert "UNSUPPORTED_FILE_TYPE" in codes


@requires_sample
def test_max_week_override(session: Session) -> None:
    service = ImportService(session)
    result = service.ingest("25计科9(1).xls", _sample_bytes(), max_week=5)
    assert result["max_week"] == 5
    assert result["status"] == ImportStatus.NEEDS_REVIEW


@requires_sample
def test_list_rows_paginates(session: Session) -> None:
    service = ImportService(session)
    result = service.ingest("25计科9(1).xls", _sample_bytes())
    import_id = result["import_id"]

    page = service.list_rows(import_id, limit=5, offset=0)
    assert page["import_id"] == import_id
    assert page["limit"] == 5
    assert page["offset"] == 0
    assert len(page["rows"]) == 5
    assert page["total"] >= 5

    next_page = service.list_rows(import_id, limit=5, offset=5)
    assert len(next_page["rows"]) == 5
    assert {row["row_id"] for row in page["rows"]}.isdisjoint(
        {row["row_id"] for row in next_page["rows"]}
    )


def _make_batch_with_row(session: Session, *, file_hash: str, class_name: str | None):
    from class_table_backend.persistence.repositories import ImportRepository

    repo = ImportRepository(session)
    batch = repo.create_batch(
        file_hash=file_hash,
        original_filename="t.xls",
        status="PARSED",
        class_name=class_name,
        academic_year="2025-2026",
        semester_name="秋季",
        max_week=16,
    )
    repo.replace_rows(
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
    return batch


def test_confirm_requires_target_class(session: Session) -> None:
    service = ImportService(session)
    batch = _make_batch_with_row(session, file_hash="a" * 64, class_name=None)

    result = service.confirm(batch.id)
    assert result["status"] == ImportStatus.FAILED
    assert result["issues"][0]["code"] == "CLASS_REQUIRED"


def test_confirm_conflict_then_replace(session: Session) -> None:
    service = ImportService(session)
    b1 = _make_batch_with_row(session, file_hash="a" * 64, class_name="计科2501")
    b2 = _make_batch_with_row(session, file_hash="b" * 64, class_name="计科2501")

    first = service.confirm(b1.id, class_name="计科2501")
    assert first["status"] == ImportStatus.CONFIRMED

    conflict = service.confirm(b2.id, class_name="计科2501")
    assert conflict["status"] == ImportStatus.CONFLICT
    assert conflict["issues"][0]["code"] == "SCHEDULE_EXISTS"

    replaced = service.confirm(b2.id, class_name="计科2501", replace=True)
    assert replaced["status"] == ImportStatus.CONFIRMED
    assert _counts(session) == (1, 1)


def test_confirm_auto_creates_class_from_name(session: Session) -> None:
    from class_table_backend.persistence.repositories import ClassRepository

    service = ImportService(session)
    batch = _make_batch_with_row(session, file_hash="a" * 64, class_name=None)

    result = service.confirm(batch.id, class_name="新班2501")
    assert result["status"] == ImportStatus.CONFIRMED
    assert ClassRepository(session).get_by_name("新班2501") is not None
