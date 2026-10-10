from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import StrEnum

from class_table_backend.domain.issues import Issue, IssueCode
from class_table_backend.parsing.normalize import normalize_expression_text

_UNSUPPORTED_PHRASES = (
    "单双周",
    "隔周",
    "前半学期",
    "后半学期",
    "按通知",
    "节假日",
    "调课",
    "另行",
)

_TRAILING_MODIFIER_PATTERN = re.compile(r"(单周|双周|单|双)$")
_WEEK_SUFFIX_PATTERN = re.compile(r"周$")
_SEGMENT_PATTERN = re.compile(r"^(-?\d+)(?:-(-?\d+))?$")


class WeekParity(StrEnum):
    ALL = "ALL"
    ODD = "ODD"
    EVEN = "EVEN"


@dataclass(frozen=True)
class WeekRange:
    start: int
    end: int
    parity: WeekParity = WeekParity.ALL


@dataclass
class WeekParseResult:
    original_text: str
    ranges: list[WeekRange] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(issue.severity == "error" for issue in self.issues)

    def expanded_weeks(self) -> list[int]:
        weeks: set[int] = set()
        for week_range in self.ranges:
            step = 1
            if week_range.parity is WeekParity.ODD:
                start = week_range.start if week_range.start % 2 == 1 else week_range.start + 1
                step = 2
            elif week_range.parity is WeekParity.EVEN:
                start = week_range.start if week_range.start % 2 == 0 else week_range.start + 1
                step = 2
            else:
                start = week_range.start
            for week in range(start, week_range.end + 1, step):
                weeks.add(week)
        return sorted(weeks)


def _parse_segment(segment: str) -> WeekRange | Issue:
    match = _SEGMENT_PATTERN.fullmatch(segment)
    if match is None:
        return Issue(
            code=IssueCode.INVALID_WEEK_EXPRESSION,
            message=f"无法解析的周次片段: {segment!r}",
            raw_text=segment,
        )
    start = int(match.group(1))
    end = int(match.group(2)) if match.group(2) is not None else start
    if start <= 0 or end <= 0:
        return Issue(
            code=IssueCode.INVALID_WEEK_BOUND,
            message=f"周次必须为正整数: {segment!r}",
            raw_text=segment,
            details={"start": start, "end": end},
        )
    if start > end:
        return Issue(
            code=IssueCode.REVERSED_WEEK_RANGE,
            message=f"周次区间起始大于结束: {segment!r}",
            raw_text=segment,
            details={"start": start, "end": end},
        )
    return WeekRange(start=start, end=end, parity=WeekParity.ALL)


def parse_week_expression(text: str) -> WeekParseResult:
    original_text = text
    normalized = normalize_expression_text(text)

    if not normalized:
        return WeekParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.EMPTY_WEEK_EXPRESSION,
                    message="周次表达式为空",
                    raw_text=original_text,
                )
            ],
        )

    for phrase in _UNSUPPORTED_PHRASES:
        if phrase in normalized:
            return WeekParseResult(
                original_text=original_text,
                issues=[
                    Issue(
                        code=IssueCode.UNSUPPORTED_WEEK_PHRASE,
                        message=f"不支持的周次表述: {phrase}",
                        raw_text=original_text,
                        details={"phrase": phrase},
                    )
                ],
            )

    body = normalized
    parity = WeekParity.ALL

    modifier_match = _TRAILING_MODIFIER_PATTERN.search(body)
    if modifier_match is not None:
        token = modifier_match.group(1)
        if token in ("单周", "单"):
            parity = WeekParity.ODD
        else:
            parity = WeekParity.EVEN
        body = body[: modifier_match.start()]

    body = _WEEK_SUFFIX_PATTERN.sub("", body, count=1)

    if not body:
        return WeekParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.EMPTY_WEEK_EXPRESSION,
                    message="周次表达式为空",
                    raw_text=original_text,
                )
            ],
        )

    ranges: list[WeekRange] = []
    issues: list[Issue] = []
    for raw_segment in body.split(","):
        segment = raw_segment.strip()
        if not segment:
            issues.append(
                Issue(
                    code=IssueCode.EMPTY_WEEK_EXPRESSION,
                    message="周次片段为空",
                    raw_text=raw_segment,
                )
            )
            continue
        parsed = _parse_segment(segment)
        if isinstance(parsed, Issue):
            issues.append(parsed)
        else:
            ranges.append(WeekRange(start=parsed.start, end=parsed.end, parity=parity))

    if not ranges and not issues:
        return WeekParseResult(
            original_text=original_text,
            issues=[
                Issue(
                    code=IssueCode.EMPTY_WEEK_EXPRESSION,
                    message="周次表达式为空",
                    raw_text=original_text,
                )
            ],
        )

    result = WeekParseResult(original_text=original_text, ranges=ranges, issues=issues)
    if result.ok and not result.expanded_weeks():
        result.issues.append(
            Issue(
                code=IssueCode.EMPTY_WEEK_EXPANSION,
                message="周次表达式展开后为空",
                severity="warning",
                raw_text=original_text,
            )
        )
    return result
