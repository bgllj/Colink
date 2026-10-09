from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from class_table_backend.domain.issues import Issue
from class_table_backend.parsing.week_expr import WeekParseResult, WeekRange


@dataclass
class SemesterMeta:
    academic_year: str | None = None
    semester_name: str | None = None
    start_date: date | None = None
    department: str | None = None
    grade: str | None = None
    major: str | None = None
    class_name: str | None = None
    title: str | None = None


@dataclass
class ParsedOccurrence:
    course_code: str | None
    course_name: str | None
    weekday: int
    period_start: int | None
    period_end: int | None
    week_text: str | None
    week_ranges: list[WeekRange] = field(default_factory=list)
    room_text: str | None = None
    sheet: str = ""
    coordinate: str = ""
    line_index: int = 0
    raw_line: str = ""
    daypart: str | None = None
    slot_label: str | None = None
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(issue.severity == "error" for issue in self.issues)

    def expanded_weeks(self) -> list[int]:
        return WeekParseResult(
            original_text=self.week_text or "",
            ranges=list(self.week_ranges),
        ).expanded_weeks()
