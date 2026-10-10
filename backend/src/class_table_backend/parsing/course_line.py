from __future__ import annotations

import re
from dataclasses import dataclass, field

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.parsing.period_expr import PeriodParseResult, parse_period_expression
from class_table_backend.parsing.week_expr import WeekParseResult, parse_week_expression

_COURSE_LINE_PATTERN = re.compile(
    r"[\[［](?P<code>[^\]］]+)[\]］]"
    r"(?P<name>[^\[\［\]］]+)"
    r"\s*[\[［](?P<weeks>[^\]］]+)[\]］]"
    r"\s*[\[［](?P<periods>[^\]］]+)[\]］]"
    r"(?:\s+(?P<room>.+))?"
)


@dataclass
class ParsedCourseLine:
    raw_text: str
    line_index: int
    course_code: str | None
    course_name: str | None
    week_text: str | None
    period_text: str | None
    room_text: str | None
    week_result: WeekParseResult | None
    period_result: PeriodParseResult | None
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(issue.severity == "error" for issue in self.issues)


def parse_course_line(raw_line: str, *, line_index: int = 0) -> ParsedCourseLine:
    line = raw_line.strip()
    match = _COURSE_LINE_PATTERN.fullmatch(line)
    if match is None:
        return ParsedCourseLine(
            raw_text=raw_line,
            line_index=line_index,
            course_code=None,
            course_name=None,
            week_text=None,
            period_text=None,
            room_text=None,
            week_result=None,
            period_result=None,
            issues=[
                Issue(
                    code=IssueCode.INVALID_COURSE_LINE,
                    message=f"无法解析的课程行: {raw_line!r}",
                    raw_text=raw_line,
                    line_index=line_index,
                )
            ],
        )

    course_code = match.group("code").strip()
    course_name = match.group("name").strip()
    week_text = match.group("weeks").strip()
    period_text = match.group("periods").strip()
    room_raw = match.group("room")
    room_text = room_raw.strip() if room_raw is not None else None

    week_result = parse_week_expression(week_text)
    period_result = parse_period_expression(period_text)
    issues = [*week_result.issues, *period_result.issues]

    return ParsedCourseLine(
        raw_text=raw_line,
        line_index=line_index,
        course_code=course_code,
        course_name=course_name,
        week_text=week_text,
        period_text=period_text,
        room_text=room_text,
        week_result=week_result,
        period_result=period_result,
        issues=issues,
    )
