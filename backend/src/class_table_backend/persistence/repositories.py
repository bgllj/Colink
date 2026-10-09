from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from class_table_backend.parsing.week_expr import WeekParity, WeekParseResult, WeekRange
from class_table_backend.persistence.tables import (
    CourseRow,
    ImportBatchRow,
    ImportRowRow,
    MeetingOccurrenceRow,
    MeetingWeekRow,
)

BATCH_STATUS_UPLOADED = "UPLOADED"
BATCH_STATUS_PARSED = "PARSED"
BATCH_STATUS_NEEDS_REVIEW = "NEEDS_REVIEW"
BATCH_STATUS_CONFIRMED = "CONFIRMED"
BATCH_STATUS_FAILED = "FAILED"

ROW_STATUS_PARSED = "PARSED"
ROW_STATUS_CONFIRMED = "CONFIRMED"


class ImportConfirmError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def _expand_week_ranges(week_ranges_json: Any) -> list[int]:
    if not week_ranges_json:
        return []
    ranges: list[WeekRange] = []
    for item in week_ranges_json:
        ranges.append(
            WeekRange(
                start=int(item["start"]),
                end=int(item["end"]),
                parity=WeekParity(item.get("parity", "ALL")),
            )
        )
    return WeekParseResult(original_text="", ranges=ranges).expanded_weeks()


class ImportRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def create_batch(
        self,
        *,
        file_hash: str,
        original_filename: str,
        status: str = BATCH_STATUS_UPLOADED,
        profile: str | None = None,
        academic_year: str | None = None,
        semester_name: str | None = None,
        start_date: Any = None,
        class_name: str | None = None,
        max_week: int | None = None,
        meta_json: dict[str, Any] | None = None,
    ) -> ImportBatchRow:
        batch = ImportBatchRow(
            id=str(uuid.uuid4()),
            file_hash=file_hash,
            original_filename=original_filename,
            status=status,
            profile=profile,
            academic_year=academic_year,
            semester_name=semester_name,
            start_date=start_date,
            class_name=class_name,
            max_week=max_week,
            meta_json=meta_json,
        )
        self.session.add(batch)
        self.session.flush()
        return batch

    def get_batch(self, batch_id: str) -> ImportBatchRow | None:
        return self.session.get(ImportBatchRow, batch_id)

    def get_batch_by_hash(self, file_hash: str) -> ImportBatchRow | None:
        stmt = select(ImportBatchRow).where(ImportBatchRow.file_hash == file_hash)
        return self.session.scalars(stmt).first()

    def replace_rows(
        self,
        batch_id: str,
        rows_payload: list[dict[str, Any]],
    ) -> list[ImportRowRow]:
        self.session.execute(delete(ImportRowRow).where(ImportRowRow.batch_id == batch_id))
        rows: list[ImportRowRow] = []
        for payload in rows_payload:
            row = ImportRowRow(
                id=str(uuid.uuid4()),
                batch_id=batch_id,
                row_status=payload.get("row_status", ROW_STATUS_PARSED),
                selected=int(payload.get("selected", 1)),
                course_code=payload.get("course_code"),
                course_name=payload.get("course_name"),
                weekday=payload.get("weekday"),
                period_start=payload.get("period_start"),
                period_end=payload.get("period_end"),
                week_text=payload.get("week_text"),
                week_ranges_json=payload.get("week_ranges_json"),
                room_text=payload.get("room_text"),
                sheet=payload.get("sheet"),
                coordinate=payload.get("coordinate"),
                line_index=payload.get("line_index"),
                raw_line=payload.get("raw_line"),
                issues_json=payload.get("issues_json"),
            )
            self.session.add(row)
            rows.append(row)
        self.session.flush()
        return rows

    def confirm_rows(
        self,
        batch_id: str,
        row_ids: list[str] | None = None,
    ) -> ImportBatchRow:
        batch = self.get_batch(batch_id)
        if batch is None:
            raise ImportConfirmError("BATCH_NOT_FOUND", f"导入批次不存在: {batch_id}")
        if batch.status == BATCH_STATUS_CONFIRMED:
            return batch

        stmt = select(ImportRowRow).where(ImportRowRow.batch_id == batch_id)
        if row_ids is not None:
            stmt = stmt.where(ImportRowRow.id.in_(row_ids))
        candidates = list(self.session.scalars(stmt))

        for row in candidates:
            if row.row_status != ROW_STATUS_PARSED or not row.selected:
                continue
            self._write_occurrence(row)

            row.row_status = ROW_STATUS_CONFIRMED

        batch.status = BATCH_STATUS_CONFIRMED
        self.session.flush()
        return batch

    def _write_occurrence(self, row: ImportRowRow) -> None:
        course = self.session.scalars(
            select(CourseRow).where(
                CourseRow.course_code == row.course_code,
                CourseRow.name == row.course_name,
            )
        ).first()
        if course is None:
            course = CourseRow(course_code=row.course_code, name=row.course_name)
            self.session.add(course)
            self.session.flush()

        meeting = MeetingOccurrenceRow(
            course_id=course.id,
            weekday=row.weekday if row.weekday is not None else 0,
            period_start=row.period_start,
            period_end=row.period_end,
            week_text=row.week_text,
            room_text=row.room_text,
            sheet=row.sheet,
            coordinate=row.coordinate,
            line_index=row.line_index,
            import_row_id=row.id,
        )
        self.session.add(meeting)
        self.session.flush()

        for week_no in _expand_week_ranges(row.week_ranges_json):
            self.session.add(MeetingWeekRow(meeting_id=meeting.id, week_no=week_no))
