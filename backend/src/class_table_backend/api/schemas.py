from __future__ import annotations

from datetime import date
from typing import Any

from pydantic import BaseModel, Field


class IssueOut(BaseModel):
    code: str
    message: str
    severity: str = "error"
    sheet: str | None = None
    coordinate: str | None = None
    line_index: int | None = None


class ImportIngestResponse(BaseModel):
    import_id: str
    status: str
    max_week: int | None = None
    duplicate_of: str | None = None
    issues: list[IssueOut] = Field(default_factory=list)


class ImportRowOut(BaseModel):
    row_id: str
    status: str
    selected: bool = False
    course_code: str | None = None
    course_name: str | None = None
    weekday: int | None = None
    period_start: int | None = None
    period_end: int | None = None
    week_text: str | None = None
    week_ranges: list[dict[str, Any]] = Field(default_factory=list)
    room_text: str | None = None
    sheet: str | None = None
    coordinate: str | None = None
    line_index: int | None = None
    raw_line: str | None = None
    issues: list[IssueOut] = Field(default_factory=list)


class ImportPreviewOut(BaseModel):
    import_id: str
    status: str
    max_week: int | None = None
    issues: list[IssueOut] = Field(default_factory=list)
    meta: dict[str, Any] = Field(default_factory=dict)
    rows: list[ImportRowOut] = Field(default_factory=list)


class ConfirmRequest(BaseModel):
    row_ids: list[str] | None = None
    class_id: str | None = None
    class_name: str | None = None
    replace: bool = False


class ConfirmOut(BaseModel):
    import_id: str
    status: str
    class_id: str | None = None
    rows_confirmed: int = 0
    issues: list[IssueOut] = Field(default_factory=list)


class RowsPageOut(BaseModel):
    import_id: str
    status: str
    total: int
    limit: int = 50
    offset: int = 0
    rows: list[ImportRowOut] = Field(default_factory=list)


class MeetingOut(BaseModel):
    meeting_id: int
    weekday: int
    period_start: int | None = None
    period_end: int | None = None
    week_text: str | None = None
    weeks: list[int] = Field(default_factory=list)
    room_text: str | None = None
    sheet: str | None = None
    coordinate: str | None = None
    line_index: int | None = None


class ScheduleCourseOut(BaseModel):
    course_id: int
    course_code: str
    name: str
    meetings: list[MeetingOut] = Field(default_factory=list)


class ScheduleSemesterOut(BaseModel):
    start_date: str | None = None
    max_week: int | None = None
    academic_year: str | None = None
    semester_name: str | None = None


class SemesterUpdateIn(BaseModel):
    """开学日期修改；显式传 ``null`` 表示清空。"""

    start_date: date | None


class ScheduleOut(BaseModel):
    class_id: str
    class_name: str
    courses: list[ScheduleCourseOut] = Field(default_factory=list)
    semester: ScheduleSemesterOut = Field(default_factory=ScheduleSemesterOut)


class ClassOut(BaseModel):
    id: str
    name: str
    grade: str | None = None
    major: str | None = None
    department: str | None = None


class ClassCreateIn(BaseModel):
    name: str = Field(min_length=1, max_length=128)
    grade: str | None = None
    major: str | None = None
    department: str | None = None


class ClassUpdateIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=128)
    grade: str | None = None
    major: str | None = None
    department: str | None = None
