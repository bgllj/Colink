from __future__ import annotations

import uuid
from datetime import UTC, date, datetime
from typing import Any

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from class_table_backend.parsing.week_expr import WeekParity, WeekParseResult, WeekRange
from class_table_backend.persistence.tables import (
    AdminUserRow,
    ClassRow,
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


class ClassRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def create(
        self,
        *,
        name: str,
        grade: str | None = None,
        major: str | None = None,
        department: str | None = None,
    ) -> ClassRow:
        row = ClassRow(
            id=str(uuid.uuid4()),
            name=name,
            grade=grade,
            major=major,
            department=department,
        )
        self.session.add(row)
        self.session.flush()
        return row

    def get_by_id(self, class_id: str) -> ClassRow | None:
        return self.session.get(ClassRow, class_id)

    def get_by_name(self, name: str) -> ClassRow | None:
        stmt = select(ClassRow).where(ClassRow.name == name)
        return self.session.scalars(stmt).first()

    def list_all(self) -> list[ClassRow]:
        stmt = select(ClassRow).order_by(ClassRow.name)
        return list(self.session.scalars(stmt))

    def rename(self, class_id: str, new_name: str) -> ClassRow:
        row = self.get_by_id(class_id)
        if row is None:
            raise ImportConfirmError("CLASS_NOT_FOUND", f"班级不存在: {class_id}")
        row.name = new_name
        self.session.flush()
        return row

    def delete(self, class_id: str) -> None:
        row = self.get_by_id(class_id)
        if row is None:
            raise ImportConfirmError("CLASS_NOT_FOUND", f"班级不存在: {class_id}")
        course_ids = list(
            self.session.scalars(select(CourseRow.id).where(CourseRow.class_id == class_id))
        )
        if course_ids:
            meeting_ids = list(
                self.session.scalars(
                    select(MeetingOccurrenceRow.id).where(
                        MeetingOccurrenceRow.course_id.in_(course_ids)
                    )
                )
            )
            if meeting_ids:
                self.session.execute(
                    delete(MeetingWeekRow).where(MeetingWeekRow.meeting_id.in_(meeting_ids))
                )
                self.session.execute(
                    delete(MeetingOccurrenceRow).where(
                        MeetingOccurrenceRow.id.in_(meeting_ids)
                    )
                )
            self.session.execute(delete(CourseRow).where(CourseRow.id.in_(course_ids)))
        self.session.execute(
            delete(ImportBatchRow).where(ImportBatchRow.class_id == class_id)
        )
        self.session.delete(row)
        self.session.flush()


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
        class_id: str | None = None,
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
            class_id=class_id,
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
        *,
        class_id: str | None = None,
        class_name: str | None = None,
        replace: bool = False,
    ) -> ImportBatchRow:
        batch = self.get_batch(batch_id)
        if batch is None:
            raise ImportConfirmError("BATCH_NOT_FOUND", f"导入批次不存在: {batch_id}")
        if batch.status == BATCH_STATUS_CONFIRMED:
            return batch

        target = self._resolve_class(batch, class_id=class_id, class_name=class_name)
        if self._class_has_schedule(target.id) and not replace:
            raise ImportConfirmError(
                "SCHEDULE_EXISTS",
                f"班级「{target.name}」已有确认课表，需显式 replace=true 才能替换",
            )
        if replace:
            self._delete_class_schedule(target.id)

        batch.class_id = target.id

        stmt = select(ImportRowRow).where(ImportRowRow.batch_id == batch_id)
        if row_ids is not None:
            stmt = stmt.where(ImportRowRow.id.in_(row_ids))
        candidates = list(self.session.scalars(stmt))

        for row in candidates:
            if row.row_status != ROW_STATUS_PARSED or not row.selected:
                continue
            self._write_occurrence(row, class_id=target.id)

            row.row_status = ROW_STATUS_CONFIRMED

        batch.status = BATCH_STATUS_CONFIRMED
        self.session.flush()
        return batch

    def _resolve_class(
        self,
        batch: ImportBatchRow,
        *,
        class_id: str | None,
        class_name: str | None,
    ) -> ClassRow:
        classes = ClassRepository(self.session)

        if class_id and class_name:
            row = classes.get_by_id(class_id)
            if row is not None and row.name != class_name:
                raise ImportConfirmError(
                    "CLASS_CONFLICT",
                    f"class_id 指向「{row.name}」但 class_name 为「{class_name}」",
                )
            if row is not None:
                return row
            raise ImportConfirmError("CLASS_NOT_FOUND", f"班级不存在: {class_id}")

        if class_id:
            row = classes.get_by_id(class_id)
            if row is None:
                raise ImportConfirmError("CLASS_NOT_FOUND", f"班级不存在: {class_id}")
            return row

        if class_name:
            row = classes.get_by_name(class_name)
            if row is not None:
                return row
            return classes.create(name=class_name)

        if batch.class_id:
            row = classes.get_by_id(batch.class_id)
            if row is not None:
                return row

        if batch.class_name:
            row = classes.get_by_name(batch.class_name)
            if row is not None:
                return row
            return classes.create(name=batch.class_name)

        raise ImportConfirmError(
            "CLASS_REQUIRED",
            "确认导入必须指定目标班级（class_id 或 class_name）",
        )

    def _class_has_schedule(self, class_id: str) -> bool:
        stmt = select(CourseRow.id).where(CourseRow.class_id == class_id).limit(1)
        return self.session.scalars(stmt).first() is not None

    def _delete_class_schedule(self, class_id: str) -> None:
        course_ids = list(
            self.session.scalars(select(CourseRow.id).where(CourseRow.class_id == class_id))
        )
        if not course_ids:
            return
        meeting_ids = list(
            self.session.scalars(
                select(MeetingOccurrenceRow.id).where(
                    MeetingOccurrenceRow.course_id.in_(course_ids)
                )
            )
        )
        if meeting_ids:
            self.session.execute(
                delete(MeetingWeekRow).where(MeetingWeekRow.meeting_id.in_(meeting_ids))
            )
            self.session.execute(
                delete(MeetingOccurrenceRow).where(MeetingOccurrenceRow.id.in_(meeting_ids))
            )
        self.session.execute(delete(CourseRow).where(CourseRow.id.in_(course_ids)))

    def _write_occurrence(self, row: ImportRowRow, *, class_id: str) -> None:
        course = self.session.scalars(
            select(CourseRow).where(
                CourseRow.class_id == class_id,
                CourseRow.course_code == row.course_code,
                CourseRow.name == row.course_name,
            )
        ).first()
        if course is None:
            course = CourseRow(
                class_id=class_id,
                course_code=row.course_code,
                name=row.course_name,
            )
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


class ScheduleRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def load_semester_meta(self, class_id: str) -> dict[str, Any]:
        batch = self._latest_confirmed_batch(class_id)
        if batch is None:
            return {
                "start_date": None,
                "max_week": None,
                "academic_year": None,
                "semester_name": None,
            }
        start_date = batch.start_date.isoformat() if batch.start_date else None
        return {
            "start_date": start_date,
            "max_week": batch.max_week,
            "academic_year": batch.academic_year,
            "semester_name": batch.semester_name,
        }

    def _latest_confirmed_batch(self, class_id: str) -> ImportBatchRow | None:
        return self.session.scalars(
            select(ImportBatchRow)
            .where(
                ImportBatchRow.status == BATCH_STATUS_CONFIRMED,
                ImportBatchRow.class_id == class_id,
            )
            .order_by(ImportBatchRow.created_at.desc())
            .limit(1)
        ).first()

    def update_start_date(
        self, class_id: str, start_date: date | None
    ) -> dict[str, Any]:
        """Update the semester start date on the class's confirmed import batch."""
        batch = self._latest_confirmed_batch(class_id)
        if batch is None:
            raise ImportConfirmError(
                "SCHEDULE_NOT_FOUND",
                f"班级尚无已确认课表，无法修改开学日期: {class_id}",
            )
        batch.start_date = start_date
        self.session.flush()
        return self.load_semester_meta(class_id)

    def load_schedule(self, class_id: str, week: int | None = None) -> list[dict[str, Any]]:
        course_rows = list(
            self.session.scalars(
                select(CourseRow)
                .where(CourseRow.class_id == class_id)
                .order_by(CourseRow.id)
            )
        )
        if not course_rows:
            return []

        meetings = list(
            self.session.scalars(
                select(MeetingOccurrenceRow)
                .where(MeetingOccurrenceRow.course_id.in_([c.id for c in course_rows]))
                .order_by(MeetingOccurrenceRow.id)
            )
        )

        weeks_by_meeting: dict[int, list[int]] = {}
        if meetings:
            week_rows = self.session.execute(
                select(MeetingWeekRow.meeting_id, MeetingWeekRow.week_no)
                .where(MeetingWeekRow.meeting_id.in_([m.id for m in meetings]))
                .order_by(MeetingWeekRow.meeting_id, MeetingWeekRow.week_no)
            )
            for meeting_id, week_no in week_rows:
                weeks_by_meeting.setdefault(meeting_id, []).append(week_no)

        meetings_by_course: dict[int, list[dict[str, Any]]] = {}
        for meeting in meetings:
            weeks = weeks_by_meeting.get(meeting.id, [])
            if week is not None and week not in weeks:
                continue
            meetings_by_course.setdefault(meeting.course_id, []).append(
                {
                    "meeting_id": meeting.id,
                    "weekday": meeting.weekday,
                    "period_start": meeting.period_start,
                    "period_end": meeting.period_end,
                    "week_text": meeting.week_text,
                    "weeks": weeks,
                    "room_text": meeting.room_text,
                    "sheet": meeting.sheet,
                    "coordinate": meeting.coordinate,
                    "line_index": meeting.line_index,
                }
            )

        schedule: list[dict[str, Any]] = []
        for course in course_rows:
            course_meetings = meetings_by_course.get(course.id)
            if not course_meetings:
                continue
            schedule.append(
                {
                    "course_id": course.id,
                    "course_code": course.course_code,
                    "name": course.name,
                    "meetings": course_meetings,
                }
            )
        return schedule


class AdminUserRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get_by_username(self, username: str) -> AdminUserRow | None:
        stmt = select(AdminUserRow).where(AdminUserRow.username == username)
        return self.session.scalars(stmt).first()

    def get_by_id(self, user_id: str) -> AdminUserRow | None:
        return self.session.get(AdminUserRow, user_id)

    def create(self, *, user_id: str, username: str, password_hash: str) -> AdminUserRow:
        user = AdminUserRow(
            id=user_id,
            username=username,
            password_hash=password_hash,
            created_at=datetime.now(UTC),
        )
        self.session.add(user)
        self.session.flush()
        return user
